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

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BetterTrades economy");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Mode mode = Mode.NONE;

    private MoneyService() {}

    /**
     * Impactor registers after the mods, so it has to be asked once the server is up and not at
     * initialisation: earlier it would say no.
     */
    public static CompletableFuture<Boolean> refreshAvailability() {
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
        }, WORKER);
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
     * some arbitrary number, possibly negative. The real transfer does not come through here - pay()
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
     * Moves {@code amount} from one player to the other, with the provider's atomic operation.
     *
     * The outcome is false when the balance is no longer enough as well: between the offer and the
     * confirmation the player may have spent elsewhere, and in that case the caller MUST cancel the
     * trade.
     *
     * Whoever cancels on the expiry of {@link Operation#awaited()} must also watch
     * {@link Operation#settled()}: the transfer is still running, and if it goes through afterwards
     * the money has moved for a trade that no longer exists.
     */
    public static Operation<Boolean> pay(UUID from, UUID to, long amount) {
        return onWorker("Transfer of " + amount + " from " + from + " to " + to, () -> {
            if (amount == 0) return true;
            if (mode == Mode.NONE) return false;

            Account source = account(from);
            Account destination = account(to);
            if (source == null || destination == null) return false;

            BigDecimal value = BigDecimal.valueOf(amount);
            if (source.balance().compareTo(value) < 0) return false;
            return source.transfer(destination, value).successful();
        });
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
            WORKER.execute(() -> {
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
