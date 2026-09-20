package com.bettertrades.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.bettertrades.api.ApiKeys;
import com.bettertrades.api.HistoryLookup;
import com.bettertrades.api.TradeView;
import com.bettertrades.blacklist.Blacklist;
import com.bettertrades.blacklist.BlacklistRule;
import com.bettertrades.db.Database;
import com.bettertrades.lang.Lang;
import com.bettertrades.trade.CancelReason;
import com.bettertrades.trade.OfferEntry;
import com.bettertrades.trade.PokemonFingerprint;
import com.bettertrades.trade.TradeSession;
import com.bettertrades.trade.TradeSessions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The admin commands sit under {@code bettertrades.admin} (see {@link Permissions}); your own
 * trade history is open to everyone, otherwise a player could not even check what they traded.
 *
 * {@code /bt} is an alias of {@code /bettertrades}.
 */
public final class BetterTradesCommand {

    private static final int HISTORY_PAGE_SIZE = 8;

    private BetterTradesCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var root = dispatcher.register(CommandManager.literal("bettertrades")
                    .executes(BetterTradesCommand::help)
                    .then(CommandManager.literal("help").executes(BetterTradesCommand::help))
                    .then(history())
                    .then(blacklist().requires(Permissions::admin))
                    .then(api().requires(Permissions::admin))
                    .then(db().requires(Permissions::admin))
                    .then(CommandManager.literal("active").requires(Permissions::admin)
                            .executes(BetterTradesCommand::active))
                    .then(CommandManager.literal("cancel").requires(Permissions::admin)
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                    .executes(BetterTradesCommand::cancel))));
            dispatcher.register(CommandManager.literal("bt").redirect(root));
        });
    }

    // ------------------------------------------------------------------ help

    private static int help(CommandContext<ServerCommandSource> context) {
        info(context, "command.help.header");
        for (Text line : Lang.lines("command.help.player")) context.getSource()
                .sendFeedback(() -> line, false);
        if (Permissions.admin(context.getSource())) {
            for (Text line : Lang.lines("command.help.admin")) context.getSource()
                    .sendFeedback(() -> line, false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ history

    private static LiteralArgumentBuilder<ServerCommandSource> history() {
        return CommandManager.literal("history")
                .requires(source -> Permissions.ownHistory(source) || Permissions.admin(source))
                .executes(context -> ownHistory(context, 1))
                .then(CommandManager.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> ownHistory(context,
                                IntegerArgumentType.getInteger(context, "page"))))
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .requires(Permissions::admin)
                        .executes(context -> otherHistory(context, 1))
                        .then(CommandManager.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> otherHistory(context,
                                        IntegerArgumentType.getInteger(context, "page")))));
    }

    private static int ownHistory(CommandContext<ServerCommandSource> context, int page) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            info(context, "command.history.console");
            return 0;
        }
        return sendHistory(context, player.getUuid(), player.getGameProfile().getName(), page);
    }

    private static int otherHistory(CommandContext<ServerCommandSource> context, int page) {
        ServerPlayerEntity target;
        try {
            target = EntityArgumentType.getPlayer(context, "player");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            info(context, "command.player_not_found");
            return 0;
        }
        return sendHistory(context, target.getUuid(), target.getGameProfile().getName(), page);
    }

    private static int sendHistory(CommandContext<ServerCommandSource> context, UUID player,
                                   String playerName, int page) {
        if (!Database.usable()) {
            info(context, "command.history.unavailable");
            return 0;
        }
        int offset = (page - 1) * HISTORY_PAGE_SIZE;
        info(context, "command.history.loading");

        HistoryLookup.byPlayer(player, HISTORY_PAGE_SIZE, offset).whenComplete((trades, error) ->
                context.getSource().getServer().execute(() -> {
                    if (error != null) {
                        context.getSource().sendError(Lang.text("command.error.failed", error.getMessage()));
                        return;
                    }
                    if (trades.isEmpty()) {
                        info(context, "command.history.empty", playerName);
                        return;
                    }
                    info(context, "command.history.header", playerName, page);
                    for (TradeView trade : trades) line(context, historyLine(context, trade, player));
                }));
        return 1;
    }

    private static Text historyLine(CommandContext<ServerCommandSource> context, TradeView trade,
                                    UUID player) {
        UUID otherId = trade.leftPlayer().equals(player) ? trade.rightPlayer() : trade.leftPlayer();
        String date = DateTimeFormatter.ofPattern(Lang.raw("command.history.date_format"))
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(trade.completedAt()));

        int received = 0;
        int given = 0;
        for (TradeView.ItemView item : trade.items()) {
            if (item.fromPlayer().equals(player)) given++; else received++;
        }
        for (TradeView.MonView mon : trade.pokemon()) {
            if (mon.fromPlayer().equals(player)) given++; else received++;
        }

        MutableText line = Lang.text("command.history.entry", date,
                nameOf(context, otherId), given, received);
        return line.styled(style -> style.withHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, historyTooltip(trade, player))));
    }

    private static Text historyTooltip(TradeView trade, UUID player) {
        MutableText tooltip = Lang.text("command.history.tooltip.header", trade.tradeId());
        for (TradeView.ItemView item : trade.items()) {
            tooltip.append(Text.literal("\n")).append(Lang.text(
                    item.fromPlayer().equals(player) ? "command.history.tooltip.given"
                            : "command.history.tooltip.received",
                    item.amount() + "x " + item.itemId()));
        }
        for (TradeView.MonView mon : trade.pokemon()) {
            tooltip.append(Text.literal("\n")).append(Lang.text(
                    mon.fromPlayer().equals(player) ? "command.history.tooltip.given"
                            : "command.history.tooltip.received",
                    mon.speciesId() + " Lv." + mon.level()));
        }
        return tooltip;
    }

    private static String nameOf(CommandContext<ServerCommandSource> context, UUID player) {
        ServerPlayerEntity online = context.getSource().getServer().getPlayerManager().getPlayer(player);
        if (online != null) return online.getGameProfile().getName();
        var cache = context.getSource().getServer().getUserCache();
        if (cache != null) {
            var profile = cache.getByUuid(player);
            if (profile.isPresent()) return profile.get().getName();
        }
        return player.toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ blacklist

    private static LiteralArgumentBuilder<ServerCommandSource> blacklist() {
        return CommandManager.literal("blacklist")
                .then(CommandManager.literal("add")
                        .then(CommandManager.literal("item")
                                .then(CommandManager.argument("item", StringArgumentType.string())
                                        .executes(context -> addItem(context, ""))
                                        .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> addItem(context,
                                                        StringArgumentType.getString(context, "reason"))))))
                        .then(CommandManager.literal("pokemon")
                                .then(CommandManager.argument("species", StringArgumentType.string())
                                        .executes(context -> addPokemon(context, null, Set.of(), ""))
                                        .then(CommandManager.argument("form", StringArgumentType.string())
                                                .executes(context -> addPokemon(context,
                                                        StringArgumentType.getString(context, "form"),
                                                        Set.of(), ""))
                                                .then(CommandManager.argument("aspect",
                                                                StringArgumentType.greedyString())
                                                        .executes(context -> addPokemon(context,
                                                                StringArgumentType.getString(context, "form"),
                                                                aspects(StringArgumentType.getString(context,
                                                                        "aspect")), "")))))))
                .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("rule", StringArgumentType.string())
                                .executes(BetterTradesCommand::removeRule)))
                .then(CommandManager.literal("list").executes(BetterTradesCommand::listBlacklist));
    }

    /**
     * Set.of throws IllegalArgumentException on duplicates, and this comes from a hand-typed
     * greedyString: "shiny shiny" ended the command with Brigadier's generic error, without writing
     * anything and without explaining why. Set.copyOf collapses duplicates instead.
     * An empty string, meanwhile, produced a set holding an aspect "" that does not exist.
     */
    private static Set<String> aspects(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return Set.of();
        return Set.copyOf(Arrays.asList(trimmed.split("\\s+")));
    }

    private static int addItem(CommandContext<ServerCommandSource> context, String reason) {
        String itemId = StringArgumentType.getString(context, "item");
        // Without this check a wrong identifier created a row in item and a rule that would never
        // have blocked anything, and the command answered "added".
        Identifier parsed = Identifier.tryParse(itemId);
        if (parsed == null || !Registries.ITEM.containsId(parsed)) {
            info(context, "command.blacklist.item_unknown", itemId);
            return 0;
        }
        String author = context.getSource().getName();
        Blacklist.addItem(itemId, reason, author).whenComplete((ruleId, error) ->
                reply(context, error, "command.blacklist.item_added", itemId, ruleId));
        return 1;
    }

    private static int addPokemon(CommandContext<ServerCommandSource> context, String form,
                                  Set<String> aspects, String reason) {
        String species = StringArgumentType.getString(context, "species");
        String author = context.getSource().getName();
        String described = species
                + (form == null ? "" : " " + form)
                + (aspects.isEmpty() ? "" : " " + aspects);
        Blacklist.addPokemon(species, form, aspects, reason, author).whenComplete((ruleId, error) ->
                reply(context, error, "command.blacklist.pokemon_added", described, ruleId));
        return 1;
    }

    private static int removeRule(CommandContext<ServerCommandSource> context) {
        String ruleId = StringArgumentType.getString(context, "rule");
        Blacklist.revoke(ruleId, context.getSource().getName()).whenComplete((revoked, error) ->
                reply(context, error, Boolean.TRUE.equals(revoked)
                        ? "command.blacklist.removed" : "command.blacklist.rule_not_found", ruleId));
        return 1;
    }

    private static int listBlacklist(CommandContext<ServerCommandSource> context) {
        List<BlacklistRule> rules = Blacklist.rules();
        if (rules.isEmpty()) {
            info(context, "command.blacklist.empty");
            return 1;
        }
        info(context, "command.blacklist.header", rules.size());
        for (BlacklistRule rule : rules) {
            String description = rule instanceof BlacklistRule.Item item
                    ? "item " + item.itemId()
                    : describeMon((BlacklistRule.Mon) rule);
            info(context, "command.blacklist.entry", rule.id(), description, rule.addedBy(),
                    rule.reason() == null ? "" : rule.reason());
        }
        return 1;
    }

    private static String describeMon(BlacklistRule.Mon rule) {
        return "pokemon " + rule.speciesId()
                + (rule.form() == null ? "" : " " + rule.form())
                + (rule.aspects().isEmpty() ? "" : " " + rule.aspects());
    }

    // ------------------------------------------------------------------ API keys

    private static LiteralArgumentBuilder<ServerCommandSource> api() {
        return CommandManager.literal("api")
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("modid", StringArgumentType.string())
                                .then(CommandManager.argument("level", IntegerArgumentType.integer(1, 2))
                                        .executes(BetterTradesCommand::issueKey))))
                .then(CommandManager.literal("revoke")
                        .then(CommandManager.argument("modid", StringArgumentType.string())
                                .executes(BetterTradesCommand::revokeKey)))
                .then(CommandManager.literal("list").executes(BetterTradesCommand::listKeys));
    }

    private static int issueKey(CommandContext<ServerCommandSource> context) {
        String modid = StringArgumentType.getString(context, "modid");
        int level = IntegerArgumentType.getInteger(context, "level");
        ApiKeys.issue(modid, level).whenComplete((key, error) ->
                reply(context, error, "command.api.issued", modid, level, key));
        return 1;
    }

    private static int revokeKey(CommandContext<ServerCommandSource> context) {
        String modid = StringArgumentType.getString(context, "modid");
        ApiKeys.revoke(modid).whenComplete((revoked, error) ->
                reply(context, error, Boolean.TRUE.equals(revoked)
                        ? "command.api.revoked" : "command.api.not_found", modid));
        return 1;
    }

    private static int listKeys(CommandContext<ServerCommandSource> context) {
        var registered = ApiKeys.registered();
        if (registered.isEmpty()) {
            info(context, "command.api.empty");
            return 1;
        }
        registered.forEach((modid, level) -> info(context, "command.api.entry", modid, level));
        return 1;
    }

    // ------------------------------------------------------------------ database

    private static LiteralArgumentBuilder<ServerCommandSource> db() {
        return CommandManager.literal("db")
                .then(CommandManager.literal("status").executes(context -> {
                    info(context, "command.db.status", Database.backend(),
                            Lang.raw(Database.degraded() ? "command.db.degraded" : "command.db.healthy"));
                    return 1;
                }))
                .then(CommandManager.literal("sync").executes(context -> {
                    if (Database.backend() != Database.Backend.MARIADB) {
                        info(context, "command.db.sync_needs_mariadb");
                        return 0;
                    }
                    com.bettertrades.db.Replay.schedule(
                            net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                                    .resolve("bettertrades").resolve("bettertrades.db"));
                    info(context, "command.db.sync_started");
                    return 1;
                }))
                .then(CommandManager.literal("reload").executes(context -> {
                    Blacklist.reload().thenCompose(ignored -> ApiKeys.reload())
                            .whenComplete((ignored, error) -> reply(context, error, "command.db.reloaded"));
                    return 1;
                }))
                .then(CommandManager.literal("lang").executes(context -> {
                    com.bettertrades.config.BetterTradesConfig.load();
                    Lang.load();
                    info(context, "command.lang.reloaded", Lang.folder());
                    return 1;
                }));
    }

    // ------------------------------------------------------------------ active trades

    private static int active(CommandContext<ServerCommandSource> context) {
        var sessions = TradeSessions.active();
        if (sessions.isEmpty()) {
            info(context, "command.active.empty");
            return 1;
        }
        info(context, "command.active.header", sessions.size());
        for (TradeSession session : sessions) {
            MutableText entry = Lang.text("command.active.entry", session.left().playerName(),
                            session.right().playerName(), session.stage())
                    .styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            offerTooltip(session))));
            line(context, entry);
        }
        return 1;
    }

    /** The contents go in the tooltip: in chat a list of offers becomes unreadable fast. */
    private static Text offerTooltip(TradeSession session) {
        MutableText tooltip = Lang.text("command.active.tooltip.header", session.tradeId());
        appendOffer(tooltip, session, session.left());
        appendOffer(tooltip, session, session.right());
        return tooltip;
    }

    private static void appendOffer(MutableText tooltip, TradeSession session, TradeSession.Side side) {
        tooltip.append(Text.literal("\n")).append(Lang.text("command.active.tooltip.side", side.playerName()));
        List<String> lines = new ArrayList<>();
        ServerPlayerEntity player = session.playerOf(side);
        for (OfferEntry entry : side.entries()) {
            if (entry instanceof OfferEntry.Item item) {
                lines.add(item.stack().getCount() + "x " + item.stack().getItem().toString());
            } else if (entry instanceof OfferEntry.Mon mon) {
                var pokemon = player == null ? null : PokemonFingerprint.find(player, mon.pokemonUuid());
                lines.add(pokemon == null ? Lang.raw("command.active.tooltip.missing")
                        : pokemon.getSpecies().getName() + " Lv." + pokemon.getLevel());
            }
        }
        if (side.money() > 0) lines.add(Lang.raw("command.active.tooltip.money", side.money()));
        if (lines.isEmpty()) {
            tooltip.append(Text.literal("\n")).append(Lang.text("command.active.tooltip.nothing"));
            return;
        }
        for (String entry : lines) {
            tooltip.append(Text.literal("\n")).append(Lang.text("command.active.tooltip.entry", entry));
        }
    }

    private static int cancel(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity target;
        try {
            target = EntityArgumentType.getPlayer(context, "player");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            info(context, "command.player_not_found");
            return 0;
        }
        TradeSession session = TradeSessions.of(target.getUuid());
        if (session == null) {
            info(context, "command.cancel.not_trading", target.getGameProfile().getName());
            return 0;
        }
        if (!session.cancel(CancelReason.ADMIN, context.getSource().getName())) {
            // Session in COMMITTING: the cancel is only queued and will apply when the payment
            // comes back. Saying "done" sends the admin looking elsewhere for two players still in it.
            info(context, "command.cancel.queued", target.getGameProfile().getName());
            return 0;
        }
        info(context, "command.cancel.done", target.getGameProfile().getName());
        return 1;
    }

    // ------------------------------------------------------------------ replies

    private static void reply(CommandContext<ServerCommandSource> context, Throwable error,
                              String key, Object... arguments) {
        context.getSource().getServer().execute(() -> {
            if (error != null) {
                context.getSource().sendError(Lang.text("command.error.failed", error.getMessage()));
                return;
            }
            info(context, key, arguments);
        });
    }

    private static void info(CommandContext<ServerCommandSource> context, String key, Object... arguments) {
        line(context, Lang.text(key, arguments));
    }

    private static void line(CommandContext<ServerCommandSource> context, Text text) {
        context.getSource().sendFeedback(() -> text, false);
    }
}
