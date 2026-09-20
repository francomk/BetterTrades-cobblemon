package com.bettertrades.command;

import com.bettertrades.BetterTrades;
import net.luckperms.api.LuckPermsProvider;
import net.minecraft.server.network.ServerPlayerEntity;

/** Only touched when LuckPerms is loaded: see {@link Permissions}. */
final class LuckPermsCheck {

    private LuckPermsCheck() {}

    static boolean has(ServerPlayerEntity player, String node) {
        try {
            return LuckPermsProvider.get()
                    .getPlayerAdapter(ServerPlayerEntity.class)
                    .getPermissionData(player)
                    .checkPermission(node)
                    .asBoolean();
        } catch (IllegalStateException | NoClassDefFoundError e) {
            BetterTrades.LOGGER.warn("LuckPerms could not be queried, falling back to OP level: {}", e.toString());
            return false;
        }
    }
}
