package com.bettertrades.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * The two slot screens share only this: putting a stack with a name and lore in place.
 *
 * There used to be an {@code openChild()} here holding an "I am opening another window" flag, so
 * that closing would not be mistaken for the player walking out. The flag was never cleared:
 * opening a screen server-side does not call {@code onClose()} on the previous one, on any sgui
 * path - {@code close(true)} only comes from the client's close packet, and
 * {@code openHandledScreen} goes through the branch sgui diverts. It stayed on forever, and at the
 * first real Esc on the trade it made {@code onClose()} return without cancelling anything: the
 * other player was left staring at the window of a trade that no longer existed.
 *
 * Now every {@code onClose()} looks at the session stage, which is a fact and not a flag.
 * Moving from one screen to another is just {@code open()}.
 */
public abstract class SimpleGuiBase extends SimpleGui implements SessionScreen {

    protected SimpleGuiBase(ScreenHandlerType<?> type, ServerPlayerEntity player, boolean includeInventory) {
        super(type, player, includeInventory);
    }

    protected void setStack(int slot, ItemStack stack, Text name, List<Text> lore) {
        setSlot(slot, GuiElementBuilder.from(stack).setName(name).setLore(lore).build());
    }
}
