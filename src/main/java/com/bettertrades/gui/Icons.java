package com.bettertrades.gui;

import com.cobblemon.mod.common.CobblemonItems;
import com.bettertrades.BetterTrades;
import com.bettertrades.config.BetterTradesConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import eu.pb4.polymer.resourcepack.api.PolymerModelData;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The items that make up the screens.
 *
 * The trade screen's buttons are paper with a custom model: the button art lives in the model,
 * not in the background, so the client draws it in the slot and the slot keeps its tooltip.
 * The models scale themselves up so one texture pixel covers one screen pixel, which is how
 * they land exactly where the mockup drew them - see tools/gen_gui_assets.py.
 *
 * The other screens still use vanilla items. They get their own artwork when their mockups do.
 */
public final class Icons {

    private static final int CANCEL_FRAMES = 8;

    private static PolymerModelData trash;
    private static PolymerModelData money;
    private static PolymerModelData moneyOff;
    private static PolymerModelData confirm;
    private static PolymerModelData confirmDone;
    private static final PolymerModelData[] cancel = new PolymerModelData[CANCEL_FRAMES];

    private Icons() {}

    /**
     * Claims a custom model value for every button. Must run during mod init: Polymer writes
     * the pack from what has been requested by the time the server starts.
     */
    public static void register() {
        trash = model("btn_trash");
        money = model("btn_money");
        moneyOff = model("btn_money_off");
        confirm = model("btn_confirm");
        confirmDone = model("btn_confirm_done");
        for (int frame = 0; frame < CANCEL_FRAMES; frame++) {
            cancel[frame] = model("btn_cancel_" + (frame + 1));
        }
    }

    /**
     * False when the player refused the server pack, so every button would be invisible to them.
     *
     * Polymer's auto-host is off by default outside a dev environment, so a server that hands
     * the pack out some other way can turn the check off in the config instead.
     */
    public static boolean ready(ServerPlayerEntity player) {
        return !BetterTradesConfig.get().general.requireResourcePack
                || PolymerResourcePackUtils.hasMainPack(player);
    }

    /**
     * Says at startup whether the pack will actually reach the players.
     *
     * Polymer's auto-host writes {@code config/polymer/auto-host.json} with {@code enabled} set to
     * false outside a development environment, so a server that installs BetterTrades and nothing
     * else serves no pack: {@link #ready} is false for everyone and no trade ever opens. Failing
     * that way is silent, which is the worst way to fail, so it is said out loud once.
     */
    public static void checkPackDelivery() {
        if (!BetterTradesConfig.get().general.requireResourcePack) return;

        Path autoHost = FabricLoader.getInstance().getConfigDir()
                .resolve("polymer").resolve("auto-host.json");
        boolean hosting = false;
        if (Files.isRegularFile(autoHost)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(autoHost)).getAsJsonObject();
                hosting = json.has("enabled") && json.get("enabled").getAsBoolean();
            } catch (Exception e) {
                BetterTrades.LOGGER.warn("Could not read {}: {}", autoHost, e.toString());
                return;
            }
        }
        if (hosting) return;

        BetterTrades.LOGGER.warn("The trade screen needs the Polymer resource pack, and Polymer's"
                + " auto-host is off. Set \"enabled\": true in config/polymer/auto-host.json (restart"
                + " afterwards), or serve the pack yourself, or set general.requireResourcePack to"
                + " false in config/bettertrades/config.json to open trades without it - the buttons"
                + " will be blank for players who do not have the pack.");
    }

    private static PolymerModelData model(String name) {
        return PolymerResourcePackUtils.requestModel(Items.PAPER,
                Identifier.of(BetterTrades.MOD_ID, "item/" + name));
    }

    public static ItemStack head(GameProfile profile) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponentTypes.PROFILE, new ProfileComponent(profile));
        return head;
    }

    public static ItemStack trash() {
        return trash.asStack();
    }

    /** The real Cobblemon Poke Ball: it sits inside the frame the background already draws. */
    public static ItemStack addPokemon() {
        return new ItemStack(CobblemonItems.POKE_BALL);
    }

    public static ItemStack money(boolean enabled) {
        return (enabled ? money : moneyOff).asStack();
    }

    public static ItemStack confirm(boolean alreadyConfirmed) {
        return (alreadyConfirmed ? confirmDone : confirm).asStack();
    }

    /** The cancel button fades over the countdown: frame 0 is full red, frame 7 nearly gone. */
    public static ItemStack cancel(int frame) {
        return cancel[Math.clamp(frame, 0, CANCEL_FRAMES - 1)].asStack();
    }

    public static int cancelFrames() {
        return CANCEL_FRAMES;
    }

    // ------------------------------------------------------------ the screens still on vanilla

    public static ItemStack withdraw() {
        return new ItemStack(Items.BARRIER);
    }

    public static ItemStack divider() {
        return new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
    }

    public static ItemStack empty() {
        return new ItemStack(Items.LIGHT_GRAY_STAINED_GLASS_PANE);
    }

    public static ItemStack arrow() {
        return new ItemStack(Items.ARROW);
    }
}
