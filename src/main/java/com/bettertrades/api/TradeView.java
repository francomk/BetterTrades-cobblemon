package com.bettertrades.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What other mods see of a trade: copied data, never live entities. */
public record TradeView(String tradeId, long startedAt, long completedAt, String world,
                        UUID leftPlayer, UUID rightPlayer, List<ItemView> items, List<MonView> pokemon,
                        List<MoneyView> money) {

    public record ItemView(UUID fromPlayer, String itemId, int amount) {}

    public record MonView(UUID fromPlayer, String speciesId, String form, int level, boolean shiny,
                          Set<String> aspects) {}

    /**
     * One player's money in the trade.
     *
     * {@code offered} is what they put on the table; {@code transferred} is the signed change to
     * their balance, positive when money came in. Only the difference between the two offers moves,
     * so the two numbers usually differ.
     */
    public record MoneyView(UUID player, long offered, long transferred, String currency) {}

    /**
     * A trade about to be committed, as the pre-commit listener sees it, and a committed one, as
     * the post-commit listener sees it. The post-commit one describes what really changed hands:
     * Pokemon that could not be delivered are missing, evolved ones carry their new species.
     */
    public record Pending(String tradeId, UUID leftPlayer, UUID rightPlayer, List<ItemView> items,
                          List<MonView> pokemon, List<MoneyView> money) {}
}
