package com.bettertrades.trade;

/** Why a trade was cancelled. The wording lives in the language files; this is only the key. */
public enum CancelReason {

    GUI_CLOSED("chat.cancel.gui_closed"),
    PLAYER_CANCELLED("chat.cancel.player"),
    DISCONNECT("chat.cancel.disconnect"),
    DISTANCE("chat.cancel.distance"),
    DIMENSION("chat.cancel.dimension"),
    BATTLE("chat.cancel.battle"),
    POKEMON_CHANGED("chat.cancel.pokemon_changed"),
    DATABASE_DOWN("chat.cancel.database_down"),
    INVENTORY_FULL("chat.cancel.inventory_full"),
    MONEY("chat.cancel.money"),
    BLACKLIST("chat.cancel.blacklist"),
    BLOCKED("chat.cancel.blocked"),
    ADMIN("chat.cancel.admin");

    private final String key;

    CancelReason(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
