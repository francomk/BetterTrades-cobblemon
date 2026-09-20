package com.bettertrades.gui;

import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.bettertrades.economy.MoneyService;
import com.bettertrades.lang.Lang;
import com.bettertrades.trade.CancelReason;
import com.bettertrades.trade.OfferEntry;
import com.bettertrades.trade.PokemonFingerprint;
import com.bettertrades.trade.TradeSession;
import com.bettertrades.util.Sounds;
import com.bettertrades.util.Texts;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.elements.GuiElementInterface;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * The trade screen. One per player, always mirrored: whoever is looking is on the left.
 *
 * The background, the head frames and the full-inventory panel are not slots: they are artwork
 * slipped into the window title, see {@link GuiTextures}. The buttons, on the other hand, are
 * items with a model of their own, so the client draws them in the slot and hovering shows the lore.
 *
 * The grid is vanilla GENERIC_9X6 and lines up with the mockup without moving anything:
 * row 0 heads and confirm, rows 2 and 3 the two offers, row 5 the three buttons.
 */
public final class TradeGui extends SimpleGuiBase {

    private static final int MY_HEAD = 1;
    private static final int THEIR_HEAD = 7;
    private static final int[] CONFIRM_SLOTS = {3, 4, 5};
    private static final int CONFIRM_ICON = 4;
    private static final int[] MY_SLOTS = {18, 19, 20, 21, 27, 28, 29, 30};
    private static final int[] THEIR_SLOTS = {23, 24, 25, 26, 32, 33, 34, 35};
    private static final int BUTTON_TRASH = 47;
    private static final int BUTTON_POKEMON = 49;
    private static final int BUTTON_MONEY = 51;

    /** Where each piece of artwork lands on the mockup, in texture pixels. */
    private static final int MY_FRAME_X = 22;
    private static final int THEIR_FRAME_X = 130;
    private static final int MY_NAME_X = 7;
    private static final int THEIR_NAME_END_X = 169;
    private static final int WARNING_X = 182;
    private static final int WARNING_CENTRE_X = 221;

    /** The text lines, in pixels below the title line. Same values in gen_gui_assets.py. */
    private static final int NAME_LINE = 36;
    private static final int[] WARNING_LINES = {16, 29, 37, 45, 57, 65, 73};

    private final TradeScreens screens;
    private final TradeSession session;
    private final TradeSession.Side mine;
    private final TradeSession.Side theirs;
    private String drawnTitle = "";

    private TradeGui(ServerPlayerEntity player, TradeScreens screens) {
        // false, not true: with true sgui replaces the inventory slots with slots of its own,
        // which only show what the GUI puts in them, and the inventory looks empty. With false
        // the real slots stay, and the block below stops anything being moved into them while
        // still letting our own clicks through.
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        setLockPlayerInventory(true);
        this.screens = screens;
        this.session = screens.session();
        this.mine = session.sideOf(player.getUuid());
        this.theirs = session.otherSide(player.getUuid());
    }

    public static void openFor(TradeSession session) {
        ServerPlayerEntity leftPlayer = session.playerOf(session.left());
        ServerPlayerEntity rightPlayer = session.playerOf(session.right());
        if (leftPlayer == null || rightPlayer == null) {
            session.cancel(CancelReason.DISCONNECT, session.left().playerName());
            return;
        }

        TradeScreens screens = new TradeScreens(session);
        TradeGui leftGui = new TradeGui(leftPlayer, screens);
        TradeGui rightGui = new TradeGui(rightPlayer, screens);
        screens.main(leftGui);
        screens.main(rightGui);

        session.onChanged(() -> {
            // Before redrawing: the countdown can only be cancelled from this screen, so anyone in
            // a child screen is brought back here as soon as it starts.
            if (session.stage() == TradeSession.Stage.LOCKED) screens.backToMain();
            leftGui.refresh();
            rightGui.refresh();
        });
        // Not just the two main screens: whatever each of them really has in front of them is closed.
        session.onClosed(reason -> screens.closeAll());

        leftGui.refresh();
        rightGui.refresh();
        leftGui.open();
        rightGui.open();
    }

    // ------------------------------------------------------------------ drawing

    private void refresh() {
        drawTitle();
        drawHeads();
        drawOffer(MY_SLOTS, mine);
        drawOffer(THEIR_SLOTS, theirs);
        drawButtons();
    }

    /**
     * Recomposes the background and every label.
     *
     * Changing the title of an open window resends the open packet with the same syncId: it is the
     * only way to redraw the background, and it is how the Cancel button fades. Comparing against
     * the last title avoids resending it when nothing has changed.
     */
    private void drawTitle() {
        GuiTextures.Composer composer = GuiTextures.composer()
                .background()
                .headFrame(MY_FRAME_X, mine.ready())
                .headFrame(THEIR_FRAME_X, theirs.ready());

        List<Text> warning = warningLines();
        if (!warning.isEmpty()) composer.warningPanel(WARNING_X);

        // The name plaque and the button labels are not here: the first sits above the title line
        // and the second would end up under the button's item, so they are painted into the
        // textures by gen_gui_assets.py.
        composer.line(MY_NAME_X, NAME_LINE, Lang.name("gui.trade.name.you"))
                .rightAligned(THEIR_NAME_END_X, NAME_LINE,
                        Lang.name("gui.trade.name.other", theirs.playerName()));

        for (int line = 0; line < warning.size() && line < WARNING_LINES.length; line++) {
            composer.centred(WARNING_CENTRE_X, WARNING_LINES[line], warning.get(line));
        }

        Text title = composer.build();
        if (!title.getString().equals(drawnTitle)) {
            drawnTitle = title.getString();
            setTitle(title);
        }
    }

    /**
     * The "inventory full" panel: both players see it, each with their own version.
     *
     * It is not only a warning, because the confirm button stays blocked while it is lit: items
     * that do not fit are not dropped, the trade does not start.
     */
    private List<Text> warningLines() {
        boolean mineBlocked = session.inventoryBlocked(mine);
        boolean theirsBlocked = session.inventoryBlocked(theirs);
        if (!mineBlocked && !theirsBlocked) return List.of();

        List<Text> lines = new java.util.ArrayList<>();
        lines.add(Lang.name("gui.warn.title"));
        lines.addAll(mineBlocked
                ? Lang.lines("gui.warn.you")
                : Lang.lines("gui.warn.other", theirs.playerName()));
        return lines;
    }

    private void drawHeads() {
        setStack(MY_HEAD, Icons.head(getPlayer().getGameProfile()),
                Lang.name("gui.trade.head.you", mine.playerName()),
                Lang.lines(mine.ready() ? "gui.trade.head.ready" : "gui.trade.head.waiting"));

        ServerPlayerEntity other = session.playerOf(theirs);
        if (other != null) {
            setStack(THEIR_HEAD, Icons.head(other.getGameProfile()),
                    Lang.name("gui.trade.head.other", theirs.playerName()),
                    Lang.lines(theirs.ready() ? "gui.trade.head.ready" : "gui.trade.head.waiting"));
        }
    }

    /** Empty slots stay empty slots: the cell is already drawn in the background underneath. */
    private void drawOffer(int[] slots, TradeSession.Side side) {
        List<OfferEntry> entries = side.entries();
        boolean removable = side == mine && !session.frozen();

        for (int index = 0; index < slots.length; index++) {
            if (index >= entries.size()) {
                clearSlot(slots[index]);
                continue;
            }
            OfferEntry entry = entries.get(index);
            List<Text> lore = removable ? Lang.lines("gui.trade.slot.remove") : List.of();

            if (entry instanceof OfferEntry.Item item) {
                setSlot(slots[index], GuiElementBuilder.from(item.stack().copy())
                        .setLore(lore).build());
            } else if (entry instanceof OfferEntry.Mon mon) {
                ItemStack icon = pokemonIcon(side, mon);
                setSlot(slots[index], GuiElementBuilder.from(icon).setLore(lore).build());
            }
        }
    }

    private ItemStack pokemonIcon(TradeSession.Side side, OfferEntry.Mon offered) {
        ServerPlayerEntity owner = session.playerOf(side);
        Pokemon pokemon = owner == null ? null : PokemonFingerprint.find(owner, offered.pokemonUuid());
        return pokemon == null ? Icons.empty() : PokemonItem.from(pokemon);
    }

    private void drawButtons() {
        boolean locked = session.stage() == TradeSession.Stage.LOCKED;

        if (locked) {
            // The wide button sits in a single slot and the model stretches it over three: the slots
            // on either side stay empty but clickable, so the target covers the whole drawing.
            setStack(CONFIRM_ICON, Icons.cancel(session.countdownFrame()),
                    Lang.name("gui.button.cancel"), Lang.lines("gui.button.cancel.lore"));
        } else if (session.inventoryBlocked(mine)) {
            setStack(CONFIRM_ICON, Icons.confirm(false),
                    Lang.name("gui.button.confirm.blocked"),
                    Lang.lines("gui.button.confirm.blocked.lore"));
        } else {
            setStack(CONFIRM_ICON, Icons.confirm(mine.ready()),
                    Lang.name(mine.ready() ? "gui.button.confirmed" : "gui.button.confirm"),
                    Lang.lines("gui.button.confirm.lore"));
        }
        clearSlot(CONFIRM_SLOTS[0]);
        clearSlot(CONFIRM_SLOTS[2]);

        setStack(BUTTON_TRASH, Icons.trash(), Lang.name("gui.button.trash"),
                Lang.lines("gui.button.trash.lore"));

        setStack(BUTTON_POKEMON, Icons.addPokemon(), Lang.name("gui.button.pokemon"),
                Lang.lines("gui.button.pokemon.lore"));

        if (MoneyService.available()) {
            setStack(BUTTON_MONEY, Icons.money(true), Lang.name("gui.button.money"),
                    Lang.lines("gui.button.money.lore", mine.money(), theirs.playerName(), theirs.money()));
        } else {
            setStack(BUTTON_MONEY, Icons.money(false), Lang.name("gui.button.money.disabled"),
                    Lang.lines("gui.button.money.disabled.lore"));
        }
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public boolean onClick(int index, ClickType type, SlotActionType action, GuiElementInterface element) {
        if (session.stage() == TradeSession.Stage.CLOSED) {
            closeQuietly();
            return false;
        }

        int containerSize = getWidth() * getHeight();
        if (index >= containerSize) {
            offerFromInventory(index - containerSize, type);
            return false;
        }

        if (index == CONFIRM_SLOTS[0] || index == CONFIRM_SLOTS[1] || index == CONFIRM_SLOTS[2]) {
            if (session.stage() == TradeSession.Stage.LOCKED) {
                session.cancelCountdown(getPlayer());
            } else {
                session.setReady(getPlayer().getUuid(), !mine.ready());
            }
            return false;
        }

        int offered = indexIn(MY_SLOTS, index);
        if (offered >= 0) {
            session.removeEntry(getPlayer(), offered);
            return false;
        }

        switch (index) {
            case BUTTON_TRASH -> session.withdraw(getPlayer());
            case BUTTON_POKEMON -> {
                if (session.frozen()) return false;
                Sounds.click(getPlayer());
                PokemonPickerGui.open(getPlayer(), screens, this);
            }
            case BUTTON_MONEY -> {
                if (session.frozen()) return false;
                if (MoneyService.available()) {
                    Sounds.click(getPlayer());
                    MoneyInputGui.open(getPlayer(), screens, this);
                } else {
                    Texts.denied(getPlayer(), "chat.error.economy_missing");
                }
            }
            default -> { }
        }
        return false;
    }

    private static int indexIn(int[] slots, int slot) {
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == slot) return index;
        }
        return -1;
    }

    /**
     * Takes the item out of the inventory BEFORE offering it: the session puts whatever it receives
     * into escrow, so handing it over while it is still in the inventory would duplicate it.
     */
    private void offerFromInventory(int offset, ClickType type) {
        if (session.frozen()) return;

        int inventorySlot = offset < 27 ? offset + 9 : offset - 27;
        ItemStack inSlot = getPlayer().getInventory().getStack(inventorySlot);
        if (inSlot.isEmpty()) return;
        if (mine.full()) {
            Texts.denied(getPlayer(), "chat.error.offer_full");
            return;
        }

        int amount = switch (type) {
            case MOUSE_RIGHT, MOUSE_RIGHT_SHIFT -> 1;
            case MOUSE_LEFT_SHIFT -> Math.max(1, inSlot.getCount() / 2);
            default -> inSlot.getCount();
        };

        ItemStack taken = getPlayer().getInventory().removeStack(inventorySlot, amount);
        if (taken.isEmpty()) return;
        session.offerItem(getPlayer(), taken);
    }

    @Override
    public void onOpen() {
        screens.showing(this);
    }

    /**
     * Closing the window cancels the trade for both of them, countdown included: there Esc is worth
     * the same as the Cancel button, and nothing has moved yet.
     *
     * The two exceptions:
     *
     * CLOSED - the close comes from the session, which has already finished. There is nothing to
     * cancel, and trying would bounce off the guard inside {@code cancel()} anyway.
     *
     * COMMITTING - the money is already with the economy. Queueing a cancel here would mean taking
     * the refund road, which is the only one that can really make money disappear if the economy
     * does not answer. Both of them had confirmed the trade: it is left to finish, and the items
     * arrive all the same. Cancels not chosen by the player - disconnect, distance, battle - stay
     * queued as before: those cannot be ignored.
     */
    @Override
    public void onClose() {
        TradeSession.Stage stage = session.stage();
        if (stage == TradeSession.Stage.CLOSED || stage == TradeSession.Stage.COMMITTING) return;
        session.cancel(CancelReason.GUI_CLOSED, getPlayer().getGameProfile().getName());
    }
}
