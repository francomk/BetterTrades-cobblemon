package com.bettertrades.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The local copy of the blacklist that a server starting in fallback reads. The primary is a second
 * SQLite file here: the mirror only reads it with plain SELECTs.
 */
class BlacklistMirrorTest {

    @Test
    void activeRulesReachTheLocalCopy() throws Exception {
        Path primaryFile = temp("primary");
        Path localFile = temp("local");
        try (Connection primary = open(primaryFile)) {
            addItemRule(primary, "RULE_A", "minecraft:diamond");
            addItemRule(primary, "RULE_B", "minecraft:netherite_ingot");

            Replay.mirrorBlacklist(primary, localFile);
            // Twice: the copy is repeated on every reload and must not duplicate anything.
            Replay.mirrorBlacklist(primary, localFile);

            try (Connection local = open(localFile)) {
                assertEquals(Set.of("RULE_A", "RULE_B"), activeItemRules(local));
            }
        } finally {
            Files.deleteIfExists(primaryFile);
            Files.deleteIfExists(localFile);
        }
    }

    @Test
    void revocationsOnThePrimaryReachTheLocalCopyButQueuedRulesStay() throws Exception {
        Path primaryFile = temp("primary");
        Path localFile = temp("local");
        try (Connection primary = open(primaryFile)) {
            addItemRule(primary, "RULE_A", "minecraft:diamond");
            Replay.mirrorBlacklist(primary, localFile);

            try (Connection local = open(localFile)) {
                // Born in a fallback and not replayed yet: the primary does not know it.
                addItemRule(local, "RULE_LOCAL", "minecraft:emerald");
                try (PreparedStatement queue = local.prepareStatement(
                        "INSERT INTO replay_pending (kind, entity_id, created_at) VALUES (?, ?, ?)")) {
                    queue.setString(1, "BLACKLIST_ITEM");
                    queue.setString(2, "RULE_LOCAL");
                    queue.setLong(3, 1L);
                    queue.executeUpdate();
                }
            }

            try (PreparedStatement revoke = primary.prepareStatement(
                    "UPDATE blacklist_item SET revoked_at = 1 WHERE id = 'RULE_A'")) {
                revoke.executeUpdate();
            }
            Replay.mirrorBlacklist(primary, localFile);

            try (Connection local = open(localFile)) {
                assertEquals(Set.of("RULE_LOCAL"), activeItemRules(local));
            }
        } finally {
            Files.deleteIfExists(primaryFile);
            Files.deleteIfExists(localFile);
        }
    }

    private static Path temp(String name) throws Exception {
        Path file = Files.createTempFile("bettertrades-mirror-" + name, ".db");
        Files.delete(file);
        return file;
    }

    private static Connection open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        Schema.applyTo(connection, Dialect.SQLITE);
        return connection;
    }

    private static void addItemRule(Connection connection, String id, String itemId) throws SQLException {
        try (PreparedStatement item = connection.prepareStatement(
                "INSERT OR IGNORE INTO item (identifier, first_seen) VALUES (?, 1)")) {
            item.setString(1, itemId);
            item.executeUpdate();
        }
        try (PreparedStatement rule = connection.prepareStatement(
                "INSERT INTO blacklist_item (id, item_id, reason, added_by, created_at, revoked_at)"
                        + " VALUES (?, ?, NULL, 'test', 1, NULL)")) {
            rule.setString(1, id);
            rule.setString(2, itemId);
            rule.executeUpdate();
        }
    }

    private static Set<String> activeItemRules(Connection connection) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (ResultSet rows = connection.createStatement()
                .executeQuery("SELECT id FROM blacklist_item WHERE revoked_at IS NULL")) {
            while (rows.next()) ids.add(rows.getString(1));
        }
        return ids;
    }
}
