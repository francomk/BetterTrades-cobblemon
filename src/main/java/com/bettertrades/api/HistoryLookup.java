package com.bettertrades.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The same reads as the API, but for the mod's own commands: no key needed in-house.
 * It stays in the {@code api} package because {@link TradeQueries} does not leave it.
 */
public final class HistoryLookup {

    private HistoryLookup() {}

    public static CompletableFuture<List<TradeView>> byPlayer(UUID player, int limit, int offset) {
        return TradeQueries.byPlayer(player, limit, offset);
    }
}
