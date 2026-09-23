package com.bettertrades.trade;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.TradeEvent;
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.evolution.variants.TradeEvolution;
import com.cobblemon.mod.common.trade.PlayerTradeParticipant;
import com.cobblemon.mod.common.trade.TradeParticipant;
import com.bettertrades.BetterTrades;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * What TradeManager.performTrade used to do and what BetterTrades, by replacing the native trade,
 * had stopped doing: the TRADE_EVENT_PRE / TRADE_EVENT_POST events and trade evolutions.
 *
 * The mixin cancels onAccept ALWAYS, so performTrade is never reached. Without these calls, a mod
 * forbidding certain trades through Cobblemon's standard event stopped being applied the moment
 * BetterTrades was installed, Cobblemon's trade advancement was no longer granted, and trade
 * evolutions stopped happening across the whole server.
 *
 * Cobblemon's event knows one kind of trade only: one Pokemon for one Pokemon. A BetterTrades one
 * moves up to eight per side, with items and money in between, so the pairs are formed by position.
 * For the veto the shorter side wraps around ({@link #pairs}), so every traded Pokemon is asked
 * about; for the post event it does not, so no Pokemon is counted twice. KNOWN LIMIT: if one side
 * offers no Pokemon at all - Pokemon against items or money only - there is no pair to build and
 * the event does not fire, because the only alternative would be inventing a second Pokemon and
 * presenting it to listeners as traded.
 */
final class CobblemonTrade {

    private CobblemonTrade() {}

    /** One Pokemon leaving a side, with whatever faces it on the other. */
    private record Pair(Pokemon fromLeft, Pokemon fromRight) {}

    /**
     * Fires TRADE_EVENT_PRE on every pair and reports whether anyone cancelled.
     *
     * It has to be called before anything moves, as on the native path: TRADE_EVENT_PRE is a
     * CancelableObservable, that is, the point where another mod forbids the trade.
     */
    static boolean vetoed(String tradeId, ServerPlayerEntity leftPlayer, List<Pokemon> fromLeft,
                          ServerPlayerEntity rightPlayer, List<Pokemon> fromRight) {
        List<Pair> pairs = pairs(fromLeft, fromRight);
        if (pairs.isEmpty()) return false;

        TradeParticipant one = new PlayerTradeParticipant(leftPlayer);
        TradeParticipant two = new PlayerTradeParticipant(rightPlayer);
        for (Pair pair : pairs) {
            // The argument order is performTrade's: each participant is paired with the Pokemon
            // they RECEIVE, not the one they give.
            TradeEvent.Pre event = new TradeEvent.Pre(one, pair.fromRight(), two, pair.fromLeft());
            try {
                CobblemonEvents.TRADE_EVENT_PRE.emit(new TradeEvent.Pre[] { event });
            } catch (RuntimeException e) {
                // The code running here is not ours. A listener that throws must not block the
                // trade: the native path would not fail it silently.
                BetterTrades.LOGGER.error("Trade {}: a TRADE_EVENT_PRE listener threw:"
                        + " that listener's veto was not applied", tradeId, e);
                continue;
            }
            if (event.isCanceled()) {
                BetterTrades.LOGGER.info("Trade {} forbidden by Cobblemon's TRADE_EVENT_PRE", tradeId);
                return true;
            }
        }
        return false;
    }

    /**
     * Trade evolutions, on the Pokemon already delivered into their new store.
     *
     * A copy of performTrade's logic: among the locked evolutions only the TradeEvolutions are taken
     * and the first one that fires wins. The list is copied before iterating because a successful
     * evolution touches the Pokemon's locked evolutions.
     *
     * Exactly one attempt per Pokemon, against the Pokemon at the same position on the other side.
     * Walking the wrapped pairs instead made the lone Pokemon of a one-against-three trade try three
     * times, each time against a different counterpart.
     */
    static void evolve(String tradeId, List<Pokemon> fromLeft, List<Pokemon> fromRight) {
        if (fromLeft.isEmpty() || fromRight.isEmpty()) return;
        for (int i = 0; i < fromLeft.size(); i++) {
            attempt(tradeId, fromLeft.get(i), fromRight.get(i % fromRight.size()));
        }
        for (int i = 0; i < fromRight.size(); i++) {
            attempt(tradeId, fromRight.get(i), fromLeft.get(i % fromLeft.size()));
        }
    }

    private static void attempt(String tradeId, Pokemon moved, Pokemon counterpart) {
        List<TradeEvolution> candidates = new ArrayList<>();
        try {
            for (Evolution evolution : moved.getLockedEvolutions()) {
                if (evolution instanceof TradeEvolution trade) candidates.add(trade);
            }
            for (TradeEvolution evolution : candidates) {
                if (evolution.attemptEvolution(moved, counterpart)) return;
            }
        } catch (RuntimeException e) {
            // The items are already delivered and the Pokemon already moved: an evolution that
            // throws must not take the history and the end-of-trade messages with it.
            BetterTrades.LOGGER.error("Trade {}: trade evolution failed on {}",
                    tradeId, moved.getUuid(), e);
        }
    }

    /**
     * Puts friendship back to the form's base value, as performTrade does before handing over.
     *
     * Without this, BetterTrades opens a service that does not exist on Cobblemon's path: you max
     * out a Pokemon's friendship, trade it, and whoever receives it holds a Pokemon already ready
     * for a friendship evolution. The reset exists precisely to prevent that.
     */
    static void resetFriendship(String tradeId, Pokemon pokemon) {
        try {
            // The second argument is Cobblemon's default for setFriendship: in performTrade's
            // bytecode the call goes through the $default bridge with that parameter left at its
            // default, which is true.
            pokemon.setFriendship(pokemon.getForm().getBaseFriendship(), true);
        } catch (RuntimeException e) {
            BetterTrades.LOGGER.error("Trade {}: friendship not reset on {}",
                    tradeId, pokemon.getUuid(), e);
        }
    }

    /**
     * Fires TRADE_EVENT_POST on the Pokemon really delivered, as performTrade does once done.
     *
     * Only the position-by-position pairs, without wrapping: a listener counting trades - quests,
     * statistics, "trade N Pokemon" rewards - must see each Pokemon once. With wrapping, one Pokemon
     * against eight was eight trades. The extra Pokemon on the longer side have no partner left and
     * get no event, the same known limit as a Pokemon traded for items only.
     */
    static void firePost(String tradeId, ServerPlayerEntity leftPlayer, List<Pokemon> fromLeft,
                         ServerPlayerEntity rightPlayer, List<Pokemon> fromRight) {
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < Math.min(fromLeft.size(), fromRight.size()); i++) {
            pairs.add(new Pair(fromLeft.get(i), fromRight.get(i)));
        }
        if (pairs.isEmpty()) return;

        TradeParticipant one = new PlayerTradeParticipant(leftPlayer);
        TradeParticipant two = new PlayerTradeParticipant(rightPlayer);
        for (Pair pair : pairs) {
            try {
                CobblemonEvents.TRADE_EVENT_POST.emit(new TradeEvent.Post[] {
                        new TradeEvent.Post(one, pair.fromRight(), two, pair.fromLeft()) });
            } catch (RuntimeException e) {
                BetterTrades.LOGGER.error("Trade {}: a TRADE_EVENT_POST listener threw."
                        + " The trade was already complete", tradeId, e);
            }
        }
    }

    /**
     * The pairs the veto is asked about. The shorter side wraps around, so every traded Pokemon
     * shows up at least once - a listener that forbids a Pokemon has to see it - and asking twice
     * about the same one is harmless for a veto. With one side empty there is no pair.
     */
    private static List<Pair> pairs(List<Pokemon> fromLeft, List<Pokemon> fromRight) {
        if (fromLeft.isEmpty() || fromRight.isEmpty()) return List.of();
        int count = Math.max(fromLeft.size(), fromRight.size());
        List<Pair> pairs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            pairs.add(new Pair(fromLeft.get(i % fromLeft.size()), fromRight.get(i % fromRight.size())));
        }
        return pairs;
    }
}
