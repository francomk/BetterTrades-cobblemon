package com.bettertrades.gui;

import com.bettertrades.BetterTrades;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws the trade screen's artwork, which the vanilla client renders as the window title.
 *
 * A server-side mod cannot ship a screen: the client draws the container texture it already
 * has. The way around it is the resource pack's font. A bitmap font provider places its image
 * relative to the text line with {@code ascent}, and an image can be any size, so a whole
 * 176x234 panel is a single character. The title is then a string of those characters with
 * measured gaps between them, and the client happens to draw a GUI.
 *
 * Two limits shape everything here:
 *
 * <ul>
 *   <li>The title is drawn at {@code (x + 8, y + 6)} and a glyph can only be pushed down from
 *       there, because lifting it needs an ascent above the glyph height, which Minecraft
 *       refuses. Anything that has to sit higher must be part of the background image.</li>
 *   <li>The title is drawn after the slots, but its render layer tests depth and items are
 *       drawn at {@code z = 150} against the title's {@code z = 0}, so the items stay on
 *       top of the artwork rather than under it.</li>
 * </ul>
 *
 * Callers give x coordinates measured on the vanilla screen texture; the composer turns them
 * into pixels from the title's own origin, which is {@code x + 8} on a chest screen and
 * {@code x + 60} on an anvil.
 */
public final class GuiTextures {

    /** The font holding the panel sprites and the spacing characters. */
    private static final Identifier SPRITES = Identifier.of(BetterTrades.MOD_ID, "gui");

    /** Sprites, and the advance each one adds to the pen. Both come from tools/gen_gui_assets.py. */
    private static final char BACKGROUND = '';
    private static final int BACKGROUND_ADVANCE = 177;
    private static final char HEAD_IDLE = '';
    private static final char HEAD_READY = '';
    private static final int HEAD_ADVANCE = 25;
    private static final char WARNING = '';
    private static final int WARNING_ADVANCE = 80;
    private static final char MONEY_PANEL = '';
    private static final int MONEY_PANEL_ADVANCE = 80;
    private static final char MONEY_INPUT = '';
    private static final int MONEY_INPUT_ADVANCE = 136;

    /** Spacing characters: 2^n to the right from E100, 2^n to the left from E120. */
    private static final char SPACE_RIGHT = '';
    private static final char SPACE_LEFT = '';
    private static final int SPACE_POWERS = 11;

    /** Where the client draws the title, in pixels from the screen texture's left edge. */
    private static final int CHEST_TITLE_X = 8;
    private static final int ANVIL_TITLE_X = 60;

    private static final Style PLAIN = Style.EMPTY
            .withItalic(false)
            .withColor(TextColor.fromRgb(0xFFFFFF));

    private static final Map<Integer, Integer> WIDTHS = loadWidths();

    private GuiTextures() {}

    /** A title for a chest screen, the trade screen's GENERIC_9X6. */
    public static Composer composer() {
        return new Composer(CHEST_TITLE_X);
    }

    /** A title for an anvil screen, which draws its title further right. */
    public static Composer anvilComposer() {
        return new Composer(ANVIL_TITLE_X);
    }

    /**
     * How wide the client will draw this string, in pixels.
     *
     * The widths are measured off the vanilla glyph sheet when the assets are generated, so
     * the server can centre a line without a TextRenderer, which only exists on the client.
     * Characters outside the sheet fall back to 6, the common width.
     */
    public static int width(String text) {
        int total = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            total += WIDTHS.getOrDefault(codePoint, 6);
        }
        return total;
    }

    public static int width(Text text) {
        return width(text.getString());
    }

    /** Builds one title: sprites and text lines, each placed at an exact pixel. */
    public static final class Composer {

        private final MutableText out = Text.empty();
        private final int titleX;
        private int pen;

        private Composer(int titleX) {
            this.titleX = titleX;
        }

        /** Turns an x measured on the screen texture into the pen coordinate. */
        private int from(int textureX) {
            return textureX - titleX;
        }

        /** Moves the pen without drawing, by spending powers of two of blank space. */
        public Composer move(int to) {
            int delta = to - pen;
            if (delta != 0) {
                char base = delta > 0 ? SPACE_RIGHT : SPACE_LEFT;
                int remaining = Math.abs(delta);
                StringBuilder gap = new StringBuilder();
                for (int power = SPACE_POWERS - 1; power >= 0; power--) {
                    int step = 1 << power;
                    while (remaining >= step) {
                        gap.append((char) (base + power));
                        remaining -= step;
                    }
                }
                out.append(Text.literal(gap.toString()).setStyle(PLAIN.withFont(SPRITES)));
                pen = to;
            }
            return this;
        }

        public Composer background() {
            return sprite(from(0), BACKGROUND, BACKGROUND_ADVANCE);
        }

        /** The frame around a head slot: red while the player is still deciding, green once ready. */
        public Composer headFrame(int textureX, boolean ready) {
            return sprite(from(textureX), ready ? HEAD_READY : HEAD_IDLE, HEAD_ADVANCE);
        }

        public Composer warningPanel(int textureX) {
            return sprite(from(textureX), WARNING, WARNING_ADVANCE);
        }

        public Composer moneyPanel(int textureX) {
            return sprite(from(textureX), MONEY_PANEL, MONEY_PANEL_ADVANCE);
        }

        /** The anvil's panel: its blue box sits under the anvil's own text field. */
        public Composer moneyInput(int textureX) {
            return sprite(from(textureX), MONEY_INPUT, MONEY_INPUT_ADVANCE);
        }

        public Composer line(int textureX, int offset, Text content) {
            return write(from(textureX), offset, content);
        }

        /** Centres a line on a texture x, which is what every label in the mockup does. */
        public Composer centred(int textureCentreX, int offset, Text content) {
            return write(from(textureCentreX) - width(content) / 2, offset, content);
        }

        /** Ends a line on a texture x: the opponent's name hugs the right of their offer. */
        public Composer rightAligned(int textureEndX, int offset, Text content) {
            return write(from(textureEndX) - width(content), offset, content);
        }

        public Text build() {
            return out;
        }

        private Composer sprite(int at, char glyph, int advance) {
            move(at);
            out.append(Text.literal(String.valueOf(glyph)).setStyle(PLAIN.withFont(SPRITES)));
            pen += advance;
            return this;
        }

        /**
         * Writes text on the line {@code offset} pixels below the title's own.
         *
         * Each offset is a separate font, because the shift lives in the font provider rather
         * than in the component; {@code tools/gen_gui_assets.py} writes one per offset it is
         * told about, so a new offset here needs a new offset there.
         */
        private Composer write(int at, int offset, Text content) {
            move(at);
            out.append(content.copy().setStyle(content.getStyle()
                    .withItalic(false)
                    .withFont(Identifier.of(BetterTrades.MOD_ID, "o" + offset))));
            pen += width(content);
            return this;
        }
    }

    private static Map<Integer, Integer> loadWidths() {
        try (InputStream stream = GuiTextures.class.getResourceAsStream("/bettertrades/font_widths.json")) {
            if (stream == null) {
                BetterTrades.LOGGER.error("font_widths.json missing from the jar: GUI text will be off centre");
                return Map.of();
            }
            Map<String, Integer> raw = new Gson().fromJson(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeToken<LinkedHashMap<String, Integer>>() {}.getType());
            Map<Integer, Integer> widths = new LinkedHashMap<>();
            raw.forEach((codePoint, width) -> widths.put(Integer.parseInt(codePoint), width));
            return Map.copyOf(widths);
        } catch (IOException | RuntimeException e) {
            BetterTrades.LOGGER.error("Could not read font_widths.json: GUI text will be off centre", e);
            return Map.of();
        }
    }
}
