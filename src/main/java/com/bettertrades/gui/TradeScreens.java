package com.bettertrades.gui;

import com.bettertrades.trade.TradeSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which screen each of the two players has in front of them right now.
 *
 * Without this registry the end of a session only closed the two main screens: whoever was in the
 * picker or in the anvil was left with a window open on a trade that had already finished, holding
 * items and Pokemon that were no longer theirs.
 *
 * It lives in the gui package and not inside {@link TradeSession} on purpose: the session is
 * domain logic and does not know about screens.
 */
final class TradeScreens {

    private final TradeSession session;
    private final Map<UUID, TradeGui> main = new LinkedHashMap<>();
    private final Map<UUID, SessionScreen> showing = new LinkedHashMap<>();

    TradeScreens(TradeSession session) {
        this.session = session;
    }

    TradeSession session() {
        return session;
    }

    void main(TradeGui gui) {
        main.put(gui.getPlayer().getUuid(), gui);
    }

    /** Called by every screen when it opens, main or child alike. */
    void showing(SessionScreen screen) {
        showing.put(screen.getPlayer().getUuid(), screen);
    }

    void closeAll() {
        for (SessionScreen screen : showing.values()) {
            screen.closeQuietly();
        }
        showing.clear();
    }

    /**
     * Brings whoever is in a child screen back to the trade screen.
     *
     * Needed at the start of the countdown: the child screens can be opened while already confirmed,
     * because until the other player confirms the stage is still OFFERING and {@code frozen()} lets
     * it through. Without this, the three seconds would run behind a window that does not show them
     * and has no button to cancel them.
     *
     * Copying the map is not decorative: {@code open()} re-enters here through {@link #showing}.
     */
    void backToMain() {
        for (Map.Entry<UUID, SessionScreen> entry : new LinkedHashMap<>(showing).entrySet()) {
            TradeGui gui = main.get(entry.getKey());
            if (gui == null || entry.getValue() == gui) continue;
            gui.open();
        }
    }
}
