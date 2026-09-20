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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UNREADABLE is the terminal state of an escrow row that can no longer be read back.
 *
 * Two things could break it and neither shows up at compile time: the column not being wide
 * enough, and the row still being picked up by the login query. If the second one fails, the
 * player retries the decode on every join, which is exactly the defect this state was meant to
 * close.
 */
class EscrowStateTest {

    private static final String OWNER = "33333333-3333-3333-3333-333333333333";

    @Test
    void escrowClosedAsUnreadableLeavesTheLoginQueue() throws Exception {
        Path file = Files.createTempFile("bettertrades-escrow-state", ".db");
        Files.delete(file);
        try (Connection escrow = open(file)) {
            givenPlayer(escrow);
            givenItem(escrow);
            givenHeld(escrow, "ESCROW_OK");
            givenHeld(escrow, "ESCROW_BROKEN");

            assertTrue(openIds(escrow).contains("ESCROW_BROKEN"), "beforehand it is still queued");

            // The same UPDATE as EscrowService.setState, with the same guard on HELD.
            assertEquals(1, setState(escrow, "ESCROW_BROKEN", "UNREADABLE"));

            assertEquals("UNREADABLE", stateOf(escrow, "ESCROW_BROKEN"),
                    "the column accepts the state: {SHORT} is wide enough");
            assertFalse(openIds(escrow).contains("ESCROW_BROKEN"),
                    "the login no longer picks it up: no retry on every join");
            assertTrue(openIds(escrow).contains("ESCROW_OK"), "the others stay where they were");

            // The guard on HELD makes it idempotent: a second pass touches nothing.
            assertEquals(0, setState(escrow, "ESCROW_BROKEN", "RETURNED"));
            assertEquals("UNREADABLE", stateOf(escrow, "ESCROW_BROKEN"),
                    "a terminal state cannot be rewritten");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * The UPDATE's rowcount is the lock between two paths wanting the same escrow row.
     *
     * EscrowService.claim hands back ONLY the ids that moved from HELD to the requested state. Two
     * paths contending for the same row - the disconnect cancel still queued and the return at the
     * next login - must see only one of them claim it, otherwise the item is delivered twice: the
     * database row was already protected by the guard on HELD, the in-memory delivery was not.
     */
    @Test
    void onlyOnePathClaimsTheSameEscrow() throws Exception {
        Path file = Files.createTempFile("bettertrades-escrow-claim", ".db");
        Files.delete(file);
        try (Connection escrow = open(file)) {
            givenPlayer(escrow);
            givenItem(escrow);
            givenHeld(escrow, "ESCROW_CONTENDED");

            // First path: the queued cancel. It claims, so it delivers.
            assertEquals(1, setState(escrow, "ESCROW_CONTENDED", "RETURNED"));
            // Second path: returnOpen at login. It claims nothing, so it delivers nothing.
            assertEquals(0, setState(escrow, "ESCROW_CONTENDED", "RETURNED"));

            assertEquals("RETURNED", stateOf(escrow, "ESCROW_CONTENDED"));
            assertFalse(openIds(escrow).contains("ESCROW_CONTENDED"),
                    "closed once only: no third path picks it up");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * An interrupted delivery leaves the row HELD, and HELD means "it comes back at the next login".
     *
     * It is the branch every non-delivering path rests on: a disconnected player, a failed close, an
     * offer landing on an already closed session. If an unclaimed row left the login queue, the item
     * would be lost rather than postponed.
     */
    @Test
    void unclaimedEscrowStaysInTheLoginQueue() throws Exception {
        Path file = Files.createTempFile("bettertrades-escrow-held", ".db");
        Files.delete(file);
        try (Connection escrow = open(file)) {
            givenPlayer(escrow);
            givenItem(escrow);
            givenHeld(escrow, "ESCROW_UNDELIVERED");

            assertTrue(openIds(escrow).contains("ESCROW_UNDELIVERED"),
                    "nobody closed it: the login picks it up");
            assertEquals("HELD", stateOf(escrow, "ESCROW_UNDELIVERED"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static Connection open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA foreign_keys = ON");
        }
        Schema.applyTo(connection, Dialect.SQLITE);
        return connection;
    }

    private static void givenPlayer(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO player (uuid, current_name, first_seen) VALUES (?, 'Verdi', 1)")) {
            insert.setString(1, OWNER);
            insert.executeUpdate();
        }
    }

    private static void givenItem(Connection connection) throws SQLException {
        try (Statement insert = connection.createStatement()) {
            insert.executeUpdate("INSERT INTO item (identifier, first_seen) VALUES ('minecraft:stone', 1)");
        }
    }

    private static void givenHeld(Connection connection, String id) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO escrow (id, player_uuid, trade_id, item_id, amount, components, state,"
                        + " created_at, resolved_at)"
                        + " VALUES (?, ?, 'TRADE1', 'minecraft:stone', 1, NULL, 'HELD', 1, NULL)")) {
            insert.setString(1, id);
            insert.setString(2, OWNER);
            insert.executeUpdate();
        }
    }

    private static int setState(Connection connection, String id, String state) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE escrow SET state = ?, resolved_at = ? WHERE id = ? AND state = 'HELD'")) {
            update.setString(1, state);
            update.setLong(2, 2);
            update.setString(3, id);
            return update.executeUpdate();
        }
    }

    /** The login query: HELD only. */
    private static java.util.List<String> openIds(Connection connection) throws SQLException {
        java.util.List<String> ids = new java.util.ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM escrow WHERE player_uuid = ? AND state = 'HELD'")) {
            select.setString(1, OWNER);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
        }
        return ids;
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
}
