package com.bettertrades.api;

import com.bettertrades.BetterTrades;
import com.bettertrades.config.BetterTradesConfig;
import com.bettertrades.db.Database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The API reads go through a connection of their own, with a MariaDB user that only has SELECT
 * permission: that way not even a bug of ours can write to the history from the side reserved
 * for other mods.
 *
 * On the SQLite fallback that separation does not exist — a file has no users — so the shared
 * connection is used instead. The interface barrier still holds: it exposes no write methods.
 */
public final class ReadOnlyDb {

    private static final AtomicBoolean SHARED_WARNING_LOGGED = new AtomicBoolean();

    // Not final: closed in SERVER_STOPPED, it has to be recreated if another server starts in the same JVM.
    private static volatile ExecutorService worker = newWorker();

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BetterTrades API");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Called on every server start: brings back the executor the previous one closed. */
    public static synchronized void reopen() {
        if (worker.isShutdown()) worker = newWorker();
        SHARED_WARNING_LOGGED.set(false);
    }

    private static Connection connection;

    private ReadOnlyDb() {}

    public static <T> CompletableFuture<T> supply(Database.Query<T> query) {
        if (!useDedicatedConnection()) {
            if (SHARED_WARNING_LOGGED.compareAndSet(false, true)) {
                BetterTrades.LOGGER.warn("Read-only API on the shared connection:"
                        + " no read-only user configured, or the backend is SQLite");
            }
            return Database.supply(query);
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return query.run(connect());
                } catch (SQLException e) {
                    closeQuietly();
                    throw new IllegalStateException("API read failed", e);
                }
            }, worker);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Same as Database and EscrowDb: a rejected executor belongs in the future, not in the
            // caller's face, and here the caller is another mod.
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The API thread is already closed", e));
        }
    }

    private static boolean useDedicatedConnection() {
        BetterTradesConfig.Database settings = BetterTradesConfig.get().database;
        return Database.backend() == Database.Backend.MARIADB
                && settings.readUser != null && !settings.readUser.isBlank();
    }

    private static Connection connect() throws SQLException {
        if (connection != null && connection.isValid(3)) return connection;
        // isValid being false means that connection is of no further use, not that it can be left
        // to drift: without this close one leaked on every expiry. MariaDB closes connections idle
        // past wait_timeout, and this one is only used when another mod queries the API, so expiry
        // is the ordinary case, not the rare one.
        closeQuietly();
        BetterTradesConfig.Database settings = BetterTradesConfig.get().database;
        String url = "jdbc:mariadb://" + settings.host + ":" + settings.port + "/" + settings.name
                + "?connectTimeout=5000&socketTimeout=15000";
        connection = DriverManager.getConnection(url, settings.readUser, settings.readPassword);
        connection.setReadOnly(true);
        BetterTrades.LOGGER.info("Read-only API connection opened as '{}'", settings.readUser);
        return connection;
    }

    public static void close() {
        worker.execute(ReadOnlyDb::closeQuietly);
        worker.shutdown();
    }

    private static void closeQuietly() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            BetterTrades.LOGGER.warn("Closing the API connection failed: {}", e.getMessage());
        }
        connection = null;
    }
}
