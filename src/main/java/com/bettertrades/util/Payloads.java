package com.bettertrades.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;

/**
 * ItemStacks are stored in the database as JSON: they go through ItemStack.CODEC, so they carry
 * the DataComponents of any mod with them and stay readable from a plain SELECT.
 */
public final class Payloads {

    private Payloads() {}

    public static RegistryOps<JsonElement> ops(MinecraftServer server) {
        RegistryWrapper.WrapperLookup registries = server.getRegistryManager();
        return RegistryOps.of(JsonOps.INSTANCE, registries);
    }

    public static byte[] encodeStack(MinecraftServer server, ItemStack stack) {
        JsonElement json = ItemStack.CODEC.encodeStart(ops(server), stack).getOrThrow();
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static ItemStack decodeStack(MinecraftServer server, byte[] payload) {
        JsonElement json = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
        return ItemStack.CODEC.parse(ops(server), json).getOrThrow();
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
