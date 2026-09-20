package com.bettertrades.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.bettertrades.BetterTrades;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BetterTradesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile BetterTradesConfig current = new BetterTradesConfig();

    public General general = new General();
    public Database database = new Database();
    public Trade trade = new Trade();
    public Economy economy = new Economy();

    public static final class General {
        /** The name of a file in config/bettertrades/lang/, without the extension. en_us and it_it ship with the mod. */
        public String language = "en_us";
        // The trade screen is made of models and fonts that live in Polymer's resource pack.
        // A server that serves that pack itself, or makes it mandatory on join, can switch
        // this check off.
        public boolean requireResourcePack = true;
        public boolean sounds = true;
        /** Short feedback on the action bar instead of the chat: with a GUI open the chat is covered. */
        public boolean actionBar = true;
        /** Recap of what was traded, sent to both players when the trade ends. */
        public boolean tradeSummary = true;
    }

    public static final class Database {
        public boolean useRemote = false;
        public String host = "127.0.0.1";
        public int port = 3306;
        public String name = "bettertrades";
        public String user = "bettertrades";
        public String password = "";
        /** A MariaDB user with SELECT permission only, used by the API other mods talk to. */
        public String readUser = "";
        public String readPassword = "";
        /** Seconds between two attempts to come back to MariaDB while writing to SQLite. */
        public int retryIntervalSeconds = 60;
        /** Consecutive errors after which the mod falls back without trying again. */
        public int failureThreshold = 3;
    }

    public static final class Trade {
        public double maxDistance = 10.0;
        public boolean cancelOnDimensionChange = true;
        public boolean cancelOnBattle = true;
        public boolean respectTradeableFlag = true;
    }

    public static final class Economy {
        public boolean enabled = true;
        public String currency = "";
    }

    public static BetterTradesConfig get() {
        return current;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("bettertrades").resolve("config.json");
    }

    public static void load() {
        Path path = file();
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(new BetterTradesConfig()));
                BetterTrades.LOGGER.info("Config created at {}", path);
                current = new BetterTradesConfig();
                return;
            }
            BetterTradesConfig loaded = GSON.fromJson(Files.readString(path), BetterTradesConfig.class);
            current = loaded != null ? loaded : new BetterTradesConfig();
        } catch (IOException | RuntimeException e) {
            BetterTrades.LOGGER.error("Config at {} could not be read: falling back to the defaults", path, e);
            current = new BetterTradesConfig();
        }
    }
}
