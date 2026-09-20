package com.bettertrades.trade;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.bettertrades.BetterTrades;
import com.bettertrades.config.BetterTradesConfig;
import com.bettertrades.db.Database;
import com.bettertrades.db.EscrowDb;
import com.bettertrades.escrow.EscrowService;
import com.bettertrades.gui.Icons;
import com.bettertrades.gui.TradeGui;
import com.bettertrades.util.Texts;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mod's only real lock: a player is in one session at a time.
 * The periodic checks that cancel a trade (distance, dimension, battle) and the cancel on
 * disconnect live here too.
 */
public final class TradeSessions {

    private static final Map<UUID, TradeSession> BY_PLAYER = new ConcurrentHashMap<>();
    private static final int CHECK_EVERY_TICKS = 20;

    private static int tickCounter;

    private TradeSessions() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                EscrowService.returnOpen(server, handler.getPlayer()));

        // Fabric fires DISCONNECT from two places: handleDisconnection, on the server thread, and
        // channelInactive, on Netty's event loop. On an abrupt disconnect the second one arrives
        // first, and cancel() mutates the session - stage, entries, inventories - while the server
        // thread may be inside commit(). From here on, one thread only touches the session.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.getPlayer().getUuid();
            String playerName = handler.getPlayer().getGameProfile().getName();
            server.execute(() -> {
                TradeSession session = BY_PLAYER.get(playerId);
                if (session != null) {
                    session.cancel(CancelReason.DISCONNECT, playerName);
                }
            });
        });

        ServerTickEvents.END_SERVER_TICK.register(TradeSessions::tick);
    }

    /**
     * Compares BetterTrades' distance limit with Cobblemon's.
     *
     * They are two numbers in two different config files and neither knows about the other:
     * OPENING a trade goes through Cobblemon, which applies its own tradeMaxDistance in
     * isValidInteraction, while the open session is watched by BetterTrades' trade.maxDistance. If
     * ours is larger, the excess does nothing because the request is not even accepted at that
     * distance; if it is smaller, the trade opens and the periodic check cancels it straight away.
     * Either way whoever runs the server changes the documented value and does not see the effect
     * they expect: better to say so at startup than to let them find out.
     */
    public static void checkDistanceAgainstCobblemon() {
        double ours = BetterTradesConfig.get().trade.maxDistance;
        float cobblemon;
        try {
            cobblemon = Cobblemon.INSTANCE.getConfig().getTradeMaxDistance();
        } catch (RuntimeException | NoClassDefFoundError e) {
            BetterTrades.LOGGER.warn("Cobblemon's distance limit could not be read: {}", e.toString());
            return;
        }
        if (Math.abs(ours - cobblemon) < 0.001) return;
        BetterTrades.LOGGER.warn("trade.maxDistance = {} but Cobblemon opens trades within {}."
                        + " {} Align the two values if the behaviour surprises you.",
                ours, cobblemon,
                ours > cobblemon
                        ? "Beyond " + cobblemon + " the request is not even accepted, so"
                                + " the difference has no effect."
                        : "Between " + ours + " and " + cobblemon + " the trade opens and is cancelled"
                                + " immediately by the periodic check.");
    }

    /** Called by the mixin when a Cobblemon request is accepted. */
    public static TradeSession open(MinecraftServer server, ServerPlayerEntity a, ServerPlayerEntity b) {
        // Without escrow the offered items have nowhere to come back from after a crash: better
        // not to let them leave the inventory at all.
        if (!Database.usable() || !EscrowDb.usable()) {
            Texts.denied(a, "chat.error.database");
            Texts.denied(b, "chat.error.database");
            return null;
        }
        if (BY_PLAYER.containsKey(a.getUuid()) || BY_PLAYER.containsKey(b.getUuid())) {
            // Both of them, as in the other two refusals below. While the mixin only cancelled on
            // success, whoever did not get the message ended up in front of Cobblemon's native
            // screen and worked out for themselves that BetterTrades was not involved. Now the
            // native trade no longer starts: without a warning, whichever of the two accepted the
            // request would see nothing open and nobody would tell them why.
            Texts.denied(a, "chat.error.busy");
            Texts.denied(b, "chat.error.busy");
            return null;
        }
        // The screen is made of models and fonts from the pack: without it the player would see an
        // empty window and click blindly.
        for (ServerPlayerEntity player : List.of(a, b)) {
            if (!Icons.ready(player)) {
                Texts.denied(a, "chat.error.no_pack", player.getGameProfile().getName());
                Texts.denied(b, "chat.error.no_pack", player.getGameProfile().getName());
                return null;
            }
        }
        TradeSession session = new TradeSession(server, a, b);
        BY_PLAYER.put(a.getUuid(), session);
        BY_PLAYER.put(b.getUuid(), session);
        try {
            TradeGui.openFor(session);
        } catch (RuntimeException e) {
            // The map was updated before construction had finished. Without this branch the two
            // stay "already in another trade" in front of nothing, and the only ways out are walking
            // away, changing dimension, disconnecting or /bt cancel.
            forget(session);
            throw e;
        }
        Texts.chat(a, "chat.trade.opened", b.getGameProfile().getName());
        Texts.chat(b, "chat.trade.opened", a.getGameProfile().getName());
        return session;
    }

    public static TradeSession of(UUID playerId) {
        return BY_PLAYER.get(playerId);
    }

    public static Collection<TradeSession> active() {
        return List.copyOf(new java.util.LinkedHashSet<>(BY_PLAYER.values()));
    }

    /**
     * The last net: the session has thrown and its state is no longer known, so cancelling is
     * attempted and it is taken off the map regardless. Leaving it there would mean two players
     * stuck on "already in another trade" until the restart.
     */
    private static void closeAfterFailure(TradeSession session) {
        try {
            session.cancel(CancelReason.ADMIN, session.left().playerName());
        } catch (RuntimeException e) {
            BetterTrades.LOGGER.error("Cancelling trade {} failed as well", session.tradeId(), e);
        } finally {
            forget(session);
        }
    }

    /**
     * Removes from the map ONLY the entries pointing at this session.
     *
     * With a plain remove(key), an old session closing late - the payment landing after
     * closeAfterFailure had already removed it - frees the UUIDs of the NEW one: the new session
     * stays alive with both screens open but outside BY_PLAYER, so no tick, no countdown, no
     * distance checks, and /bt cancel answers that they are not trading.
     */
    static void forget(TradeSession session) {
        BY_PLAYER.remove(session.left().playerId(), session);
        BY_PLAYER.remove(session.right().playerId(), session);
    }

    /**
     * Server shutdown: open sessions are closed and the static state is cleared.
     *
     * BY_PLAYER is static and lives as long as the JVM, not as long as the server: singleplayer and
     * LAN worlds start several in the same one. Without this, in the next world the players of
     * every session left open are "already in another trade" forever - cancel() on a session of a
     * dead server does not free them - and the map holds on to the previous server's MinecraftServer,
     * worlds and entities.
     *
     * It must be called with the players already disconnected: the item return sees there is nobody
     * to deliver to and leaves the escrow rows HELD, which is exactly what makes them come back at
     * the first login of the next world.
     */
    public static void shutdown() {
        for (TradeSession session : active()) {
            try {
                session.cancel(CancelReason.ADMIN, session.left().playerName());
            } catch (RuntimeException e) {
                BetterTrades.LOGGER.error("Closing trade {} at shutdown failed",
                        session.tradeId(), e);
            }
        }
        BY_PLAYER.clear();
    }

    private static void tick(MinecraftServer server) {
        if (BY_PLAYER.isEmpty()) return;

        // The cancel countdown measures three real seconds, so it cannot wait for the periodic
        // check: it runs ahead of it every tick.
        //
        // Every session sits inside its own try: END_SERVER_TICK has no net underneath, and an
        // exception from one trade - Cobblemon on a broken Pokemon, a third-party observer - would
        // break the whole server tick, not just that trade.
        for (TradeSession session : active()) {
            try {
                session.tick();
            } catch (RuntimeException e) {
                BetterTrades.LOGGER.error("Tick of trade {} failed: session closed by force",
                        session.tradeId(), e);
                closeAfterFailure(session);
            }
        }

        if (++tickCounter < CHECK_EVERY_TICKS) return;
        tickCounter = 0;

        BetterTradesConfig.Trade settings = BetterTradesConfig.get().trade;
        double maxSquared = settings.maxDistance * settings.maxDistance;

        for (TradeSession session : new ArrayList<>(active())) {
            ServerPlayerEntity left = session.playerOf(session.left());
            ServerPlayerEntity right = session.playerOf(session.right());
            if (left == null || right == null) {
                session.cancel(CancelReason.DISCONNECT,
                        left == null ? session.left().playerName() : session.right().playerName());
                continue;
            }
            if (settings.cancelOnBattle && BattleRegistry.getBattleByParticipatingPlayer(left) != null) {
                session.cancel(CancelReason.BATTLE, session.left().playerName());
                continue;
            }
            if (settings.cancelOnBattle && BattleRegistry.getBattleByParticipatingPlayer(right) != null) {
                session.cancel(CancelReason.BATTLE, session.right().playerName());
                continue;
            }
            if (settings.cancelOnDimensionChange && left.getServerWorld() != right.getServerWorld()) {
                session.cancel(CancelReason.DIMENSION, session.right().playerName());
                continue;
            }
            if (left.getServerWorld() == right.getServerWorld() && left.squaredDistanceTo(right) > maxSquared) {
                session.cancel(CancelReason.DISTANCE, session.left().playerName());
            }
        }
    }
}
