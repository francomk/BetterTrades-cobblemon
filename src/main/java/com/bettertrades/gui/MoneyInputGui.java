package com.bettertrades.gui;

import com.bettertrades.economy.MoneyService;
import com.bettertrades.lang.Lang;
import com.bettertrades.trade.TradeSession;
import com.bettertrades.util.Texts;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.elements.GuiElementInterface;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;


/**
 * The amount is typed into an anvil: a slot screen has no text fields, and a chat command will not
 * do because opening the chat closes the window and would cancel the trade.
 */
public final class MoneyInputGui extends AnvilInputGui implements SessionScreen {

    private static final int CONFIRM_SLOT = 2;

    private final TradeScreens screens;
    private final TradeSession session;
    private final TradeGui parent;
    /**
     * True while a balance read is in flight.
     *
     * Every click on confirm used to queue a MoneyService.balance on the economy worker, which has
     * a single thread and also serves everyone else's payments and refunds. By holding the click
     * down, any player could fill that queue: other people's payments expired before they even
     * started - the timeout starts when the operation is created - and their trades were cancelled
     * for MONEY. One click at a time, and the cost stays with whoever causes it.
     */
    private boolean reading;

    private MoneyInputGui(ServerPlayerEntity player, TradeScreens screens, TradeGui parent) {
        super(player, false);
        this.screens = screens;
        this.session = screens.session();
        this.parent = parent;
        setTitle(Lang.text("gui.money.title"));
        setDefaultInputValue(String.valueOf(session.sideOf(player.getUuid()).money()));
        setSlot(0, new GuiElementBuilder(Items.PAPER)
                .setName(Lang.name("gui.money.input"))
                .setLore(Lang.lines("gui.money.input.lore"))
                .build());
        drawConfirm(0);
    }

    public static void open(ServerPlayerEntity player, TradeScreens screens, TradeGui parent) {
        new MoneyInputGui(player, screens, parent).open();
    }

    private void drawConfirm(long amount) {
        setSlot(CONFIRM_SLOT, new GuiElementBuilder(Items.GOLD_NUGGET)
                .setName(Lang.name("gui.money.confirm", amount))
                .setLore(Lang.lines("gui.money.confirm.lore"))
                .build());
    }

    @Override
    public void onInput(String input) {
        drawConfirm(parse(input));
    }

    @Override
    public boolean onClick(int index, ClickType type, SlotActionType action, GuiElementInterface element) {
        if (session.stage() == TradeSession.Stage.CLOSED) {
            closeQuietly();
            return false;
        }
        if (index != CONFIRM_SLOT) return false;

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

        if (reading) return false;
        reading = true;
        MoneyService.balance(getPlayer().getUuid()).whenComplete((balance, error) ->
                getPlayer().getServer().execute(() -> {
                    reading = false;
                    if (error != null || balance == null || balance < amount) {
                        Texts.denied(getPlayer(), "chat.error.balance",
                                balance == null ? 0 : balance);
                        parent.open();
                        return;
                    }
                    session.offerMoney(getPlayer(), amount);
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

    /** -1 when it is not a number: the anvil accepts any text. */
    private static long parse(String input) {
        if (input == null || input.isBlank()) return 0;
        try {
            return Long.parseLong(input.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
