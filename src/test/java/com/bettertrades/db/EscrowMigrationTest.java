package com.bettertrades.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escrow migration runs exactly once, on a database nobody has ever seen before and on rows
 * standing for real items that left an inventory. If it gets it wrong those items vanish with
 * nobody noticing, so here it is watched actually running.
 *
 * The source is an SQLite database standing in for MariaDB: the migration queries touching it -
 * the SELECT with the JOIN and the UPDATE to MIGRATED - are standard SQL, identical on both backends.
 */
class EscrowMigrationTest {

    private static final String MARKER = EscrowMigration.markerFor(Database.Backend.SQLITE);

    @Test
    void heldRowsMigrateExactlyOnce() throws Exception {
        Path sourceFile = tempDatabase("bettertrades-migration-source");
        Path targetFile = tempDatabase("bettertrades-migration-target");
        byte[] components = {1, 2, 3, 4};

        try (Connection source = open(sourceFile); Connection target = open(targetFile)) {
            givenPlayer(source, "11111111-1111-1111-1111-111111111111", "Alpha");
            givenItem(source, "minecraft:diamond");
            givenEscrow(source, "ESCROW_HELD", "11111111-1111-1111-1111-111111111111",
                    "minecraft:diamond", 64, components, "HELD");
            // An already closed row must not travel: those items have been delivered.
            givenEscrow(source, "ESCROW_CONSUMED", "11111111-1111-1111-1111-111111111111",
                    "minecraft:diamond", 12, components, "CONSUMED");

            assertFalse(EscrowMigration.alreadyDone(target, MARKER), "the marker is not there before the migration");

            List<EscrowMigration.Row> held = EscrowMigration.selectHeld(source);
            assertEquals(1, held.size(), "only the HELD rows are read");
            assertEquals("ESCROW_HELD", held.get(0).id());
            assertEquals("Alpha", held.get(0).playerName());
            assertEquals(64, held.get(0).amount());
            assertArrayEquals(components, held.get(0).components(), "the item components are not lost");

            EscrowMigration.insertAll(target, held);
            EscrowMigration.markMigrated(source, held);
            EscrowMigration.markDone(target, MARKER);

            // The target has the row, and the foreign keys are satisfied: without the player and
            // item rows the INSERT would have failed with foreign_keys = ON.
            assertEquals("HELD", stateOf(target, "ESCROW_HELD"));
            assertArrayEquals(components, componentsOf(target, "ESCROW_HELD"));
            assertEquals(1, countEscrow(target), "only the HELD row was copied");

            // The source no longer hands it back: that is what prevents the double return.
            assertEquals("MIGRATED", stateOf(source, "ESCROW_HELD"));
            assertEquals("CONSUMED", stateOf(source, "ESCROW_CONSUMED"), "closed rows are left alone");
            assertTrue(EscrowMigration.selectHeld(source).isEmpty());
            assertTrue(EscrowMigration.alreadyDone(target, MARKER), "the marker is there after the migration");
        } finally {
            Files.deleteIfExists(sourceFile);
            Files.deleteIfExists(targetFile);
        }
    }

    @Test
    void aSecondPassDuplicatesNothing() throws Exception {
        Path sourceFile = tempDatabase("bettertrades-migration-twice-source");
        Path targetFile = tempDatabase("bettertrades-migration-twice-target");

        try (Connection source = open(sourceFile); Connection target = open(targetFile)) {
            givenPlayer(source, "22222222-2222-2222-2222-222222222222", "Beta");
            givenItem(source, "minecraft:emerald");
            givenEscrow(source, "ESCROW_TWICE", "22222222-2222-2222-2222-222222222222",
                    "minecraft:emerald", 8, new byte[]{9}, "HELD");

            List<EscrowMigration.Row> held = EscrowMigration.selectHeld(source);
            EscrowMigration.insertAll(target, held);
            // The second pass simulates a migration interrupted after the copy and before the
            // marker: the state in which the next restart comes back here holding the same rows.
            EscrowMigration.insertAll(target, held);
            EscrowMigration.markDone(target, MARKER);
            EscrowMigration.markDone(target, MARKER);

            assertEquals(1, countEscrow(target), "the row is not duplicated on the second pass");
            assertEquals(1, countMarker(target), "there is still only one marker");
        } finally {
            Files.deleteIfExists(sourceFile);
            Files.deleteIfExists(targetFile);
        }
    }

    /**
     * The migration marker holds for one backend only.
     *
     * With a single marker, a server that started in fallback migrated the SQLite rows, marked
     * itself done, and on coming back to MariaDB never looked in there again: MariaDB's HELD rows
     * stayed unreachable. The same held the other way round for a server always on SQLite.
     */
    @Test
    void theMigrationMarkerIsPerBackend() throws Exception {
        Path targetFile = tempDatabase("bettertrades-migration-marker");
        try (Connection target = open(targetFile)) {
            String sqlite = EscrowMigration.markerFor(Database.Backend.SQLITE);
            String mariadb = EscrowMigration.markerFor(Database.Backend.MARIADB);
            assertNotEquals(sqlite, mariadb, "the two backends do not share the marker");

            EscrowMigration.markDone(target, sqlite);

            assertTrue(EscrowMigration.alreadyDone(target, sqlite), "SQLite is marked done");
            assertFalse(EscrowMigration.alreadyDone(target, mariadb),
                    "MariaDB is not marked: its migration still has to run");
        } finally {
            Files.deleteIfExists(targetFile);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static Path tempDatabase(String prefix) throws Exception {
        Path file = Files.createTempFile(prefix, ".db");
        Files.delete(file);
        return file;
    }

    private static Connection open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA foreign_keys = ON");
        }
        Schema.applyTo(connection, Dialect.SQLITE);
        return connection;
    }

    private static void givenPlayer(Connection connection, String uuid, String name) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO player (uuid, current_name, first_seen) VALUES (?, ?, 1)")) {
            insert.setString(1, uuid);
            insert.setString(2, name);
            insert.executeUpdate();
        }
    }

    private static void givenItem(Connection connection, String identifier) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO item (identifier, first_seen) VALUES (?, 1)")) {
            insert.setString(1, identifier);
            insert.executeUpdate();
        }
    }

    private static void givenEscrow(Connection connection, String id, String owner, String itemId,
                                    int amount, byte[] components, String state) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO escrow (id, player_uuid, trade_id, item_id, amount, components, state,"
                        + " created_at, resolved_at) VALUES (?, ?, 'TRADE1', ?, ?, ?, ?, 1, NULL)")) {
            insert.setString(1, id);
            insert.setString(2, owner);
            insert.setString(3, itemId);
            insert.setInt(4, amount);
            insert.setBytes(5, components);
            insert.setString(6, state);
            insert.executeUpdate();
        }
    }

    private static String stateOf(Connection connection, String id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT state FROM escrow WHERE id = ?")) {
            select.setString(1, id);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static byte[] componentsOf(Connection connection, String id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT components FROM escrow WHERE id = ?")) {
            select.setString(1, id);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getBytes(1) : null;
            }
        }
    }

    private static int countEscrow(Connection connection) throws SQLException {
        try (ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM escrow")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static int countMarker(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM schema_meta WHERE meta_key = ?")) {
            select.setString(1, MARKER);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
