package com.bettertrades.util;

import com.bettertrades.BetterTrades;
import com.bettertrades.mixin.PlayerManagerAccessor;
import com.bettertrades.mixin.PokemonStoreManagerAccessor;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.factory.PokemonStoreFactory;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Writes a player's state to disk on demand, instead of waiting for vanilla's autosave.
 *
 * Escrow rows are durable the moment they commit; the inventory, the party and the PC are not. In
 * the gap between the two a crash leaves the old files on disk next to the new escrow state, and
 * the item exists twice. Every place that moves an item between the inventory and escrow calls
 * here, so the gap shrinks from an autosave interval to the time it takes to write the file.
 */
public final class PlayerSaves {

    private PlayerSaves() {}

    /** The vanilla player file: inventory, ender chest, everything but Cobblemon's stores. */
    public static void inventory(ServerPlayerEntity player) {
        if (player == null || player.isRemoved() || player.getServer() == null) return;
        try {
            ((PlayerManagerAccessor) player.getServer().getPlayerManager())
                    .bettertrades$savePlayerData(player);
        } catch (RuntimeException e) {
            BetterTrades.LOGGER.error("Saving the player file of {} failed",
                    player.getGameProfile().getName(), e);
        }
    }

    /** The vanilla file plus the party and the PC. */
    public static void everything(ServerPlayerEntity player) {
        inventory(player);
        pokemon(player);
    }

    /**
     * Party and PC, through the store factory Cobblemon actually uses.
     *
     * Only the file-backed factory can be told to save one store. A database-backed one writes on
     * its own terms, and a factory that did not create the store must not be handed it: it would
     * write a file nobody reads.
     */
    public static void pokemon(ServerPlayerEntity player) {
        if (player == null || player.isRemoved() || player.getServer() == null) return;
        try {
            PokemonStoreFactory first = null;
            for (PokemonStoreFactory factory : ((PokemonStoreManagerAccessor) Cobblemon.INSTANCE.getStorage())
                    .bettertrades$factories()) {
                first = factory;
                break;
            }
            if (!(first instanceof FileBackedPokemonStoreFactory<?> files)) return;
            var registries = player.getServer().getRegistryManager();
            files.save(Cobblemon.INSTANCE.getStorage().getParty(player), registries);
            files.save(Cobblemon.INSTANCE.getStorage().getPC(player), registries);
        } catch (RuntimeException e) {
            BetterTrades.LOGGER.error("Saving the Pokemon stores of {} failed",
                    player.getGameProfile().getName(), e);
        }
    }
}
