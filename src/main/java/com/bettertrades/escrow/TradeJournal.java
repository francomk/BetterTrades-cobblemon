package com.bettertrades.escrow;

import com.bettertrades.BetterTrades;
import com.bettertrades.db.EscrowDb;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What a trade in COMMITTING has decided, written where the escrow rows it closes live.
 *
 * Without it a crash or a /stop in the middle of a commit left the rows CONSUMED - delivered to
 * nobody, returned to nobody - and the money in whatever state the economy thread had reached. With
 * it, the next start reads the decision back: a trade that never got past OPEN gives its items back
 * to their owners and its money back to the payer; one that got to COMMITTED pays the payee.
 *
 * Every write goes through the single escrow thread, so the order in which they are queued is the
 * order in which they land.
 */
public final class TradeJournal {

    public static final String OPEN = "OPEN";
    public static final String COMMITTED = "COMMITTED";
    public static final String DONE = "DONE";
    public static final String ABORTED = "ABORTED";

    /** No money in this trade, or none taken yet. */
    public static final String MONEY_NONE = "NONE";
    /** About to withdraw from the payer: whether it happened is unknown until the next state lands. */
    public static final String MONEY_PENDING = "PENDING";
    /** Withdrawn from the payer and held here: nobody has it. */
    public static final String MONEY_HELD = "HELD";
    /** Deposit to the payee started: whether it happened is unknown until PAID lands. */
    public static final String MONEY_TO_PAYEE = "TO_PAYEE";
    /** Deposit back to the payer started. */
    public static final String MONEY_TO_PAYER = "TO_PAYER";
    /** The deposit to the payee was refused: nothing moved, safe to try again. */
    public static final String MONEY_RETRY_PAYEE = "RETRY_PAYEE";
    /** The deposit back to the payer was refused: nothing moved, safe to try again. */
    public static final String MONEY_RETRY_PAYER = "RETRY_PAYER";
    public static final String MONEY_PAID = "PAID";
    public static final String MONEY_REFUNDED = "REFUNDED";
    /** The withdraw was refused: nothing was taken. */
    public static final String MONEY_FAILED = "FAILED";
    /** A deposit or withdraw of unknown outcome found at startup: only a human can settle it. */
    public static final String MONEY_UNKNOWN = "UNKNOWN";

    private TradeJournal() {}

    /** Opens the journal row inside the transaction that closes the trade's escrow rows. */
    static void openIn(Connection connection, String tradeId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_journal (trade_id, state, payer_uuid, payee_uuid, amount, money_state,"
                        + " created_at, resolved_at) VALUES (?, ?, NULL, NULL, 0, ?, ?, NULL)")) {
            insert.setString(1, tradeId);
            insert.setString(2, OPEN);
            insert.setString(3, MONEY_NONE);
            insert.setLong(4, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    /** Marks the trade ABORTED inside another escrow transaction: nothing of it moved. */
    static void abortedIn(Connection connection, String tradeId) throws SQLException {
        setState(connection, tradeId, ABORTED);
    }

    /**
     * The point of no return: from here a restart pays the payee instead of refunding the payer.
     * Only an OPEN trade can get here, so a late write cannot overturn an ABORTED one.
     */
    public static CompletableFuture<Void> committed(String tradeId) {
        return EscrowDb.execute(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE trade_journal SET state = ? WHERE trade_id = ? AND state = ?")) {
                update.setString(1, COMMITTED);
                update.setString(2, tradeId);
                update.setString(3, OPEN);
                if (update.executeUpdate() == 0) {
                    throw new SQLException("Trade " + tradeId + " is no longer OPEN in the journal");
                }
            }
        });
    }

    /** The trade finished: whatever moved is in the player files. */
    public static void done(String tradeId) {
        EscrowDb.execute(connection -> setState(connection, tradeId, DONE)).exceptionally(error -> {
            BetterTrades.LOGGER.warn("Trade {}: journal not marked {}", tradeId, DONE, error);
            return null;
        });
    }

    /** Records the money of this trade, before the withdraw. Blocking: economy thread only. */
    public static void moneyPending(String tradeId, UUID payer, UUID payee, long amount) {
        EscrowDb.execute(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE trade_journal SET payer_uuid = ?, payee_uuid = ?, amount = ?, money_state = ?"
                            + " WHERE trade_id = ?")) {
                update.setString(1, payer.toString());
                update.setString(2, payee.toString());
                update.setLong(3, amount);
                update.setString(4, MONEY_PENDING);
                update.setString(5, tradeId);
                if (update.executeUpdate() == 0) {
                    throw new SQLException("Trade " + tradeId + " has no journal row");
                }
            }
        }).join();
    }

    /** Moves the money state. Blocking: economy thread only. */
    public static void money(String tradeId, String moneyState) {
        EscrowDb.execute(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE trade_journal SET money_state = ? WHERE trade_id = ?")) {
                update.setString(1, moneyState);
                update.setString(2, tradeId);
                update.executeUpdate();
            }
        }).join();
    }

    private static void setState(Connection connection, String tradeId, String state) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE trade_journal SET state = ?, resolved_at = ? WHERE trade_id = ?")) {
            update.setString(1, state);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, tradeId);
            update.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- startup recovery

    /** A journal row whose money has not reached a final state. */
    public record OpenMoney(String tradeId, String state, UUID payer, UUID payee, long amount,
                            String moneyState) {}

    /**
     * The item half of the recovery, at startup and before anyone joins: queued on the escrow thread
     * right after it opens, so it lands before any login looks for HELD rows.
     *
     * An OPEN trade never moved anything: its CONSUMED rows go back to HELD, and the owners get them
     * at their next login. A COMMITTED one was delivering when it stopped: whether the items reached
     * the player files is not known, and handing them over again could duplicate them, so the rows
     * are left alone and the staff is told through the log.
     */
    public static CompletableFuture<Void> recoverItems() {
        return EscrowDb.execute(connection -> {
            List<String[]> open = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT trade_id, state FROM trade_journal WHERE state IN (?, ?)")) {
                select.setString(1, OPEN);
                select.setString(2, COMMITTED);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) open.add(new String[]{rows.getString(1), rows.getString(2)});
                }
            }
            for (String[] row : open) {
                String tradeId = row[0];
                if (OPEN.equals(row[1])) {
                    int back;
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE escrow SET state = 'HELD', resolved_at = NULL"
                                    + " WHERE trade_id = ? AND state = 'CONSUMED'")) {
                        update.setString(1, tradeId);
                        back = update.executeUpdate();
                    }
                    setState(connection, tradeId, ABORTED);
                    BetterTrades.LOGGER.warn("Trade {} was interrupted before committing: {} escrow rows"
                            + " reopened, they go back to their owners at the next login", tradeId, back);
                    continue;
                }
                setState(connection, tradeId, DONE);
                BetterTrades.LOGGER.error("Trade {} was interrupted WHILE delivering. Items and Pokemon"
                        + " that had not reached the player files are missing: check the escrow rows of"
                        + " this trade (state CONSUMED) and the history before refunding by hand", tradeId);
            }
        });
    }

    /** The money half of the recovery: every row whose money is still somewhere in between. */
    public static CompletableFuture<List<OpenMoney>> openMoney() {
        return EscrowDb.supply(connection -> {
            List<OpenMoney> open = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT trade_id, state, payer_uuid, payee_uuid, amount, money_state FROM trade_journal"
                            + " WHERE money_state IN (?, ?, ?, ?, ?, ?)")) {
                select.setString(1, MONEY_PENDING);
                select.setString(2, MONEY_HELD);
                select.setString(3, MONEY_TO_PAYEE);
                select.setString(4, MONEY_TO_PAYER);
                select.setString(5, MONEY_RETRY_PAYEE);
                select.setString(6, MONEY_RETRY_PAYER);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        open.add(new OpenMoney(rows.getString(1), rows.getString(2),
                                UUID.fromString(rows.getString(3)), UUID.fromString(rows.getString(4)),
                                rows.getLong(5), rows.getString(6)));
                    }
                }
            }
            return open;
        });
    }

    /**
     * Drops the rows nobody will read again: escrow closed and journal settled longer ago than the
     * retention. Without it every offer and every removal left a row behind forever.
     */
    public static CompletableFuture<Void> prune(long retentionMillis) {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        return EscrowDb.execute(connection -> {
            int escrow;
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM escrow WHERE state IN ('RETURNED', 'CONSUMED') AND resolved_at < ?"
                            + " AND (trade_id IS NULL OR trade_id NOT IN"
                            + " (SELECT trade_id FROM trade_journal WHERE state IN (?, ?)))")) {
                delete.setLong(1, cutoff);
                delete.setString(2, OPEN);
                delete.setString(3, COMMITTED);
                escrow = delete.executeUpdate();
            }
            int journal;
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM trade_journal WHERE state IN (?, ?) AND resolved_at < ?"
                            + " AND money_state IN (?, ?, ?, ?)")) {
                delete.setString(1, DONE);
                delete.setString(2, ABORTED);
                delete.setLong(3, cutoff);
                delete.setString(4, MONEY_NONE);
                delete.setString(5, MONEY_PAID);
                delete.setString(6, MONEY_REFUNDED);
                delete.setString(7, MONEY_FAILED);
                journal = delete.executeUpdate();
            }
            if (escrow > 0 || journal > 0) {
                BetterTrades.LOGGER.info("Escrow store pruned: {} closed escrow rows and {} journal rows"
                        + " older than the retention", escrow, journal);
            }
        });
    }
}
