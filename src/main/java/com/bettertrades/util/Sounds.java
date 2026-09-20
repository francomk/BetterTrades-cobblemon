package com.bettertrades.util;

import com.bettertrades.config.BetterTradesConfig;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/**
 * The screen's sounds. The client is vanilla, so vanilla sound packets are sent to the single
 * player instead of using the world APIs, which would let everyone else hear them too.
 */
public final class Sounds {

    private Sounds() {}

    /** An object enters or leaves the offer. */
    public static void offer(ServerPlayerEntity player) {
        play(player, SoundEvents.ENTITY_ITEM_PICKUP, 0.7f, 1.5f);
    }

    public static void click(ServerPlayerEntity player) {
        play(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.0f);
    }

    public static void accept(ServerPlayerEntity player) {
        play(player, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, 1.2f);
    }

    public static void confirm(ServerPlayerEntity player) {
        play(player, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, 1.8f);
    }

    public static void success(ServerPlayerEntity player) {
        play(player, SoundEvents.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
    }

    public static void cancelled(ServerPlayerEntity player) {
        play(player, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.8f, 0.7f);
    }

    /** Move refused: blacklisted item, offer full, not enough money. */
    public static void denied(ServerPlayerEntity player) {
        play(player, SoundEvents.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
    }

    private static void play(ServerPlayerEntity player, SoundEvent sound, float volume, float pitch) {
        if (player == null || !BetterTradesConfig.get().general.sounds) return;
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(RegistryEntry.of(sound),
                SoundCategory.MASTER, player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }
}
