package com.bettertrades.escrow;

import com.bettertrades.BetterTrades;
import com.bettertrades.db.Columns;
import com.bettertrades.db.Database;
import com.bettertrades.db.Dialect;
import com.bettertrades.db.EscrowDb;
import com.bettertrades.db.Ulid;
import com.bettertrades.util.Payloads;
import com.bettertrades.util.Texts;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Offered items really do leave the inventory and stay here until the trade closes. The database
 * row is what brings them back after a crash: without it, escrow would only live in memory and a
 * kill of the process would eat them.
 *
 * That row ALWAYS sits in the local file of {@link EscrowDb}, never on the active backend: an item
 * held on MariaDB and looked for after a failover on SQLite would come back twice, or never. Escrow
 * state has exactly one home because the player's inventory is exactly one.
 */
public final class EscrowService {

    private static final String HELD = "HELD";
    private static final String RETURNED = "RETURNED";
    private static final String CONSUMED = "CONSUMED";
    /**
     * Terminal state for escrow that cannot be read back any more: a removed mod, a component that
     * no longer exists, a corrupted payload.
     *
     * Without such a state the row stayed HELD, and the decode attempt repeated identically at every
     * login of that player, forever: log noise on every join, the item lost anyway, and no way for
     * the staff to close the case short of editing the database by hand.
     */
    private static final String UNREADABLE = "UNREADABLE";

    private EscrowService() {}

    /**
     * Writes the escrow row. The item must already have been taken out of the inventory by the
     * caller, on the server thread; if the write fails the future fails and the item must be given back.
     */
    public static CompletableFuture<String> hold(MinecraftServer server, ServerPlayerEntity player,
                                                 ItemStack stack, String tradeId) {
        return hold(server, player.getUuid(), player.getGameProfile().getName(), stack, tradeId);
    }

    /** As above, for when the owner is a UUID and not a player you have in hand. */
    public static CompletableFuture<String> hold(MinecraftServer server, UUID owner, String name,
                                                 ItemStack stack, String tradeId) {
        String id = Ulid.next();
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        int amount = stack.getCount();
        byte[] payload = Payloads.encodeStack(server, stack);

        return EscrowDb.supply(connection -> {
            ensurePlayer(connection, Dialect.SQLITE, owner, name);
            ensureItem(connection, Dialect.SQLITE, itemId);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO escrow (id, player_uuid, trade_id, item_id, amount, components,"
                            + " state, created_at, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)")) {
                insert.setString(1, id);
                insert.setString(2, owner.toString());
                insert.setString(3, tradeId);
                insert.setString(4, itemId);
                insert.setInt(5, amount);
                insert.setBytes(6, payload);
                insert.setString(7, HELD);
                insert.setLong(8, System.currentTimeMillis());
                insert.executeUpdate();
            }
            return id;
        });
    }

    /**
     * What is in escrow and has a recipient: the row id, the item, and who is to receive it.
     *
     * The recipient is a UUID and not a {@code ServerPlayerEntity} on purpose. Between closing the
     * row and handing the item over there is one trip on the escrow thread, and a player frozen in a
     * variable outlives their own disconnect: they are resolved at delivery time.
     */
    public record Custody(String escrowId, ItemStack stack, UUID toPlayer, String toName) {}

    /**
     * Gives back to the owner what they had offered: cancel, trash slot, entry taken off the grid.
     */
    public static void giveBack(MinecraftServer server, String tradeId, List<Custody> items,
                                boolean notify) {
        settle(server, tradeId, RETURNED, items, notify);
    }

    /** Delivery once the trade is done: the escrow closes as CONSUMED and the item changes owner. */
    public static void handOver(MinecraftServer server, String tradeId, List<Custody> items) {
        settle(server, tradeId, CONSUMED, items, false);
    }

    /**
     * The row is closed first, the item handed over after, and ONLY what the close really claimed
     * gets handed over.
     *
     * That is the order the commit path had already chosen, and for the same reason: with delivery
     * before the UPDATE, a crash in the window between them leaves the row HELD and after the
     * restart the item is delivered a second time. The window still exists here, but it is turned
     * the other way round, where the worst case is a lost item instead of a duplicated one.
     *
     * The UPDATE's rowcount is also the lock between two paths that want the same escrow - the
     * queued cancel from a disconnect and the return at the next login, say: only one of them moves
     * the row out of HELD, and only that one delivers. The {@code state = 'HELD'} guard already
     * protected the row, but not the delivery, which happened regardless.
     *
     * No waiting on the server thread: delivery comes back in a {@code server.execute}, so the trade
     * no longer pays for the disk.
     */
    private static void settle(MinecraftServer server, String tradeId, String state,
                               List<Custody> items, boolean notify) {
        if (items.isEmpty()) return;

        // Whoever is offline receives nothing and their row is left alone: it stays HELD and
        // returnOpen gives it back at their next login. That is the normal disconnect-cancel case.
        List<Custody> deliverable = new ArrayList<>(items.size());
        for (Custody item : items) {
            if (online(server, item.toPlayer()) != null) {
                deliverable.add(item);
                continue;
            }
            BetterTrades.LOGGER.info("Trade {}: {} is not connected, escrow {} left HELD",
                    tradeId, item.toName(), item.escrowId());
        }
        if (deliverable.isEmpty()) return;

        List<String> ids = new ArrayList<>(deliverable.size());
        for (Custody item : deliverable) ids.add(item.escrowId());

        claim(ids, state).whenComplete((claimed, error) -> server.execute(() -> {
            if (error != null) {
                // The UPDATE was rolled back: the rows are still HELD and go back to their owner at
                // the next login. No delivery, or it would be a duplicate.
                BetterTrades.LOGGER.error("Trade {}: {} escrow rows not closed ({}), nothing delivered:"
                                + " they stay HELD and come back at the next login",
                        tradeId, ids.size(), state, error);
                Texts.staff(server, "chat.staff.escrow_stuck", tradeId, ids.size());
                return;
            }
            java.util.Map<UUID, Integer> handed = new java.util.LinkedHashMap<>();
            for (Custody item : deliverable) {
                if (!claimed.contains(item.escrowId())) continue;
                ServerPlayerEntity recipient = online(server, item.toPlayer());
                if (recipient == null) {
                    // Disconnected just now, with the row already closed: the only way not to lose
                    // the item is to put it back into escrow in their name.
                    rehold(server, tradeId, item);
                    continue;
                }
                give(recipient, item.stack());
                handed.merge(item.toPlayer(), 1, Integer::sum);
            }
            if (!notify) return;
            handed.forEach((player, count) -> {
                ServerPlayerEntity recipient = online(server, player);
                if (recipient != null) Texts.chat(recipient, "chat.escrow.returned", count);
            });
        }));
    }

    /**
     * The ids that really moved from HELD to the requested state.
     *
     * One executeUpdate per row instead of a batch: the per-row rowcount is what decides whether to
     * deliver, and {@code executeBatch} may answer SUCCESS_NO_INFO. A trade has at most sixteen rows
     * and they all sit in one transaction.
     */
    private static CompletableFuture<List<String>> claim(List<String> escrowIds, String state) {
        if (escrowIds.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return EscrowDb.supply(connection -> {
            List<String> claimed = new ArrayList<>(escrowIds.size());
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE escrow SET state = ?, resolved_at = ? WHERE id = ? AND state = '" + HELD + "'")) {
                for (String id : escrowIds) {
                    update.setString(1, state);
                    update.setLong(2, System.currentTimeMillis());
                    update.setString(3, id);
                    if (update.executeUpdate() > 0) {
                        claimed.add(id);
                        continue;
                    }
                    // Zero rows touched means that escrow had already been closed by another path:
                    // here it is the lock that prevents the second delivery, and in the log it is the
                    // symptom of two paths contending for the same row.
                    BetterTrades.LOGGER.warn("Escrow {} already closed: {} not applied,"
                            + " nothing delivered from this path", id, state);
                }
            }
            return claimed;
        });
    }

    /** The player if they really are still connected, null otherwise. */
    private static ServerPlayerEntity online(MinecraftServer server, UUID player) {
        ServerPlayerEntity found = server.getPlayerManager().getPlayer(player);
        return found == null || found.isRemoved() ? null : found;
    }

    /** Last resort: row already closed and the recipient gone, so a fresh escrow row is opened. */
    private static void rehold(MinecraftServer server, String tradeId, Custody item) {
        String itemId = Registries.ITEM.getId(item.stack().getItem()).toString();
        hold(server, item.toPlayer(), item.toName(), item.stack(), tradeId)
                .whenComplete((id, error) -> {
                    if (error == null) {
                        BetterTrades.LOGGER.warn("Trade {}: {} disconnected during delivery,"
                                        + " {} x{} put back into escrow as {}",
                                tradeId, item.toName(), itemId, item.stack().getCount(), id);
                        return;
                    }
                    BetterTrades.LOGGER.error("Trade {}: {} disconnected during delivery and escrow"
                                    + " did not reopen. {} x{} NOT automatically recoverable",
                            tradeId, item.toName(), itemId, item.stack().getCount(), error);
                    server.execute(() -> Texts.staff(server, "chat.staff.item_lost",
                            tradeId, item.toName(), itemId, item.stack().getCount()));
                });
    }

    /**
     * At login: whatever was in escrow and never traded goes back to its owner.
     *
     * It goes through the same close-before-delivery as every other path, so two paths wanting the
     * same row - this one and a disconnect-cancel still queued - cannot both deliver it: only one
     * claims it.
     */
    public static void returnOpen(MinecraftServer server, ServerPlayerEntity player) {
        UUID owner = player.getUuid();
        String name = player.getGameProfile().getName();
        EscrowDb.<List<Held>>supply(connection -> {
            List<Held> open = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, components FROM escrow WHERE player_uuid = ? AND state = '" + HELD + "'")) {
                select.setString(1, owner.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) open.add(new Held(rows.getString(1), rows.getBytes(2)));
                }
            }
            return open;
        }).thenAccept(open -> {
            if (open.isEmpty()) return;
            server.execute(() -> {
                List<Custody> decoded = new ArrayList<>(open.size());
                List<String> unreadable = new ArrayList<>();
                for (Held held : open) {
                    try {
                        decoded.add(new Custody(held.id(), Payloads.decodeStack(server, held.payload()),
                                owner, name));
                    } catch (RuntimeException e) {
                        // The payload will not become readable on its own: retrying it at every login
                        // recovers nothing and hides the problem. It is closed, and the staff knows.
                        BetterTrades.LOGGER.error("Escrow {} could not be decoded: closed as {},"
                                + " the item is not automatically recoverable", held.id(), UNREADABLE, e);
                        unreadable.add(held.id());
                    }
                }
                if (!unreadable.isEmpty()) {
                    claim(unreadable, UNREADABLE);
                    Texts.staff(server, "chat.staff.escrow_unreadable", unreadable.size(), name);
                }
                giveBack(server, "login", decoded, true);
            });
        }).exceptionally(error -> {
            BetterTrades.LOGGER.error("Recovering the escrow of {} failed", owner, error);
            return null;
        });
    }

    /**
     * insertStack answers "I put some of it in", not "I put all of it in": what is left in the stack
     * after the call is the remainder that did not fit, and it goes on the ground instead of nowhere.
     */
    public static void give(ServerPlayerEntity player, ItemStack stack) {
        if (stack.isEmpty()) return;
        player.getInventory().insertStack(stack);
        if (!stack.isEmpty()) {
            player.dropItem(stack, false);
        }
    }

    public static void ensurePlayer(Connection connection, UUID uuid, String name) throws SQLException {
        ensurePlayer(connection, mainDialect(), uuid, name);
    }

    public static void ensurePlayer(Connection connection, Dialect dialect, UUID uuid, String name)
            throws SQLException {
        String stored = Columns.truncate(name, Columns.NAME);
        try (PreparedStatement insert = connection.prepareStatement(dialect.insertIgnorePrefix()
                + "player (uuid, current_name, first_seen) VALUES (?, ?, ?)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, stored);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        }
        // current_name is overwritten, so on its own it remembers nothing: the history resolves
        // participants by UUID and would show TODAY's name on a trade from a year ago. The history
        // row is the only place where the name of back then survives. The unique (player_uuid, name)
        // makes the INSERT idempotent: it is written once per name, not per trade, and a name already
        // seen produces nothing.
        try (PreparedStatement history = connection.prepareStatement(dialect.insertIgnorePrefix()
                + "player_name_history (id, player_uuid, name, seen_at) VALUES (?, ?, ?, ?)")) {
            history.setString(1, Ulid.next());
            history.setString(2, uuid.toString());
            history.setString(3, stored);
            history.setLong(4, System.currentTimeMillis());
            history.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE player SET current_name = ? WHERE uuid = ?")) {
            update.setString(1, stored);
            update.setString(2, uuid.toString());
            update.executeUpdate();
        }
    }

    public static void ensureItem(Connection connection, String identifier) throws SQLException {
        ensureItem(connection, mainDialect(), identifier);
    }

    public static void ensureItem(Connection connection, Dialect dialect, String identifier)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(dialect.insertIgnorePrefix()
                + "item (identifier, first_seen) VALUES (?, ?)")) {
            // item.identifier is {IDENT}: on MariaDB a longer string fails the INSERT instead of
            // being truncated, and hand-typed identifiers arrive here too.
            insert.setString(1, Columns.truncate(identifier, Columns.IDENT));
            insert.setLong(2, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    private static Dialect mainDialect() {
        return Database.backend() == Database.Backend.MARIADB ? Dialect.MARIADB : Dialect.SQLITE;
    }

    private record Held(String id, byte[] payload) {}
}
