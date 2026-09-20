package com.bettertrades.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaTest {

    @Test
    void theSchemaAppliesOnSqlite() throws Exception {
        Path file = Files.createTempFile("bettertrades-test", ".db");
        Files.delete(file);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA foreign_keys = ON");
            }
            Schema.applyTo(connection, Dialect.SQLITE);
            Schema.applyTo(connection, Dialect.SQLITE);

            Set<String> tables = tablesOf(connection);
            assertTrue(tables.contains("trade"));
            assertTrue(tables.contains("trade_pokemon_snapshot"));
            assertTrue(tables.contains("trade_money"));
            assertTrue(tables.contains("blacklist_pokemon_aspect"));
            assertTrue(tables.contains("api_client"));
            assertTrue(tables.contains("replay_pending"));

            try (ResultSet versions = connection.createStatement()
                    .executeQuery("SELECT meta_value FROM schema_meta WHERE meta_key = 'version'")) {
                assertTrue(versions.next());
                assertEquals(Schema.VERSION, versions.getString(1));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void foreignKeysAreEnforced() throws Exception {
        Path file = Files.createTempFile("bettertrades-fk", ".db");
        Files.delete(file);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA foreign_keys = ON");
            }
            Schema.applyTo(connection, Dialect.SQLITE);

            try (Statement insert = connection.createStatement()) {
                assertThrows(SQLException.class, () -> insert.execute(
                        "INSERT INTO trade_participant (trade_id, player_uuid, side)"
                                + " VALUES ('01ABC', '00000000-0000-0000-0000-000000000000', 0)"));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * A database of another version does not open.
     *
     * CREATE TABLE IF NOT EXISTS does not add columns to a table that already exists: without this
     * refusal, an old schema opened silently and the first INSERT naming a new column failed at
     * runtime, once per trade, with an error that said nothing about versions.
     */
    @Test
    void aDatabaseOfAnotherVersionDoesNotOpen() throws Exception {
        Path file = Files.createTempFile("bettertrades-version", ".db");
        Files.delete(file);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            Schema.applyTo(connection, Dialect.SQLITE);

            try (Statement update = connection.createStatement()) {
                update.execute("UPDATE schema_meta SET meta_value = '0' WHERE meta_key = 'version'");
            }

            SQLException refused = assertThrows(SQLException.class,
                    () -> Schema.applyTo(connection, Dialect.SQLITE));
            assertTrue(refused.getMessage().contains("version 0"),
                    "the message must name the version it found: " + refused.getMessage());

            // The code's own version reopens without fuss: the refusal is about the mismatch,
            // not about schema_meta already being populated.
            try (Statement restore = connection.createStatement()) {
                restore.execute("UPDATE schema_meta SET meta_value = '" + Schema.VERSION
                        + "' WHERE meta_key = 'version'");
            }
            Schema.applyTo(connection, Dialect.SQLITE);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void theMariadbDialectLeavesNoOpenTokens() {
        for (String statement : Schema.statements(Dialect.MARIADB)) {
            assertFalse(statement.contains("{"), "unresolved token in: " + statement);
            assertFalse(statement.contains("IF NOT EXISTS") && statement.startsWith("CREATE INDEX"),
                    "MariaDB does not know CREATE INDEX IF NOT EXISTS: " + statement);
        }
    }

    private static Set<String> tablesOf(Connection connection) throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = connection.createStatement()
                .executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }
}
