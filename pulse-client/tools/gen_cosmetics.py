#!/usr/bin/env python3
"""
Generates the original cosmetic textures and the data-driven registry used by the client.

  python3 tools/gen_cosmetics.py        (needs Pillow: pip install pillow)

Writes:
  src/main/resources/assets/pulse/textures/cape/<id>.png     64x32 cape textures (vanilla cape layout)
  src/main/resources/assets/pulse/textures/elytra/<id>.png   64x32 elytra textures (vanilla elytra layout)
  src/main/resources/assets/pulse/cosmetics/registry.json    every cosmetic the client knows about

To add a cosmetic by hand you do not need this script: drop a 64x32 PNG in the folder above and add one row to
registry.json (see CosmeticRegistry.java for the fields). Trails and auras are registry rows only.
"""
import json, math, os, random
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "pulse")
W, H = 10, 16

def rgb(h): return ((h >> 16) & 255, (h >> 8) & 255, h & 255)
def lerp(a, b, t): return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))
def shade(c, f): return tuple(max(0, min(255, int(v * f))) for v in c)
def clamp01(x): return max(0.0, min(1.0, x))

# ------------------------------------------------------------------ pattern engine (design space 10x16)
def design(kind, c1, c2, c3, seed):
    r = random.Random(seed)
    g = [[c1] * W for _ in range(H)]
    for y in range(H):
        for x in range(W):
            u, v = x / (W - 1), y / (H - 1)
            if kind == "vgrad":      c = lerp(c1, c2, v)
            elif kind == "hgrad":    c = lerp(c1, c2, u)
            elif kind == "diag":     c = lerp(c1, c2, (u + v) / 2)
            elif kind == "stripes_v": c = c1 if (x // 2) % 2 == 0 else c2
            elif kind == "stripes_h": c = c1 if (y // 2) % 2 == 0 else c2
            elif kind == "bands":    c = lerp(c1, c2, v) if (y // 4) % 2 == 0 else lerp(c2, c1, v)
            elif kind == "checker":  c = c1 if (x // 2 + y // 2) % 2 == 0 else c2
            elif kind == "diag_stripes": c = c1 if ((x + y) // 3) % 2 == 0 else c2
            elif kind == "chevron":  c = c1 if ((y + abs(x - 4.5)) // 3) % 2 == 0 else c2
            elif kind == "wave":     c = lerp(c1, c2, clamp01(0.5 + 0.5 * math.sin(v * 9 + u * 3)))
            elif kind == "split":    c = c1 if u + v < 1 else c2
            elif kind == "border":
                edge = x in (0, W - 1) or y in (0, H - 1) or y == H - 2
                c = c3 if edge else lerp(c1, c2, v)
            elif kind == "emblem_circle":
                d = math.hypot((x - 4.5) / 4.5, (y - 6.5) / 5.5)
                c = c3 if d < 0.55 else lerp(c1, c2, v)
            elif kind == "emblem_diamond":
                d = abs(x - 4.5) / 4.5 + abs(y - 6.5) / 6.5
                c = c3 if d < 0.6 else lerp(c1, c2, v)
            elif kind == "emblem_cross":
                c = c3 if (x in (4, 5) and 2 <= y <= 11) or (y in (6, 7) and 1 <= x <= 8) else lerp(c1, c2, v)
            elif kind == "stars":    c = lerp(c1, c2, v)
            elif kind == "noise":    c = lerp(c1, c2, clamp01(v + (r.random() - 0.5) * 0.45))
            else: raise ValueError(kind)
            g[y][x] = c
    if kind == "stars":
        for _ in range(9):
            g[r.randrange(1, H - 2)][r.randrange(0, W)] = c3
    # every cape gets a soft darkening toward the hem so it reads as cloth
    for y in range(H):
        f = 1.0 - 0.22 * (y / (H - 1)) ** 2
        for x in range(W):
            g[y][x] = shade(g[y][x], f)
    return g

KINDS = ["vgrad", "hgrad", "diag", "stripes_v", "stripes_h", "bands", "checker", "diag_stripes", "chevron", "wave",
         "split", "border", "emblem_circle", "emblem_diamond", "emblem_cross", "stars", "noise"]

def mean(g):
    n = len(g) * len(g[0])
    return tuple(sum(p[i] for row in g for p in row) // n for i in range(3))

def paint(img, x0, y0, w, h, fn):
    for yy in range(h):
        for xx in range(w):
            img.putpixel((x0 + xx, y0 + yy), fn(xx, yy) + (255,))

def cape_png(g):
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    dark = shade(mean(g), 0.55)
    paint(img, 1, 1, W, H, lambda x, y: g[y][x])            # outer face (what you see from behind)
    paint(img, 12, 1, W, H, lambda x, y: dark)              # inner face
    paint(img, 1, 0, W, 1, lambda x, y: shade(g[0][x], 0.8))   # top
    paint(img, 11, 0, W, 1, lambda x, y: shade(g[H - 1][x], 0.7))  # bottom
    paint(img, 0, 1, 1, H, lambda x, y: shade(g[y][0], 0.75))  # left edge
    paint(img, 11, 1, 1, H, lambda x, y: shade(g[y][W - 1], 0.75))  # right edge
    return img

def elytra_png(g):
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    def tall(x, y): return g[min(H - 1, y * H // 20)][x]
    paint(img, 24, 2, 10, 20, tall)                         # outer wing face
    paint(img, 36, 2, 10, 20, lambda x, y: shade(tall(x, y), 0.7))   # inner wing face
    paint(img, 22, 2, 2, 20, lambda x, y: shade(tall(0, y), 0.6))
    paint(img, 34, 2, 2, 20, lambda x, y: shade(tall(W - 1, y), 0.6))
    paint(img, 24, 0, 10, 2, lambda x, y: shade(tall(x, 0), 0.8))
    paint(img, 34, 0, 10, 2, lambda x, y: shade(tall(x, 19), 0.6))
    return img

def face_hex(g):
    return "".join("%02x%02x%02x" % g[y][x] for y in range(H) for x in range(W))

def slug(name): return name.lower().replace(" ", "_")

# ------------------------------------------------------------------ palettes: (c1, c2, accent)
PALETTES = [
    (0xE8C98A, 0xB98A4C, 0xFFF3D1), (0x1FA7A0, 0x0B4C5F, 0x9AF2E8), (0x5A3A31, 0xD2542B, 0xFFB36B),
    (0x4C7A34, 0x1E3D1A, 0xB8E07A), (0xB04FD1, 0x4A1E75, 0xF0C2FF), (0x3A4252, 0x161A23, 0x9AA7C2),
    (0xF4B63A, 0xC0561C, 0xFFE9A8), (0x2D8CC9, 0x0E2E6B, 0xA6DAFF), (0xF4A6C0, 0xB84F7C, 0xFFE0EC),
    (0x1B1B22, 0x050507, 0xE5C15A), (0xBEE6F2, 0x4E96C9, 0xFFFFFF), (0xB5532A, 0x5A2412, 0xF2B07A),
    (0xB9A2F0, 0x5B45B0, 0xEFE8FF), (0x3DBE82, 0x0F5A3C, 0xB4F5D6), (0xC97B3E, 0x6A3A18, 0xF5C28A),
    (0x5F4B8B, 0xE69A8D, 0xFFE3D6), (0x7FCB6A, 0x2F7A3A, 0xE3FFB8), (0x7A2A5E, 0x2B0F3E, 0xE89AD0),
    (0xF2EDE0, 0xB9B3A0, 0x6A6455), (0x2F5BD6, 0x0C1E5E, 0x9DB8FF), (0xFFC83D, 0xFF6A1F, 0xFFF2A8),
    (0x2B6E86, 0x0F2B3A, 0x8FD6E8), (0xD4263E, 0x6B0E1E, 0xFFA3B0), (0x8FE3C2, 0x2F8A74, 0xE6FFF5),
    (0x4B4F57, 0x1C1E22, 0xC9CED6), (0xF6D14A, 0xC78A12, 0xFFF4B0), (0x4F5D75, 0x1B2233, 0xA8B6D4),
    (0xFF8A6B, 0x1F8A8A, 0xFFE3C2),
]

CAPE_NAMES = ["Dune", "Lagoon", "Cinder", "Moss", "Orchid", "Slate", "Saffron", "Tidal", "Blush", "Obsidian",
    "Glacier", "Rust", "Lilac", "Jade", "Copper", "Dusk", "Meadow", "Plum", "Ivory", "Cobalt", "Sunburst", "Fjord",
    "Ruby", "Mint", "Graphite", "Marigold", "Storm", "Coral Reef", "Midnight Bloom", "Neon Alley", "Terra", "Opal",
    "Velvet", "Citrus", "Harbor", "Ashfall", "Sakura Drift", "Pine Hollow", "Solar Flare", "Deep Current",
    "Candy Pop", "Birch", "Amber Hour", "Violet Static", "Seafoam", "Monsoon", "Charcoal Gold", "Lantern",
    "Frostbite", "Wildfire", "Nebula", "Pixel Garden", "Sandstorm", "Eclipse", "Rosewood", "Skyline"]

WING_NAMES = ["Dragonfly", "Monarch", "Gale", "Moth", "Phoenix Down", "Starling", "Kingfisher", "Cicada", "Zephyr",
    "Nightjar", "Lacewing", "Peacock", "Wisp", "Raven", "Swallowtail", "Firefly", "Condor", "Auroral", "Mirage",
    "Storm Petrel", "Ember Wing", "Glasswing", "Tundra", "Vesper"]

def rarity(i, n):
    f = i / n
    return "common" if f < 0.45 else "rare" if f < 0.75 else "epic" if f < 0.93 else "legendary"

# the eight capes that already shipped keep their art; only their registry rows are produced
LEGACY = [("pulse", "Pulse", "A violet-to-cyan sweep with a bright pulse line.", "rare"),
          ("japan", "Japan", "A clean white field with a single red sun.", "common"),
          ("heartbeat", "Heartbeat", "Rose pink with a bold heart.", "rare"),
          ("nightfall", "Nightfall", "Deep indigo with a pale moon and stars.", "epic"),
          ("breaker", "Breaker", "Forest green with a gold-trimmed crest.", "epic"),
          ("aurora", "Aurora", "Northern lights over a dark teal sky.", "legendary"),
          ("ember", "Ember", "Glowing coals fading to ash.", "epic"),
          ("frost", "Frost", "Pale ice with sharp crystalline edges.", "rare")]

TRAILS = [  # name, particle key, swatch color (RGB), description
    ("Ember Trail", "flame", 0xFF8A2B, "Little flames at your heels."),
    ("Soul Trail", "soul_fire_flame", 0x38D6F0, "Cold blue fire that follows you."),
    ("Snowfall", "snowflake", 0xEAF6FF, "Flakes drift off your steps."),
    ("Star Dust", "end_rod", 0xF5F2D8, "A thin line of white sparks."),
    ("Heart Trail", "heart", 0xFF5C8A, "Hearts float up behind you."),
    ("Melody", "note", 0x7CFF6B, "Music notes in your wake."),
    ("Blossom", "cherry_leaves", 0xF7B6D2, "Cherry petals scatter as you move."),
    ("Arcane Script", "enchant", 0xB38CFF, "Enchanting-table glyphs trail behind."),
    ("Voltage", "electric_spark", 0x8FE8FF, "Crackling sparks at every step."),
    ("Neon Pink", "dust", 0xFF3DAE, "A bright pink dust line."),
    ("Neon Lime", "dust", 0x8CFF2E, "A bright lime dust line."),
    ("Gold Rush", "dust", 0xFFC93D, "A glittering gold dust line."),
]

AURAS = [
    ("Halo Glow", "glow", 0xFFE08A, "A soft golden shimmer around you."),
    ("Void Haze", "portal", 0xA64DFF, "Purple portal motes orbit you."),
    ("Dragon Mist", "dragon_breath", 0xC84DFF, "Wisps of dragon breath."),
    ("Spore Drift", "spore_blossom_air", 0x7ED66B, "Green spores hang in the air."),
    ("Hex", "witch", 0xB04DFF, "Witchy purple sparkles."),
    ("Good Vibes", "happy_villager", 0x6BE87D, "Green sparkles of cheer."),
    ("Crimson Spores", "crimson_spore", 0xD6334D, "Red spores from the Nether."),
    ("Warped Spores", "warped_spore", 0x2BD6B0, "Teal spores from the Nether."),
    ("Ash Fall", "white_ash", 0xD8D8D8, "Pale ash drifting down."),
    ("Mirror Portal", "reverse_portal", 0xE0B3FF, "Motes spiral inward."),
    ("Cloud Puff", "cloud", 0xF2F6FA, "A little cloud trails your shoulders."),
    ("Sculk Whisper", "sculk_soul", 0x38C9D6, "Pale blue souls rise slowly."),
]

def main():
    os.makedirs(os.path.join(ROOT, "textures", "cape"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "textures", "elytra"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "cosmetics"), exist_ok=True)
    items = []

    # legacy capes: face is read from the shipped PNG (outer face at 1,1 10x16)
    for cid, name, desc, rar in LEGACY:
        im = Image.open(os.path.join(ROOT, "textures", "cape", cid + ".png")).convert("RGBA")
        g = [[im.getpixel((1 + x, 1 + y))[:3] for x in range(W)] for y in range(H)]
        items.append(dict(id=cid, name=name, category="cape", rarity=rar, asset=cid, desc=desc, face=face_hex(g)))

    assert len(CAPE_NAMES) == 56, len(CAPE_NAMES)
    for i, name in enumerate(CAPE_NAMES):
        p = PALETTES[i % len(PALETTES)]
        kind = KINDS[(i * 5 + i // len(PALETTES)) % len(KINDS)]
        g = design(kind, rgb(p[0]), rgb(p[1]), rgb(p[2]), seed=1000 + i)
        cid = slug(name)
        cape_png(g).save(os.path.join(ROOT, "textures", "cape", cid + ".png"))
        items.append(dict(id=cid, name=name, category="cape", rarity=rarity(i, len(CAPE_NAMES)), asset=cid,
                          desc="A %s cape in %s tones." % (kind.replace("_", " "), name.lower()), face=face_hex(g)))

    assert len(WING_NAMES) == 24, len(WING_NAMES)
    for i, name in enumerate(WING_NAMES):
        p = PALETTES[(i * 3 + 4) % len(PALETTES)]
        kind = ["wave", "diag_stripes", "chevron", "vgrad", "noise", "bands", "emblem_diamond", "stars"][i % 8]
        g = design(kind, rgb(p[0]), rgb(p[1]), rgb(p[2]), seed=5000 + i)
        cid = "wing_" + slug(name)
        elytra_png(g).save(os.path.join(ROOT, "textures", "elytra", cid + ".png"))
        items.append(dict(id=cid, name=name, category="wings", rarity=rarity(i, len(WING_NAMES)), asset=cid,
                          desc="Elytra skin: %s wings." % name.lower(), face=face_hex(g)))

    for i, (name, particle, color, desc) in enumerate(TRAILS):
        items.append(dict(id="trail_" + slug(name), name=name, category="trail", rarity=rarity(i, len(TRAILS)),
                          particle=particle, color="%06X" % color, desc=desc))
    for i, (name, particle, color, desc) in enumerate(AURAS):
        items.append(dict(id="aura_" + slug(name), name=name, category="aura", rarity=rarity(i, len(AURAS)),
                          particle=particle, color="%06X" % color, desc=desc))

    ids = [it["id"] for it in items]
    assert len(ids) == len(set(ids)), "duplicate cosmetic id"
    with open(os.path.join(ROOT, "cosmetics", "registry.json"), "w") as f:
        json.dump({"version": 1, "items": items}, f, indent=1)
    counts = {}
    for it in items: counts[it["category"]] = counts.get(it["category"], 0) + 1
    print("wrote", len(items), "cosmetics", counts)

if __name__ == "__main__":
    main()
