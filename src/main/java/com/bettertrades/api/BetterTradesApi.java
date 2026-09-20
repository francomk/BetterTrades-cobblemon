package com.bettertrades.api;

import com.bettertrades.blacklist.Blacklist;
import com.bettertrades.blacklist.BlacklistRule;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The entry point for other mods.
 *
 * <pre>
 * BetterTradesApi.Queries api = BetterTradesApi.connect("mymod", "key");
 * api.byPlayer(uuid, 20, 0).thenAccept(trades -&gt; ...);
 * </pre>
 *
 * Level 1: history reads and blacklist writes only.
 * Level 2: on top of that, the cancellable pre-commit event and the post-commit one.
 *
 * Reads are asynchronous because the database cannot run on the server thread. The blacklist, on
 * the other hand, is read synchronously: it lives in memory.
 */
public final class BetterTradesApi {

    public interface Queries {

        CompletableFuture<List<TradeView>> byPlayer(UUID player, int limit, int offset);

        CompletableFuture<List<TradeView>> byPeriod(long fromEpochMillis, long toEpochMillis,
                                                    int limit, int offset);

        CompletableFuture<List<TradeView>> containingItem(String itemId, int limit, int offset);

        CompletableFuture<List<TradeView>> containingSpecies(String speciesId, int limit, int offset);

        CompletableFuture<Optional<TradeView>> byId(String tradeId);

        List<BlacklistRule> blacklist();

        CompletableFuture<String> blacklistItem(String itemId, String reason);

        /** The species is mandatory: aspects only apply within that species. */
        CompletableFuture<String> blacklistPokemon(String speciesId, String form, Set<String> aspects,
                                                   String reason);

        CompletableFuture<Boolean> revokeBlacklistRule(String ruleId);
    }

    public interface Events extends Queries {

        /** Returning a reason cancels the trade and shows it to both players. */
        void onPreTrade(Function<TradeView.Pending, Optional<String>> handler);

        void onPostTrade(Consumer<TradeView.Pending> handler);

        void removeListeners();
    }

    private BetterTradesApi() {}

    public static Queries connect(String modid, String key) {
        ApiKeys.requireLevel(modid, key, ApiKeys.LEVEL_QUERY);
        return new Client(modid);
    }

    public static Events connectWithEvents(String modid, String key) {
        ApiKeys.requireLevel(modid, key, ApiKeys.LEVEL_EVENTS);
        return new Client(modid);
    }

    private record Client(String modid) implements Events {

        @Override
        public CompletableFuture<List<TradeView>> byPlayer(UUID player, int limit, int offset) {
            return TradeQueries.byPlayer(player, limit, offset);
        }

        @Override
        public CompletableFuture<List<TradeView>> byPeriod(long fromEpochMillis, long toEpochMillis,
                                                           int limit, int offset) {
            return TradeQueries.byPeriod(fromEpochMillis, toEpochMillis, limit, offset);
        }

        @Override
        public CompletableFuture<List<TradeView>> containingItem(String itemId, int limit, int offset) {
            return TradeQueries.containingItem(itemId, limit, offset);
        }

        @Override
        public CompletableFuture<List<TradeView>> containingSpecies(String speciesId, int limit, int offset) {
            return TradeQueries.containingSpecies(speciesId, limit, offset);
        }

        @Override
        public CompletableFuture<Optional<TradeView>> byId(String tradeId) {
            return TradeQueries.byId(tradeId);
        }

        @Override
        public List<BlacklistRule> blacklist() {
            return Blacklist.rules();
        }

        @Override
        public CompletableFuture<String> blacklistItem(String itemId, String reason) {
            return Blacklist.addItem(itemId, reason, modid);
        }

        @Override
        public CompletableFuture<String> blacklistPokemon(String speciesId, String form,
                                                          Set<String> aspects, String reason) {
            return Blacklist.addPokemon(speciesId, form, aspects, reason, modid);
        }

        @Override
        public CompletableFuture<Boolean> revokeBlacklistRule(String ruleId) {
            return Blacklist.revoke(ruleId, modid);
        }

        @Override
        public void onPreTrade(Function<TradeView.Pending, Optional<String>> handler) {
            TradeEvents.addPre(modid, handler);
        }

        @Override
        public void onPostTrade(Consumer<TradeView.Pending> handler) {
            TradeEvents.addPost(modid, handler);
        }

        @Override
        public void removeListeners() {
            TradeEvents.removeAll(modid);
        }
    }
}
