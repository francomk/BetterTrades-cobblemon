package com.bettertrades.db;

import com.bettertrades.BetterTrades;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One-off migration: before this version escrow lived on the active backend, so HELD rows nobody
 * will ever read again may be sitting there. They are copied into the local file here and marked
 * MIGRATED at the source, so the items come back to their owner at the next login instead of
 * disappearing.
 *
 * The done marker lives in the local file and is PER BACKEND: the origin of the data is the backend
 * the previous version ran on, and one server can see two of them - SQLite in fallback now,
 * MariaDB when it comes back. A single marker would have declared finished a migration that had
 * only looked in one place.
 */
public final class EscrowMigration {

    private static final String MARKER_PREFIX = "escrow_migrated_";

    record Row(String id, String playerUuid, String playerName, String tradeId,
                       String itemId, int amount, byte[] components, long createdAt) {}

    private EscrowMigration() {}

    static String markerFor(Database.Backend backend) {
        return MARKER_PREFIX + backend;
    }

    public static void runOnce() {
        // The condition describes where the data COMES FROM, not the preferred backend.
        //
        // Tied to MARIADB, a server always on SQLite - which is the default, database.useRemote =
        // false - never started the migration at all: its HELD rows stayed in bettertrades.db,
        // which EscrowService no longer queries, and the items had already left the inventory.
        // Permanent loss, not a configuration choice.
        Database.Backend source = Database.backend();
        if (source == Database.Backend.NONE || !EscrowDb.usable()) return;
        String marker = markerFor(source);

        EscrowDb.supply(connection -> alreadyDone(connection, marker))
                .thenCompose(done -> done
                        ? CompletableFuture.completedFuture(null)
                        : Database.supply(connection -> readHeld(connection, source)).thenCompose(rows ->
                                EscrowDb.execute(connection -> insertAll(connection, rows))
                                        .thenCompose(ignored -> rows.isEmpty()
                                                ? CompletableFuture.completedFuture(null)
                                                : Database.execute(connection -> markMigrated(connection, rows)))
                                        .thenCompose(ignored -> EscrowDb.execute(connection -> markDone(connection, marker)))
                                        .thenRun(() -> BetterTrades.LOGGER.info(
                                                "Escrow: {} HELD rows migrated from {} into the local file",
                                                rows.size(), source))))
                .exceptionally(error -> {
                    BetterTrades.LOGGER.error("Escrow migration from {} failed:"
                            + " it stays queued for the next start or recovery", source, error);
                    return null;
                });
    }

    static boolean alreadyDone(Connection connection, String marker) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT meta_value FROM schema_meta WHERE meta_key = ?")) {
            select.setString(1, marker);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static List<Row> readHeld(Connection connection, Database.Backend expected) throws SQLException {
        // The backend can have changed between registration and execution: reading the fallback's
        // escrow and marking the MariaDB migration done would erase the reason to run it again.
        if (Database.backend() != expected) {
            throw new SQLException("Escrow migration cancelled: the active backend changed from "
                    + expected + " a " + Database.backend());
        }
        return selectHeld(connection);
    }

    static List<Row> selectHeld(Connection connection) throws SQLException {
        List<Row> held = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT e.id, e.player_uuid, p.current_name, e.trade_id, e.item_id, e.amount,"
                        + " e.components, e.created_at FROM escrow e"
                        + " JOIN player p ON p.uuid = e.player_uuid WHERE e.state = 'HELD'");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                held.add(new Row(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getInt(6), rows.getBytes(7),
                        rows.getLong(8)));
            }
        }
        return held;
    }

    static void insertAll(Connection connection, List<Row> rows) throws SQLException {
        if (rows.isEmpty()) return;
        for (Row row : rows) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO player (uuid, current_name, first_seen) VALUES (?, ?, ?)")) {
                insert.setString(1, row.playerUuid());
                insert.setString(2, row.playerName());
                insert.setLong(3, row.createdAt());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO item (identifier, first_seen) VALUES (?, ?)")) {
                insert.setString(1, row.itemId());
                insert.setLong(2, row.createdAt());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO escrow (id, player_uuid, trade_id, item_id, amount,"
                            + " components, state, created_at, resolved_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'HELD', ?, NULL)")) {
                insert.setString(1, row.id());
                insert.setString(2, row.playerUuid());
                insert.setString(3, row.tradeId());
                insert.setString(4, row.itemId());
                insert.setInt(5, row.amount());
                insert.setBytes(6, row.components());
                insert.setLong(7, row.createdAt());
                insert.executeUpdate();
            }
        }
    }

    static void markMigrated(Connection connection, List<Row> rows) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE escrow SET state = 'MIGRATED', resolved_at = ? WHERE id = ? AND state = 'HELD'")) {
            long now = System.currentTimeMillis();
            for (Row row : rows) {
                update.setLong(1, now);
                update.setString(2, row.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    static void markDone(Connection connection, String marker) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO schema_meta (meta_key, meta_value) VALUES (?, ?)")) {
            insert.setString(1, marker);
            insert.setString(2, String.valueOf(System.currentTimeMillis()));
            insert.executeUpdate();
        }
    }
}
