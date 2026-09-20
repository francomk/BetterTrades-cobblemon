package com.bettertrades.lang;

import com.bettertrades.BetterTrades;
import com.bettertrades.config.BetterTradesConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every string shown to players.
 *
 * BetterTrades runs server-side only and the clients are vanilla, so Minecraft's translation keys
 * would be useless: the client does not have the mod's language file and would show the raw key.
 * Here the languages live on the server, the strings are resolved before they are sent, and they
 * travel as ready-made text.
 *
 * The files end up in {@code config/bettertrades/lang/}: whoever runs the server can rewrite any
 * message without recompiling. The bundled languages are rewritten on every start into the
 * {@code lang/default/} folder for reference, while the editable files are never touched again
 * after they are first created.
 */
public final class Lang {

    /** Colours and styles in the language files are written with &a, &l, &r, as in plugins. */
    private static final char COLOR_CHAR = '&';

    private static final String BUNDLED_PATH = "/assets/bettertrades/lang/";
    private static final List<String> BUNDLED = List.of("en_us", "it_it");
    private static final String FALLBACK_LANGUAGE = "en_us";
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new com.google.gson.GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile Map<String, String> active = Map.of();
    private static volatile Map<String, String> fallback = Map.of();

    private Lang() {}

    public static void load() {
        fallback = bundled(FALLBACK_LANGUAGE);
        Path directory = folder();
        try {
            Files.createDirectories(directory.resolve("default"));
            for (String language : BUNDLED) {
                // The copy in default/ is always current: it is the reference to copy a key from
                // after a mod update.
                Files.writeString(directory.resolve("default").resolve(language + ".json"),
                        bundledText(language), StandardCharsets.UTF_8);
                Path editable = directory.resolve(language + ".json");
                if (Files.notExists(editable)) {
                    Files.writeString(editable, bundledText(language), StandardCharsets.UTF_8);
                } else {
                    addMissingKeys(editable, bundled(language));
                }
            }
        } catch (IOException e) {
            BetterTrades.LOGGER.error("Language folder is not writable at {}", directory, e);
        }

        String language = BetterTradesConfig.get().general.language;
        if (language == null || language.isBlank()) language = FALLBACK_LANGUAGE;

        Map<String, String> loaded = fromFile(directory.resolve(language + ".json"));
        if (loaded.isEmpty()) loaded = bundled(language);
        if (loaded.isEmpty()) {
            BetterTrades.LOGGER.warn("Language '{}' not found: using {}", language, FALLBACK_LANGUAGE);
            loaded = fallback;
        }
        active = loaded;
        BetterTrades.LOGGER.info("Language '{}' loaded, {} messages", language, active.size());
    }

    /**
     * Copies new keys into the editable file, without touching the ones already there.
     *
     * The administrator's file is never rewritten in full, so after a mod update it would be left
     * without the newly added messages and the player would see the raw key. Their own edits stay
     * where they are.
     */
    private static void addMissingKeys(Path editable, Map<String, String> reference) {
        Map<String, String> current = fromFile(editable);
        if (current.isEmpty() || reference.isEmpty()) return;

        Map<String, String> merged = new LinkedHashMap<>(current);
        int added = 0;
        for (Map.Entry<String, String> entry : reference.entrySet()) {
            if (merged.putIfAbsent(entry.getKey(), entry.getValue()) == null) added++;
        }
        if (added == 0) return;

        try {
            Files.writeString(editable, PRETTY.toJson(merged), StandardCharsets.UTF_8);
            BetterTrades.LOGGER.info("Added {} new messages to {}", added, editable.getFileName());
        } catch (IOException e) {
            BetterTrades.LOGGER.error("Could not update {}", editable, e);
        }
    }

    public static Path folder() {
        return FabricLoader.getInstance().getConfigDir().resolve(BetterTrades.MOD_ID).resolve("lang");
    }

    /** A single line, already coloured. Placeholders are {0}, {1}, ... in argument order. */
    public static MutableText text(String key, Object... arguments) {
        return parse(raw(key, arguments));
    }

    /** Like {@link #text}, but for item names in the GUIs: without the default italics. */
    public static MutableText name(String key, Object... arguments) {
        return text(key, arguments).styled(style -> style.withItalic(false));
    }

    /** A message over several lines: written with \n in the language file. */
    public static List<Text> lines(String key, Object... arguments) {
        List<Text> lines = new ArrayList<>();
        for (String line : raw(key, arguments).split("\n")) {
            lines.add(parse(line).styled(style -> style.withItalic(false)));
        }
        return lines;
    }

    /**
     * Like {@link #text}, but a placeholder can be a ready-made component: needed for item names,
     * which the client translates into its own language.
     */
    public static MutableText mixed(String key, Object... arguments) {
        String template = template(key);
        MutableText result = Text.empty();
        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < template.length(); i++) {
            int end = placeholderEnd(template, i);
            if (end < 0) {
                buffer.append(template.charAt(i));
                continue;
            }
            int index = Integer.parseInt(template.substring(i + 1, end));
            result.append(parse(buffer.toString()));
            buffer.setLength(0);
            Object argument = index < arguments.length ? arguments[index] : "";
            result.append(argument instanceof Text ? (Text) argument : parse(String.valueOf(argument)));
            i = end;
        }
        result.append(parse(buffer.toString()));
        return result.styled(style -> style.withItalic(false));
    }

    /** -1 when no placeholder like {0} starts at {@code start}, otherwise the index of the closing brace. */
    private static int placeholderEnd(String template, int start) {
        if (template.charAt(start) != '{') return -1;
        int end = template.indexOf('}', start);
        if (end < 0) return -1;
        String digits = template.substring(start + 1, end);
        if (digits.isEmpty()) return -1;
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return -1;
        }
        return end;
    }

    private static String template(String key) {
        String template = active.get(key);
        if (template == null) template = fallback.get(key);
        if (template == null) {
            BetterTrades.LOGGER.warn("Message '{}' missing from the language files", key);
            return key;
        }
        return template;
    }

    public static String raw(String key, Object... arguments) {
        return fill(template(key), arguments);
    }

    private static String fill(String template, Object... arguments) {
        String result = template;
        for (int i = 0; i < arguments.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(arguments[i]));
        }
        return result;
    }

    /** Turns &a codes into real styling: that way they work in item names too, not only in chat. */
    public static MutableText parse(String raw) {
        MutableText result = Text.empty();
        StringBuilder buffer = new StringBuilder();
        Style style = Style.EMPTY;

        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if ((current == COLOR_CHAR || current == '§') && i + 1 < raw.length()) {
                Formatting formatting = Formatting.byCode(Character.toLowerCase(raw.charAt(i + 1)));
                if (formatting != null) {
                    if (!buffer.isEmpty()) {
                        result.append(Text.literal(buffer.toString()).setStyle(style));
                        buffer.setLength(0);
                    }
                    style = formatting == Formatting.RESET ? Style.EMPTY : style.withFormatting(formatting);
                    i++;
                    continue;
                }
            }
            buffer.append(current);
        }
        if (!buffer.isEmpty()) result.append(Text.literal(buffer.toString()).setStyle(style));
        return result;
    }

    private static Map<String, String> fromFile(Path path) {
        if (Files.notExists(path)) return Map.of();
        try {
            return parseJson(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            BetterTrades.LOGGER.error("Language file at {} could not be read: using the bundled one", path, e);
            return Map.of();
        }
    }

    private static Map<String, String> bundled(String language) {
        String text = bundledText(language);
        return text.isEmpty() ? Map.of() : parseJson(text);
    }

    private static String bundledText(String language) {
        try (InputStream stream = Lang.class.getResourceAsStream(BUNDLED_PATH + language + ".json")) {
            if (stream == null) return "";
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BetterTrades.LOGGER.error("Bundled language '{}' could not be read from the jar", language, e);
            return "";
        }
    }

    private static Map<String, String> parseJson(String json) {
        Map<String, String> parsed = GSON.fromJson(json,
                new TypeToken<LinkedHashMap<String, String>>() {}.getType());
        return parsed == null ? Map.of() : parsed;
    }
}
