package com.bettertrades.util;

import com.bettertrades.lang.Lang;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** A single place to talk to the player: every string comes from {@link Lang}. */
public final class Texts {

    private Texts() {}

    /** Important message: it stays written in the chat. */
    public static void chat(ServerPlayerEntity player, String key, Object... arguments) {
        if (player != null) player.sendMessage(Lang.text(key, arguments), false);
    }

    public static void chat(ServerPlayerEntity player, Text text) {
        if (player != null) player.sendMessage(text, false);
    }

    /**
     * Quick feedback: with a screen open the chat is covered, while the action bar stays visible.
     * When it is switched off in the config, this falls back to the chat.
     */
    public static void feedback(ServerPlayerEntity player, String key, Object... arguments) {
        if (player == null) return;
        player.sendMessage(Lang.text(key, arguments),
                com.bettertrades.config.BetterTradesConfig.get().general.actionBar);
    }

    /** Move refused: visual feedback plus sound. */
    public static void denied(ServerPlayerEntity player, String key, Object... arguments) {
        feedback(player, key, arguments);
        Sounds.denied(player);
    }

    /** Notice to the staff: an outside change during an open trade is rare and needs to be seen. */
    public static void staff(MinecraftServer server, String key, Object... arguments) {
        Text text = Lang.text("chat.staff.prefix").append(Lang.text(key, arguments));
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (server.getPlayerManager().isOperator(player.getGameProfile())) {
                player.sendMessage(text, false);
            }
        }
    }
}
