package com.bettertrades.mixin;

import com.cobblemon.mod.common.api.PrioritizedList;
import com.cobblemon.mod.common.api.storage.PokemonStoreManager;
import com.cobblemon.mod.common.api.storage.factory.PokemonStoreFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cobblemon exposes no way to save a single player's stores: the factories are private. */
@Mixin(value = PokemonStoreManager.class, remap = false)
public interface PokemonStoreManagerAccessor {

    @Accessor(value = "factories", remap = false)
    PrioritizedList<PokemonStoreFactory> bettertrades$factories();
}
