package com.bettertrades.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** savePlayerData is protected: this is the only way to write one player's file on demand. */
@Mixin(PlayerManager.class)
public interface PlayerManagerAccessor {

    @Invoker("savePlayerData")
    void bettertrades$savePlayerData(ServerPlayerEntity player);
}
