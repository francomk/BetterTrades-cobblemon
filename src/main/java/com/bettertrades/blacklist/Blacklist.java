package com.bettertrades.blacklist;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.bettertrades.BetterTrades;
import com.bettertrades.db.Columns;
import com.bettertrades.db.Database;
import com.bettertrades.db.Ulid;
import com.bettertrades.escrow.EscrowService;
import com.bettertrades.lang.Lang;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The rules live in memory because the GUI queries them inside a tick and cannot wait for the
 * database. The database stays the truth: every change goes through it and then reloads the cache.
 */
public final class Blacklist {

    private static final AtomicReference<List<BlacklistRule>> CACHE =
            new AtomicReference<>(List.of());

    private Blacklist() {}

    public static List<BlacklistRule> rules() {
        return CACHE.get();
    }

    /**
     * In fallback the rules are NOT replaced.
     *
     * SQLite never received the ones written to MariaDB, so readAll() returns an empty list that
     * does not mean "there are no rules" but "this backend does not have them". Overwriting the
     * cache with it would put everything blacklisted back on the market, without a warning and
     * without the second check at commit noticing: it uses the same cache.
     */
    public static CompletableFuture<Void> reload() {
        return Database.supply(Blacklist::readAll)
                .thenAccept(rules -> {
                    if (Database.degraded() && !CACHE.get().isEmpty()) {
                        BetterTrades.LOGGER.warn("Database in fallback: the in-memory blacklist stays"
                                + " the MariaDB one ({} rules), the {} read from SQLite are ignored",
                                CACHE.get().size(), rules.size());
                        return;
                    }
                    CACHE.set(rules);
                })
                .exceptionally(error -> {
                    BetterTrades.LOGGER.error("Blacklist not loaded: the in-memory one stays", error);
                    return null;
                });
    }

    /**
     * Puts a freshly written rule straight into the cache, without going through the database.
     *
     * In fallback reload() discards what it reads from SQLite - rightly so, see above - and with it
     * it used to discard the rule just added: the administrator read "added" and the rule blocked
     * nothing, neither on offer nor at the second check at commit, which uses this very cache.
     * Adding it here before reload() means the fallback branch keeps it and the normal branch finds
     * it again in the re-read anyway.
     */
    private static void cacheAdd(BlacklistRule rule) {
        CACHE.updateAndGet(current -> {
            List<BlacklistRule> merged = new ArrayList<>(current);
            merged.add(rule);
            return List.copyOf(merged);
        });
    }

    /** Why it is blocked, when the item is blacklisted. */
    public static Optional<String> check(ItemStack stack) {
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        for (BlacklistRule rule : CACHE.get()) {
            if (rule instanceof BlacklistRule.Item item && item.itemId().equals(itemId)) {
                return Optional.of(describe(rule));
            }
        }
        return Optional.empty();
    }

    /** Why it is blocked, when the Pokemon falls under a rule. */
    public static Optional<String> check(Pokemon pokemon) {
        String species = pokemon.getSpecies().getResourceIdentifier().toString();
        String form = pokemon.getForm().getName();
        Set<String> aspects = pokemon.getAspects();

        for (BlacklistRule rule : CACHE.get()) {
            if (!(rule instanceof BlacklistRule.Mon mon)) continue;
            if (!mon.speciesId().equals(species)) continue;
            if (mon.form() != null && !mon.form().equalsIgnoreCase(form)) continue;
            if (!aspects.containsAll(mon.aspects())) continue;
            return Optional.of(describe(rule));
        }
        return Optional.empty();
    }

    private static String describe(BlacklistRule rule) {
        return rule.reason() == null || rule.reason().isBlank()
                ? "blacklisted" : rule.reason();
    }

    public static CompletableFuture<String> addItem(String rawItemId, String rawReason, String rawAddedBy) {
        String id = Ulid.next();
        // Text columns are cut to MariaDB's width even when writing to SQLite: added_by comes from
        // context.getSource().getName(), which with /execute as <entity> can be a custom name, and
        // reason from a greedyString with no limit. On MariaDB with STRICT_TRANS_TABLES a value that
        // is too long fails the INSERT instead of being truncated, so the same command would succeed
        // on one backend and not on the other.
        String itemId = Columns.truncate(rawItemId, Columns.IDENT);
        String reason = Columns.truncate(rawReason, Columns.LONGTEXT);
        String addedBy = Columns.truncate(rawAddedBy, Columns.SHORT);
        return Database.supply(connection -> {
            EscrowService.ensureItem(connection, itemId);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO blacklist_item (id, item_id, reason, added_by, created_at, revoked_at)"
                            + " VALUES (?, ?, ?, ?, ?, NULL)")) {
                insert.setString(1, id);
                insert.setString(2, itemId);
                insert.setString(3, reason);
                insert.setString(4, addedBy);
                insert.setLong(5, System.currentTimeMillis());
                insert.executeUpdate();
            }
            Database.markForReplay(connection, "BLACKLIST_ITEM", id);
            return id;
        }).thenCompose(ruleId -> {
            cacheAdd(new BlacklistRule.Item(ruleId, itemId, reason, addedBy));
            return reload().thenApply(ignored -> ruleId);
        });
    }

    public static CompletableFuture<String> addPokemon(String rawSpeciesId, String rawForm,
                                                       Set<String> aspects, String rawReason,
                                                       String rawAddedBy) {
        String id = Ulid.next();
        String speciesId = Columns.truncate(rawSpeciesId, Columns.IDENT);
        String form = Columns.truncate(rawForm, Columns.SHORT);
        String reason = Columns.truncate(rawReason, Columns.LONGTEXT);
        String addedBy = Columns.truncate(rawAddedBy, Columns.SHORT);
        Set<String> copy = new java.util.LinkedHashSet<>();
        for (String aspect : aspects) copy.add(Columns.truncate(aspect, Columns.IDENT));

        return Database.supply(connection -> {
            ensureSpecies(connection, speciesId);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO blacklist_pokemon (id, species_id, form, reason, added_by, created_at,"
                            + " revoked_at) VALUES (?, ?, ?, ?, ?, ?, NULL)")) {
                insert.setString(1, id);
                insert.setString(2, speciesId);
                insert.setString(3, form);
                insert.setString(4, reason);
                insert.setString(5, addedBy);
                insert.setLong(6, System.currentTimeMillis());
                insert.executeUpdate();
            }
            if (!copy.isEmpty()) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO blacklist_pokemon_aspect (blacklist_id, aspect) VALUES (?, ?)")) {
                    for (String aspect : copy) {
                        insert.setString(1, id);
                        insert.setString(2, aspect);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            Database.markForReplay(connection, "BLACKLIST_POKEMON", id);
            return id;
        }).thenCompose(ruleId -> {
            cacheAdd(new BlacklistRule.Mon(ruleId, speciesId, form, copy, reason, addedBy));
            return reload().thenApply(ignored -> ruleId);
        });
    }

    /**
     * Rules are not deleted: they are revoked, so the history says who removed what and when.
     *
     * In fallback a revocation is refused rather than executed. The replay queue can copy inserts,
     * not updates: a revocation written to SQLite would never reach MariaDB and the rule would come
     * back alive on recovery. Better to tell the administrator to try again.
     */
    public static CompletableFuture<Boolean> revoke(String ruleId, String requestedBy) {
        if (Database.degraded()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(Lang.raw("command.blacklist.degraded")));
        }
        return Database.supply(connection -> {
            int changed = revokeIn(connection, "blacklist_item", ruleId, requestedBy);
            if (changed == 0) changed = revokeIn(connection, "blacklist_pokemon", ruleId, requestedBy);
            return changed > 0;
        }).thenCompose(revoked -> reload().thenApply(ignored -> revoked));
    }

    private static int revokeIn(Connection connection, String table, String ruleId, String requestedBy)
            throws SQLException {
        String sql = "UPDATE " + table + " SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, ruleId);
            int changed = update.executeUpdate();
            if (changed > 0) {
                BetterTrades.LOGGER.info("Blacklist rule {} revoked by {}", ruleId, requestedBy);
            }
            return changed;
        }
    }

    private static List<BlacklistRule> readAll(Connection connection) throws SQLException {
        List<BlacklistRule> rules = new ArrayList<>();

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, item_id, reason, added_by FROM blacklist_item WHERE revoked_at IS NULL");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                rules.add(new BlacklistRule.Item(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4)));
            }
        }

        Map<String, Set<String>> aspectsByRule = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT blacklist_id, aspect FROM blacklist_pokemon_aspect");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                aspectsByRule.computeIfAbsent(rows.getString(1), key -> new HashSet<>()).add(rows.getString(2));
            }
        }

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, species_id, form, reason, added_by FROM blacklist_pokemon"
                        + " WHERE revoked_at IS NULL");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                String id = rows.getString(1);
                rules.add(new BlacklistRule.Mon(id, rows.getString(2), rows.getString(3),
                        Set.copyOf(aspectsByRule.getOrDefault(id, Set.of())),
                        rows.getString(4), rows.getString(5)));
            }
        }
        return List.copyOf(rules);
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
