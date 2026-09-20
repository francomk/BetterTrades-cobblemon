package com.bettertrades.api;

import com.bettertrades.BetterTrades;
import com.bettertrades.db.Columns;
import com.bettertrades.db.Database;
import com.bettertrades.lang.Lang;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mod keys: hashed in the {@code api_client} table, editable in game without a restart.
 *
 * This is not a security barrier. Two mods in the same JVM can read each other's memory: this
 * stops accidental access and integrations written without reading the documentation, not a
 * hostile mod.
 *
 * The in-memory cache also exists to hold a revocation while the database is down: the last known
 * truth stays valid until it can be read again.
 */
public final class ApiKeys {

    public static final int LEVEL_QUERY = 1;
    public static final int LEVEL_EVENTS = 2;

    private static final Map<String, Registered> CACHE = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Registered(String hash, int level) {}

    private ApiKeys() {}

    /**
     * In fallback the cache is NOT replaced: SQLite never received the keys written to MariaDB, and
     * a clear() followed by an empty map would silently revoke every integration. The last known
     * truth stays valid until MariaDB comes back.
     */
    public static CompletableFuture<Void> reload() {
        return Database.<Map<String, Registered>>supply(ApiKeys::readAll)
                .thenAccept(loaded -> {
                    if (Database.degraded() && !CACHE.isEmpty()) {
                        BetterTrades.LOGGER.warn("Database in fallback: the {} API keys in memory"
                                + " stay valid, the {} read from SQLite are ignored",
                                CACHE.size(), loaded.size());
                        return;
                    }
                    CACHE.clear();
                    CACHE.putAll(loaded);
                })
                .exceptionally(error -> {
                    BetterTrades.LOGGER.error("API keys not reloaded: the in-memory copy stays", error);
                    return null;
                });
    }

    /** The plain key exists only here and in the message to the administrator: the database gets the hash. */
    public static CompletableFuture<String> issue(String modid, int level) {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String key = HexFormat.of().formatHex(raw);
        String hash = hash(key);

        String storedModid = Columns.truncate(modid, Columns.SHORT);
        return Database.supply(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM api_client WHERE modid = ?")) {
                delete.setString(1, storedModid);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO api_client (modid, key_hash, level, created_at, revoked_at)"
                            + " VALUES (?, ?, ?, ?, NULL)")) {
                insert.setString(1, storedModid);
                insert.setString(2, hash);
                insert.setInt(3, level);
                insert.setLong(4, System.currentTimeMillis());
                insert.executeUpdate();
            }
            Database.markForReplay(connection, "API_CLIENT", storedModid);
            return key;
        }).thenCompose(issued -> reload().thenApply(ignored -> issued));
    }

    /**
     * Immediate revocation: the cache is updated before the database is even read again.
     *
     * In fallback a revocation is refused rather than executed, for the same reason as
     * Blacklist.revoke: the replay queue can copy inserts, not updates, so a revoked_at written to
     * SQLite never reaches MariaDB. The administrator would read "revoked", and on recovery reload()
     * would read the real table - where revoked_at is still NULL - putting a key whose access had
     * been taken away back into the cache.
     */
    public static CompletableFuture<Boolean> revoke(String modid) {
        if (Database.degraded()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(Lang.raw("command.api.degraded")));
        }
        CACHE.remove(modid);
        // The same width it was written with: a revocation on an untruncated modid would not find
        // the row issued by issue().
        String storedModid = Columns.truncate(modid, Columns.SHORT);
        return Database.supply(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE api_client SET revoked_at = ? WHERE modid = ? AND revoked_at IS NULL")) {
                update.setLong(1, System.currentTimeMillis());
                update.setString(2, storedModid);
                return update.executeUpdate() > 0;
            }
        }).thenCompose(revoked -> reload().thenApply(ignored -> revoked));
    }

    public static Map<String, Integer> registered() {
        Map<String, Integer> levels = new java.util.LinkedHashMap<>();
        CACHE.forEach((modid, entry) -> levels.put(modid, entry.level()));
        return levels;
    }

    static void requireLevel(String modid, String key, int level) {
        Registered registered = CACHE.get(modid);
        if (registered == null || !registered.hash().equals(hash(key))) {
            BetterTrades.LOGGER.warn("API access refused to '{}': key missing or wrong", modid);
            throw new SecurityException("BetterTrades: invalid API key for " + modid);
        }
        if (registered.level() < level) {
            BetterTrades.LOGGER.warn("API access refused to '{}': level {} required, has {}",
                    modid, level, registered.level());
            throw new SecurityException("BetterTrades: insufficient level for " + modid);
        }
    }

    private static Map<String, Registered> readAll(Connection connection) throws SQLException {
        Map<String, Registered> loaded = new java.util.LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT modid, key_hash, level FROM api_client WHERE revoked_at IS NULL");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                loaded.put(rows.getString(1), new Registered(rows.getString(2), rows.getInt(3)));
            }
        }
        return loaded;
    }

    private static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
