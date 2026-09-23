package com.bettertrades.blacklist;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.bettertrades.BetterTrades;
import com.bettertrades.db.Columns;
import com.bettertrades.db.Database;
import com.bettertrades.db.Replay;
import com.bettertrades.db.Ulid;
import com.bettertrades.escrow.EscrowService;
import com.bettertrades.lang.Lang;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * True once this server run has read the rules from the primary backend.
     *
     * An empty cache does not tell "never loaded" apart from "no rules". Keying the fallback guard
     * on emptiness meant that a server starting with MariaDB down always took SQLite's list, which
     * held only the rules born in some earlier fallback: everything else went back on the market.
     */
    private static volatile boolean loadedFromPrimary;

    /** How deep a container inside a container is searched before the item is refused outright. */
    private static final int MAX_NESTING = 16;

    private Blacklist() {}

    public static List<BlacklistRule> rules() {
        return CACHE.get();
    }

    /** Server shutdown: the next world starts from the database, not from this one's cache. */
    public static void clear() {
        CACHE.set(List.of());
        loadedFromPrimary = false;
    }

    private record Loaded(List<BlacklistRule> rules, boolean fromPrimary) {}

    /**
     * In fallback the rules are NOT replaced once the primary's have been seen.
     *
     * SQLite never received the ones written to MariaDB directly, so what it returns is a local copy
     * that can be behind. Overwriting a cache loaded from MariaDB with it could put blacklisted
     * things back on the market, without a warning and without the second check at commit noticing:
     * it uses the same cache.
     *
     * A server that STARTS in fallback has nothing better, and uses that copy. It is kept current by
     * {@link Replay#mirrorBlacklist} on every read from MariaDB, so it is as recent as the last time
     * MariaDB answered.
     */
    public static CompletableFuture<Void> reload() {
        return Database.supply(connection -> {
                    List<BlacklistRule> rules = readAll(connection);
                    boolean fromPrimary = !Database.degraded();
                    if (Database.backend() == Database.Backend.MARIADB) {
                        try {
                            Replay.mirrorBlacklist(connection, Database.localFile());
                        } catch (SQLException | RuntimeException e) {
                            BetterTrades.LOGGER.warn("Local copy of the blacklist not updated: a start"
                                    + " in fallback would use an older one", e);
                        }
                    }
                    return new Loaded(rules, fromPrimary);
                })
                .thenAccept(loaded -> {
                    if (!loaded.fromPrimary() && loadedFromPrimary) {
                        BetterTrades.LOGGER.warn("Database in fallback: the in-memory blacklist stays"
                                + " the MariaDB one ({} rules), the {} read from SQLite are ignored",
                                CACHE.get().size(), loaded.rules().size());
                        return;
                    }
                    if (!loaded.fromPrimary()) {
                        BetterTrades.LOGGER.warn("Blacklist loaded from the local copy ({} rules):"
                                + " MariaDB is not answering, rules added there since the last copy"
                                + " are missing until it comes back", loaded.rules().size());
                    }
                    CACHE.set(loaded.rules());
                    if (loaded.fromPrimary()) loadedFromPrimary = true;
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

    /**
     * Why it is blocked, when the item is blacklisted, or when it carries one that is.
     *
     * The contents count as much as the container: a shulker box or a bundle full of a banned item
     * is that item, and checking only the outer id let it through both on offer and at commit.
     */
    public static Optional<String> check(ItemStack stack) {
        if (CACHE.get().isEmpty()) return Optional.empty();
        return check(stack, 0);
    }

    private static Optional<String> check(ItemStack stack, int depth) {
        if (stack.isEmpty()) return Optional.empty();
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        for (BlacklistRule rule : CACHE.get()) {
            if (rule instanceof BlacklistRule.Item item && item.itemId().equals(itemId)) {
                return Optional.of(describe(rule));
            }
        }
        List<ItemStack> inside = new ArrayList<>();
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) container.iterateNonEmpty().forEach(inside::add);
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) bundle.iterate().forEach(inside::add);
        if (inside.isEmpty()) return Optional.empty();
        // Bundles go inside bundles: past this depth the contents are not read any more, and an
        // item that cannot be checked is not traded.
        if (depth >= MAX_NESTING) return Optional.of(Lang.raw("chat.error.item_too_nested"));
        for (ItemStack inner : inside) {
            Optional<String> blocked = check(inner, depth + 1);
            if (blocked.isPresent()) return blocked;
        }
        return Optional.empty();
    }

    /** Why it is blocked, when the Pokemon falls under a rule or holds a blacklisted item. */
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
        // The held item travels with the Pokemon: a harmless Pokemon holding a banned item is the
        // banned item.
        return check(pokemon.heldItem());
    }

    /**
     * The canonical form of an item id, or null when no such item is registered.
     *
     * A bare path gets Minecraft's namespace, as everywhere else in the game. The rule has to be
     * stored in this form: check() compares against the registry's full id, and a rule saved as
     * "diamond" never matched "minecraft:diamond".
     */
    public static String itemId(String raw) {
        if (raw == null) return null;
        Identifier parsed = Identifier.tryParse(raw.trim().toLowerCase(Locale.ROOT));
        if (parsed == null || !Registries.ITEM.containsId(parsed)) return null;
        return parsed.toString();
    }

    /**
     * The canonical form of a species id, or null when Cobblemon has no such species.
     *
     * A bare name, or one given Minecraft's default namespace by the command parser, is looked up
     * under "cobblemon" too: no species lives in the minecraft namespace.
     */
    public static String speciesId(String raw) {
        if (raw == null) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        Identifier parsed = Identifier.tryParse(lower.contains(":") ? lower : "cobblemon:" + lower);
        if (parsed == null) return null;
        if (PokemonSpecies.getByIdentifier(parsed) != null) return parsed.toString();
        if (!"minecraft".equals(parsed.getNamespace())) return null;
        Identifier cobblemon = Identifier.tryParse("cobblemon:" + parsed.getPath());
        return cobblemon != null && PokemonSpecies.getByIdentifier(cobblemon) != null
                ? cobblemon.toString() : null;
    }

    /**
     * The same normalisation without the registry lookup, for rules already stored: an item or a
     * species that is no longer registered must still load, and rules written before ids were
     * normalised must still match.
     */
    private static String stored(String raw, String namespace) {
        if (raw == null) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : namespace + ":" + lower;
    }

    private static String describe(BlacklistRule rule) {
        return rule.reason() == null || rule.reason().isBlank()
                ? "blacklisted" : rule.reason();
    }

    public static CompletableFuture<String> addItem(String rawItemId, String rawReason, String rawAddedBy) {
        String canonical = itemId(rawItemId);
        if (canonical == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No item is registered as " + rawItemId));
        }
        String id = Ulid.next();
        // Text columns are cut to MariaDB's width even when writing to SQLite: added_by comes from
        // context.getSource().getName(), which with /execute as <entity> can be a custom name, and
        // reason from a greedyString with no limit. On MariaDB with STRICT_TRANS_TABLES a value that
        // is too long fails the INSERT instead of being truncated, so the same command would succeed
        // on one backend and not on the other.
        String itemId = Columns.truncate(canonical, Columns.IDENT);
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
        String canonical = speciesId(rawSpeciesId);
        if (canonical == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No species is registered as " + rawSpeciesId));
        }
        String id = Ulid.next();
        String speciesId = Columns.truncate(canonical, Columns.IDENT);
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
                rules.add(new BlacklistRule.Item(rows.getString(1), stored(rows.getString(2), "minecraft"),
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
                rules.add(new BlacklistRule.Mon(id, stored(rows.getString(2), "cobblemon"), rows.getString(3),
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
