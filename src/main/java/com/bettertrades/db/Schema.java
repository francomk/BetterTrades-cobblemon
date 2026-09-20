package com.bettertrades.db;

import com.bettertrades.BetterTrades;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class Schema {

    public static final String VERSION = "1";

    private static final String RESOURCE = "/data/bettertrades/schema.sql";
    /** MariaDB: "Duplicate key name", that is, the index already exists. */
    private static final int MARIADB_DUPLICATE_INDEX = 1061;

    private Schema() {}

    /**
     * Applies the schema, but first checks that the database is not of another version.
     *
     * Without that check the version row was written and never read back, which made it useless.
     * {@code CREATE TABLE IF NOT EXISTS} does not add columns to a table that already exists: an
     * old database would open silently, without the new columns, and the first INSERT naming them
     * would fail at runtime - once per trade, with an error that says nothing about versions.
     * Better not to open it: the caller treats the exception as "database unavailable", which is
     * loud and reversible.
     */
    public static void applyTo(Connection connection, Dialect dialect) throws SQLException {
        String existing = storedVersion(connection);
        if (existing != null && !existing.equals(VERSION)) {
            throw new SQLException("Database schema is at version " + existing + ", this version of"
                    + " the mod wants " + VERSION + ". The database is NOT opened:"
                    + " carrying on would mean writing to a schema that does not match. A"
                    + " migration is needed, or a fresh database.");
        }
        for (String statement : statements(dialect)) {
            try (Statement s = connection.createStatement()) {
                s.execute(statement);
            } catch (SQLException e) {
                if (isIndexAlreadyThere(dialect, statement, e)) continue;
                throw new SQLException("Schema failed on: " + firstLine(statement), e);
            }
        }
        try (Statement s = connection.createStatement()) {
            s.execute(dialect.insertIgnorePrefix()
                    + "schema_meta (meta_key, meta_value) VALUES ('version', '" + VERSION + "')");
        }
        BetterTrades.LOGGER.info("Schema version {} applied on {}", VERSION, dialect);
    }

    /** The version already written, or null when the database is new and schema_meta does not exist. */
    private static String storedVersion(Connection connection) {
        try (Statement s = connection.createStatement();
             ResultSet rows = s.executeQuery(
                     "SELECT meta_value FROM schema_meta WHERE meta_key = 'version'")) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) {
            // New database: schema_meta is not there yet, and that is why the SELECT fails.
            return null;
        }
    }

    private static boolean isIndexAlreadyThere(Dialect dialect, String statement, SQLException e) {
        return dialect == Dialect.MARIADB
                && statement.startsWith("CREATE INDEX")
                && e.getErrorCode() == MARIADB_DUPLICATE_INDEX;
    }

    static List<String> statements(Dialect dialect) {
        String sql = read();
        List<String> out = new ArrayList<>();
        for (String raw : sql.split(";")) {
            String cleaned = stripComments(raw).trim();
            if (cleaned.isEmpty()) continue;
            String rendered = dialect.render(cleaned);
            if (!dialect.supportsCreateIndexIfNotExists()) {
                rendered = rendered.replace("CREATE INDEX IF NOT EXISTS ", "CREATE INDEX ");
            }
            out.add(rendered);
        }
        return out;
    }

    private static String stripComments(String block) {
        StringBuilder out = new StringBuilder(block.length());
        for (String line : block.split("\n")) {
            if (line.stripLeading().startsWith("--")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String firstLine(String statement) {
        int newline = statement.indexOf('\n');
        return newline < 0 ? statement : statement.substring(0, newline);
    }

    private static String read() {
        try (InputStream in = Schema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Reading " + RESOURCE + " failed", e);
        }
    }
}
