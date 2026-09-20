package com.bettertrades.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truncation is the last thing standing between a long nickname and a trade with no history: on
 * MariaDB in strict mode an oversized value is not cut, it fails the INSERT.
 */
class ColumnsTest {

    @Test
    void shortValuesAreLeftAlone() {
        assertNull(Columns.truncate(null, Columns.SHORT));
        String short_ = "Pikachu";
        assertSame(short_, Columns.truncate(short_, Columns.SHORT), "no pointless copy");
        String exact = "x".repeat(Columns.NAME);
        assertSame(exact, Columns.truncate(exact, Columns.NAME), "an exact fit passes through whole");
    }

    @Test
    void longValuesAreCutToWidth() {
        String long_ = "y".repeat(Columns.SHORT + 40);
        assertEquals(Columns.SHORT, Columns.truncate(long_, Columns.SHORT).length());
    }

    @Test
    void theCutDoesNotBreakASurrogatePair() {
        // An emoji takes two chars: cutting between them would leave half a character, which the
        // driver either writes as a replacement or refuses.
        String emoji = "😀";
        String value = "a".repeat(Columns.NAME - 1) + emoji;
        String cut = Columns.truncate(value, Columns.NAME);

        assertEquals(Columns.NAME - 1, cut.length(), "the high surrogate is left out");
        assertTrue(cut.chars().noneMatch(c -> Character.isSurrogate((char) c)), "no orphan surrogate");

        // When the pair fits entirely, on the other hand, it stays whole.
        String inside = "b".repeat(Columns.NAME - 2) + emoji + "ccc";
        assertEquals(emoji, Columns.truncate(inside, Columns.NAME).substring(Columns.NAME - 2));
    }

    @Test
    void theSchemaHasTheColumnsTheCodeWrites() throws Exception {
        Path file = Files.createTempFile("bettertrades-columns", ".db");
        Files.delete(file);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            Schema.applyTo(connection, Dialect.SQLITE);

            // Without this column every trade involving money would fail to write its history.
            assertTrue(columnsOf(connection, "trade_money").contains("transferred"));
            assertTrue(columnsOf(connection, "trade_pokemon").contains("original_trainer"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void onMariadbNicknameAndOriginalTrainerAreNoLongerNarrow() {
        String pokemon = Schema.statements(Dialect.MARIADB).stream()
                .filter(statement -> statement.contains("CREATE TABLE IF NOT EXISTS trade_pokemon ("))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the CREATE TABLE of trade_pokemon is missing"));

        // The nickname has no server-side limit: VARCHAR(64) made it fail in strict mode.
        assertTrue(pokemon.contains("nickname           VARCHAR(512)"), pokemon);
        // In Cobblemon the original trainer can be a name, not a UUID: CHAR(36) padded it with
        // spaces up to 36 characters.
        assertTrue(pokemon.contains("original_trainer   VARCHAR(64)"), pokemon);
    }

    private static Set<String> columnsOf(Connection connection, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rows.next()) names.add(rows.getString("COLUMN_NAME"));
        }
        return names;
    }
}
