package com.bettertrades.gui;

import eu.pb4.sgui.api.gui.GuiInterface;

/**
 * One of the trade screens: the main one or either of its two children.
 *
 * It exists for the registry in {@link TradeScreens}, which has to be able to close the screen the
 * player really has in front of them without knowing which of the three it is. The three classes
 * meet nowhere else: {@link TradeGui} and {@link PokemonPickerGui} sit under {@link SimpleGuiBase},
 * {@link MoneyInputGui} under AnvilInputGui, and Java only inherits from one.
 */
public interface SessionScreen extends GuiInterface {

    /**
     * A close decided by the session, not by the player.
     *
     * No flag is needed to tell the two apart: when the session closes the screens, the stage is
     * already CLOSED, because both {@code cancel()} and {@code finishTransfer()} set it before
     * notifying anyone. The {@code onClose()} handlers key off that. A flag, by contrast, stays on
     * when the path that was meant to clear it is never taken.
     */
    default void closeQuietly() {
        if (isOpen()) close(false);
    }
}
