package com.bettertrades.gui;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.pc.PCBox;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.bettertrades.trade.PokemonFingerprint;
import com.bettertrades.trade.TradeSession;
import com.bettertrades.lang.Lang;
import com.bettertrades.util.Texts;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementInterface;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Party on the first row, PC boxes below, one box per page. */
public final class PokemonPickerGui extends SimpleGuiBase {

    private static final int PARTY_SIZE = 6;
    private static final int BOX_FIRST_SLOT = 9;
    private static final int BOX_CAPACITY = 30;
    private static final int PREVIOUS_BOX = 45;
    private static final int BACK = 48;
    private static final int BOX_LABEL = 49;
    private static final int NEXT_BOX = 53;

    private final TradeScreens screens;
    private final TradeSession session;
    private final TradeGui parent;
    private int boxIndex;

    private PokemonPickerGui(ServerPlayerEntity player, TradeScreens screens, TradeGui parent) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        this.screens = screens;
        this.session = screens.session();
        this.parent = parent;
        setTitle(Lang.text("gui.picker.title"));
    }

    public static void open(ServerPlayerEntity player, TradeScreens screens, TradeGui parent) {
        PokemonPickerGui picker = new PokemonPickerGui(player, screens, parent);
        picker.refresh();
        picker.open();
    }

    private void refresh() {
        List<Pokemon> party = party();
        for (int slot = 0; slot < PARTY_SIZE; slot++) {
            Pokemon pokemon = slot < party.size() ? party.get(slot) : null;
            if (pokemon == null) {
                setStack(slot, Icons.empty(), Lang.name("gui.picker.party_empty"), List.of());
            } else {
                setStack(slot, PokemonItem.from(pokemon), name(pokemon),
                        lore(pokemon, Lang.raw("gui.picker.where.party")));
            }
        }
        for (int slot = 6; slot < 9; slot++) {
            setStack(slot, Icons.divider(), Text.empty(), List.of());
        }

        PCBox box = box();
        for (int index = 0; index < BOX_CAPACITY; index++) {
            Pokemon pokemon = box == null ? null : box.get(index);
            int slot = BOX_FIRST_SLOT + index;
            if (pokemon == null) {
                setStack(slot, Icons.empty(), Lang.name("gui.picker.empty"), List.of());
            } else {
                setStack(slot, PokemonItem.from(pokemon), name(pokemon),
                        lore(pokemon, Lang.raw("gui.picker.where.box", boxIndex + 1)));
            }
        }

        setStack(PREVIOUS_BOX, Icons.arrow(), Lang.name("gui.picker.previous"), List.of());
        setStack(NEXT_BOX, Icons.arrow(), Lang.name("gui.picker.next"), List.of());
        setStack(BOX_LABEL, Icons.divider(),
                Lang.name("gui.picker.box", boxIndex + 1, boxCount()), List.of());
        setStack(BACK, Icons.withdraw(), Lang.name("gui.picker.back"), List.of());
    }

    private Text name(Pokemon pokemon) {
        String nickname = pokemon.getNickname() == null ? null : pokemon.getNickname().getString();
        String label = nickname != null && !nickname.isBlank() ? nickname : pokemon.getSpecies().getName();
        return Lang.name(pokemon.getShiny() ? "gui.picker.entry.shiny" : "gui.picker.entry",
                label, pokemon.getLevel());
    }

    private List<Text> lore(Pokemon pokemon, String where) {
        List<Text> lore = new ArrayList<>(3);
        lore.add(Lang.name("gui.picker.lore.where", where));
        if (pokemon.getShiny()) lore.add(Lang.name("gui.picker.lore.shiny"));
        if (!pokemon.getTradeable()) lore.add(Lang.name("gui.picker.lore.untradeable"));
        return lore;
    }

    private List<Pokemon> party() {
        List<Pokemon> pokemon = new ArrayList<>(PARTY_SIZE);
        for (Pokemon inParty : Cobblemon.INSTANCE.getStorage().getParty(getPlayer())) {
            pokemon.add(inParty);
        }
        return pokemon;
    }

    private PCStore pc() {
        return Cobblemon.INSTANCE.getStorage().getPC(getPlayer());
    }

    private PCBox box() {
        List<PCBox> boxes = pc().getBoxes();
        if (boxes.isEmpty()) return null;
        boxIndex = Math.floorMod(boxIndex, boxes.size());
        return boxes.get(boxIndex);
    }

    private int boxCount() {
        return Math.max(1, pc().getBoxes().size());
    }

    @Override
    public boolean onClick(int index, ClickType type, SlotActionType action, GuiElementInterface element) {
        if (session.stage() == TradeSession.Stage.CLOSED) {
            closeQuietly();
            return false;
        }
        switch (index) {
            case BACK -> {
                openParent();
                return false;
            }
            case PREVIOUS_BOX -> {
                boxIndex--;
                refresh();
                return false;
            }
            case NEXT_BOX -> {
                boxIndex++;
                refresh();
                return false;
            }
            default -> { }
        }

        Pokemon chosen = null;
        PokemonFingerprint.Location location = PokemonFingerprint.Location.PARTY;
        if (index >= 0 && index < PARTY_SIZE) {
            List<Pokemon> party = party();
            if (index < party.size()) chosen = party.get(index);
        } else if (index >= BOX_FIRST_SLOT && index < BOX_FIRST_SLOT + BOX_CAPACITY) {
            PCBox box = box();
            chosen = box == null ? null : box.get(index - BOX_FIRST_SLOT);
            location = PokemonFingerprint.Location.PC;
        }
        if (chosen == null) return false;

        if (session.sideOf(getPlayer().getUuid()).full()) {
            Texts.denied(getPlayer(), "chat.error.offer_full");
            return false;
        }
        session.offerPokemon(getPlayer(), chosen, location);
        openParent();
        return false;
    }

    private void openParent() {
        parent.open();
    }

    @Override
    public void onOpen() {
        screens.showing(this);
    }

    /**
     * Esc in here is the Back button, not a walk-out: it returns to the trade, which stays
     * intact. If the session is already closed - cancelled by the other player, or completed -
     * nothing reopens, and that is also the case where {@code closeQuietly()} comes from the session.
     */
    @Override
    public void onClose() {
        if (session.stage() == TradeSession.Stage.CLOSED) return;
        openParent();
    }
}
