#!/usr/bin/env python3
"""
Renders the Modrinth/README images from the generated resource pack:
  docs/icon.png              project icon (512x512)
  docs/blocks.png            every block, isometric
  docs/recipes-blocks.png    crafting recipes for blocks and the warp stone
  docs/recipes-items.png     crafting recipes for scrolls and other items
  docs/how-to-get.png        how to find / obtain everything

Run tools/make_pack.py first (it builds build/pack), then:  python3 tools/make_docs.py
Vanilla ingredients are drawn as labelled tiles, so no Minecraft textures are used.
"""
import json, math, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, "build", "pack", "assets", "waystones")
OUT = os.path.join(ROOT, "docs")
FONT_DIR = "/usr/share/fonts/truetype/google-fonts"

BG = (24, 26, 34)
PANEL = (34, 37, 48)
SLOT = (52, 56, 70)
SLOT_EDGE = (72, 77, 96)
TEXT = (232, 234, 240)
MUTED = (156, 162, 180)
ACCENT = (184, 150, 255)


def font(size, weight="Medium"):
    for name in (f"Poppins-{weight}.ttf", "Poppins-Regular.ttf"):
        p = os.path.join(FONT_DIR, name)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", size)


# ── textures and isometric model rendering ─────────────────────────

_tex_cache = {}

def tex(ref):
    if ref not in _tex_cache:
        path = os.path.join(PACK, "textures", ref.split(":")[1] + ".png")
        _tex_cache[ref] = Image.open(path).convert("RGBA").crop((0, 0, 16, 16))
    return _tex_cache[ref]

def item_sprite(name, size):
    return tex(f"waystones:item/{name}").resize((size, size), Image.NEAREST)

def shade(img, factor):
    rgb = ImageEnhance.Brightness(img.convert("RGB")).enhance(factor)
    rgb.putalpha(img.getchannel("A"))
    return rgb

def draw_face(canvas, texture, origin, U, V, factor):
    """Maps a 16x16 texture onto the parallelogram origin + u*U + v*V."""
    det = U[0] * V[1] - U[1] * V[0]
    if abs(det) < 1e-6:
        return
    inv = [[V[1] / det, -V[0] / det], [-U[1] / det, U[0] / det]]
    W = H = 16
    a, b = W * inv[0][0], W * inv[0][1]
    d, e = H * inv[1][0], H * inv[1][1]
    c = -(a * origin[0] + b * origin[1])
    f = -(d * origin[0] + e * origin[1])
    t = shade(texture, factor)
    warped = t.transform(canvas.size, Image.AFFINE, (a, b, c, d, e, f), resample=Image.NEAREST)
    mask = Image.new("L", canvas.size, 0)
    pts = [origin, (origin[0] + U[0], origin[1] + U[1]),
           (origin[0] + U[0] + V[0], origin[1] + U[1] + V[1]), (origin[0] + V[0], origin[1] + V[1])]
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    mask = Image.composite(warped.getchannel("A"), Image.new("L", canvas.size, 0), mask)
    canvas.paste(warped, (0, 0), mask)

def render_iso(model, scale=8, size=None):
    m = json.load(open(os.path.join(PACK, "models", model + ".json")))
    T = m["textures"]
    w = size or (int(40 * scale), int(52 * scale))
    canvas = Image.new("RGBA", w, (0, 0, 0, 0))
    c30, s30 = math.cos(math.radians(30)), math.sin(math.radians(30))
    ox, oy = w[0] / 2, w[1] - 8 * scale

    def P(x, y, z):  # model coords (centred on 8,8) -> screen
        x -= 8; z -= 8
        return (ox + (x - z) * c30 * scale, oy + (x + z) * s30 * scale - y * scale)

    elements = sorted(m["elements"], key=lambda e: (e["from"][1], -(e["to"][0] - e["from"][0])))
    for el in elements:
        (x0, y0, z0), (x1, y1, z1) = el["from"], el["to"]
        faces = el["faces"]
        bright = 1.15 if el.get("light_emission") else 1.0
        def t(face):
            return tex(T[faces[face]["texture"][1:]]) if face in faces else None
        # south face (z = z1): u along +x, v down -y
        if t("south") is not None:
            o = P(x0, y1, z1); a = P(x1, y1, z1); b = P(x0, y0, z1)
            draw_face(canvas, t("south"), o, (a[0] - o[0], a[1] - o[1]), (b[0] - o[0], b[1] - o[1]), 0.82 * bright)
        # east face (x = x1): u along -z (from z1 to z0), v down
        if t("east") is not None:
            o = P(x1, y1, z1); a = P(x1, y1, z0); b = P(x1, y0, z1)
            draw_face(canvas, t("east"), o, (a[0] - o[0], a[1] - o[1]), (b[0] - o[0], b[1] - o[1]), 0.66 * bright)
        # top face (y = y1)
        if t("up") is not None:
            o = P(x0, y1, z0); a = P(x1, y1, z0); b = P(x0, y1, z1)
            draw_face(canvas, t("up"), o, (a[0] - o[0], a[1] - o[1]), (b[0] - o[0], b[1] - o[1]), 1.0 * bright)
    bbox = canvas.getbbox()
    return canvas.crop(bbox) if bbox else canvas

def glow(img, colour, radius):
    alpha = img.getchannel("A").filter(ImageFilter.GaussianBlur(radius))
    g = Image.new("RGBA", img.size, colour + (0,))
    g.putalpha(alpha.point(lambda v: int(v * 0.8)))
    return g


# ── icon ───────────────────────────────────────────────────────────

def make_icon():
    S = 512
    icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    d.rounded_rectangle((0, 0, S - 1, S - 1), radius=96, fill=(28, 24, 48, 255))
    # radial purple glow
    radial = Image.new("L", (S, S), 0)
    ImageDraw.Draw(radial).ellipse((56, 40, S - 56, S - 72), fill=255)
    radial = radial.filter(ImageFilter.GaussianBlur(70))
    purple = Image.new("RGBA", (S, S), (130, 80, 230, 255))
    bg = Image.composite(purple, bg, radial.point(lambda v: int(v * 0.55)))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=96, fill=255)
    icon.paste(bg, (0, 0), mask)

    stone = render_iso("block/waystone_stone", scale=9)
    h = 430
    stone = stone.resize((int(stone.width * h / stone.height), h), Image.NEAREST)
    x = (S - stone.width) // 2
    y = S - stone.height - 38
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    layer.paste(stone, (x, y), stone)
    icon.alpha_composite(glow(layer, (200, 170, 255), 18))
    # sparkles
    sd = ImageDraw.Draw(icon)
    for (sx, sy, r) in ((112, 120, 7), (402, 96, 5), (430, 230, 6), (86, 260, 4), (380, 330, 4)):
        sd.polygon([(sx, sy - r * 2), (sx + r // 2, sy), (sx, sy + r * 2), (sx - r // 2, sy)], fill=(235, 220, 255, 230))
        sd.polygon([(sx - r * 2, sy), (sx, sy - r // 2), (sx + r * 2, sy), (sx, sy + r // 2)], fill=(235, 220, 255, 230))
    icon.alpha_composite(layer)
    icon.save(os.path.join(OUT, "icon.png"))


# ── block lineup ───────────────────────────────────────────────────

def make_blocks():
    styles = ["stone", "sandy", "mossy", "deepslate", "end_stone", "blackstone", "nether", "purpur", "prismarine", "muddy"]
    colours = ["amethyst", "copper", "diamond", "emerald", "gold", "lapis", "prismarine", "redstone"]
    W, H = 1600, 900
    img = mc_background(W, H)
    d = ImageDraw.Draw(img)
    shadow_text(d, (60, 36), "Paper Waystones", pix(54, True), off=4)
    shadow_text(d, (62, 106), "10 waystone styles - 8 sharestones - portstones - warp plates", pix(22), fill=(200, 200, 200))

    def row(models, y, h, labels):
        n = len(models)
        cell = (W - 120) / n
        for i, (mdl, lab) in enumerate(zip(models, labels)):
            r = render_iso(mdl, scale=6)
            k = min(h / r.height, (cell - 18) / r.width)
            r = r.resize((int(r.width * k), int(r.height * k)), Image.NEAREST)
            cx = 60 + cell * i + cell / 2
            img.alpha_composite(r, (int(cx - r.width / 2), y + h - r.height))
            tw = d.textlength(lab, font=pix(17))
            shadow_text(d, (cx - tw / 2, y + h + 10), lab, pix(17), fill=(230, 230, 230))

    names = ["Stone", "Sandy", "Mossy", "Deepslate", "Ender", "Black", "Nether", "Purpur", "Prismarine", "Muddy"]
    row([f"block/waystone_{s}" for s in styles], 170, 260, names)
    row([f"block/sharestone_{c}" for c in colours] + ["block/portstone_plain", "block/warp_plate"], 540, 250,
        [c.capitalize() for c in colours] + ["Portstone", "Warp Plate"])
    img.convert("RGB").save(os.path.join(OUT, "blocks.png"))


# ── recipes (Minecraft-style GUI, original pixel art) ──────────────

U = 3  # pixels per GUI unit (Minecraft GUIs are drawn on a 1-unit grid)
GUI_BG = (198, 198, 198)
GUI_LIGHT = (255, 255, 255)
GUI_DARK = (85, 85, 85)
GUI_SLOT = (139, 139, 139)
GUI_SLOT_DARK = (55, 55, 55)
GUI_TEXT = (64, 64, 64)
PIX_DIR = os.path.join(ROOT, "tools", "fonts")

def pix(size, bold=False):
    return ImageFont.truetype(os.path.join(PIX_DIR, "Silkscreen-700.ttf" if bold else "Silkscreen-400.ttf"), size)

def shadow_text(d, xy, text, f, fill=(255, 255, 255), shadow=(63, 63, 63), off=2):
    d.text((xy[0] + off, xy[1] + off), text, font=f, fill=shadow)
    d.text(xy, text, font=f, fill=fill)

def gui_panel(w, h):
    """A generic bevelled light-grey window, w x h in GUI units."""
    img = Image.new("RGBA", (w * U, h * U), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    def r(x0, y0, x1, y1, c):
        d.rectangle((x0 * U, y0 * U, x1 * U - 1, y1 * U - 1), fill=c)
    r(1, 0, w - 1, h, (0, 0, 0)); r(0, 1, w, h - 1, (0, 0, 0))
    r(1, 1, w - 1, h - 1, GUI_BG)
    r(1, 1, w - 2, 3, GUI_LIGHT); r(1, 1, 3, h - 2, GUI_LIGHT)
    r(3, h - 3, w - 1, h - 1, GUI_DARK); r(w - 3, 3, w - 1, h - 1, GUI_DARK)
    r(3, 3, w - 3, h - 3, GUI_BG)
    return img

def gui_slot(img, x, y, size=18):
    d = ImageDraw.Draw(img)
    def r(x0, y0, x1, y1, c):
        d.rectangle(((x + x0) * U, (y + y0) * U, (x + x1) * U - 1, (y + y1) * U - 1), fill=c)
    r(0, 0, size, size, GUI_SLOT)
    r(0, 0, size - 1, 1, GUI_SLOT_DARK); r(0, 0, 1, size - 1, GUI_SLOT_DARK)
    r(1, size - 1, size, size, GUI_LIGHT); r(size - 1, 1, size, size, GUI_LIGHT)

def gui_arrow(img, x, y):
    d = ImageDraw.Draw(img)
    rows = ["..............#.......", "..............##......", "..............###.....",
            "..............####....", "###################...", "####################..",
            "#####################.", "####################..", "###################...",
            "..............####....", "..............###.....", "..............##......",
            "..............#......."]
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch == "#":
                d.rectangle(((x + i) * U, (y + j + 1) * U, (x + i + 1) * U - 1, (y + j + 2) * U - 1), fill=GUI_SLOT)

# Original 16x16 ingredient sprites (drawn for this project, not taken from the game).
SPRITES = {
    "amethyst": (["................", "..........L.....", ".........LlL....", "........LlmmL...",
                  ".......Lllmmd...", "......Llmmmd....", ".....Llmmmd.....", "....Llmmmd......",
                  "...Llmmmd.......", "...Lmmmd........", "..Lmmdd.........", "..Lmd...........",
                  "..dd............", "................", "................", "................"],
                 {"L": (225, 195, 255), "l": (200, 160, 250), "m": (160, 105, 225), "d": (105, 60, 170)}),
    "ender_pearl": (["................", "................", ".....dddddd.....", "....dmmmmmmd....",
                     "...dmlllmmmmd...", "..dmlwlmmmmmmd..", "..dmllmmmmmmmd..", "..dmmmmmGGmmmd..",
                     "..dmmmmGggGmmd..", "..dmmmmGggGmmd..", "..dmmmmmGGmmmd..", "...dmmmmmmmmd...",
                     "....dmmmmmmd....", ".....dddddd.....", "................", "................"],
                    {"d": (12, 60, 52), "m": (30, 110, 96), "l": (80, 170, 150), "w": (200, 245, 235),
                     "G": (20, 80, 70), "g": (8, 40, 34)}),
    "gold_ingot": (["................", "................", "................", "................",
                    "........dddddd..", "......ddYYYYYYd.", "....ddYWWYYYYod.", "..ddYYYYYYYYood.",
                    ".dYYYYYYYYYoodd.", ".dooooooooooodd..", ".dooooooooodd...", "..ddddddddd.....",
                    "................", "................", "................", "................"],
                   {"d": (120, 80, 10), "Y": (250, 210, 60), "W": (255, 250, 200), "o": (215, 160, 30)}),
    "gold_nugget": (["................", "................", "................", "................",
                     "................", "......dd........", ".....dYYd..dd...", "....dYWYod.dYd..",
                     "....dYYood.dod..", ".....ddd...dd...", "......dd........", ".....dYYd.......",
                     ".....dYod.......", "......dd........", "................", "................"],
                    {"d": (120, 80, 10), "Y": (250, 210, 60), "W": (255, 250, 200), "o": (215, 160, 30)}),
    "paper": (["................", "................", "...dddddddddd...", "...dwwwwwwwwwd..",
               "...dwggggggwwd..", "...dwwwwwwwwwd..", "...dwggggggggd..", "...dwwwwwwwwwd..",
               "...dwgggggwwwd..", "...dwwwwwwwwwd..", "...dwggggggggd..", "...dwwwwwwwwwd..",
               "...dwwwwwwwwwd..", "...ddddddddddd..", "................", "................"],
              {"d": (150, 140, 120), "w": (240, 238, 228), "g": (200, 195, 180)}),
    "ink_sac": (["................", "................", "......dddd......", ".....dmmmmd.....",
                 "....dmllmmmd....", "....dmlmmmmd....", "...dmmmmmmmmd...", "..dmmmmmmmmmmd..",
                 "..dmmmmmmmmmmd..", "..dmmmmmmmmmmd..", "...dmmmmmmmmd...", "....dmd.dmdmd...",
                 "....dd..dd.dd...", "................", "................", "................"],
                {"d": (15, 15, 22), "m": (45, 45, 62), "l": (100, 100, 125)}),
    "feather": (["............ww..", "...........wWw..", "..........wWwg..", ".........wWwwg..",
                 "........wWwwg...", ".......wWwwg....", "......wWwwg.....", ".....wWwwg......",
                 "....wWwwg.......", "...wWwgg........", "...wWg..........", "..gW............",
                 ".gg.............", "gg..............", "g...............", "................"],
                {"w": (245, 245, 250), "W": (210, 210, 222), "g": (150, 150, 165)}),
    "flint": (["................", "................", "......dd........", ".....dmmd.......",
               "....dmllmd......", "....dmlmmmd.....", "...dmmmmmmd.....", "...dmmmmmmmd....",
               "..dmmmmmmmmd....", "..dmmmmmmmmmd...", "...dmmmmmmmd....", "....ddmmmdd.....",
               "......ddd.......", "................", "................", "................"],
              {"d": (25, 25, 28), "m": (70, 70, 76), "l": (125, 125, 135)}),
}

def sprite(name, px):
    rows, pal = SPRITES[name]
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in pal:
                img.putpixel((x, y), pal[ch] + (255,))
    return img.resize((px, px), Image.NEAREST)

def block_icon(texture_img, px, half=False):
    """Small isometric cube made from one of this pack's own textures."""
    global _tex_cache
    canvas = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    s = 2.0
    c30, s30 = math.cos(math.radians(30)), math.sin(math.radians(30))
    ox, oy = 32, 50
    h = 8 if half else 16
    def P(x, y, z):
        x -= 8; z -= 8
        return (ox + (x - z) * c30 * s, oy + (x + z) * s30 * s - y * s)
    t = texture_img
    o = P(0, h, 16); a = P(16, h, 16); b = P(0, 0, 16)
    draw_face(canvas, t, o, (a[0]-o[0], a[1]-o[1]), (b[0]-o[0], b[1]-o[1]), 0.8)
    o = P(16, h, 16); a = P(16, h, 0); b = P(16, 0, 16)
    draw_face(canvas, t, o, (a[0]-o[0], a[1]-o[1]), (b[0]-o[0], b[1]-o[1]), 0.62)
    o = P(0, h, 0); a = P(16, h, 0); b = P(0, h, 16)
    draw_face(canvas, t, o, (a[0]-o[0], a[1]-o[1]), (b[0]-o[0], b[1]-o[1]), 1.0)
    bb = canvas.getbbox()
    c = canvas.crop(bb)
    c.thumbnail((px, px), Image.NEAREST)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.alpha_composite(c, ((px - c.width) // 2, (px - c.height) // 2))
    return out

def green_tex(seed):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    import random as _r
    r = _r.Random(seed)
    for y in range(16):
        for x in range(16):
            g = r.choice([(70, 120, 45), (88, 140, 52), (58, 100, 38), (100, 150, 60)])
            img.putpixel((x, y), g + (255,))
    return img

INGREDIENTS = {  # code -> (display name, icon factory)
    "stone_bricks": ("Stone Bricks", lambda px: block_icon(tex("waystones:block/stone_bricks"), px)),
    "style_block": ("Style block", lambda px: block_icon(tex("waystones:block/sandy_bricks"), px)),
    "slab": ("Stone Brick Slab", lambda px: block_icon(tex("waystones:block/stone_bricks"), px, half=True)),
    "deepslate": ("Deepslate", lambda px: block_icon(tex("waystones:block/deepslate_body"), px)),
    "vines": ("Vines or Moss Block", lambda px: block_icon(green_tex(5), px)),
    "material": ("Material*", lambda px: sprite("amethyst", px)),
    "amethyst": ("Amethyst Shard", lambda px: sprite("amethyst", px)),
    "ender_pearl": ("Ender Pearl", lambda px: sprite("ender_pearl", px)),
    "gold_ingot": ("Gold Ingot", lambda px: sprite("gold_ingot", px)),
    "gold_nugget": ("Gold Nugget", lambda px: sprite("gold_nugget", px)),
    "paper": ("Paper", lambda px: sprite("paper", px)),
    "ink_sac": ("Ink Sac", lambda px: sprite("ink_sac", px)),
    "feather": ("Feather", lambda px: sprite("feather", px)),
    "flint": ("Flint", lambda px: sprite("flint", px)),
    "warp_stone": ("Warp Stone", lambda px: tex("waystones:item/warp_stone").resize((px, px), Image.NEAREST)),
    "waystone": ("Waystone", lambda px: _model_icon("block/waystone_stone", px)),
}

def _model_icon(model, px):
    r = render_iso(model, scale=3)
    r.thumbnail((px, px), Image.NEAREST)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.alpha_composite(r, ((px - r.width) // 2, (px - r.height) // 2))
    return out

def result_icon(kind, ref, px):
    if kind == "item":
        return tex(f"waystones:item/{ref}").resize((px, px), Image.NEAREST)
    return _model_icon(ref, px)

def recipe_card(title, grid, legend, result, count=1, note=None, shapeless=False):
    W, H = 262, 96
    card = gui_panel(W, H)
    d = ImageDraw.Draw(card)
    d.text((8 * U, 6 * U), title, font=pix(27, True), fill=GUI_TEXT)
    gx, gy = 8, 22
    if shapeless:
        cells = [c for row in grid for c in row if c != " "]
        grid = ["".join(cells[i:i + 3]).ljust(3) for i in range(0, 9, 3)]
    used = []
    for r in range(3):
        for c in range(3):
            gui_slot(card, gx + c * 18, gy + r * 18)
            ch = grid[r][c] if r < len(grid) and c < len(grid[r]) else " "
            if ch != " ":
                code = legend[ch]
                if code not in used:
                    used.append(code)
                card.alpha_composite(INGREDIENTS[code][1](16 * U), ((gx + c * 18 + 1) * U, (gy + r * 18 + 1) * U))
    gui_arrow(card, gx + 54 + 6, gy + 19)
    rx, ry = gx + 54 + 32, gy + 14
    gui_slot(card, rx, ry, 26)
    card.alpha_composite(result_icon(*result, 16 * U), ((rx + 5) * U, (ry + 5) * U))
    if count > 1:
        shadow_text(d, ((rx + 15) * U, (ry + 15) * U), str(count), pix(27, True))
    # legend
    lx = rx + 34
    ly = gy
    f = pix(18)
    for code in used:
        card.alpha_composite(INGREDIENTS[code][1](8 * U), (lx * U, ly * U))
        d.text(((lx + 11) * U, (ly - 0.5) * U), INGREDIENTS[code][0], font=f, fill=GUI_TEXT)
        ly += 10
    if shapeless:
        d.text(((lx) * U, (ly + 1) * U), "(any arrangement)", font=pix(16), fill=(110, 110, 110))
        ly += 9
    if note:
        d.text((8 * U, (H - 11) * U), note, font=pix(16), fill=(90, 90, 90))
    return card

def mc_background(W, H):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    import random as _r
    r = _r.Random(3)
    tile = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            v = r.choice([38, 42, 46, 50, 34])
            tile.putpixel((x, y), (v, v - 4, v - 8, 255))
    tile = tile.resize((64, 64), Image.NEAREST)
    for y in range(0, H, 64):
        for x in range(0, W, 64):
            img.paste(tile, (x, y))
    return img

def sheet(cards, title, subtitle, path, cols=2):
    pad = 24
    cw, ch = cards[0].size
    rows = (len(cards) + cols - 1) // cols
    W = pad + cols * (cw + pad)
    H = 130 + rows * (ch + pad)
    img = mc_background(W, H)
    d = ImageDraw.Draw(img)
    shadow_text(d, (pad, 26), title, pix(46, True), off=4)
    shadow_text(d, (pad + 2, 84), subtitle, pix(22), fill=(200, 200, 200), off=2)
    for i, c in enumerate(cards):
        img.alpha_composite(c, (pad + (i % cols) * (cw + pad), 130 + (i // cols) * (ch + pad)))
    img.convert("RGB").save(path)

def make_recipes():
    blocks = [
        recipe_card("Warp Stone", ["APA", "PGP", "APA"],
                    {"A": "amethyst", "P": "ender_pearl", "G": "gold_ingot"}, ("item", "warp_stone"),
                    note="Needed for every waystone-type block"),
        recipe_card("Waystone", [" B ", "BWB", "BBB"], {"B": "style_block", "W": "warp_stone"},
                    ("model", "block/waystone_sandy"),
                    note="The style block sets the look: stone bricks, sandstone, mud bricks..."),
        recipe_card("Mossy Waystone", ["WVVV"], {"W": "waystone", "V": "vines"},
                    ("model", "block/waystone_mossy"), shapeless=True, note="Or use mossy stone bricks as the style block"),
        recipe_card("Sharestone", ["BCB", "BWB", "BCB"],
                    {"B": "stone_bricks", "C": "material", "W": "warp_stone"}, ("model", "block/sharestone_amethyst"),
                    note="*Amethyst, copper, diamond, emerald, gold, lapis, prismarine, redstone"),
        recipe_card("Portstone", ["LBL", " W ", "BLB"],
                    {"L": "slab", "B": "stone_bricks", "W": "warp_stone"}, ("model", "block/portstone_plain"),
                    note="One-way: warps to your activated waystones"),
        recipe_card("Material Portstone", ["CLB", "LWB", "CLB"],
                    {"C": "material", "L": "slab", "B": "stone_bricks", "W": "warp_stone"},
                    ("model", "block/portstone_amethyst"), note="One-way: warps to that material's sharestones"),
        recipe_card("Warp Plate", ["BAB", "AFA", "BAB"],
                    {"B": "stone_bricks", "A": "amethyst", "F": "flint"}, ("model", "block/warp_plate"),
                    note="Pops out an Attuned Shard when placed"),
        recipe_card("Scoped Warp Stone", ["WCCCC"], {"W": "warp_stone", "C": "material"},
                    ("item", "warp_stone_amethyst"), shapeless=True, note="*Any of the 8 sharestone materials"),
    ]
    sheet(blocks, "Recipes - Blocks", "All recipes also show in the in-game recipe book",
          os.path.join(OUT, "recipes-blocks.png"))

    items = [
        recipe_card("Return Scroll", ["G G", "PIP", " P "],
                    {"G": "gold_nugget", "P": "paper", "I": "ink_sac"}, ("item", "return_scroll"), 3,
                    note="Back to your nearest activated waystone"),
        recipe_card("Warp Scroll", ["GPG", "PIP", "GAG"],
                    {"G": "gold_nugget", "P": "paper", "I": "ink_sac", "A": "amethyst"}, ("item", "warp_scroll"), 3,
                    note="One trip to any activated waystone"),
        recipe_card("Unbound Scroll", ["G G", "PFP", " P "],
                    {"G": "gold_nugget", "P": "paper", "F": "feather"}, ("item", "unbound_scroll"), 3,
                    note="Use it on a waystone to make a Bound Scroll"),
        recipe_card("Portal Scroll", ["AIA", "PEP", "APA"],
                    {"A": "amethyst", "I": "ink_sac", "P": "paper", "E": "ender_pearl"}, ("item", "portal_scroll"), 3,
                    note="Opens a 5-minute portal anyone can use"),
        recipe_card("Twinbound Feather", ["FIAG"],
                    {"F": "feather", "I": "ink_sac", "A": "amethyst", "G": "gold_nugget"},
                    ("item", "twinbound_feather"), shapeless=True, note="Bind two by waving them at each other"),
        recipe_card("Dormant Shard", [" A ", "AFA", " A "], {"A": "amethyst", "F": "flint"},
                    ("item", "dormant_shard"), note="Put in a warp plate to get an Attuned Shard"),
        recipe_card("Epitaph", ["NAN", "NDN", "NAN"],
                    {"N": "gold_nugget", "A": "amethyst", "D": "deepslate"}, ("item", "epitaph"),
                    note="Keep it on you - leaves a memorial when you die"),
    ]
    sheet(items, "Recipes - Items", "Scrolls are used up; warp stones lose durability",
          os.path.join(OUT, "recipes-items.png"))


# ── how to get things ──────────────────────────────────────────────

def make_how_to():
    W, H = 1560, 1110
    img = mc_background(W, H)
    d = ImageDraw.Draw(img)
    shadow_text(d, (40, 30), "Finding & getting everything", pix(46, True), off=4)
    shadow_text(d, (42, 90), "Waystones generate in new terrain - everything else is crafted", pix(22), fill=(200, 200, 200))

    panels = [
        ("block/waystone_mossy", "Wild waystones",
         ["About one every 25 chunks", "Styled to the biome", "(mossy, sandy, ...)",
          "On a small stone platform", "Only in new terrain"]),
        ("block/waystone_stone", "Village waystones",
         ["About half of villages", "(most on FREQUENT)", "Desert villages get", "sandy ones",
          "Near the village centre"]),
        ("block/waystone_blackstone", "Nether & End",
         ["Wild ones spawn in the", "Nether (blackstone) and", "the End (ender style)",
          "Cross-dimension warps", "cost 27 XP points"]),
    ]
    pw, ph = 160, 168  # GUI units
    for i, (mdl, title, lines) in enumerate(panels):
        x, y = 40 + i * (pw * U + 20), 140
        panel = gui_panel(pw, ph)
        pd = ImageDraw.Draw(panel)
        r = render_iso(mdl, scale=5)
        r = r.resize((int(r.width * 210 / r.height), 210), Image.NEAREST)
        panel.alpha_composite(r, ((pw * U - r.width) // 2, 20))
        pd.text((10 * U, 82 * U), title, font=pix(26, True), fill=GUI_TEXT)
        for j, line in enumerate(lines):
            pd.text((10 * U, (96 + j * 13) * U), line, font=pix(19), fill=GUI_TEXT)
        img.alpha_composite(panel, (x, y))

    rows = [
        ("warp_stone", "Every block and item", "is craftable - see the recipe images"),
        ("unbound_scroll", "Bound Scroll", "right-click a waystone, sharestone or warp plate with an Unbound Scroll"),
        ("attuned_shard", "Attuned Shard", "placing a warp plate gives one; or put a Dormant Shard in a plate"),
        ("twinbound_feather", "Bound feathers", "two players hold right-click facing each other until the hearts finish"),
        ("epitaph", "Fleeting Memorial", "die with an Epitaph on you, then pick it from any waystone menu"),
    ]
    panel = gui_panel(500, 136)
    pd = ImageDraw.Draw(panel)
    y = 8
    for name, title, desc in rows:
        gui_slot(panel, 8, y)
        panel.alpha_composite(item_sprite(name, 16 * U), (9 * U, (y + 1) * U))
        pd.text((32 * U, (y + 1) * U), title, font=pix(22, True), fill=GUI_TEXT)
        pd.text((32 * U, (y + 10) * U), desc, font=pix(17), fill=(80, 80, 80))
        y += 25
    img.alpha_composite(panel, (40, 690))
    img.convert("RGB").save(os.path.join(OUT, "how-to-get.png"))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    make_icon()
    make_blocks()
    make_recipes()
    make_how_to()
    print("Wrote images to", OUT)
