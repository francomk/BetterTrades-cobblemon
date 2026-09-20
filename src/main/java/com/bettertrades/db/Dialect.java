package com.bettertrades.db;

import java.util.Map;

/**
 * Translates the single schema into the types of the two backends. Every token in schema.sql must
 * resolve here: an unknown token fails startup instead of leaving broken SQL behind.
 */
public enum Dialect {

    MARIADB(Map.ofEntries(
            Map.entry("UUID", "CHAR(36)"),
            Map.entry("ULID", "CHAR(26)"),
            Map.entry("IDENT", "VARCHAR(96)"),
            Map.entry("NAME", "VARCHAR(16)"),
            Map.entry("SHORT", "VARCHAR(64)"),
            Map.entry("LONGTEXT", "VARCHAR(512)"),
            Map.entry("HASH", "CHAR(64)"),
            Map.entry("INT", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("BOOL", "TINYINT"),
            Map.entry("BLOB", "LONGBLOB"),
            Map.entry("OPTS", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"))),

    SQLITE(Map.ofEntries(
            Map.entry("UUID", "TEXT"),
            Map.entry("ULID", "TEXT"),
            Map.entry("IDENT", "TEXT"),
            Map.entry("NAME", "TEXT"),
            Map.entry("SHORT", "TEXT"),
            Map.entry("LONGTEXT", "TEXT"),
            Map.entry("HASH", "TEXT"),
            Map.entry("INT", "INTEGER"),
            Map.entry("BIGINT", "INTEGER"),
            Map.entry("BOOL", "INTEGER"),
            Map.entry("BLOB", "BLOB"),
            Map.entry("OPTS", "")));

    private final Map<String, String> types;

    Dialect(Map<String, String> types) {
        this.types = types;
    }

    /** MariaDB does not know CREATE INDEX IF NOT EXISTS. */
    public boolean supportsCreateIndexIfNotExists() {
        return this == SQLITE;
    }

    public String insertIgnorePrefix() {
        return this == MARIADB ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ";
    }

    public String render(String statement) {
        StringBuilder out = new StringBuilder(statement.length());
        int i = 0;
        while (i < statement.length()) {
            char c = statement.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int end = statement.indexOf('}', i);
            if (end < 0) {
                throw new IllegalStateException("Unclosed token in the schema: " + statement.substring(i));
            }
            String token = statement.substring(i + 1, end);
            String replacement = types.get(token);
            if (replacement == null) {
                throw new IllegalStateException("Unknown schema token: {" + token + "}");
            }
            out.append(replacement);
            i = end + 1;
        }
        return out.toString();
    }
}
