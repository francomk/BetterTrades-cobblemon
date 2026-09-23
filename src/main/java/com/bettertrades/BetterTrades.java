package com.bettertrades;

import com.bettertrades.config.BetterTradesConfig;
import com.bettertrades.api.ApiKeys;
import com.bettertrades.api.TradeEvents;
import com.bettertrades.api.ReadOnlyDb;
import com.bettertrades.blacklist.Blacklist;
import com.bettertrades.command.BetterTradesCommand;
import com.bettertrades.db.Database;
import com.bettertrades.db.EscrowDb;
import com.bettertrades.db.EscrowMigration;
import com.bettertrades.db.Replay;
import com.bettertrades.economy.MoneyService;
import com.bettertrades.gui.Icons;
import com.bettertrades.gui.MoneyInputGui;
import com.bettertrades.lang.Lang;
import com.bettertrades.trade.TradeSessions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterTrades implements ModInitializer {

    public static final String MOD_ID = "bettertrades";
    public static final Logger LOGGER = LoggerFactory.getLogger("BetterTrades");

    @Override
    public void onInitialize() {
        BetterTradesConfig.load();
        Lang.load();
        // The models have to be requested now: Polymer builds the pack from whatever was asked
        // for by the time the server starts.
        Icons.register();
        TradeSessions.register();
        BetterTradesCommand.register();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            java.nio.file.Path configDirectory = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
            java.nio.file.Path localDatabase = configDirectory.resolve("bettertrades.db");
            // Item escrow lives in a file of its own, always the same one, whatever backend is
            // active: it is the one piece of state that must stay put even during a failover.
            java.nio.file.Path escrowDatabase = configDirectory.resolve("escrow.db");

            ReadOnlyDb.reopen();
            Database.onRecovered(() -> {
                Replay.schedule(localDatabase);
                // At startup the migration only looked at the backend active at that moment, which
                // in fallback is SQLite. The HELD rows of the old escrow on MariaDB only become
                // visible now, and that is exactly why the migration marker is per backend.
                EscrowMigration.runOnce();
                // While in fallback the two caches refused to be overwritten by the wrong backend:
                // now that MariaDB answers, and that the replay has handed it what was born on
                // SQLite, they have to be read again from whoever really owns them.
                Blacklist.reload().thenCompose(ignored -> ApiKeys.reload());
            });

            EscrowDb.open(escrowDatabase)
                    .thenCompose(ignored -> Database.open(localDatabase))
                    .thenCompose(ignored -> Blacklist.reload())
                    .thenCompose(ignored -> ApiKeys.reload())
                    .thenRun(EscrowMigration::runOnce)
                    .exceptionally(error -> {
                        LOGGER.error("Database startup failed", error);
                        return null;
                    });
        });

        // Impactor registers after the mods: asking it earlier would always get a no.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MoneyService.refreshAvailability();
            // Once the server is up Cobblemon's config is loaded: before that it is not.
            TradeSessions.checkDistanceAgainstCobblemon();
            // Whether the screen's artwork will reach the players at all.
            Icons.checkPackDelivery();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            // Static state first, databases after: open sessions must be closed while escrow still
            // answers, and by this point every player is already disconnected, so the items stay in
            // escrow and come back at the first login of the next world.
            TradeSessions.shutdown();
            TradeEvents.clear();
            Blacklist.clear();
            MoneyInputGui.clearReads();
            ReadOnlyDb.close();
            Database.close();
            EscrowDb.close();
        });
    }
}
