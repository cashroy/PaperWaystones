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
    img = Image.new("RGBA", (W, H), BG + (255,))
    d = ImageDraw.Draw(img)
    d.text((60, 40), "Paper Waystones", font=font(48, "Bold"), fill=TEXT)
    d.text((62, 104), "10 waystone styles · 8 sharestones · portstones · warp plates", font=font(24), fill=MUTED)

    def row(models, y, h, labels):
        n = len(models)
        cell = (W - 120) / n
        for i, (mdl, lab) in enumerate(zip(models, labels)):
            r = render_iso(mdl, scale=6)
            k = min(h / r.height, (cell - 18) / r.width)
            r = r.resize((int(r.width * k), int(r.height * k)), Image.NEAREST)
            cx = 60 + cell * i + cell / 2
            img.alpha_composite(r, (int(cx - r.width / 2), y + h - r.height))
            tw = d.textlength(lab, font=font(17))
            d.text((cx - tw / 2, y + h + 10), lab, font=font(17), fill=MUTED)

    names = ["Stone", "Sandy", "Mossy", "Deepslate", "Ender", "Black", "Nether", "Purpur", "Prismarine", "Muddy"]
    row([f"block/waystone_{s}" for s in styles], 170, 260, names)
    row([f"block/sharestone_{c}" for c in colours] + ["block/portstone_plain", "block/warp_plate"], 540, 250,
        [c.capitalize() for c in colours] + ["Portstone", "Warp Plate"])
    img.convert("RGB").save(os.path.join(OUT, "blocks.png"))


# ── recipes ────────────────────────────────────────────────────────

VANILLA = {  # code -> (label, tile colour)
    "stone_bricks": ("Stone\nBricks", (122, 122, 122)),
    "style_block": ("Style\nBlock", (150, 140, 120)),
    "slab": ("Brick\nSlab", (110, 110, 110)),
    "amethyst": ("Amethyst\nShard", (150, 100, 220)),
    "ender_pearl": ("Ender\nPearl", (30, 110, 100)),
    "gold_ingot": ("Gold\nIngot", (230, 180, 40)),
    "gold_nugget": ("Gold\nNugget", (230, 190, 60)),
    "paper": ("Paper", (225, 222, 205)),
    "ink_sac": ("Ink\nSac", (40, 40, 52)),
    "feather": ("Feather", (220, 220, 230)),
    "flint": ("Flint", (70, 70, 74)),
    "deepslate": ("Deep-\nslate", (70, 70, 78)),
    "material": ("Material*", (90, 200, 190)),
    "vines": ("Vines\nor Moss", (70, 120, 50)),
}

CUSTOM = {  # code -> (sprite name or model, label)
    "warp_stone": ("item:warp_stone", "Warp Stone"),
    "waystone": ("model:block/waystone_stone", "Waystone"),
    "dormant_shard": ("item:dormant_shard", "Dormant Shard"),
}

def tile(code, size):
    t = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if code in CUSTOM:
        kind, ref = CUSTOM[code][0].split(":", 1)
        if kind == "item":
            spr = tex(f"waystones:item/{ref}").resize((size - 12, size - 12), Image.NEAREST)
        else:
            spr = render_iso(ref, scale=4)
            spr.thumbnail((size - 10, size - 10), Image.NEAREST)
        t.alpha_composite(spr, ((size - spr.width) // 2, (size - spr.height) // 2))
        return t
    label, colour = VANILLA[code]
    d = ImageDraw.Draw(t)
    d.rounded_rectangle((5, 5, size - 6, size - 6), radius=8, fill=colour + (255,))
    lum = 0.3 * colour[0] + 0.59 * colour[1] + 0.11 * colour[2]
    fg = (20, 20, 24) if lum > 140 else (245, 245, 250)
    f = font(int(size * 0.17), "Medium")
    lines = label.split("\n")
    lh = f.size + 2
    y = size / 2 - lh * len(lines) / 2
    for line in lines:
        w = d.textlength(line, font=f)
        d.text((size / 2 - w / 2, y), line, font=f, fill=fg)
        y += lh
    return t

def result_image(kind, ref, size):
    if kind == "item":
        return tex(f"waystones:item/{ref}").resize((size, size), Image.NEAREST)
    r = render_iso(ref, scale=5)
    r.thumbnail((size, int(size * 1.4)), Image.NEAREST)
    return r

def recipe_card(title, grid, legend, result, count=1, note=None, shapeless=False):
    S = 78                 # slot size
    W, H = 760, 364
    card = Image.new("RGBA", (W, H), PANEL + (255,))
    d = ImageDraw.Draw(card)
    d.text((24, 16), title, font=font(26, "Bold"), fill=TEXT)
    if note:
        d.text((24, 52), note, font=font(17), fill=MUTED)
    gx, gy = 24, 88
    if shapeless:
        cells = [c for row in grid for c in row if c != " "]
        grid = [cells[i:i + 3] + [" "] * (3 - len(cells[i:i + 3])) for i in range(0, 9, 3)]
    for r in range(3):
        for c in range(3):
            x, y = gx + c * (S + 6), gy + r * (S + 6)
            d.rounded_rectangle((x, y, x + S, y + S), radius=8, fill=SLOT, outline=SLOT_EDGE, width=2)
            ch = grid[r][c] if r < len(grid) and c < len(grid[r]) else " "
            if ch != " ":
                card.alpha_composite(tile(legend[ch], S), (x, y))
    ax = gx + 3 * (S + 6) + 26
    ay = gy + 1 * (S + 6) + S // 2
    d.polygon([(ax, ay - 18), (ax + 46, ay), (ax, ay + 18)], fill=ACCENT)
    rx = ax + 76
    res = result_image(*result, 120)
    card.alpha_composite(res, (rx + (150 - res.width) // 2, ay - res.height // 2))
    if count > 1:
        d.text((rx + 120, ay + 24), f"×{count}", font=font(30, "Bold"), fill=TEXT)
    if shapeless:
        d.text((rx + 30, ay + 80), "shapeless", font=font(16), fill=MUTED)
    return card

def sheet(cards, title, subtitle, path, cols=2):
    pad = 30
    cw, ch = cards[0].size
    rows = (len(cards) + cols - 1) // cols
    W = pad + cols * (cw + pad)
    H = 150 + rows * (ch + pad)
    img = Image.new("RGBA", (W, H), BG + (255,))
    d = ImageDraw.Draw(img)
    d.text((pad, 34), title, font=font(44, "Bold"), fill=TEXT)
    d.text((pad + 2, 92), subtitle, font=font(21), fill=MUTED)
    for i, c in enumerate(cards):
        img.alpha_composite(c, (pad + (i % cols) * (cw + pad), 150 + (i // cols) * (ch + pad)))
    img.convert("RGB").save(path)

def make_recipes():
    blocks = [
        recipe_card("Warp Stone", ["APA", "PGP", "APA"],
                    {"A": "amethyst", "P": "ender_pearl", "G": "gold_ingot"}, ("item", "warp_stone"),
                    note="Needed for every waystone-type block"),
        recipe_card("Waystone", [" B ", "BWB", "BBB"], {"B": "style_block", "W": "warp_stone"},
                    ("model", "block/waystone_stone"),
                    note="Style block decides the look (stone bricks, sandstone, mud bricks...)"),
        recipe_card("Mossy Waystone", ["WVVV"], {"W": "waystone", "V": "vines"},
                    ("model", "block/waystone_mossy"), shapeless=True, note="Or craft it with mossy stone bricks"),
        recipe_card("Sharestone", ["BCB", "BWB", "BCB"],
                    {"B": "stone_bricks", "C": "material", "W": "warp_stone"}, ("model", "block/sharestone_amethyst"),
                    note="*Amethyst, copper, diamond, emerald, gold, lapis, prismarine or redstone"),
        recipe_card("Portstone (plain)", ["LBL", " W ", "BLB"],
                    {"L": "slab", "B": "stone_bricks", "W": "warp_stone"}, ("model", "block/portstone_plain"),
                    note="One-way: warps to your activated waystones"),
        recipe_card("Portstone (material)", ["CLB", "LWB", "CLB"],
                    {"C": "material", "L": "slab", "B": "stone_bricks", "W": "warp_stone"},
                    ("model", "block/portstone_diamond"), note="One-way: warps to that material's sharestones"),
        recipe_card("Warp Plate", ["BAB", "AFA", "BAB"],
                    {"B": "stone_bricks", "A": "amethyst", "F": "flint"}, ("model", "block/warp_plate"),
                    note="Pops out an Attuned Shard when placed"),
        recipe_card("Scoped Warp Stone", ["WCCCC"], {"W": "warp_stone", "C": "material"},
                    ("item", "warp_stone_emerald"), shapeless=True, note="Warp stone + 4 of a material"),
    ]
    sheet(blocks, "Recipes — blocks", "Everything shows up in the recipe book once you join.",
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
                    note="Use on a waystone / sharestone / plate to bind it"),
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
                    note="Keep it on you — leaves a memorial when you die"),
    ]
    sheet(items, "Recipes — items", "Scrolls are consumed when used; warp stones lose durability.",
          os.path.join(OUT, "recipes-items.png"))


# ── how to get things ──────────────────────────────────────────────

def make_how_to():
    W, H = 1560, 1060
    img = Image.new("RGBA", (W, H), BG + (255,))
    d = ImageDraw.Draw(img)
    d.text((40, 34), "Finding & getting everything", font=font(44, "Bold"), fill=TEXT)
    d.text((42, 92), "Waystones generate in the world; everything else is crafted or made from other items.",
           font=font(21), fill=MUTED)

    panels = [
        ("block/waystone_mossy", "Wild waystones",
         ["About one every 25 chunks", "Styled to the biome (mossy in", "jungles, sandy in deserts...)",
          "Sit on a small stone platform", "Only in newly generated terrain"]),
        ("block/waystone_stone", "Village waystones",
         ["About half of villages", "(most, on FREQUENT)", "Desert villages get sandy ones",
          "Near the village centre", "Random place names"]),
        ("block/waystone_blackstone", "Nether & End",
         ["Wild waystones also spawn", "in the Nether (blackstone)", "and the End (ender style)",
          "Warping between dimensions", "costs 27 XP points"]),
    ]
    pw, ph = 480, 470
    for i, (mdl, title, lines) in enumerate(panels):
        x, y = 40 + i * (pw + 20), 150
        d.rounded_rectangle((x, y, x + pw, y + ph), radius=18, fill=PANEL)
        r = render_iso(mdl, scale=5)
        r = r.resize((int(r.width * 230 / r.height), 230), Image.NEAREST)
        rx = x + (pw - r.width) // 2
        img.alpha_composite(r, (rx, y + 24))
        d.text((x + 28, y + 272), title, font=font(28, "Bold"), fill=ACCENT)
        for j, line in enumerate(lines):
            d.text((x + 28, y + 318 + j * 28), line, font=font(20), fill=TEXT if j % 2 == 0 or True else MUTED)

    rows = [
        ("warp_stone", "Every block and item", "is craftable — see the recipe images"),
        ("unbound_scroll", "Bound Scroll", "right-click a waystone, sharestone or warp plate with an Unbound Scroll"),
        ("attuned_shard", "Attuned Shard", "placing a warp plate gives one; or put a Dormant Shard in a plate's menu"),
        ("twinbound_feather", "Bound feathers", "two players hold right-click facing each other until the hearts finish"),
        ("epitaph", "Fleeting Memorial", "die with an Epitaph in your inventory; pick it from any waystone menu"),
    ]
    y = 650
    for name, title, desc in rows:
        img.alpha_composite(item_sprite(name, 56), (52, y))
        d.text((130, y + 2), title, font=font(24, "Bold"), fill=TEXT)
        d.text((130, y + 32), desc, font=font(19), fill=MUTED)
        y += 80
    img.convert("RGB").save(os.path.join(OUT, "how-to-get.png"))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    make_icon()
    make_blocks()
    make_recipes()
    make_how_to()
    print("Wrote images to", OUT)
