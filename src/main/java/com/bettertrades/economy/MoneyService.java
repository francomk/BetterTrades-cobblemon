package com.bettertrades.economy;

import com.bettertrades.BetterTrades;
import com.bettertrades.config.BetterTradesConfig;
import net.impactdev.impactor.api.economy.EconomyService;
import net.impactdev.impactor.api.economy.accounts.Account;
import net.impactdev.impactor.api.economy.currency.Currency;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The bridge to the server's economy (Impactor).
 *
 * Impactor is compileOnly and a soft dependency: when it is missing, {@link #available()} answers
 * false and BetterTrades keeps working without the money side instead of failing at startup.
 *
 * Impactor resolves accounts asynchronously and the result is waited for here with a timeout, so
 * EVERY method that reads or moves money is BLOCKING and must never be called from the server
 * thread: they are used from this class's own pool.
 */
public final class MoneyService {

    public enum Mode { NONE, IMPACTOR }

    /**
     * One money operation, watched through two different futures.
     *
     * {@code awaited} expires after {@link #OPERATION_TIMEOUT_SECONDS} and exists for whoever
     * cannot hang - a session in COMMITTING blocks two players. {@code settled} never expires and
     * carries the REAL outcome, which can arrive much later: it is the only way to know whether a
     * payment given up for lost did move the money after all, and therefore has to be refunded.
     */
    public record Operation<T>(CompletableFuture<T> awaited, CompletableFuture<T> settled) {}

    private static final long ACCOUNT_LOOKUP_TIMEOUT_SECONDS = 7;

    /**
     * A limit on the WHOLE operation, not just on resolving the account.
     *
     * transfer() ends up inside Impactor with no time limit, on a single-threaded executor: if it
     * does not come back, neither does the future, and whoever waits on that future - a session in
     * COMMITTING - never gets unstuck. Two lookups plus the transaction either fit in twenty seconds
     * or they never will.
     */
    private static final long OPERATION_TIMEOUT_SECONDS = 20;

    /**
     * The limit above, for whoever has to pick their own.
     *
     * Anything watching a money operation must expire AFTER this, not before: a shorter leash closes
     * the session while the payment is still in flight.
     */
    public static long operationTimeoutSeconds() {
        return OPERATION_TIMEOUT_SECONDS;
    }

    /** The currency used is always the provider's primary one: this is only its label. */
    private static final String PRIMARY = "primary";

    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);

    /** How long the stop waits for deposits already queued: a refund cut off here is money lost. */
    private static final long SHUTDOWN_WAIT_SECONDS = OPERATION_TIMEOUT_SECONDS + 10;

    private static volatile ExecutorService worker = newWorker();

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BetterTrades economy");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static volatile Mode mode = Mode.NONE;

    private MoneyService() {}

    /**
     * Impactor registers after the mods, so it has to be asked once the server is up and not at
     * initialisation: earlier it would say no.
     */
    public static CompletableFuture<Boolean> refreshAvailability() {
        // A previous server in the same JVM (singleplayer, LAN) closed it on the way out.
        if (worker.isShutdown()) worker = newWorker();
        return CompletableFuture.supplyAsync(() -> {
            if (!BetterTradesConfig.get().economy.enabled) {
                mode = Mode.NONE;
                BetterTrades.LOGGER.info("Economy disabled in the config");
                return false;
            }
            String configured = BetterTradesConfig.get().economy.currency;
            if (configured != null && !configured.isBlank() && !PRIMARY.equals(configured)) {
                BetterTrades.LOGGER.warn("economy.currency = '{}' selects no currency:"
                        + " BetterTrades always uses the provider's primary currency", configured);
            }
            mode = service() != null && currency() != null ? Mode.IMPACTOR : Mode.NONE;
            if (mode == Mode.NONE) {
                BetterTrades.LOGGER.warn("Impactor not available: money trades stay switched off");
            } else {
                BetterTrades.LOGGER.info("Impactor economy connected, currency '{}'", currencyKey());
            }
            return mode != Mode.NONE;
        }, worker);
    }

    /**
     * Server stop: lets the queued operations finish instead of dying with the JVM.
     *
     * The thread is a daemon, so without this a refund queued by the last trades of the session was
     * cut off mid-queue by the exit: the payer's money withdrawn and never given back.
     */
    public static void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                BetterTrades.LOGGER.error("The economy thread did not finish its queue in {}s: the"
                        + " trade journal settles what was left at the next start", SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The last known availability, without touching Impactor: the GUI asks on every redraw. */
    public static boolean available() {
        return mode != Mode.NONE;
    }

    /**
     * The currency label for the history. Impactor is not asked: its accessors return Adventure
     * types, which with a non-transitive dependency are not on the classpath.
     *
     * It is always "primary" because what gets moved is always {@code service.currencies().primary()}.
     * Writing the config value into the column meant labelling rows with a currency that had not been
     * used, given that the value selects nothing.
     */
    public static String currencyKey() {
        return PRIMARY;
    }

    public static CompletableFuture<Long> balance(UUID player) {
        return onWorker("Balance read of " + player, () -> {
            if (mode == Mode.NONE) return 0L;
            Account account = account(player);
            return account == null ? 0L : clamped(account.balance(), player);
        }).awaited();
    }

    /**
     * The balance as a long, without the silent truncation of {@link java.math.BigDecimal#longValue()}.
     *
     * longValue() does not throw on overflow: it returns the low 64 bits, so a huge balance becomes
     * some arbitrary number, possibly negative. The real withdraw does not come through here - take()
     * compares in BigDecimal - so no money is created or lost, but the upstream checks do, and with a
     * wrong balance they refuse a valid offer while showing a nonsensical figure, or drop the
     * confirmation of a trade that could have gone through. Saturating is the only answer those
     * checks can use: whoever has more than Long.MAX_VALUE has enough for any offer, which is exactly
     * what the comparison is meant to conclude.
     */
    private static long clamped(BigDecimal balance, UUID player) {
        if (balance.compareTo(LONG_MAX) >= 0) {
            BetterTrades.LOGGER.warn("Balance of {} above Long.MAX_VALUE: read as {}", player, Long.MAX_VALUE);
            return Long.MAX_VALUE;
        }
        if (balance.compareTo(LONG_MIN) <= 0) {
            BetterTrades.LOGGER.warn("Balance of {} below Long.MIN_VALUE: read as {}", player, Long.MIN_VALUE);
            return Long.MIN_VALUE;
        }
        return balance.longValue();
    }

    /**
     * Takes {@code amount} from the payer and keeps it out of everyone's hands until the trade
     * decides: see {@link #release}.
     *
     * An atomic transfer straight to the payee looked safer and was not. When the trade had to stop
     * after the payment - a Pokemon renamed or taken off the offer while the payment was in flight -
     * the money had to come back with a second transfer from the payee, and a payee who had already
     * spent it made that transfer fail: the payer lost the money, the payee kept it and everything
     * they had offered. Held here, a refund is a deposit, and a deposit does not depend on anyone's
     * balance.
     *
     * The journal is written before the withdraw and after it, on this thread, so that a crash
     * anywhere in between leaves a row that says how far it got.
     *
     * @return true when the money is held; false when it was refused and nothing moved
     */
    public static Operation<Boolean> take(String tradeId, UUID payer, UUID payee, long amount) {
        return onWorker("Hold of " + amount + " from " + payer + " for trade " + tradeId, () -> {
            if (mode == Mode.NONE) return false;
            Account source = account(payer);
            if (source == null) return false;
            BigDecimal value = BigDecimal.valueOf(amount);
            if (source.balance().compareTo(value) < 0) return false;

            // No journal, no withdraw: money taken without a row describing it is money that only
            // this JVM remembers.
            com.bettertrades.escrow.TradeJournal.moneyPending(tradeId, payer, payee, amount);
            boolean taken = source.withdraw(value).successful();
            try {
                com.bettertrades.escrow.TradeJournal.money(tradeId, taken
                        ? com.bettertrades.escrow.TradeJournal.MONEY_HELD
                        : com.bettertrades.escrow.TradeJournal.MONEY_FAILED);
            } catch (RuntimeException e) {
                // The money is held all the same: the row stays PENDING and the next start reports
                // it. Returning false here would leave the payer without the amount and the session
                // convinced nothing was taken.
                BetterTrades.LOGGER.error("Trade {}: hold of {} from {} is {}, but the journal did not"
                        + " record it", tradeId, amount, payer, taken ? "done" : "refused", e);
            }
            return taken;
        });
    }

    /**
     * Hands held money to whoever the trade decided: the payee when it went through, back to the
     * payer when it did not.
     *
     * A deposit that is refused moved nothing and is retried once; a second refusal leaves the row
     * in RETRY, which the next start tries again. A deposit that throws or hangs has an unknown
     * outcome: the row stays in TO_PAYEE / TO_PAYER, which the next start reports and does NOT
     * retry, because trying again could pay twice.
     *
     * @param onFailure run on the economy thread when the money did not arrive, to tell the staff
     */
    public static Operation<Boolean> release(String tradeId, UUID to, long amount, boolean toPayee,
                                             Runnable onFailure) {
        return onWorker("Release of " + amount + " to " + to + " for trade " + tradeId, () -> {
            boolean done;
            try {
                done = deposit(tradeId, to, amount, toPayee);
            } catch (Throwable failure) {
                onFailure.run();
                throw failure;
            }
            if (!done) onFailure.run();
            return done;
        });
    }

    /**
     * At startup: settles the money of the trades a crash or a stop left in between.
     * Called once Impactor is known to be there.
     */
    public static void recover() {
        com.bettertrades.escrow.TradeJournal.openMoney().thenAccept(rows -> {
            if (rows.isEmpty()) return;
            onWorker("Trade journal money recovery", () -> {
                for (com.bettertrades.escrow.TradeJournal.OpenMoney row : rows) {
                    try {
                        recoverOne(row);
                    } catch (RuntimeException e) {
                        BetterTrades.LOGGER.error("Trade {}: money recovery failed, state left at {}",
                                row.tradeId(), row.moneyState(), e);
                    }
                }
                return true;
            });
        }).exceptionally(error -> {
            BetterTrades.LOGGER.error("The money recovery of the trade journal failed", error);
            return null;
        });
    }

    private static void recoverOne(com.bettertrades.escrow.TradeJournal.OpenMoney row) {
        String state = row.moneyState();
        if (com.bettertrades.escrow.TradeJournal.MONEY_PENDING.equals(state)
                || com.bettertrades.escrow.TradeJournal.MONEY_TO_PAYEE.equals(state)
                || com.bettertrades.escrow.TradeJournal.MONEY_TO_PAYER.equals(state)) {
            com.bettertrades.escrow.TradeJournal.money(row.tradeId(),
                    com.bettertrades.escrow.TradeJournal.MONEY_UNKNOWN);
            BetterTrades.LOGGER.error("MONEY OF UNKNOWN OUTCOME trade={} amount={} payer={} payee={}"
                            + " state={}: the operation was interrupted and may or may not have happened."
                            + " Check both balances in the economy log and settle it by hand",
                    row.tradeId(), row.amount(), row.payer(), row.payee(), state);
            return;
        }
        boolean toPayee;
        if (com.bettertrades.escrow.TradeJournal.MONEY_RETRY_PAYEE.equals(state)) {
            toPayee = true;
        } else if (com.bettertrades.escrow.TradeJournal.MONEY_RETRY_PAYER.equals(state)) {
            toPayee = false;
        } else {
            // HELD: nobody has it. The trade's own state says who should.
            String trade = row.state();
            toPayee = com.bettertrades.escrow.TradeJournal.COMMITTED.equals(trade)
                    || com.bettertrades.escrow.TradeJournal.DONE.equals(trade);
        }
        UUID to = toPayee ? row.payee() : row.payer();
        if (deposit(row.tradeId(), to, row.amount(), toPayee)) {
            BetterTrades.LOGGER.warn("Trade {}: held money {} settled at startup to the {} {}",
                    row.tradeId(), row.amount(), toPayee ? "payee" : "payer", to);
        } else {
            BetterTrades.LOGGER.error("Trade {}: held money {} could not be settled at startup to the"
                    + " {} {}: retried at the next start", row.tradeId(), row.amount(),
                    toPayee ? "payee" : "payer", to);
        }
    }

    /** Economy thread only. True when the money arrived. */
    private static boolean deposit(String tradeId, UUID to, long amount, boolean toPayee) {
        String sending = toPayee ? com.bettertrades.escrow.TradeJournal.MONEY_TO_PAYEE
                : com.bettertrades.escrow.TradeJournal.MONEY_TO_PAYER;
        String retry = toPayee ? com.bettertrades.escrow.TradeJournal.MONEY_RETRY_PAYEE
                : com.bettertrades.escrow.TradeJournal.MONEY_RETRY_PAYER;
        String arrived = toPayee ? com.bettertrades.escrow.TradeJournal.MONEY_PAID
                : com.bettertrades.escrow.TradeJournal.MONEY_REFUNDED;
        try {
            com.bettertrades.escrow.TradeJournal.money(tradeId, sending);
        } catch (RuntimeException e) {
            // Without the row saying a deposit started, a crash after it would pay a second time at
            // the next start. The money stays held and the next start settles it from the journal.
            BetterTrades.LOGGER.error("Trade {}: journal not writable, deposit of {} to {} postponed"
                    + " to the next start", tradeId, amount, to, e);
            return false;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean done = false;
            if (mode != Mode.NONE) {
                Account account = account(to);
                done = account != null && account.deposit(BigDecimal.valueOf(amount)).successful();
            }
            if (done) {
                try {
                    com.bettertrades.escrow.TradeJournal.money(tradeId, arrived);
                } catch (RuntimeException e) {
                    BetterTrades.LOGGER.error("Trade {}: {} deposited to {} but the journal still says {}:"
                            + " the next start will report it as unknown", tradeId, amount, to, sending, e);
                }
                return true;
            }
        }
        try {
            com.bettertrades.escrow.TradeJournal.money(tradeId, retry);
        } catch (RuntimeException e) {
            BetterTrades.LOGGER.error("Trade {}: deposit of {} to {} refused and the journal not updated",
                    tradeId, amount, to, e);
        }
        return false;
    }

    // ---------------------------------------------------------------- economy thread

    /**
     * Queues the body on the worker and keeps it separate from the future that expires.
     *
     * The timeout CANNOT sit on the future produced by supplyAsync. orTimeout() interrupts nothing:
     * it completes the future and leaves the task queued, and when the worker gets there
     * CompletableFuture.AsyncSupply.run() sees the future already complete and SKIPS the supplier.
     * The body is never executed and nobody notices. On a refund queued behind a slow Impactor call
     * that is worth the payer's money: they paid, the cancel says "refunded", and the refund never
     * started.
     *
     * Here the body is a task of its own: it runs anyway, sooner or later. The timeout lives on a
     * copy of the future, so whoever waits gets unstuck without switching the operation off, and the
     * late outcome ends up in the log instead of vanishing.
     */
    private static <T> Operation<T> onWorker(String what, Callable<T> body) {
        CompletableFuture<T> settled = new CompletableFuture<>();
        CompletableFuture<T> awaited = settled.copy()
                .orTimeout(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        settled.whenComplete((result, error) -> {
            if (!awaited.isCompletedExceptionally()) return;
            if (error != null) {
                BetterTrades.LOGGER.error("{}: failed after the {}s timeout, once the caller"
                        + " had already given up", what, OPERATION_TIMEOUT_SECONDS, error);
            } else {
                BetterTrades.LOGGER.error("{}: finished with outcome {} after the {}s timeout, once"
                                + " the caller had already given up",
                        what, result, OPERATION_TIMEOUT_SECONDS);
            }
        });

        try {
            worker.execute(() -> {
                try {
                    settled.complete(body.call());
                } catch (Throwable failure) {
                    // Errors too: a NoClassDefFoundError from an Impactor removed at runtime would
                    // kill the thread, and from then on every money operation would hang until the
                    // timeout with nobody knowing why.
                    settled.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException e) {
            settled.completeExceptionally(e);
        }
        return new Operation<>(awaited, settled);
    }

    private static Account account(UUID player) {
        try {
            EconomyService service = service();
            Currency currency = currency();
            if (service == null || currency == null) return null;
            return service.account(currency, player).get(ACCOUNT_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            BetterTrades.LOGGER.error("Timeout of {}s reading the account of {}",
                    ACCOUNT_LOOKUP_TIMEOUT_SECONDS, player);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException e) {
            BetterTrades.LOGGER.error("Account of {} could not be read: {}", player, e.toString());
            return null;
        }
    }

    private static EconomyService service() {
        try {
            return EconomyService.instance();
        } catch (NoClassDefFoundError | IllegalStateException | NullPointerException e) {
            return null;
        }
    }

    private static Currency currency() {
        EconomyService service = service();
        if (service == null) return null;
        try {
            return service.currencies().primary();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
