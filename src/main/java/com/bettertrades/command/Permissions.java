package com.bettertrades.command;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * The {@code bettertrades.admin} node comes from LuckPerms, which is compileOnly: when it is
 * missing, the commands fall back to the OP level instead of taking the mod down at startup.
 * That is why the class touching the API is separate and only loaded when the mod is really there.
 */
public final class Permissions {

    public static final String ADMIN = "bettertrades.admin";

    private static final boolean LUCKPERMS_PRESENT =
            FabricLoader.getInstance().isModLoaded("luckperms");

    private Permissions() {}

    /** Your own history is everyone's: without this a player could not even check their own trades. */
    public static boolean ownHistory(ServerCommandSource source) {
        return source.getPlayer() != null;
    }

    public static boolean admin(ServerCommandSource source) {
        if (source.hasPermissionLevel(4)) return true;
        if (!LUCKPERMS_PRESENT) return false;
        ServerPlayerEntity player = source.getPlayer();
        return player != null && LuckPermsCheck.has(player, ADMIN);
    }
}
