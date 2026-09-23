package com.bettertrades.db;

import com.bettertrades.BetterTrades;
import com.bettertrades.config.BetterTradesConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * All database work runs on a single thread: the connection is never shared, so it needs no
 * synchronisation, and the main server thread never waits.
 *
 * MariaDB is the primary, SQLite the fallback. The switch happens at startup and while running;
 * coming back to MariaDB is automatic and notifies whoever registered with {@link #onRecovered}.
 * If SQLite goes down too the backend stays {@link Backend#NONE} and trades must be blocked:
 * when the disk will not write, a queue on a file would fail along with it.
 *
 * Dates are epoch milliseconds in integer columns, so no time zone parameter is passed to the
 * driver: that is how we avoid repeating the mistake that dropped the old mod into fallback over
 * an "Unknown or incorrect time zone".
 */
public final class Database {

    public enum Backend { NONE, MARIADB, SQLITE }

    @FunctionalInterface
    public interface Work {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface Query<T> {
        T run(Connection connection) throws SQLException;
    }

    // Not final: onInitialize runs once per JVM, SERVER_STARTING once per server start. Closing
    // them in SERVER_STOPPED and not recreating them makes the second start in the same JVM fail -
    // singleplayer, LAN world, a world reopened without leaving the game.
    private static volatile ExecutorService worker = newWorker();
    private static volatile ScheduledExecutorService retry = newRetry();

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BetterTrades DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ScheduledExecutorService newRetry() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BetterTrades DB retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** How long shutdown waits for the worker to finish writing. */
    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private static final List<Runnable> RECOVERY_LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile Backend backend = Backend.NONE;
    private static Path sqliteFile;
    private static Connection connection;
    private static int consecutiveFailures;

    private Database() {}

    public static Backend backend() {
        return backend;
    }

    /** The SQLite fallback file, null before {@link #open}. */
    public static Path localFile() {
        return sqliteFile;
    }

    /** False when not even SQLite writes: trades must not start. */
    public static boolean usable() {
        return backend != Backend.NONE;
    }

    /** True while writing to the fallback: whoever saves must mark the row in replay_pending. */
    public static boolean degraded() {
        return backend == Backend.SQLITE && BetterTradesConfig.get().database.useRemote;
    }

    /**
     * Marks a row to be handed over when MariaDB comes back. Does nothing when not writing to the
     * fallback: on MariaDB the queue must stay empty.
     */
    public static void markForReplay(java.sql.Connection connection, String kind, String entityId)
            throws SQLException {
        if (!degraded()) return;
        // INSERT OR IGNORE, not a plain INSERT: the primary key is (kind, entity_id), and for
        // API_CLIENT the entity_id is the modid, that is, a natural key that repeats. With the plain
        // INSERT the second key issued to the same mod violated the primary key and failed the whole
        // Work of issue(), and with it the write to api_client: no key issued, and a generic error
        // that never mentioned the replay queue. The queued row is already there and the replay reads
        // the current state of the real row anyway.
        try (java.sql.PreparedStatement insert = connection.prepareStatement(
                Dialect.SQLITE.insertIgnorePrefix()
                        + "replay_pending (kind, entity_id, created_at) VALUES (?, ?, ?)")) {
            insert.setString(1, kind);
            insert.setString(2, entityId);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    public static void onRecovered(Runnable listener) {
        RECOVERY_LISTENERS.add(listener);
    }

    /** Only here may the executors come back to life: a new start, not some scattered call. */
    public static synchronized CompletableFuture<Void> open(Path localFile) {
        sqliteFile = localFile;
        if (worker.isShutdown()) worker = newWorker();
        if (retry.isShutdown()) retry = newRetry();
        return submit(() -> CompletableFuture.runAsync(Database::connectBestAvailable, worker));
    }

    public static CompletableFuture<Void> execute(Work work) {
        return submit(() -> CompletableFuture.runAsync(() -> withConnection(conn -> {
            work.run(conn);
            return null;
        }), worker));
    }

    public static <T> CompletableFuture<T> supply(Query<T> query) {
        return submit(() -> CompletableFuture.supplyAsync(() -> withConnection(query), worker));
    }

    /**
     * A rejected executor becomes a failed future instead of a synchronous exception.
     *
     * supplyAsync and runAsync propagate RejectedExecutionException to the caller, not into the
     * future: whoever waits on the future never goes through its error branch. For an offer that
     * branch is the only one that gives back the item already taken out of the inventory, so a click
     * arriving after SERVER_STOPPED destroyed it.
     */
    private static <T> CompletableFuture<T> submit(java.util.function.Supplier<CompletableFuture<T>> task) {
        try {
            return task.get();
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The database thread is already closed", e));
        }
    }

    /**
     * Waits for the queue to drain before letting go.
     *
     * Both executors are daemons: without this wait, a quick JVM shutdown takes the queued tasks
     * with it. At SERVER_STOPPED the queue holds the history of the last trades and the resolves of
     * the escrows cancelled by the mass disconnect - things that, once lost, turn into missing
     * history and HELD rows handed back a second time after the restart.
     */
    public static void close() {
        retry.shutdownNow();
        worker.execute(Database::closeConnection);
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                BetterTrades.LOGGER.error("The database thread did not drain the queue in {}s:"
                        + " some writes may have been lost", SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The listeners are registered by SERVER_STARTING: keeping them would start the replay once
        // for every world opened since the JVM came up.
        RECOVERY_LISTENERS.clear();
        backend = Backend.NONE;
        consecutiveFailures = 0;
    }

    // ---------------------------------------------------------------- database thread

    /**
     * Every Work runs inside a transaction, and the second attempt only starts after a rollback.
     *
     * Without it, the retry re-runs in full a piece of work made of several statements in autocommit:
     * if the error arrived after the server had already applied one of them - deadlock, lock wait
     * timeout, socketTimeout on a write that did succeed - the second run repeats half the work. For
     * escrow that case is worth a duplicated item, because the primary key is already written and the
     * caller gets an error about a row that really exists.
     */
    private static <T> T withConnection(Query<T> query) {
        if (backend == Backend.NONE) {
            connectBestAvailable();
            if (backend == Backend.NONE) {
                throw new IllegalStateException("No database available");
            }
        }
        try {
            T result = inTransaction(query);
            consecutiveFailures = 0;
            return result;
        } catch (SQLException first) {
            BetterTrades.LOGGER.warn("Query failed on {}: {}", backend, first.getMessage());
            if (!recoverFrom(first)) {
                throw new IllegalStateException("Database unreachable", first);
            }
            try {
                T result = inTransaction(query);
                consecutiveFailures = 0;
                return result;
            } catch (SQLException second) {
                throw new IllegalStateException("Query failed even after the fallback", second);
            }
        }
    }

    private static <T> T inTransaction(Query<T> query) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = query.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException failure) {
            rollbackQuietly();
            throw failure;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException e) {
                BetterTrades.LOGGER.warn("Autocommit not restored: {}", e.getMessage());
            }
        }
    }

    /** The rollback can fail on an already dead connection: that is the normal failover case. */
    private static void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            BetterTrades.LOGGER.warn("Rollback failed: {}", e.getMessage());
        }
    }

    /** Reconnects or switches to the fallback. True when a usable connection is in place afterwards. */
    private static boolean recoverFrom(SQLException error) {
        consecutiveFailures++;
        closeConnection();
        if (backend == Backend.MARIADB && consecutiveFailures < BetterTradesConfig.get().database.failureThreshold) {
            if (connectMariadb()) return true;
        }
        if (backend == Backend.MARIADB) {
            BetterTrades.LOGGER.error("MariaDB is not answering, switching to the SQLite fallback", error);
        }
        if (connectSqlite()) {
            if (BetterTradesConfig.get().database.useRemote) scheduleRetryToMariadb();
            return true;
        }
        backend = Backend.NONE;
        BetterTrades.LOGGER.error("Not even SQLite answers: trades stay blocked");
        return false;
    }

    private static void connectBestAvailable() {
        if (BetterTradesConfig.get().database.useRemote && connectMariadb()) return;
        if (BetterTradesConfig.get().database.useRemote) {
            BetterTrades.LOGGER.warn("MariaDB unreachable at startup: starting on the SQLite fallback");
        }
        if (connectSqlite()) {
            if (BetterTradesConfig.get().database.useRemote) scheduleRetryToMariadb();
            return;
        }
        backend = Backend.NONE;
    }

    private static boolean connectMariadb() {
        BetterTradesConfig.Database settings = BetterTradesConfig.get().database;
        String url = "jdbc:mariadb://" + settings.host + ":" + settings.port + "/" + settings.name
                + "?connectTimeout=5000&socketTimeout=15000";
        try {
            Connection opened = DriverManager.getConnection(url, settings.user, settings.password);
            Schema.applyTo(opened, Dialect.MARIADB);
            // The old connection is closed HERE, where the field is replaced, and only once the new
            // one is up: recoverFrom() had already closed it, but the automatic comeback goes through
            // scheduleRetryToMariadb and arrives here with SQLite still open. That Connection was lost
            // without a close(), and with journal_mode = WAL its -wal file was no longer checkpointed:
            // on a flapping server it piled up until the file descriptors ran out.
            closeConnection();
            connection = opened;
            boolean returning = backend == Backend.SQLITE;
            backend = Backend.MARIADB;
            consecutiveFailures = 0;
            BetterTrades.LOGGER.info("Connected to MariaDB at {}:{}/{}", settings.host, settings.port, settings.name);
            if (returning) RECOVERY_LISTENERS.forEach(Runnable::run);
            return true;
        } catch (SQLException e) {
            BetterTrades.LOGGER.warn("Connecting to MariaDB failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean connectSqlite() {
        try {
            Files.createDirectories(sqliteFile.getParent());
            Connection opened = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
            try (Statement pragma = opened.createStatement()) {
                pragma.execute("PRAGMA foreign_keys = ON");
                pragma.execute("PRAGMA journal_mode = WAL");
                pragma.execute("PRAGMA busy_timeout = 5000");
            }
            Schema.applyTo(opened, Dialect.SQLITE);
            // Same reason as connectMariadb: the close sits where the replacement happens.
            closeConnection();
            connection = opened;
            backend = Backend.SQLITE;
            BetterTrades.LOGGER.info("Local SQLite database at {}", sqliteFile);
            return true;
        } catch (Exception e) {
            BetterTrades.LOGGER.error("SQLite could not be opened at {}", sqliteFile, e);
            return false;
        }
    }

    private static void scheduleRetryToMariadb() {
        int seconds = Math.max(10, BetterTradesConfig.get().database.retryIntervalSeconds);
        retry.schedule(() -> worker.execute(() -> {
            if (backend != Backend.SQLITE || !BetterTradesConfig.get().database.useRemote) return;
            if (!connectMariadb()) scheduleRetryToMariadb();
        }), seconds, TimeUnit.SECONDS);
    }

    private static void closeConnection() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            BetterTrades.LOGGER.warn("Closing the connection failed: {}", e.getMessage());
        }
        connection = null;
    }
}
