#!/usr/bin/env python3
"""
Generates the Waystones resource pack: original pixel-art textures, 3D block models and
item model definitions, then zips it into src/main/resources/waystones-pack.zip.

Run:  python3 tools/make_pack.py      (needs Pillow: pip install pillow)
Edit the palettes / drawing functions below to restyle anything, then rebuild the plugin.
"""
import io, json, math, os, random, shutil, zipfile
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "build", "pack")
ZIP_PATH = os.path.join(ROOT, "src", "main", "resources", "waystones-pack.zip")
NS = "waystones"

STYLES = {
    #            light            mid              dark            mortar
    "stone":      ((152,152,152), (124,124,124), (96,96,96),    (72,72,72)),
    "sandy":      ((232,218,164), (214,196,136), (184,164,104), (160,138,86)),
    "mossy":      ((146,150,140), (118,122,112), (90,94,86),    (70,72,66)),
    "deepslate":  ((96,96,104),   (74,74,82),    (54,54,61),    (38,38,44)),
    "end_stone":  ((234,238,180), (216,221,158), (190,194,132), (160,164,108)),
    "blackstone": ((64,58,68),    (48,43,52),    (34,30,38),    (22,19,25)),
    "nether":     ((122,22,26),   (96,14,19),    (70,9,13),     (44,5,8)),
    "purpur":     ((184,138,184), (160,114,160), (128,88,128),  (100,66,100)),
    "prismarine": ((104,178,164), (84,150,138),  (62,116,106),  (44,88,80)),
    "muddy":      ((146,112,86),  (124,94,70),   (98,72,52),    (74,54,38)),
}
COLOURS = {
    "amethyst":   ((200,160,255), (150,100,220), (100,60,170)),
    "copper":     ((246,160,120), (214,110,72),  (150,70,44)),
    "diamond":    ((170,255,248), (80,226,216),  (36,160,152)),
    "emerald":    ((140,255,160), (52,206,96),   (20,140,56)),
    "gold":       ((255,240,140), (246,200,60),  (190,140,20)),
    "lapis":      ((130,170,255), (60,104,226),  (30,56,160)),
    "prismarine": ((170,236,220), (110,194,176), (60,140,124)),
    "redstone":   ((255,120,110), (220,30,24),   (140,10,8)),
    "plain":      ((210,170,255), (160,110,230), (110,70,180)),
}
RUNE_GLOW = ((210, 190, 255), (150, 120, 240))

rng = random.Random(1234)


# ── texture helpers ────────────────────────────────────────────────

def new(w=16, h=16):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))

def jitter(c, amount, r):
    d = r.randint(-amount, amount)
    return tuple(max(0, min(255, v + d)) for v in c[:3]) + (255,)

def noise_fill(img, base, amount, seed):
    r = random.Random(seed)
    for y in range(img.height):
        for x in range(img.width):
            img.putpixel((x, y), jitter(base, amount, r))

def bricks(style, seed):
    light, mid, dark, mortar = STYLES[style]
    img = new()
    r = random.Random(seed)
    noise_fill(img, mid, 6, seed)
    for row in range(4):
        y0 = row * 4
        offset = 4 if row % 2 else 0
        for x in range(16):
            img.putpixel((x, y0 + 3), jitter(mortar, 4, r))
        for bx in range(-8, 16, 8):
            x = (bx + offset) % 16
            for y in range(y0, y0 + 3):
                img.putpixel((x, y), jitter(mortar, 4, r))
            # highlight top-left of each brick
            for xx in range(x + 1, min(16, x + 7)):
                img.putpixel((xx, y0), jitter(light, 5, r))
            for y in range(y0, y0 + 3):
                if x + 7 < 16:
                    img.putpixel((x + 7, y), jitter(dark, 5, r))
    if style == "mossy":
        add_moss(img, seed)
    return img

def add_moss(img, seed, density=0.22):
    r = random.Random(seed + 7)
    moss = [(76, 110, 46), (96, 132, 56), (62, 92, 38)]
    for y in range(img.height):
        for x in range(img.width):
            # more moss at the bottom
            if r.random() < density * (0.4 + y / img.height):
                c = moss[r.randrange(3)]
                img.putpixel((x, y), c + (255,))

def carved(style, seed):
    """Smooth dressed stone with a bevelled border (the pillar body)."""
    light, mid, dark, mortar = STYLES[style]
    img = new()
    r = random.Random(seed)
    noise_fill(img, mid, 5, seed)
    for i in range(16):
        img.putpixel((i, 0), jitter(light, 4, r))
        img.putpixel((0, i), jitter(light, 4, r))
        img.putpixel((i, 15), jitter(dark, 4, r))
        img.putpixel((15, i), jitter(dark, 4, r))
    # inset panel
    for i in range(3, 13):
        img.putpixel((i, 2), jitter(dark, 3, r))
        img.putpixel((2, i), jitter(dark, 3, r))
        img.putpixel((i, 13), jitter(light, 3, r))
        img.putpixel((13, i), jitter(light, 3, r))
    if style == "mossy":
        add_moss(img, seed, 0.12)
    return img

def top(style, seed):
    light, mid, dark, mortar = STYLES[style]
    img = new()
    r = random.Random(seed)
    noise_fill(img, light, 5, seed)
    for i in range(16):
        for (x, y) in ((i, 0), (0, i), (i, 15), (15, i)):
            img.putpixel((x, y), jitter(dark, 4, r))
    for i in range(4, 12):
        for (x, y) in ((i, 4), (4, i), (i, 11), (11, i)):
            img.putpixel((x, y), jitter(mid, 4, r))
    return img

GLYPHS = [
    ["#....", "#.#..", "##...", "#.#..", "#...#"],
    ["..#..", ".###.", "#.#.#", "..#..", "..#.."],
    ["#...#", ".#.#.", "..#..", "..#..", "..#.."],
    ["##...", "#.#..", "#..#.", "#.#..", "##..."],
    ["#..#.", "#.#..", "##...", "#.#..", "#..#."],
    ["..#..", ".#.#.", "#...#", ".#.#.", "..#.."],
    ["#.#.#", "#.#.#", ".###.", "..#..", "..#.."],
]

def runes(glow, seed, columns=2):
    """Transparent overlay with glowing glyphs, used on an emissive element."""
    bright, deep = glow
    img = new()
    r = random.Random(seed)
    picks = r.sample(range(len(GLYPHS)), 3)
    positions = [(5, 1), (5, 6), (5, 11)]
    for i, (ox, oy) in enumerate(positions):
        g = GLYPHS[(picks[i % len(picks)] + i) % len(GLYPHS)]
        for gy, row in enumerate(g):
            for gx, ch in enumerate(row):
                if ch == "#":
                    x, y = ox + gx, oy + gy
                    if 0 <= x < 16 and 0 <= y < 16:
                        img.putpixel((x, y), (bright if r.random() > 0.25 else deep) + (255,))
    return img

def crystal(colour, seed):
    light, mid, dark = COLOURS[colour]
    img = new()
    r = random.Random(seed)
    for y in range(16):
        for x in range(16):
            t = (x + (15 - y)) / 30
            base = tuple(int(dark[i] + (light[i] - dark[i]) * t) for i in range(3))
            img.putpixel((x, y), jitter(base, 8, r))
    # facets
    for i in range(16):
        img.putpixel((i, i), light + (255,))
        if i + 5 < 16:
            img.putpixel((i + 5, i), mid + (255,))
    for (x, y) in ((3, 3), (4, 3), (3, 4), (11, 9), (12, 9)):
        img.putpixel((x, y), (255, 255, 255, 255))
    return img

def strip(colour, seed):
    """Sharestone body: dark stone with a coloured inlaid band of runes."""
    img = carved("deepslate", seed)
    light, mid, dark = COLOURS[colour]
    for y in range(16):
        for x in (6, 7, 8, 9):
            img.putpixel((x, y), (dark if x in (6, 9) else mid) + (255,))
    for y in range(1, 16, 4):
        img.putpixel((7, y), light + (255,))
        img.putpixel((8, y + 1), light + (255,))
    return img

def plate_top(seed):
    img = top("stone", seed)
    light, mid, dark = COLOURS["amethyst"]
    for a in range(0, 360, 6):
        for rad in (5.5, 6.0):
            x = int(round(7.5 + rad * math.cos(math.radians(a))))
            y = int(round(7.5 + rad * math.sin(math.radians(a))))
            img.putpixel((x, y), mid + (255,))
    for (x, y) in ((7, 7), (8, 7), (7, 8), (8, 8)):
        img.putpixel((x, y), light + (255,))
    for (x, y) in ((7, 2), (8, 13), (2, 8), (13, 7)):
        img.putpixel((x, y), light + (255,))
    return img

def portal_frames(frames=8):
    img = Image.new("RGBA", (16, 16 * frames))
    for f in range(frames):
        for y in range(16):
            for x in range(16):
                dx, dy = x - 7.5, y - 7.5
                ang = math.atan2(dy, dx)
                dist = math.hypot(dx, dy)
                v = math.sin(ang * 3 + dist * 0.9 - f * (2 * math.pi / frames))
                t = (v + 1) / 2
                c = (int(90 + 120 * t), int(30 + 60 * t), int(170 + 80 * t))
                a = 220 if dist < 9 else 190
                img.putpixel((x, f * 16 + y), c + (a,))
    return img


# ── item sprites ───────────────────────────────────────────────────

def from_ascii(rows, palette):
    img = new()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != "." and ch in palette:
                img.putpixel((x, y), palette[ch] + (255,))
    return img

SCROLL = [
    "................",
    "................",
    "..dLLLLLLLLLd...",
    ".dlLLLLLLLLLLd..",
    ".dLppppppppppd..",
    "..dpwwwwwwwpd...",
    "..dpwwwwwwwpd...",
    "..dppppppppppd..",
    "..dpwwwwwwwwpd..",
    "...dpwwwwwwwpd..",
    "...dpppppppppd..",
    "...dpwwwwssspd..",
    "..dLLLLLLLsSsLd.",
    ".dlLLLLLLLLsLLd.",
    "..ddddddddddd...",
    "................",
]

def scroll(seal, seal_dark, ribbon=None):
    pal = {"d": (92, 64, 36), "L": (196, 160, 104), "l": (226, 196, 140),
           "p": (226, 204, 156), "w": (176, 150, 112), "s": seal, "S": seal_dark}
    img = from_ascii(SCROLL, pal)
    if ribbon:
        for y in range(4, 12):
            img.putpixel((7, y), ribbon + (255,))
    return img

WARP_STONE = [
    "................",
    "................",
    ".....dddddd.....",
    "....dmmmmmmd....",
    "...dmlllmmmmd...",
    "..dmlwlmmmmmmd..",
    "..dmllmmgmmmmd..",
    "..dmmmmgGgmmmd..",
    "..dmmmgGwGgmmd..",
    "..dmmmmgGgmmmd..",
    "..dmmmmmgmmmmd..",
    "...dmmmmmmmmd...",
    "....dmmmmmmd....",
    ".....dddddd.....",
    "................",
    "................",
]

def warp_stone(colour=None):
    if colour:
        light, mid, dark = COLOURS[colour]
    else:
        light, mid, dark = (140, 120, 190), (96, 76, 150), (52, 40, 90)
    pal = {"d": dark, "m": mid, "l": light, "w": (255, 255, 255),
           "g": (190, 140, 255), "G": (235, 210, 255)}
    return from_ascii(WARP_STONE, pal)

FEATHER = [
    "............wW..",
    "...........wWw..",
    "..........wWwg..",
    ".........wWwwg..",
    "........wWwwg...",
    ".......wWwwg....",
    "......wWwwg.....",
    ".....wWwwg......",
    "....wWwwg.......",
    "...wWwgg........",
    "...wWg..........",
    "..yW............",
    ".yY.............",
    "yY..............",
    "Y...............",
    "................",
]

def feather():
    pal = {"w": (236, 236, 244), "W": (200, 200, 216), "g": (250, 210, 90), "y": (220, 170, 40), "Y": (160, 110, 20)}
    return from_ascii(FEATHER, pal)

SHARD = [
    "................",
    "........L.......",
    ".......Llm......",
    ".......Lmm......",
    "......Llmmd.....",
    "......Lmmmd.....",
    ".....Llmmmd.....",
    ".....Lmmmmdd....",
    ".....Lmmmmmd....",
    "....Llmmmmmd....",
    "....Lmmmmmdd....",
    ".....Lmmmmd.....",
    "......Lmmd......",
    ".......dd.......",
    "................",
    "................",
]

def shard(light, mid, dark):
    return from_ascii(SHARD, {"L": light, "l": (255, 255, 255), "m": mid, "d": dark})

EPITAPH = [
    "................",
    ".....gggggg.....",
    "....gdddddddg...",
    "...gdssssssdg...",
    "...gdsLLLLsdg...",
    "...gdssssssdg...",
    "...gdsLLLssdg...",
    "...gdssssssdg...",
    "...gdsLLLLsdg...",
    "...gdssssssdg...",
    "...gdssaassdg...",
    "...gdssaassdg...",
    "...gddddddddg...",
    "..ggggggggggggg.",
    "..gGGGGGGGGGGGg.",
    "................",
]

def epitaph():
    pal = {"g": (190, 150, 40), "G": (240, 200, 70), "d": (40, 40, 48), "s": (74, 74, 84),
           "L": (130, 130, 150), "a": (180, 140, 255)}
    return from_ascii(EPITAPH, pal)


# ── models ─────────────────────────────────────────────────────────

FULL = [0, 0, 16, 16]

def box(frm, to, tex_side, tex_top, tex_bottom=None, emissive=0, uv_side=None):
    faces = {}
    for f in ("north", "south", "east", "west"):
        faces[f] = {"texture": "#" + tex_side, "uv": uv_side or FULL}
    faces["up"] = {"texture": "#" + tex_top, "uv": FULL}
    faces["down"] = {"texture": "#" + (tex_bottom or tex_top), "uv": FULL}
    el = {"from": frm, "to": to, "faces": faces}
    if emissive:
        el["light_emission"] = emissive
    return el

def sides_only(frm, to, tex, emissive=15):
    faces = {f: {"texture": "#" + tex, "uv": FULL} for f in ("north", "south", "east", "west")}
    el = {"from": frm, "to": to, "faces": faces, "shade": False}
    if emissive:
        el["light_emission"] = emissive
    return el

TALL_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, -3.2, 0], "scale": [0.36, 0.36, 0.36]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.22, 0.22, 0.22]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, -3, 0], "scale": [0.4, 0.4, 0.4]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 1.5, 1.5], "scale": [0.2, 0.2, 0.2]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 1.5, 1.5], "scale": [0.2, 0.2, 0.2]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -1, 0], "scale": [0.24, 0.24, 0.24]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, -1, 0], "scale": [0.24, 0.24, 0.24]},
}
FLAT_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 2, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [90, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
}

def waystone_model(style):
    t = lambda n: f"{NS}:block/{style}_{n}"
    return {
        "textures": {"particle": t("bricks"), "bricks": t("bricks"), "body": t("body"), "top": t("top"),
                     "runes": f"{NS}:block/runes_waystone"},
        "elements": [
            box([0, 0, 0], [16, 3, 16], "bricks", "top"),
            box([1, 3, 1], [15, 14, 15], "bricks", "top"),
            box([2, 14, 2], [14, 26, 14], "body", "top"),
            sides_only([1.9, 15, 1.9], [14.1, 25, 14.1], "runes"),
            box([1, 26, 1], [15, 29, 15], "bricks", "top"),
            box([3, 29, 3], [13, 31, 13], "body", "top"),
        ],
        "display": TALL_DISPLAY,
    }

def sharestone_model(colour):
    return {
        "textures": {"particle": f"{NS}:block/sharestone_base", "base": f"{NS}:block/sharestone_base",
                     "top": f"{NS}:block/sharestone_top", "body": f"{NS}:block/sharestone_{colour}",
                     "crystal": f"{NS}:block/crystal_{colour}", "runes": f"{NS}:block/runes_{colour}"},
        "elements": [
            box([0, 0, 0], [16, 2, 16], "base", "top"),
            box([1, 2, 1], [15, 5, 15], "base", "top"),
            box([3, 5, 3], [13, 23, 13], "body", "top"),
            sides_only([2.9, 7, 2.9], [13.1, 21, 13.1], "runes"),
            box([2, 23, 2], [14, 25, 14], "base", "top"),
            box([4, 25, 4], [12, 31, 12], "crystal", "crystal", emissive=10),
            box([6, 31, 6], [10, 32, 10], "crystal", "crystal", emissive=10),
        ],
        "display": TALL_DISPLAY,
    }

def portstone_model(colour):
    return {
        "textures": {"particle": f"{NS}:block/stone_bricks", "bricks": f"{NS}:block/stone_bricks",
                     "body": f"{NS}:block/stone_body", "top": f"{NS}:block/stone_top",
                     "crystal": f"{NS}:block/crystal_{colour}"},
        "elements": [
            box([0, 0, 0], [16, 4, 16], "bricks", "top"),
            box([4, 4, 4], [12, 18, 12], "body", "top"),
            box([1, 18, 1], [15, 22, 15], "bricks", "top"),
            box([5, 22, 5], [11, 23, 11], "body", "top"),
            box([6, 23, 6], [10, 28, 10], "crystal", "crystal", emissive=12),
        ],
        "display": TALL_DISPLAY,
    }

def warp_plate_model():
    return {
        "textures": {"particle": f"{NS}:block/warp_plate_top", "top": f"{NS}:block/warp_plate_top",
                     "side": f"{NS}:block/stone_body", "crystal": f"{NS}:block/crystal_amethyst"},
        "elements": [
            box([1, 0, 1], [15, 1, 15], "side", "top"),
            box([6.5, 1, 6.5], [9.5, 1.5, 9.5], "crystal", "crystal", emissive=12),
        ],
        "display": FLAT_DISPLAY,
    }

def portal_model():
    return {
        "textures": {"particle": f"{NS}:block/portal_swirl", "swirl": f"{NS}:block/portal_swirl",
                     "frame": f"{NS}:block/crystal_plain"},
        "elements": [
            {"from": [0, 0, 7.5], "to": [16, 30.5, 8.5], "light_emission": 15, "shade": False,
             "faces": {"north": {"texture": "#swirl", "uv": FULL}, "south": {"texture": "#swirl", "uv": FULL}}},
            box([-1.5, 0, 7], [0, 32, 9], "frame", "frame", emissive=10),
            box([16, 0, 7], [17.5, 32, 9], "frame", "frame", emissive=10),
            box([0, 30.5, 7], [16, 32, 9], "frame", "frame", emissive=10),
        ],
        "display": TALL_DISPLAY,
    }

def flat_item(texture):
    return {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{texture}"}}

def handheld_item(texture):
    return {"parent": "minecraft:item/handheld", "textures": {"layer0": f"{NS}:item/{texture}"}}


# ── build ──────────────────────────────────────────────────────────

def save_png(path, img):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)

def save_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=1)

def main():
    if os.path.exists(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    a = os.path.join(OUT_DIR, "assets", NS)
    tex_b = os.path.join(a, "textures", "block")
    tex_i = os.path.join(a, "textures", "item")
    mod_b = os.path.join(a, "models", "block")
    mod_i = os.path.join(a, "models", "item")
    items = os.path.join(a, "items")

    def item_def(model_id, model_path):
        save_json(os.path.join(items, model_id + ".json"),
                  {"model": {"type": "minecraft:model", "model": f"{NS}:{model_path}"}})

    # Waystones
    for i, style in enumerate(STYLES):
        save_png(os.path.join(tex_b, f"{style}_bricks.png"), bricks(style, 100 + i))
        save_png(os.path.join(tex_b, f"{style}_body.png"), carved(style, 200 + i))
        save_png(os.path.join(tex_b, f"{style}_top.png"), top(style, 300 + i))
        save_json(os.path.join(mod_b, f"waystone_{style}.json"), waystone_model(style))
        item_def(f"waystone_{style}", f"block/waystone_{style}")
    save_png(os.path.join(tex_b, "runes_waystone.png"), runes(RUNE_GLOW, 7))

    # Sharestones, portstones, scoped warp stones
    save_png(os.path.join(tex_b, "sharestone_base.png"), bricks("deepslate", 400))
    save_png(os.path.join(tex_b, "sharestone_top.png"), top("deepslate", 401))
    for i, colour in enumerate(COLOURS):
        save_png(os.path.join(tex_b, f"crystal_{colour}.png"), crystal(colour, 500 + i))
        if colour == "plain":
            continue
        light, mid, dark = COLOURS[colour]
        save_png(os.path.join(tex_b, f"sharestone_{colour}.png"), strip(colour, 600 + i))
        save_png(os.path.join(tex_b, f"runes_{colour}.png"), runes((light, mid), 700 + i, columns=1))
        save_json(os.path.join(mod_b, f"sharestone_{colour}.json"), sharestone_model(colour))
        item_def(f"sharestone_{colour}", f"block/sharestone_{colour}")
        save_json(os.path.join(mod_b, f"portstone_{colour}.json"), portstone_model(colour))
        item_def(f"portstone_{colour}", f"block/portstone_{colour}")
        save_png(os.path.join(tex_i, f"warp_stone_{colour}.png"), warp_stone(colour))
        save_json(os.path.join(mod_i, f"warp_stone_{colour}.json"), handheld_item(f"warp_stone_{colour}"))
        item_def(f"warp_stone_{colour}", f"item/warp_stone_{colour}")
    save_json(os.path.join(mod_b, "portstone_plain.json"), portstone_model("plain"))
    item_def("portstone_plain", "block/portstone_plain")

    # Warp plate and portal
    save_png(os.path.join(tex_b, "warp_plate_top.png"), plate_top(800))
    save_json(os.path.join(mod_b, "warp_plate.json"), warp_plate_model())
    item_def("warp_plate", "block/warp_plate")
    save_png(os.path.join(tex_b, "portal_swirl.png"), portal_frames())
    save_json(os.path.join(tex_b, "portal_swirl.png.mcmeta"), {"animation": {"frametime": 3, "interpolate": True}})
    save_json(os.path.join(mod_b, "portal.json"), portal_model())
    item_def("portal", "block/portal")

    # Items
    sprites = {
        "warp_stone": warp_stone(),
        "return_scroll": scroll((70, 170, 90), (40, 110, 56)),
        "warp_scroll": scroll((170, 110, 240), (110, 60, 180)),
        "unbound_scroll": scroll((150, 150, 150), (100, 100, 100)),
        "bound_scroll": scroll((250, 200, 70), (190, 140, 20), ribbon=(200, 160, 255)),
        "portal_scroll": scroll((230, 90, 220), (150, 40, 150), ribbon=(120, 60, 200)),
        "twinbound_feather": feather(),
        "dormant_shard": shard((190, 190, 200), (130, 130, 145), (80, 80, 95)),
        "attuned_shard": shard((230, 200, 255), (170, 120, 240), (100, 60, 170)),
        "epitaph": epitaph(),
    }
    for name, img in sprites.items():
        save_png(os.path.join(tex_i, f"{name}.png"), img)
        model = handheld_item(name) if name == "warp_stone" else flat_item(name)
        save_json(os.path.join(mod_i, f"{name}.json"), model)
        item_def(name, f"item/{name}")

    save_json(os.path.join(OUT_DIR, "pack.mcmeta"), {"pack": {
        "description": "Waystones server plugin pack",
        # pack_format/supported_formats for 1.21.4-1.21.8 clients, min/max_format for 1.21.9+ and 26.x
        "pack_format": 46,
        "supported_formats": [46, 999],
        "min_format": 46,
        "max_format": 999,
    }})
    icon = Image.new("RGBA", (64, 64))
    big = Image.open(os.path.join(tex_b, "stone_body.png")).resize((64, 64), Image.NEAREST)
    icon.paste(big)
    icon.alpha_composite(runes(RUNE_GLOW, 7).resize((64, 64), Image.NEAREST))
    icon.save(os.path.join(OUT_DIR, "pack.png"))

    os.makedirs(os.path.dirname(ZIP_PATH), exist_ok=True)
    with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(OUT_DIR):
            for f in sorted(files):
                full = os.path.join(base, f)
                info = zipfile.ZipInfo(os.path.relpath(full, OUT_DIR).replace(os.sep, "/"), (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(full, "rb") as fh:
                    z.writestr(info, fh.read())
    print("Wrote", ZIP_PATH)

if __name__ == "__main__":
    main()
