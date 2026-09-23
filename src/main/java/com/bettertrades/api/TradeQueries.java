package com.bettertrades.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The API reads. No method writes: this is the first of the two barriers, the other is the database user. */
final class TradeQueries {

    private static final int MAX_LIMIT = 200;

    private TradeQueries() {}

    static CompletableFuture<List<TradeView>> byPlayer(UUID player, int limit, int offset) {
        return ReadOnlyDb.supply(connection -> {
            List<String> ids = ids(connection,
                    "SELECT t.id FROM trade t JOIN trade_participant p ON p.trade_id = t.id"
                            + " WHERE p.player_uuid = ? ORDER BY t.completed_at DESC LIMIT ? OFFSET ?",
                    statement -> {
                        statement.setString(1, player.toString());
                        statement.setInt(2, clamp(limit));
                        statement.setInt(3, Math.max(0, offset));
                    });
            return load(connection, ids);
        });
    }

    static CompletableFuture<List<TradeView>> byPeriod(long fromEpochMillis, long toEpochMillis,
                                                      int limit, int offset) {
        return ReadOnlyDb.supply(connection -> {
            List<String> ids = ids(connection,
                    "SELECT id FROM trade WHERE completed_at >= ? AND completed_at <= ?"
                            + " ORDER BY completed_at DESC LIMIT ? OFFSET ?",
                    statement -> {
                        statement.setLong(1, fromEpochMillis);
                        statement.setLong(2, toEpochMillis);
                        statement.setInt(3, clamp(limit));
                        statement.setInt(4, Math.max(0, offset));
                    });
            return load(connection, ids);
        });
    }

    static CompletableFuture<List<TradeView>> containingItem(String itemId, int limit, int offset) {
        return ReadOnlyDb.supply(connection -> {
            List<String> ids = ids(connection,
                    "SELECT DISTINCT t.id FROM trade t JOIN trade_item i ON i.trade_id = t.id"
                            + " WHERE i.item_id = ? ORDER BY t.completed_at DESC LIMIT ? OFFSET ?",
                    statement -> {
                        statement.setString(1, itemId);
                        statement.setInt(2, clamp(limit));
                        statement.setInt(3, Math.max(0, offset));
                    });
            return load(connection, ids);
        });
    }

    static CompletableFuture<List<TradeView>> containingSpecies(String speciesId, int limit, int offset) {
        return ReadOnlyDb.supply(connection -> {
            List<String> ids = ids(connection,
                    "SELECT DISTINCT t.id FROM trade t JOIN trade_pokemon m ON m.trade_id = t.id"
                            + " WHERE m.species_id = ? ORDER BY t.completed_at DESC LIMIT ? OFFSET ?",
                    statement -> {
                        statement.setString(1, speciesId);
                        statement.setInt(2, clamp(limit));
                        statement.setInt(3, Math.max(0, offset));
                    });
            return load(connection, ids);
        });
    }

    static CompletableFuture<Optional<TradeView>> byId(String tradeId) {
        return ReadOnlyDb.supply(connection -> {
            List<TradeView> found = load(connection, List.of(tradeId));
            return found.isEmpty() ? Optional.<TradeView>empty() : Optional.of(found.get(0));
        });
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static List<String> ids(Connection connection, String sql, Binder binder) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
        }
        return ids;
    }

    private static List<TradeView> load(Connection connection, List<String> tradeIds) throws SQLException {
        if (tradeIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(tradeIds.size(), "?"));

        Map<String, List<UUID>> participants = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT trade_id, player_uuid FROM trade_participant WHERE trade_id IN (" + placeholders
                        + ") ORDER BY side")) {
            bind(statement, tradeIds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    participants.computeIfAbsent(rows.getString(1), key -> new ArrayList<>())
                            .add(UUID.fromString(rows.getString(2)));
                }
            }
        }

        Map<String, List<TradeView.ItemView>> items = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT trade_id, from_player, item_id, amount FROM trade_item WHERE trade_id IN ("
                        + placeholders + ")")) {
            bind(statement, tradeIds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.computeIfAbsent(rows.getString(1), key -> new ArrayList<>())
                            .add(new TradeView.ItemView(UUID.fromString(rows.getString(2)),
                                    rows.getString(3), rows.getInt(4)));
                }
            }
        }

        Map<String, Set<String>> aspects = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT a.trade_pokemon_id, a.aspect FROM trade_pokemon_aspect a"
                        + " JOIN trade_pokemon m ON m.id = a.trade_pokemon_id"
                        + " WHERE m.trade_id IN (" + placeholders + ")")) {
            bind(statement, tradeIds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    aspects.computeIfAbsent(rows.getString(1), key -> new HashSet<>()).add(rows.getString(2));
                }
            }
        }

        Map<String, List<TradeView.MonView>> pokemon = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, trade_id, from_player, species_id, form, level, shiny FROM trade_pokemon"
                        + " WHERE trade_id IN (" + placeholders + ")")) {
            bind(statement, tradeIds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String rowId = rows.getString(1);
                    pokemon.computeIfAbsent(rows.getString(2), key -> new ArrayList<>())
                            .add(new TradeView.MonView(UUID.fromString(rows.getString(3)),
                                    rows.getString(4), rows.getString(5), rows.getInt(6),
                                    rows.getInt(7) != 0,
                                    Set.copyOf(aspects.getOrDefault(rowId, Set.of()))));
                }
            }
        }

        Map<String, List<TradeView.MoneyView>> money = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT trade_id, player_uuid, amount, transferred, currency FROM trade_money"
                        + " WHERE trade_id IN (" + placeholders + ")")) {
            bind(statement, tradeIds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    money.computeIfAbsent(rows.getString(1), key -> new ArrayList<>())
                            .add(new TradeView.MoneyView(UUID.fromString(rows.getString(2)),
                                    rows.getLong(3), rows.getLong(4), rows.getString(5)));
                }
            }
        }

        Map<String, TradeView> byId = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, started_at, completed_at, world FROM trade WHERE id IN (" + placeholders
                        + ") ORDER BY completed_at DESC")) {
            bind(statement, tradeIds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    List<UUID> players = participants.getOrDefault(id, List.of());
                    byId.put(id, new TradeView(id, rows.getLong(2), rows.getLong(3), rows.getString(4),
                            players.size() > 0 ? players.get(0) : null,
                            players.size() > 1 ? players.get(1) : null,
                            List.copyOf(items.getOrDefault(id, List.of())),
                            List.copyOf(pokemon.getOrDefault(id, List.of())),
                            List.copyOf(money.getOrDefault(id, List.of()))));
                }
            }
        }
        return List.copyOf(byId.values());
    }

    private static void bind(PreparedStatement statement, List<String> ids) throws SQLException {
        for (int i = 0; i < ids.size(); i++) statement.setString(i + 1, ids.get(i));
    }

    private static int clamp(int limit) {
        return Math.min(MAX_LIMIT, Math.max(1, limit));
    }
}
