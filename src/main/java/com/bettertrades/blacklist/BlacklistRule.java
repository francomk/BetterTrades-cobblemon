package com.bettertrades.blacklist;

import java.util.Set;

public sealed interface BlacklistRule {

    String id();

    String reason();

    String addedBy();

    record Item(String id, String itemId, String reason, String addedBy) implements BlacklistRule {}

    /**
     * The species is mandatory and aspects are always attached to it: there is no "every shiny"
     * rule that would hit species nobody ever considered.
     *
     * A null {@code form} matches every form; an empty {@code aspects} matches every aspect,
     * otherwise the rule only fires when the Pokemon has all of them.
     */
    record Mon(String id, String speciesId, String form, Set<String> aspects, String reason,
               String addedBy) implements BlacklistRule {}
}
