package com.bettertrades.history;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonObject;
import com.bettertrades.BetterTrades;
import com.bettertrades.db.Columns;
import com.bettertrades.db.Database;
import com.bettertrades.db.Ulid;
import com.bettertrades.escrow.EscrowService;
import com.bettertrades.trade.PokemonFingerprint;
import com.bettertrades.util.Payloads;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Only completed trades reach the database, and each one reaches it whole: a single transaction,
 * otherwise an error halfway through would leave a trade without its offers.
 */
public final class TradeHistory {

    public static final String SNAPSHOT_FORMAT = "cobblemon-json-1";


    public record ItemRow(String id, UUID fromPlayer, String itemId, int amount, byte[] payload) {
        public static ItemRow of(MinecraftServer server, UUID fromPlayer, ItemStack stack) {
            return new ItemRow(Ulid.next(), fromPlayer,
                    Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(),
                    Payloads.encodeStack(server, stack));
        }
    }

    public record PokemonRow(String id, UUID fromPlayer, String species, String form, int level,
                             boolean shiny, String gender, String nickname, String nature, String ability,
                             String heldItem, String ball, String originalTrainer, UUID pokemonUuid,
                             boolean tradeable, Map<String, int[]> stats, List<String> moves,
                             Set<String> aspects, byte[] snapshot) {

        public static PokemonRow of(MinecraftServer server, UUID fromPlayer, Pokemon pokemon) {
            ItemStack held = pokemon.getHeldItem$common();
            JsonObject json = pokemon.saveToJSON(server.getRegistryManager(), new JsonObject());
            return new PokemonRow(
                    Ulid.next(), fromPlayer,
                    Columns.truncate(pokemon.getSpecies().getResourceIdentifier().toString(), Columns.IDENT),
                    Columns.truncate(pokemon.getForm().getName(), Columns.SHORT),
                    pokemon.getLevel(),
                    pokemon.getShiny(),
                    Columns.truncate(pokemon.getGender().name(), Columns.SHORT),
                    pokemon.getNickname() == null ? null
                            : Columns.truncate(pokemon.getNickname().getString(), Columns.LONGTEXT),
                    Columns.truncate(pokemon.getNature().getName().toString(), Columns.IDENT),
                    Columns.truncate(pokemon.getAbility().getName(), Columns.IDENT),
                    held.isEmpty() ? null : Registries.ITEM.getId(held.getItem()).toString(),
                    Columns.truncate(pokemon.getCaughtBall().getName().toString(), Columns.IDENT),
                    Columns.truncate(pokemon.getOriginalTrainer(), Columns.SHORT),
                    pokemon.getUuid(),
                    pokemon.getTradeable(),
                    PokemonFingerprint.stats(pokemon),
                    PokemonFingerprint.moves(pokemon),
                    new TreeSet<>(pokemon.getAspects()),
                    Payloads.utf8(json.toString()));
        }
    }

    /**
     * {@code amount} is what the player had offered, {@code transferred} the real delta on their
     * balance: negative for the payer, positive for the payee, zero when the offers cancel out.
     * Anyone reconstructing a money dispute looks at the second one, not the first.
     */
    public record MoneyRow(UUID player, long amount, long transferred, String currency) {}

    public record Completed(String tradeId, UUID leftId, String leftName, UUID rightId, String rightName,
                            long startedAt, long completedAt, String world, String origin,
                            List<ItemRow> items, List<PokemonRow> pokemon, List<MoneyRow> money) {}

    private TradeHistory() {}

    /**
     * The transaction is opened by {@link Database}, which wraps every Work: opening one here too
     * would mean committing the outer one halfway.
     */
    public static CompletableFuture<Void> write(MinecraftServer server, Completed trade) {
        return Database.execute(connection -> {
            insertTrade(connection, trade);
            for (ItemRow row : trade.items()) insertItem(connection, trade.tradeId(), row);
            for (PokemonRow row : trade.pokemon()) insertPokemon(connection, trade.tradeId(), row);
            for (MoneyRow row : trade.money()) insertMoney(connection, trade.tradeId(), row);
            Database.markForReplay(connection, "TRADE", trade.tradeId());
        }).exceptionally(error -> {
            BetterTrades.LOGGER.error("History of trade {} was not saved", trade.tradeId(), error);
            return null;
        });
    }

    private static void insertTrade(Connection connection, Completed trade) throws SQLException {
        EscrowService.ensurePlayer(connection, trade.leftId(), trade.leftName());
        EscrowService.ensurePlayer(connection, trade.rightId(), trade.rightName());

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade (id, started_at, completed_at, world, origin) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, trade.tradeId());
            insert.setLong(2, trade.startedAt());
            insert.setLong(3, trade.completedAt());
            insert.setString(4, Columns.truncate(trade.world(), Columns.IDENT));
            insert.setString(5, trade.origin());
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_participant (trade_id, player_uuid, side) VALUES (?, ?, ?)")) {
            insert.setString(1, trade.tradeId());
            insert.setString(2, trade.leftId().toString());
            insert.setInt(3, 0);
            insert.addBatch();
            insert.setString(1, trade.tradeId());
            insert.setString(2, trade.rightId().toString());
            insert.setInt(3, 1);
            insert.addBatch();
            insert.executeBatch();
        }
    }

    private static void insertItem(Connection connection, String tradeId, ItemRow row) throws SQLException {
        EscrowService.ensureItem(connection, row.itemId());
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_item (id, trade_id, from_player, item_id, amount) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, row.id());
            insert.setString(2, tradeId);
            insert.setString(3, row.fromPlayer().toString());
            insert.setString(4, row.itemId());
            insert.setInt(5, row.amount());
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_item_components (trade_item_id, payload) VALUES (?, ?)")) {
            insert.setString(1, row.id());
            insert.setBytes(2, row.payload());
            insert.executeUpdate();
        }
    }

    private static void insertPokemon(Connection connection, String tradeId, PokemonRow row) throws SQLException {
        ensureSpecies(connection, row.species());
        if (row.heldItem() != null) EscrowService.ensureItem(connection, row.heldItem());

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_pokemon (id, trade_id, from_player, species_id, form, level, shiny,"
                        + " gender, nickname, nature, ability, held_item_id, ball, original_trainer,"
                        + " pokemon_uuid, tradeable)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, row.id());
            insert.setString(2, tradeId);
            insert.setString(3, row.fromPlayer().toString());
            insert.setString(4, row.species());
            insert.setString(5, row.form());
            insert.setInt(6, row.level());
            insert.setInt(7, row.shiny() ? 1 : 0);
            insert.setString(8, row.gender());
            insert.setString(9, row.nickname());
            insert.setString(10, row.nature());
            insert.setString(11, row.ability());
            insert.setString(12, row.heldItem());
            insert.setString(13, row.ball());
            insert.setString(14, row.originalTrainer());
            insert.setString(15, row.pokemonUuid().toString());
            insert.setInt(16, row.tradeable() ? 1 : 0);
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_pokemon_stat (trade_pokemon_id, stat, iv, ev) VALUES (?, ?, ?, ?)")) {
            for (Map.Entry<String, int[]> stat : row.stats().entrySet()) {
                insert.setString(1, row.id());
                insert.setString(2, stat.getKey());
                insert.setInt(3, stat.getValue()[0]);
                insert.setInt(4, stat.getValue()[1]);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_pokemon_move (trade_pokemon_id, slot, move_id) VALUES (?, ?, ?)")) {
            List<String> moves = row.moves();
            for (int slot = 0; slot < moves.size(); slot++) {
                insert.setString(1, row.id());
                insert.setInt(2, slot);
                insert.setString(3, moves.get(slot));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_pokemon_aspect (trade_pokemon_id, aspect) VALUES (?, ?)")) {
            for (String aspect : row.aspects()) {
                insert.setString(1, row.id());
                insert.setString(2, aspect);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_pokemon_snapshot (trade_pokemon_id, format_version, payload) VALUES (?, ?, ?)")) {
            insert.setString(1, row.id());
            insert.setString(2, SNAPSHOT_FORMAT);
            insert.setBytes(3, row.snapshot());
            insert.executeUpdate();
        }
    }

    private static void insertMoney(Connection connection, String tradeId, MoneyRow row) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO trade_money (trade_id, player_uuid, amount, transferred, currency)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, tradeId);
            insert.setString(2, row.player().toString());
            insert.setLong(3, row.amount());
            insert.setLong(4, row.transferred());
            insert.setString(5, row.currency());
            insert.executeUpdate();
        }
    }

    private static void ensureSpecies(Connection connection, String identifier) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                Database.backend() == Database.Backend.MARIADB
                        ? "INSERT IGNORE INTO species (identifier, first_seen) VALUES (?, ?)"
                        : "INSERT OR IGNORE INTO species (identifier, first_seen) VALUES (?, ?)")) {
            insert.setString(1, identifier);
            insert.setLong(2, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }
}
