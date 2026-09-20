#!/usr/bin/env python3
"""Cuts the mockups in guis/ into the sprites, models and fonts the resource pack ships.

The generated assets are committed under src/main/resources, so the mod builds without running
this script. The source mockups (guis/) are not part of this repository: ask on Discord if you
need them to regenerate the artwork.

Everything this writes under src/main/resources is generated: editing those files by
hand means losing the edit at the next run. Change the mockup or this script instead.

    python3 tools/gen_gui_assets.py

The coordinates below were measured on the mockup, not guessed. The mockup's grid is a
vanilla GENERIC_9X6 screen: slot column c starts at x = 8 + 18c and row r at y = 30 + 18r,
which lines up once the whole texture is drawn 12px above the vanilla background origin.

Three things the vanilla client decides for us, which between them settle what can be a
text component and what has to be painted into a texture here:

  * The title is drawn at (x + 8, y + 6), and a bitmap glyph can only be pushed DOWN from
    that line: `ascent` does the pushing, Minecraft only checks ascent <= height, so a
    negative ascent is fine while a value above 8 is not. Nothing can be written above
    the title line, which is why the plaque's words are painted in.
  * The title is drawn after the slots, but its render layer tests depth and items sit at
    z = 150 against the title's z = 0. Items therefore cover the title, which is why the
    confirm and cancel labels are painted into the button art instead of written over it.
  * Everything else - the player names, the warning panel - has no item in front of it and
    stays a real text component, so the language file still owns those words.
"""

import json
import os
import zipfile
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GUIS = os.path.join(ROOT, "guis")
RESOURCES = os.path.join(ROOT, "src", "main", "resources")
ASSETS = os.path.join(RESOURCES, "assets", "bettertrades")
LANG = os.path.join(ASSETS, "lang", "en_us.json")

PANEL = (142, 157, 176, 255)
CLEAR = (0, 0, 0, 0)

MOCKUP = "Gui_BetterTrades copia.png"
LOCKED = "trade copia/trade_locked_%s.png"
WARN = "trade copia/Attenzione_invpieno_betterTrades.png"

# --- what to cut out of the mockup ---------------------------------------------------

PLAQUE_TEXT = (25, 3, 151, 13)       # inside of the title plaque, words only
LABEL_YOU = (6, 53, 20, 63)          # "Tu"
LABEL_THEM = (153, 53, 171, 63)      # "Lui"
HEAD_LEFT = (22, 26, 46, 50)         # 24x24 frame around the head slot
HEAD_RIGHT = (130, 26, 154, 50)
CONFIRM = (60, 30, 116, 46)          # 56x16, spans slots 3..5
BTN_TRASH = (43, 119, 61, 137)       # 18x18
BTN_MONEY = (114, 118, 134, 138)     # 20x20
CANCEL = (38, 8, 94, 24)             # 56x16 inside every trade_locked_N
HEAD_GREEN = (0, 4, 24, 28)          # 24x24 inside trade_locked_other

# The middle button of the row keeps the mockup's frame in the background, because a real
# Cobblemon Poke Ball is drawn in the slot on top of it.
PLAQUE_TEXT_COLOUR = (46, 58, 74, 255)
WHITE = (255, 255, 255, 255)
CONFIRM_SHADOW = (39, 88, 33, 255)
CANCEL_INK = (58, 18, 26, 255)

# An item renders 16x16 inside its slot. Padding the art into a square canvas and scaling
# the model by canvas/16 puts one texture pixel on one screen pixel, so the art keeps the
# exact position it has in the mockup.
ITEM_CANVAS = 32
WIDE_CANVAS = 64

# --- where every drawn line sits, in pixels below the title's own line ---------------

LABEL_OFFSET = 36                    # the player names, on the mockup's "Tu" / "Lui" row
WARN_LINES = (16, 29, 37, 45, 57, 65, 73)

# --- vertical placement of the sprites, as `ascent` values ---------------------------
# ascent = 7 - (pixels below the title line), so a sprite drawn above it has ascent > 7.

BACKGROUND_ASCENT = 25               # texture top at y - 12
HEAD_FRAME_ASCENT = -1               # frame top at y + 14
WARN_PANEL_ASCENT = 13               # panel top at y


def load(name):
    return Image.open(os.path.join(GUIS, name)).convert("RGBA")


def fill(image, box, colour):
    image.paste(Image.new("RGBA", (box[2] - box[0], box[3] - box[1]), colour), box[:2])


def centred(art, canvas):
    out = Image.new("RGBA", (canvas, canvas), CLEAR)
    out.paste(art, ((canvas - art.width) // 2, (canvas - art.height) // 2))
    return out


def save(image, *path):
    target = os.path.join(ASSETS, *path)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    image.save(target)


def save_json(data, *path, root=ASSETS):
    target = os.path.join(root, *path)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, ensure_ascii=False)
        handle.write("\n")


def dim(image, factor):
    out = image.copy()
    pixels = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = pixels[x, y]
            pixels[x, y] = (int(r * factor), int(g * factor), int(b * factor), a)
    return out


def trim(image):
    box = image.getbbox()
    return image.crop(box) if box else image


def advance_of(image):
    """What the client will use as the glyph's advance: widest opaque column + 2."""
    pixels = image.load()
    widest = 0
    for x in range(image.width):
        for y in range(image.height):
            if pixels[x, y][3] != 0:
                widest = x + 1
                break
    return widest + 1


# --- the vanilla font, read from the jar Loom already downloaded ---------------------

class Font:
    """The vanilla glyphs, so this script can paint words the way the client would.

    Anything painted into a texture has to match what the client draws elsewhere on the
    same screen, so the letters come from Minecraft's own sheet rather than from a font
    installed on whoever runs this.
    """

    def __init__(self, providers, sheets):
        self.providers = providers
        self.glyphs = {}          # codepoint -> (image, advance)
        for provider in providers:
            sheet = sheets[provider["file"]]
            rows = provider["chars"]
            height = provider.get("height", 8)
            cell_w = sheet.width // len(rows[0])
            cell_h = sheet.height // len(rows)
            scale = height / cell_h
            for row, line in enumerate(rows):
                for column, character in enumerate(line):
                    if character == "\u0000" or ord(character) in self.glyphs:
                        continue
                    box = (column * cell_w, row * cell_h,
                           (column + 1) * cell_w, (row + 1) * cell_h)
                    cell = sheet.crop(box)
                    ink = self.ink_width(cell)
                    self.glyphs[ord(character)] = (cell, int(0.5 + ink * scale) + 1)
        self.glyphs[ord(" ")] = (None, 4)      # from minecraft:include/space

    @staticmethod
    def ink_width(cell):
        pixels = cell.load()
        widest = 0
        for x in range(cell.width):
            for y in range(cell.height):
                if pixels[x, y][3] != 0:
                    widest = x + 1
                    break
        return widest

    def advances(self):
        return {str(code): advance for code, (_, advance) in self.glyphs.items()}

    def width(self, text):
        return sum(self.glyphs.get(ord(c), (None, 6))[1] for c in text)

    def draw(self, canvas, x, y, text, colour, shadow=None):
        """Paints `text` with its top edge at y, the way the client places a text line."""
        if shadow is not None:
            self.draw(canvas, x + 1, y + 1, text, shadow)
        pen = x
        for character in text:
            cell, advance = self.glyphs.get(ord(character), (None, 6))
            if cell is not None:
                pixels = cell.load()
                for row in range(cell.height):
                    for column in range(cell.width):
                        if pixels[column, row][3] != 0:
                            canvas.putpixel((pen + column, y + row), colour)
            pen += advance

    def centre(self, canvas, box, text, colour, shadow=None):
        width = self.width(text) - 1
        x = box[0] + (box[2] - box[0] - width) // 2
        y = box[1] + (box[3] - box[1] - 8) // 2
        self.draw(canvas, x, y, text, colour, shadow)


def minecraft_jar():
    cache = os.path.join(ROOT, ".gradle", "loom-cache", "minecraftMaven")
    for base, _, files in os.walk(cache):
        for name in files:
            if name.endswith(".jar") and "sources" not in name:
                return os.path.join(base, name)
    raise SystemExit("no Minecraft jar under .gradle/loom-cache: run ./gradlew build once")


def vanilla_font():
    with zipfile.ZipFile(minecraft_jar()) as jar:
        providers = json.loads(jar.read("assets/minecraft/font/include/default.json"))["providers"]
        sheets = {}
        for provider in providers:
            path = provider["file"].split(":", 1)[-1]
            with jar.open("assets/minecraft/textures/" + path) as handle:
                sheets[provider["file"]] = Image.open(handle).convert("RGBA").copy()
    return Font(providers, sheets)


def space_advances():
    """Powers of two both ways: any horizontal move is a handful of these."""
    advances = {"‌": 0, " ": 4}
    for power in range(11):
        advances[chr(0xE100 + power)] = 1 << power
        advances[chr(0xE120 + power)] = -(1 << power)
    return advances


def write_fonts(sprites, providers):
    glyphs = [{"type": "bitmap", "file": "bettertrades:%s" % path, "height": image.height,
               "ascent": ascent, "chars": [char]}
              for char, (path, image, ascent) in sprites.items()]
    glyphs.append({"type": "space", "advances": space_advances()})
    save_json({"providers": glyphs}, "font", "gui.json")

    for offset in sorted({LABEL_OFFSET, *WARN_LINES}):
        shifted = []
        for provider in providers:
            moved = dict(provider)
            moved["ascent"] = provider.get("ascent", 7) - offset
            shifted.append(moved)
        save_json({"providers": [{"type": "space", "advances": space_advances()}, *shifted]},
                  "font", "o%d.json" % offset)


def clear_face(art, inset=2):
    """Wipes a button's word while keeping everything else the art does.

    Refilling the whole face with one colour would flatten the cancel button's timer, which
    is a pale bar climbing the face one row per frame. Each row is therefore refilled from
    its own leftmost face pixel, so the bar survives and only the letters go.
    """
    out = art.copy()
    pixels = out.load()
    for y in range(inset, out.height - inset):
        colour = pixels[inset, y]
        for x in range(inset, out.width - inset):
            pixels[x, y] = colour
    return out


def label(font, art, text, colour, shadow=None, inset=2):
    """Paints a word centred on a button face."""
    out = clear_face(art, inset)
    font.centre(out, (inset, inset, out.width - inset, out.height - inset), text, colour, shadow)
    return out


def strip_colours(text):
    """Drops the &-codes the language file uses; a painted label carries no styling."""
    out = []
    index = 0
    while index < len(text):
        if text[index] in "&§" and index + 1 < len(text):
            index += 2
            continue
        out.append(text[index])
        index += 1
    return "".join(out)


def main():
    mockup = load(MOCKUP)
    font = vanilla_font()
    words = json.load(open(LANG, encoding="utf-8"))

    def say(key):
        return strip_colours(words[key])

    # ------------------------------------------------------------------ background
    background = mockup.copy()
    for box in (PLAQUE_TEXT, LABEL_YOU, LABEL_THEM, HEAD_LEFT, HEAD_RIGHT,
                CONFIRM, BTN_TRASH, BTN_MONEY):
        fill(background, box, PANEL)
    font.centre(background, PLAQUE_TEXT, say("gui.plaque"), PLAQUE_TEXT_COLOUR)
    save(background, "textures", "gui", "background.png")

    # ------------------------------------------------------------------ overlays
    head_idle = mockup.crop(HEAD_LEFT)
    head_ready = load(LOCKED % "other").crop(HEAD_GREEN)
    save(head_idle, "textures", "gui", "head_frame_idle.png")
    save(head_ready, "textures", "gui", "head_frame_ready.png")

    warn = trim(load(WARN))
    warn_pixels = warn.load()
    fill_colour = warn_pixels[warn.width // 2, 30]
    for y in range(20, 90):                       # everything under the warning triangle
        for x in range(3, warn.width - 3):
            if warn_pixels[x, y][3] > 0:
                warn_pixels[x, y] = fill_colour
    save(warn, "textures", "gui", "warn_panel.png")

    # ------------------------------------------------------------------ item art
    confirm = mockup.crop(CONFIRM)
    save(centred(label(font, confirm, say("gui.button.confirm"), WHITE, CONFIRM_SHADOW),
                 WIDE_CANVAS), "textures", "item", "btn_confirm.png")
    save(centred(label(font, dim(confirm, 0.6), say("gui.button.confirmed"), WHITE, CONFIRM_SHADOW),
                 WIDE_CANVAS), "textures", "item", "btn_confirm_done.png")
    for frame in range(1, 9):
        # Dark letters, not white: the timer bar climbing this button is itself pale, and a
        # white word sitting on it hides how far it has got.
        art = load(LOCKED % frame).crop(CANCEL)
        save(centred(label(font, art, say("gui.button.cancel"), CANCEL_INK), WIDE_CANVAS),
             "textures", "item", "btn_cancel_%d.png" % frame)

    money = mockup.crop(BTN_MONEY)
    save(centred(mockup.crop(BTN_TRASH), ITEM_CANVAS), "textures", "item", "btn_trash.png")
    save(centred(money, ITEM_CANVAS), "textures", "item", "btn_money.png")
    save(centred(dim(money, 0.45), ITEM_CANVAS), "textures", "item", "btn_money_off.png")

    # ------------------------------------------------------------------ item models
    canvases = {"btn_confirm": WIDE_CANVAS, "btn_confirm_done": WIDE_CANVAS,
                "btn_trash": ITEM_CANVAS, "btn_money": ITEM_CANVAS,
                "btn_money_off": ITEM_CANVAS}
    canvases.update({"btn_cancel_%d" % frame: WIDE_CANVAS for frame in range(1, 9)})
    for name, canvas in sorted(canvases.items()):
        scale = canvas / 16.0
        save_json({"parent": "minecraft:item/generated",
                   "textures": {"layer0": "bettertrades:item/%s" % name},
                   "display": {"gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0],
                                       "scale": [scale, scale, scale]}}},
                  "models", "item", "%s.json" % name)

    # ------------------------------------------------------------------ fonts
    sprites = {
        "": ("gui/background.png", background, BACKGROUND_ASCENT),
        "": ("gui/head_frame_idle.png", head_idle, HEAD_FRAME_ASCENT),
        "": ("gui/head_frame_ready.png", head_ready, HEAD_FRAME_ASCENT),
        "": ("gui/warn_panel.png", warn, WARN_PANEL_ASCENT),
    }
    write_fonts(sprites, font.providers)
    save_json(font.advances(), "bettertrades", "font_widths.json", root=RESOURCES)

    print("glyph advances, copy into GuiTextures if they ever change:")
    for char, (path, image, _) in sprites.items():
        print("  %-28s %dx%-4d advance %d"
              % (path, image.width, image.height, advance_of(image)))


if __name__ == "__main__":
    main()
