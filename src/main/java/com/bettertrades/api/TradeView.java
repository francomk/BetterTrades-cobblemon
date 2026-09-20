package com.bettertrades.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What other mods see of a trade: copied data, never live entities. */
public record TradeView(String tradeId, long startedAt, long completedAt, String world,
                        UUID leftPlayer, UUID rightPlayer, List<ItemView> items, List<MonView> pokemon) {

    public record ItemView(UUID fromPlayer, String itemId, int amount) {}

    public record MonView(UUID fromPlayer, String speciesId, String form, int level, boolean shiny,
                          Set<String> aspects) {}

    /** A trade about to be committed, as the pre-commit listener sees it. */
    public record Pending(String tradeId, UUID leftPlayer, UUID rightPlayer, List<ItemView> items,
                          List<MonView> pokemon) {}
}
