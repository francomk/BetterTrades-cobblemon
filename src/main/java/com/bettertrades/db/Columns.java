package com.bettertrades.db;

import com.bettertrades.BetterTrades;

/**
 * The widths of the text columns, and the truncation that respects them.
 *
 * On SQLite they are all TEXT and constrain nothing; on MariaDB they are VARCHAR, and with an
 * sql_mode containing STRICT_TRANS_TABLES - the default since MariaDB 10.2 - a value that is too
 * long is not truncated by the server, it fails the INSERT. A value that passes on one backend and
 * fails on the other would be the worst of the two, so everything is cut to the narrower width.
 *
 * The numbers are those of {@link Dialect#MARIADB}: changing them there means changing them here.
 */
public final class Columns {

    public static final int NAME = 16;
    public static final int SHORT = 64;
    public static final int IDENT = 96;
    public static final int LONGTEXT = 512;

    private Columns() {}

    /**
     * Cuts a value to the width of its column.
     *
     * Where it really matters is the history: that INSERT describes a trade that has ALREADY
     * happened, with the Pokemon and the items already moved, so a failure there cancels nothing -
     * it only loses the record of what happened. Better a truncated nickname than a trade with no history.
     */
    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        // Cutting in the middle of a surrogate pair would leave an invalid character behind.
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        BetterTrades.LOGGER.warn("Value truncated to {} characters for the database: {}", end, value);
        return value.substring(0, end);
    }
}
