package com.bettertrades.trade;

import net.minecraft.item.ItemStack;

import java.util.UUID;

/** One of the eight slots on a side: either an item already in escrow or a fingerprinted Pokemon. */
public sealed interface OfferEntry {

    record Item(String escrowId, ItemStack stack) implements OfferEntry {}

    record Mon(UUID pokemonUuid, PokemonFingerprint fingerprint) implements OfferEntry {}
}
