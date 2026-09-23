package com.bettertrades.gui;

import com.bettertrades.economy.MoneyService;
import com.bettertrades.lang.Lang;
import com.bettertrades.trade.TradeSession;
import com.bettertrades.util.Sounds;
import com.bettertrades.util.Texts;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.elements.GuiElementInterface;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


/**
 * The amount is typed into an anvil: a slot screen has no text fields, and a chat command will not
 * do because opening the chat closes the window and would cancel the trade.
 *
 * The panel is artwork in the title, like the trade screen's (see {@link GuiTextures}): its blue
 * box lies under the anvil's own text field, so the typed amount shows up on the display, and
 * the back and confirm buttons are items on the anvil's second input slot and its output slot.
 */
public final class MoneyInputGui extends AnvilInputGui implements SessionScreen {

    private static final int BACK_SLOT = 1;
    private static final int CONFIRM_SLOT = 2;
    /** Where the panel starts on the anvil texture: the buttons then fall on slots 1 and 2. */
    private static final int PANEL_X = 40;

    private final TradeScreens screens;
    private final TradeSession session;
    private final TradeGui parent;
    /**
     * The players with a balance read in flight.
     *
     * Every click on confirm used to queue a MoneyService.balance on the economy worker, which has
     * a single thread and also serves everyone else's payments and refunds. By holding the click
     * down, any player could fill that queue: other people's payments expired before they even
     * started - the timeout starts when the operation is created - and their trades were cancelled
     * for MONEY. One read per player at a time, and the cost stays with whoever causes it.
     *
     * Per player and not per screen: every press of the money button builds a new screen, so a
     * flag on the instance was reset by going back and forth. Server thread only.
     */
    private static final Set<UUID> READING = new HashSet<>();

    private MoneyInputGui(ServerPlayerEntity player, TradeScreens screens, TradeGui parent) {
        super(player, false);
        this.screens = screens;
        this.session = screens.session();
        this.parent = parent;
        setTitle(GuiTextures.anvilComposer().moneyInput(PANEL_X).build());
        // The anvil's text field starts with the name of the item in slot 0: an empty name leaves
        // the field empty, ready for the amount, instead of a hint the player has to delete first.
        setSlot(0, new GuiElementBuilder(Items.PAPER)
                .setName(Text.empty())
                .setLore(Lang.lines("gui.money.input.lore"))
                .build());
        setSlot(BACK_SLOT, GuiElementBuilder.from(Icons.moneyBack())
                .setName(Lang.name("gui.money.back"))
                .setLore(Lang.lines("gui.money.back.lore"))
                .build());
        drawConfirm(offered());
    }

    /** Server shutdown: a read whose answer never came back must not block the next world. */
    public static void clearReads() {
        READING.clear();
    }

    /** What this player already has on the table. */
    private long offered() {
        return session.sideOf(getPlayer().getUuid()).money();
    }

    public static void open(ServerPlayerEntity player, TradeScreens screens, TradeGui parent) {
        new MoneyInputGui(player, screens, parent).open();
    }

    private void drawConfirm(long amount) {
        setSlot(CONFIRM_SLOT, GuiElementBuilder.from(Icons.moneyConfirm())
                .setName(Lang.name("gui.money.confirm", amount))
                .setLore(Lang.lines("gui.money.confirm.lore"))
                .build());
    }

    @Override
    public void onInput(String input) {
        drawConfirm(isBlank(input) ? offered() : parse(input));
    }

    @Override
    public boolean onClick(int index, ClickType type, SlotActionType action, GuiElementInterface element) {
        if (session.stage() == TradeSession.Stage.CLOSED) {
            closeQuietly();
            return false;
        }
        if (index == BACK_SLOT) {
            Sounds.click(getPlayer());
            parent.open();
            return false;
        }
        if (index != CONFIRM_SLOT) return false;

        // The field starts empty, so an empty field means "leave it as it is", not zero: someone
        // who only opened the anvil to look at the amount must not withdraw it by confirming.
        if (isBlank(getInput())) {
            Sounds.click(getPlayer());
            parent.open();
            return false;
        }
        long amount = parse(getInput());
        if (amount < 0) {
            Texts.denied(getPlayer(), "chat.error.amount_invalid");
            return false;
        }
        if (amount == 0) {
            session.offerMoney(getPlayer(), 0);
            parent.open();
            return false;
        }

        UUID reader = getPlayer().getUuid();
        if (!READING.add(reader)) return false;
        MinecraftServer server = getPlayer().getServer();
        MoneyService.balance(reader).whenComplete((balance, error) -> server.execute(() -> {
            READING.remove(reader);
            // The read can take seconds. If the trade closed in the meantime, or the player left,
            // there is nothing to go back to: reopening the parent would show a dead trade.
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(reader);
            if (session.stage() == TradeSession.Stage.CLOSED || player == null || player.isRemoved()) {
                return;
            }
            if (error != null || balance == null || balance < amount) {
                Texts.denied(player, "chat.error.balance", balance == null ? 0 : balance);
                parent.open();
                return;
            }
            session.offerMoney(player, amount);
            parent.open();
        }));
        return false;
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
        parent.open();
    }

    private static boolean isBlank(String input) {
        return input == null || input.isBlank();
    }

    /** -1 when it is not a number: the anvil accepts any text. */
    private static long parse(String input) {
        if (isBlank(input)) return 0;
        try {
            return Long.parseLong(input.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
