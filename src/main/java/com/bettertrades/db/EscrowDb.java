package com.bettertrades.db;

import com.bettertrades.BetterTrades;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Item escrow ALWAYS lives here, in a local SQLite file, even when the rest of the mod writes to
 * MariaDB.
 *
 * The reason is that escrow state is the only thing with a physical counterpart in the player's
 * inventory, and of those there is exactly one. Writing the row to MariaDB and reading it back
 * after a failover on SQLite - or the other way round - means handing back twice what has already
 * been delivered, or never handing it back at all. Keeping it in a place that never changes makes
 * that class of bug impossible.
 *
 * A separate file from the fallback's: that way the two connections do not contend for the same
 * file while MariaDB is down.
 */
public final class EscrowDb {

    /** How long shutdown waits for the escrow queue to drain. */
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private static volatile ExecutorService worker = newWorker();

    private static Path file;
    private static Connection connection;
    private static volatile boolean usable;

    private EscrowDb() {}

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BetterTrades escrow");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** False when the local file will not open: without escrow, trades must not start. */
    public static boolean usable() {
        return usable;
    }

    public static Path file() {
        return file;
    }

    /**
     * Opens the local file. Recreates the executor when a previous start had closed it: in the same
     * JVM you can go from SERVER_STOPPED to a new SERVER_STARTING (singleplayer, LAN world).
     */
    public static CompletableFuture<Void> open(Path escrowFile) {
        file = escrowFile;
        if (worker.isShutdown()) worker = newWorker();
        return submit(() -> CompletableFuture.runAsync(() -> {
            if (!connect()) {
                BetterTrades.LOGGER.error("Escrow store could not be opened at {}: trades stay blocked",
                        escrowFile);
            }
        }, worker));
    }

    public static <T> CompletableFuture<T> supply(Database.Query<T> query) {
        return submit(() -> CompletableFuture.supplyAsync(() -> withConnection(query), worker));
    }

    public static CompletableFuture<Void> execute(Database.Work work) {
        return submit(() -> CompletableFuture.runAsync(() -> withConnection(connection -> {
            work.run(connection);
            return null;
        }), worker));
    }

    /**
     * Like {@code Database.submit}: an already closed executor must give a failed future, not a
     * synchronous exception.
     *
     * It weighs more here than elsewhere. hold() is called with the item ALREADY out of the
     * inventory, and the branch that gives it back lives in the future's callback: with the
     * synchronous exception that branch was never reached and the item vanished, without even an
     * escrow row to describe it.
     */
    private static <T> CompletableFuture<T> submit(java.util.function.Supplier<CompletableFuture<T>> task) {
        try {
            return task.get();
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The escrow thread is already closed", e));
        }
    }

    /** Like {@link Database#close()}: the queue holds the resolves, and losing them duplicates items. */
    public static void close() {
        worker.execute(EscrowDb::closeConnection);
        worker.shutdown();
        usable = false;
        try {
            if (!worker.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                BetterTrades.LOGGER.error("The escrow thread did not drain the queue in {}s:"
                        + " some escrow rows may have been left HELD", SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- escrow thread

    /** One transaction per Work, like {@link Database}: the retry must not repeat half the job. */
    private static <T> T withConnection(Database.Query<T> query) {
        if (connection == null && !connect()) {
            throw new IllegalStateException("Escrow store unavailable");
        }
        try {
            return inTransaction(query);
        } catch (SQLException first) {
            BetterTrades.LOGGER.warn("Escrow query failed: {}", first.getMessage());
            closeConnection();
            if (!connect()) throw new IllegalStateException("Escrow store unreachable", first);
            try {
                return inTransaction(query);
            } catch (SQLException second) {
                throw new IllegalStateException("Escrow query failed twice", second);
            }
        }
    }

    private static <T> T inTransaction(Database.Query<T> query) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = query.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException e) {
                BetterTrades.LOGGER.warn("Escrow rollback failed: {}", e.getMessage());
            }
            throw failure;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException e) {
                BetterTrades.LOGGER.warn("Escrow autocommit not restored: {}", e.getMessage());
            }
        }
    }

    private static boolean connect() {
        try {
            Files.createDirectories(file.getParent());
            Connection opened = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement pragma = opened.createStatement()) {
                pragma.execute("PRAGMA foreign_keys = ON");
                pragma.execute("PRAGMA journal_mode = WAL");
                pragma.execute("PRAGMA busy_timeout = 5000");
            }
            Schema.applyTo(opened, Dialect.SQLITE);
            connection = opened;
            usable = true;
            BetterTrades.LOGGER.info("Escrow store at {}", file);
            return true;
        } catch (Exception e) {
            BetterTrades.LOGGER.error("Escrow store could not be opened at {}", file, e);
            usable = false;
            return false;
        }
    }

    private static void closeConnection() {
        usable = false;
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            BetterTrades.LOGGER.warn("Closing the escrow store failed: {}", e.getMessage());
        }
        connection = null;
    }
}
