#!/usr/bin/env python3
"""Generates the Nightfall watch face: four pixel-art parallax scenes, a pixel bitmap font for the clock,
res/raw/watchface.xml, strings, and previews.

Pure standard library (no Pillow): art is drawn on a small grid and written as PNG with zlib.
Run from the repo root:  python3 gen/gen.py   (build.sh runs it for you)
"""
import math
import pathlib
import random
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / "res"
DRAW = RES / "drawable-nodpi"
OUT = ROOT / "out"

PX = 5                      # screen pixels per art pixel
PAD = 30                    # every layer is oversized by this much on each side, so tilting never shows an edge
G = (450 + 2 * PAD) // PX   # 102 art pixels across a layer
SIZE = G * PX               # 510 screen pixels

TILT = 40                   # degrees of wrist tilt that count as "full tilt"
FLIP_X, FLIP_Y = 1, 1       # set to -1 if the scenes slide the wrong way on the watch

BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


# ================================================================ drawing basics
def hexc(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def pal(*cs):
    return [hexc(c) for c in cs]


def bayer(x, y):
    return (BAYER[y % 4][x % 4] + 0.5) / 16


def ramp(colors, t, x, y):
    """Ordered-dither between neighbouring ramp colours: the classic pixel-art gradient."""
    t = min(1.0, max(0.0, t))
    f = t * (len(colors) - 1)
    i = int(f)
    if i < len(colors) - 1 and f - i > bayer(x, y):
        i += 1
    return colors[i]


class Layer:
    def __init__(self, w=G, h=G):
        self.w, self.h = w, h
        self.px = [[None] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def get(self, x, y):
        return self.px[y][x] if 0 <= x < self.w and 0 <= y < self.h else None

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.set(x, y, c)

    def save(self, name, scale=PX, dot=None):
        """dot: draw each pixel as a dot x dot square centred in its cell (dot-matrix look) instead of a full cell."""
        raw = bytearray()
        lo = (scale - dot) // 2 if dot else 0
        hi = lo + dot if dot else scale
        clear = bytes(4)
        for y in range(self.h):
            for sy in range(scale):
                row = bytearray(b"\x00")
                for x in range(self.w):
                    c = bytes(self.px[y][x] or clear)
                    if lo <= sy < hi:
                        row += clear * lo + c * (hi - lo) + clear * (scale - hi)
                    else:
                        row += clear * scale
                raw += row
        write_png(DRAW / f"{name}.png", self.w * scale, self.h * scale, bytes(raw))


def write_png(path, w, h, raw, rgb=False):
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2 if rgb else 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def ridge(x, parts, base):
    return base - sum(a * math.sin(f * x + p) for a, f, p in parts)


def terrain(L, parts, base, fill, rim=None, shade=None, span=20, bottom=G, cap=None):
    """A ridge line filled downwards. rim lights the slopes that rise to the right; shade dithers with depth."""
    for x in range(G):
        h = int(round(ridge(x, parts, base)))
        rising = h <= int(round(ridge(x - 1, parts, base)))
        for y in range(h, bottom):
            c = ramp([fill, shade], (y - h) / span, x, y) if shade else fill
            if cap:
                c = cap(x, y, h, rising) or c
            L.set(x, y, c)
        if rim and rising:
            L.set(x, h, rim)
    return L


def sky(colors, horizon, dim_stars=(), n=70, seed=3):
    L = Layer()
    rnd = random.Random(seed)
    for y in range(G):
        for x in range(G):
            L.set(x, y, ramp(colors, y / horizon, x, y))
    for _ in range(n if dim_stars else 0):
        L.set(rnd.randrange(G), rnd.randrange(horizon - 14), hexc(rnd.choice(dim_stars)))
    return L


def stars(seed, n=26, ymax=48, avoid=None, colors=("#ffffff", "#ffeccc", "#cfe0ff", "#ffd2ea")):
    L = Layer()
    rnd = random.Random(seed)
    placed = 0
    while placed < n:
        x, y = rnd.randrange(2, G - 2), rnd.randrange(2, ymax)
        if avoid and math.hypot(x - avoid[0], y - avoid[1]) < avoid[2]:
            continue
        col = hexc(rnd.choice(colors))
        L.set(x, y, col)
        if rnd.random() < 0.22:   # a few bigger "sparkle" stars
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                L.set(x + dx, y + dy, col[:3] + (120,))
        placed += 1
    return L


def clouds(bands, top, body, under):
    """Two tiles wide and seamless, so it can scroll forever."""
    L = Layer(2 * G, G)
    for x in range(2 * G):
        u = 2 * math.pi * (x % G) / G
        for yc, base, ph, (k1, a1), (k2, a2) in bands:
            th = base + a1 * math.sin(k1 * u + ph) + a2 * math.sin(k2 * u + ph * 2.3) - 0.9
            if th <= 0.25:
                continue
            for y in range(int(yc - th), int(yc + th * 0.6) + 1):
                rel = (y - (yc - th)) / max(1.0, th * 1.6)
                L.set(x, y, top if rel < 0.18 else (body if rel < 0.7 else under))
    return L


def falling(seed, n, length, color, width=1, wide=False, ymin=0):
    """Two tiles tall (or wide) and seamless: rain, snow, blowing sand."""
    L = Layer(2 * G, G) if wide else Layer(G, 2 * G)
    rnd = random.Random(seed)
    for _ in range(n):
        x, y = rnd.randrange(G), rnd.randrange(ymin, G)
        for k in (0, G):
            for i in range(length):
                for j in range(width):
                    if wide:
                        L.set(x + k + i, y + j, color)
                    else:
                        L.set(x + j, y + k + i, color)
    return L


def glow_dots(seed, spots, core, halo_c, outer):
    L = Layer()
    rnd = random.Random(seed)
    for _ in range(4):
        x0, x1, y0, y1 = rnd.choice(spots)
        x, y = rnd.randrange(x0, x1), rnd.randrange(y0, y1)
        L.set(x, y, hexc(core))
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            L.set(x + dx, y + dy, hexc(halo_c, 130))
        for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
            L.set(x + dx, y + dy, hexc(outer, 50))
    return L


def pine(L, cx, base, height, color, rim=None, snow=None):
    for r in range(height):
        y = base - r
        hw = int(round((1 - r / height) * height * 0.3 * (0.65 + 0.35 * ((height - r) % 5) / 4)))
        L.rect(cx - hw, y, cx + hw + 1, y + 1, color)
        if snow and hw and (height - r) % 5 == 0:   # snow on each tier's lower edge
            for x in range(cx - hw, cx + hw + 1, 2):
                L.set(x, y, snow)
        elif rim and hw:
            L.set(cx + hw, y, rim)
    L.set(cx, base - height, color)


def halo(L, cx, cy, r, color, alpha, width=4):
    for y in range(cy - r - width, cy + r + width + 1):
        for x in range(cx - r - width, cx + r + width + 1):
            d = math.hypot(x - cx, y - cy)
            if r < d <= r + width and L.get(x, y) is None:
                L.set(x, y, hexc(color, int(alpha * (1 - (d - r) / (width + 0.5)))))


def disk(cx, cy, r):
    for y in range(cy - r - 1, cy + r + 2):
        for x in range(cx - r - 1, cx + r + 2):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r + r * 0.8:
                yield x, y


def sprite(rows, colors):
    L = Layer(len(rows[0]), len(rows))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colors:
                L.set(x, y, colors[ch])
    return L


# ================================================================ watch face XML pieces
T = "[SECOND_MILLISECOND]"
K3, K10, K15 = "2.0944", "0.6283", "0.4189"   # 2*pi/period, for periods that divide 60 s evenly (no jump at :00)


def twinkle(ph, lo=150):
    amp = 255 - lo
    return f'<Transform target="alpha" value="round({lo + amp / 2:g} + {amp / 2:g} * sin({T} * {K3} + {ph}))" />'


def blink(ph, period=1.0, count=2):
    return f'<Transform target="alpha" value="(floor({T} / {period:g}) % {count} == {ph}) ? 255 : 0" />'


def scroll_x(k=1):    # k screen-widths per minute, leftwards, seamless
    return f'<Transform target="x" value="-round(({T} * {SIZE * k / 60:g}) % {SIZE})" />'


def fall(k):          # k screen-heights per minute, downwards, seamless
    return f'<Transform target="y" value="round(({T} * {SIZE * k / 60:g}) % {SIZE}) - {SIZE}" />'


def wander(ph, ax=7, ay=5):
    return (f'<Transform target="x" value="@BX@ + {ax} * sin({T} * {K15} + {ph})" />'
            f'<Transform target="y" value="@BY@ + {ay} * sin({T} * {K10} + {ph} * 2)" />'
            f'<Transform target="alpha" value="round(150 + 105 * sin({T} * {K3} + {ph} * 3))" />')


def bbox(L):
    xs = [x for y in range(L.h) for x in range(L.w) if L.px[y][x]]
    ys = [y for y in range(L.h) if any(L.px[y])]
    if not xs:
        return 0, 0, 1, 1
    return min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1


def crop(L, box):
    bx, by, bw, bh = box
    C = Layer(bw, bh)
    for y in range(bh):
        C.px[y] = L.px[by + y][bx:bx + bw]
    return C


# Calm mode: these animated images are hidden outright (things that travel, and second animation frames);
# every other animated image just freezes. Img(..., calm="hide") does the same for one image.
CALM_HIDE = {"meteor", "aurora_b", "snow_far", "snow_near", "rain_far", "rain_near", "sand", "car", "car2",
             "bird_up", "bird_dn", "shimmer_b", "win_b"}
CALM = '[CONFIGURATION.calm] == "TRUE"'
BLANK = '<PartImage x="0" y="0" width="1" height="1" alpha="0"><Image resource="blank" /></PartImage>'

# ---- live weather (WFF v2). WEATHER.CONDITION: 1 clear 2 cloudy 3 fog 4 heavy rain 5 heavy snow 6 rain 7 snow
# 8 sunny 9 thunderstorm 10 sleet 11 light snow 12 light rain 13 mist 14 partly cloudy 15 windy
WX = '([CONFIGURATION.weather] == "TRUE" &amp;&amp; [WEATHER.IS_AVAILABLE])'
WC = "[WEATHER.CONDITION]"
WEATHER = {
    "rain": (4, 6, 9, 10, 12), "heavy": (4, 6, 9), "snow": (5, 7, 10, 11), "fog": (3, 13),
    "clouds": (2, 9, 14), "overcast": (2, 9), "storm": (9,), "wind": (15,),
}


def wx_is(kind):
    return "(" + " || ".join(f"{WC} == {c}" for c in WEATHER[kind]) + ")"


def cdata(expr):
    return "<![CDATA[" + expr.replace("&amp;", "&") + "]]>"


class Img:
    def __init__(self, suffix, layer, inner="", preview=True, x=0, y=0, w=None, h=None, attrs="", calm=None,
                 weather=None, only=None):
        """weather: this layer *is* that weather (e.g. Neon's rain) - with live weather on, it only shows when the
        weather matches. only: a weather overlay - shown only with live weather on and matching."""
        self.suffix, self.layer, self.inner, self.preview = suffix, layer, inner, preview
        self.x, self.y, self.attrs = x, y, attrs
        self.w = w if w is not None else layer.w * PX
        self.h = h if h is not None else layer.h * PX
        self.calm_hide = calm == "hide" or (calm is None and suffix in CALM_HIDE)
        self.weather, self.only = weather, only
        # Full-size layers are saved cropped to their content: far less transparent overdraw per frame.
        self.box = bbox(layer) if (layer.w, layer.h) == (G, G) and (w, h) == (None, None) else None

    def part(self, res, calm=False):
        """calm=True: the frozen version - no time-based transforms."""
        x, y, w, h = self.x, self.y, self.w, self.h
        if self.box:
            bx, by, bw, bh = self.box
            x, y, w, h = x + bx * PX, y + by * PX, bw * PX, bh * PX
        inner = "" if calm else self.inner.replace("@BX@", str(x)).replace("@BY@", str(y))
        return (f'<PartImage x="{x}" y="{y}" width="{w}" height="{h}"{self.attrs}>{inner}'
                f'<Image resource="{res}" /></PartImage>')

    def xml(self, res, ind):
        """Plain PartImage, or a Condition choosing hidden / frozen (Calm) / animated."""
        compares = []
        if self.only:
            compares.append((f"!({WX} &amp;&amp; {wx_is(self.only)})", BLANK))
        elif self.weather:
            compares.append((f"{WX} &amp;&amp; !{wx_is(self.weather)}", BLANK))
        if T in self.inner:
            compares.append((CALM, BLANK if self.calm_hide else self.part(res, calm=True)))
        if not compares:
            return f"{ind}{self.part(res)}\n"
        exprs = "".join(f'<Expression name="e{i}">{cdata(e)}</Expression>' for i, (e, _) in enumerate(compares))
        body = "".join(f'{ind}  <Compare expression="e{i}">{pt}</Compare>\n' for i, (_, pt) in enumerate(compares))
        return (f"{ind}<Condition><Expressions>{exprs}</Expressions>\n{body}"
                f"{ind}  <Default>{self.part(res)}</Default>\n{ind}</Condition>\n")

    def save(self, res):
        (crop(self.layer, self.box) if self.box else self.layer).save(res)


class Scene:
    def __init__(self, key, label):
        self.key, self.label, self.groups = key, label, []
        self.aod, self.tint = None, "#ffffff"   # always-on line art + its clock tint (set by AOD_STYLE)
        # live-weather overlays: which kinds this scene gets, and their look
        self.wx = {"rain", "snow", "fog", "clouds", "storm", "wind"}
        self.rain_col, self.fog_col = "#a8b8ff", "#8a7aa8"
        self.cloud_cols = ("#8a86a0", "#5a5670", "#3e3a52")
        self.horizon = 64
        self.extra = {}   # layers used only to trace the always-on art (e.g. rotating sprites composited flat)

    def layer(self, suffix):
        if suffix in self.extra:
            return self.extra[suffix]
        return next(im.layer for _, _, imgs in self.groups for im in imgs if im.suffix == suffix)

    def add(self, name, depth, *imgs):
        """depth = screen px the group slides at full tilt; None = a free sprite group on the 450 canvas."""
        self.groups.append((name, depth, list(imgs)))

    def marker(self, name):
        """Where the live-weather sky / fog overlays slot into the layer stack (filled in by add_weather)."""
        self.groups.append(("@" + name, None, []))

    def save(self):
        for _, _, imgs in self.groups:
            for im in imgs:
                im.save(f"{self.key}_{im.suffix}")
        self.aod_img = Img("aod", self.aod, x=-PAD, y=-PAD)
        self.aod_img.save(f"{self.key}_aod")

    def aod_xml(self, ind):
        """Always-on: hidden while awake, shown in ambient. Dim line art + dot-matrix time/date in the scene's tint."""
        k = self.key
        return (f'{ind}<Group name="{k}_aod" x="0" y="0" width="450" height="450" alpha="0">\n'
                f'{ind}  {self.aod_img.part(f"{k}_aod")}\n'
                f'{ind}  {date_xml(f"aod_{k}_date")}\n'
                f'{ind}  {batt_xml(f"aod_{k}_batt")}\n'
                f'{ind}  {clock_xml(f"aod_{k}_clock")}\n'
                f'{ind}  <Variant mode="AMBIENT" target="alpha" value="255" />\n{ind}</Group>\n')

    def xml(self, ind, prefix=""):
        out = []
        for name, depth, imgs in self.groups:
            if not imgs:
                continue
            gname = f"{prefix}{self.key}_{name}"
            if depth is None:
                out.append(f'{ind}<Group name="{gname}" x="0" y="0" width="450" height="450">\n')
            else:
                gx = f"{FLIP_X * depth} * clamp([ACCELEROMETER_ANGLE_X], -{TILT}, {TILT}) / {TILT}"
                gy = f"{FLIP_Y * depth} * clamp([ACCELEROMETER_ANGLE_Y], -{TILT}, {TILT}) / {TILT}"
                out.append(f'{ind}<Group name="{gname}" x="-{PAD}" y="-{PAD}" width="{SIZE}" height="{SIZE}">\n'
                           f'{ind}  <Gyro x="{gx}" y="{gy}" />\n')
            for im in imgs:
                out.append(im.xml(f"{self.key}_{im.suffix}", ind + "  "))
            out.append(f'{ind}  <Variant mode="AMBIENT" target="alpha" value="0" />\n{ind}</Group>\n')
        return "".join(out) + self.aod_xml(ind).replace(f'name="{self.key}_aod"', f'name="{prefix}{self.key}_aod"')

    def flatten(self, tilt=(0, 0)):
        """Composite the gyro groups on the art grid; return the visible 90x90 window."""
        out = [[(0, 0, 0)] * G for _ in range(G)]
        for _, depth, imgs in self.groups:
            if depth is None:
                continue
            ox, oy = int(round(tilt[0] * depth / PX)), int(round(tilt[1] * depth / PX))
            for im in imgs:
                if not im.preview:
                    continue
                for y in range(G):
                    for x in range(G):
                        c = im.layer.get(x - ox - im.x // PX, y - oy - im.y // PX)
                        if c:
                            a, b = c[3] / 255, out[y][x]
                            out[y][x] = tuple(int(c[i] * a + b[i] * (1 - a)) for i in range(3))
        c0 = PAD // PX
        return [row[c0:c0 + 90] for row in out[c0:c0 + 90]]


# ================================================================ scene 1: Lake (the original)
def scene_lake():
    S = Scene("lake", "Lake")
    HZ, PCX, PCY, PR = 64, 67, 49, 9
    S.horizon = HZ
    S.add("sky", 3, Img("sky", sky(pal("#06041a", "#0c0830", "#150b44", "#211058", "#351369", "#521977",
                                       "#7a1f7c", "#a8297a", "#d44270", "#f26e68"), HZ,
                                   ("#5d4a92", "#4a3f80", "#6e5aa8"))))
    S.add("stars", 5, Img("stars_a", stars(1, avoid=(PCX, PCY, PR + 12)), twinkle(0)),
          Img("stars_b", stars(2, avoid=(PCX, PCY, PR + 12)), twinkle(3.1416)))

    meteor = Layer(22, 8)
    for i in range(22):
        y, a = int(i * 7 / 21), int(255 * (i / 21) ** 1.6)
        meteor.set(i, y, hexc("#ffffff" if i > 18 else "#ffd6f0", max(a, 25)))
        if i > 15:
            meteor.set(i, y - 1 if y else y + 1, hexc("#ffb8e0", a // 2))
    m, n = f"({T} % 30)", f"floor({T} / 30)"   # a shooting star at :00 and :30, on two different paths
    S.add("meteor", None, Img("meteor", meteor, preview=False, inner=(
        f'<Transform target="x" value="round(30 + {n} * 120 + {m} * 260)" />'
        f'<Transform target="y" value="round(70 + {n} * 25 + {m} * 85)" />'
        f'<Transform target="alpha" value="{m} &lt; 0.9 ? 255 : 0" />')))

    # ringed planet: ring back half, lit body, ring front half
    P = Layer()
    ramp_p = pal("#22113f", "#4a1d62", "#8a2f6e", "#c94a6c", "#ef7f60", "#ffb87e", "#ffe6c4")
    lx, ly, lz = -0.55, -0.5, 0.67
    RX, RY, ca, sa = 18.5, 4.6, math.cos(-0.28), math.sin(-0.28)

    def ring_at(dx, dy):
        rx, ry = dx * ca + dy * sa, -dx * sa + dy * ca
        e = (rx / RX) ** 2 + (ry / RY) ** 2
        if 0.6 < e < 1.0 and not 0.77 < e < 0.82:
            return ry, hexc("#f4bd8c") if e > 0.82 else hexc("#b8706c")
    body = set(disk(PCX, PCY, PR))
    halo(P, PCX, PCY, PR, "#ff8f8a", 70)
    ring = [(x, y, ring_at(x - PCX, y - PCY)) for y in range(PCY - 8, PCY + 9) for x in range(PCX - 20, PCX + 21)]
    for x, y, r in ring:
        if r and r[0] < 0:
            P.set(x, y, r[1])
    for x, y in body:
        dx, dy = x - PCX, y - PCY
        nx, ny = dx / (PR + 0.5), dy / (PR + 0.5)
        nz = math.sqrt(max(0.0, 1 - nx * nx - ny * ny))
        lum = 0.28 + 0.72 * max(0.0, nx * lx + ny * ly + nz * lz) + 0.09 * math.sin(dy * 1.25 + 0.5)
        P.set(x, y, ramp(ramp_p, lum * 1.05, x, y))
    for x, y, r in ring:
        if r and (r[0] >= 0 or (x, y) not in body):
            P.set(x, y, r[1])
    S.add("planet", 8, Img("planet", P))

    S.add("clouds", 11, Img("clouds", clouds([(20, 1.6, 0.0, (2, 0.9), (5, 0.7)), (31, 2.0, 1.7, (3, 1.1), (7, 0.6)),
                                               (60, 1.6, 3.1, (2, 1.1), (6, 0.7))],
                                              hexc("#c64f8c", 235), hexc("#5b2a7c", 225), hexc("#3a1a62", 215)),
                                       scroll_x(), w=2 * SIZE))
    S.marker("wx_sky")
    S.add("far", 13, Img("far", terrain(Layer(), [(5.5, 0.075, 0.6), (3.5, 0.19, 2.0), (1.4, 0.52, 1.1)], 55,
                                        hexc("#3f1a6e"), rim=hexc("#b8407c"), bottom=HZ + 1)))
    S.marker("wx_fog")

    V = Layer()
    lake = pal("#2a1458", "#1c0e46", "#130a36", "#0c0728", "#07041c")
    for y in range(HZ, G):
        for x in range(G):
            V.set(x, y, ramp(lake, (y - HZ) / (G - HZ - 6), x, y))
    parts = [(3.2, 0.11, 4.0), (2.2, 0.27, 0.3), (0.9, 0.7, 2.2)]
    terrain(V, parts, 60.5, hexc("#21104a"), rim=hexc("#5c2276"), bottom=HZ)
    for x in range(G):
        for y in range(HZ, HZ + int((HZ - ridge(x, parts, 60.5)) * 0.85)):
            if (y + x // 3) % 5:
                V.set(x, y, hexc("#160b3c"))
    rnd = random.Random(11)
    for y in range(HZ + 2, G - 10):
        if y % 2 or rnd.random() < 0.25:
            continue
        w = max(1, int((3.2 - (y - HZ) * 0.07) * (0.4 + 0.6 * rnd.random())))
        off = rnd.randint(-1, 1)
        V.rect(PCX - w + off, y, PCX + w + off, y + 1,
               hexc("#ffb87e" if y < HZ + 8 else ("#e0705e" if y < HZ + 18 else "#8e3468")))

    def shimmer(seed):
        L, r = Layer(), random.Random(seed)
        for _ in range(26):
            near = r.random() < 0.55
            x = PCX + r.randint(-9, 9) if near else r.randrange(14, G - 14)
            y = r.randrange(HZ + 2, G - 12)
            L.rect(x, y, x + r.randint(1, 3), y + 1, hexc("#ffd0b0" if near else "#c45c9a", 230))
        return L
    S.add("vista", 17, Img("vista", V), Img("shimmer_a", shimmer(7), blink(0, 0.5)),
          Img("shimmer_b", shimmer(8), blink(1, 0.5), preview=False))

    N = Layer()
    land, land_rim = hexc("#0b0620"), hexc("#2e1248")
    for x in range(G):
        for top in ((69 + x * 0.36 + 0.8 * math.sin(x * 0.9)) if x < 38 else None,
                    (71 + (G - x) * 0.33 + 0.8 * math.sin(x * 0.7)) if x > 70 else None):
            if top is not None:
                N.rect(x, int(top), x + 1, G, land)
                N.set(x, int(top), land_rim)
    for cx, base, h in [(12, 74, 26), (18, 76, 20), (24, 79, 15), (30, 81, 11), (15, 80, 9),
                        (90, 74, 25), (84, 77, 19), (78, 80, 13), (94, 79, 16), (74, 82, 9)]:
        pine(N, cx, base, h, land, rim=land_rim)
    S.add("near", 22, Img("near", N))

    F = Layer()
    rnd = random.Random(5)
    for x in range(G):
        F.rect(x, int(92 + 2 * math.sin(x * 0.35) + rnd.random()), x + 1, G, hexc("#040210"))
    for _ in range(36):
        x = rnd.randrange(10, G - 10)
        h = int(3 + abs(x - G / 2) / (G / 2) * 10 + rnd.random() * 4)
        lean = rnd.choice((-1, 1))
        for i in range(h):
            F.set(x + (lean if i > h * 0.7 else 0), 92 - i, hexc("#040210"))
    spots = [(16, 36, 66, 86), (68, 88, 66, 86)]
    S.add("front", 28, Img("front", F),
          Img("flies_a", glow_dots(21, spots, "#f6ffa0", "#b9e64c", "#8fd13a"), wander(0)),
          Img("flies_b", glow_dots(22, spots, "#f6ffa0", "#b9e64c", "#8fd13a"), wander(2.1)))
    return S


# ================================================================ scene 2: Aurora over snowy peaks
def scene_frost():
    S = Scene("frost", "Aurora")
    HZ = 70
    S.horizon, S.fog_col, S.rain_col = HZ, "#7a9ab8", "#9ab8e0"
    S.wx.discard("snow")   # it already snows here; with live weather on, only when it really is
    S.add("sky", 3, Img("sky", sky(pal("#01040e", "#030a1c", "#06142a", "#0a1f38", "#0f2c48", "#163a58", "#21506c"),
                                   HZ, ("#3a5a7a", "#2c4864", "#4a6e8e"), n=90)))
    S.add("stars", 5, Img("stars_a", stars(31, n=30, ymax=40), twinkle(0)),
          Img("stars_b", stars(32, n=30, ymax=40), twinkle(3.1416)))

    def aurora(ph):
        L = Layer(2 * G, G)
        cols = pal("#9dffc4", "#4fe895", "#22b89a", "#1c7f9a", "#5a46c0")
        for x in range(2 * G):
            u = 2 * math.pi * (x % G) / G
            base = 52 + 5 * math.sin(2 * u + ph) + 3 * math.sin(5 * u + ph * 1.7)
            inten = (0.55 + 0.45 * math.sin(3 * u + ph * 2)) * (0.65 + 0.35 * math.sin(11 * u + ph))
            height = 20 + 6 * math.sin(4 * u + ph)
            for y in range(int(base - height), int(base) + 2):
                rel = min(1.0, max(0.0, (base - y) / height))
                a = inten * 235 * (1 - rel) ** 0.8 * (0.8 if x % 3 else 1.0)
                if a > 18:
                    L.set(x, y, ramp(cols, rel * 1.1, x, y)[:3] + (int(a),))
        return L
    fade = lambda s: f'<Transform target="alpha" value="round(128 {s} 127 * sin({T} * {K15}))" />'
    S.add("aurora", 7, Img("aurora_a", aurora(0.0), scroll_x() + fade("+"), w=2 * SIZE),
          Img("aurora_b", aurora(1.4), scroll_x() + fade("-"), preview=False, w=2 * SIZE))
    S.marker("wx_sky")
    S.add("snow_far", 9, Img("snow_far", falling(41, 70, 1, hexc("#c8dcf0", 170)), fall(3), preview=False,
                             weather="snow"))

    def snowcap(x, y, h, rising):
        d = y - h
        if d < 3 + 2 * math.sin(x * 0.7):
            return hexc("#e4f1ff") if rising else hexc("#8fb0d2")
        if d < 6 + 2 * math.sin(x * 0.7) and (x + y) % 3 == 0:
            return hexc("#5d7fa2")
    S.add("far", 12, Img("far", terrain(Layer(), [(6, 0.07, 1.0), (3, 0.2, 2.5), (1.2, 0.55, 0.4)], 63,
                                        hexc("#22405e"), shade=hexc("#162c44"), span=14, cap=snowcap)))
    S.marker("wx_fog")

    hills = [(2.5, 0.09, 0.8), (1.2, 0.25, 2.0)]
    M = terrain(Layer(), hills, 71, hexc("#a9c2dc"), rim=hexc("#e8f3ff"), shade=hexc("#6f8fb0"), span=16)
    for cx in range(4, G, 6):
        pine(M, cx + cx % 3, int(ridge(cx, hills, 71)) + 3, 8 + (cx * 7) % 8, hexc("#0b1d2a"), snow=hexc("#d6e8f6"))
    S.add("mid", 16, Img("mid", M))

    ground = [(2, 0.06, 2.0), (1, 0.2, 0.5)]
    N = terrain(Layer(), ground, 83, hexc("#cfe2f4"), rim=hexc("#ffffff"), shade=hexc("#8eaccb"), span=12)
    cx = 60                                                     # a little log cabin
    gy = int(ridge(cx + 6, ground, 83)) + 2
    N.rect(cx, gy - 7, cx + 13, gy, hexc("#4a2c1c"))
    for y in range(gy - 6, gy, 2):
        N.rect(cx, y, cx + 13, y + 1, hexc("#36200f"))
    N.rect(cx + 10, gy - 16, cx + 12, gy - 10, hexc("#3a2418"))  # chimney
    N.rect(cx + 10, gy - 17, cx + 12, gy - 16, hexc("#ffffff"))
    for i in range(8):                                           # snowy roof
        N.rect(cx - 2 + i, gy - 8 - i, cx + 15 - i, gy - 7 - i, hexc("#eef6ff"))
        N.set(cx - 2 + i, gy - 8 - i, hexc("#ffffff"))
        N.set(cx + 14 - i, gy - 8 - i, hexc("#9fb8d2"))
    N.rect(cx + 2, gy - 5, cx + 4, gy, hexc("#20120a"))           # door
    N.rect(cx + 7, gy - 5, cx + 10, gy - 2, hexc("#ffcf6a"))       # window
    W = Layer()
    halo(W, cx + 8, gy - 4, 2, "#ffb040", 120, 3)
    W.rect(cx + 7, gy - 5, cx + 10, gy - 2, hexc("#fff0b0"))
    smoke = Layer()
    for i, (dx, dy, r) in enumerate(((0, -19, 1), (1, -22, 1), (0, -25, 2))):
        for x, y in disk(cx + 11 + dx, gy + dy, r):
            smoke.set(x, y, hexc("#9cb0c8", 170 - i * 40))
    for x2, base, h in [(14, 85, 20), (20, 86, 14), (9, 87, 12), (88, 84, 18), (94, 86, 13), (82, 87, 10)]:
        pine(N, x2, base, h, hexc("#0a1a26"), snow=hexc("#dcecf8"))
    S.add("near", 21, Img("near", N),
          Img("window", W, f'<Transform target="alpha" value="round(215 + 40 * sin({T} * 7.3) * sin({T} * 3.1))" />'),
          Img("smoke", smoke, f'<Transform target="y" value="@BY@ - ({T} % 6) * 7" />'
                              f'<Transform target="alpha" value="round(230 * (1 - ({T} % 6) / 6))" />'))

    F = Layer()
    for x in range(G):
        top = int(92 + 2.5 * math.sin(x * 0.12 + 1) + math.sin(x * 0.5))
        for y in range(top, G):
            F.set(x, y, ramp(pal("#f4faff", "#c4d8ee", "#9cb6d4"), (y - top) / 8, x, y))
    sp = Layer()
    rnd = random.Random(9)
    for _ in range(14):
        x = rnd.randrange(20, 82)
        sp.set(x, int(93 + 2.5 * math.sin(x * 0.12 + 1)) + rnd.randrange(2, 6), hexc("#ffffff"))
    S.add("front", 26, Img("front", F), Img("sparkle", sp, blink(0, 0.7)))
    S.add("snow_near", 27, Img("snow_near", falling(42, 30, 2, hexc("#ffffff", 225), width=2), fall(6),
                               preview=False, weather="snow"))
    return S


# ================================================================ scene 3: Neon (rainy city)
def scene_neon():
    S = Scene("neon", "Neon city")
    HZ = 74
    S.horizon, S.fog_col = 66, "#6a3a7a"
    S.wx.discard("rain")   # it already rains here; with live weather on, only when it really is
    S.add("sky", 3, Img("sky", sky(pal("#05020c", "#0b0418", "#140726", "#1f0a34", "#2d0c42", "#40104e",
                                       "#5a1556", "#7a1c5a"), HZ)))
    MO = Layer()
    mx, my, mr = 77, 47, 9
    halo(MO, mx, my, mr, "#c8a8ff", 60, 5)
    rnd = random.Random(4)
    craters = [(mx + rnd.randint(-6, 6), my + rnd.randint(-6, 6), rnd.randint(1, 2)) for _ in range(6)]
    for x, y in disk(mx, my, mr):
        dx, dy = x - mx, y - my
        t = 0.35 + 0.65 * (1 - (dx + dy + mr * 1.4) / (mr * 2.8))
        if any((x - a) ** 2 + (y - b) ** 2 <= r * r for a, b, r in craters):
            t -= 0.25
        MO.set(x, y, ramp(pal("#6a5890", "#a796cc", "#e6dcfa"), t, x, y))
    S.add("moon", 6, Img("moon", MO))
    S.marker("wx_sky")

    def skyline(seed, top_lo, top_hi, wmin, wmax, color, win, p_lit, blinkers=None):
        L, r = Layer(), random.Random(seed)
        x, tops = -2, []
        while x < G:
            w, top = r.randint(wmin, wmax), r.randint(top_lo, top_hi)
            L.rect(x, top, x + w, G, color)
            tops.append((x, w, top))
            for wy in range(top + 2, G - 4, 2):
                for wx in range(x + 1, x + w - 1, 2):
                    roll = r.random()
                    if roll < p_lit:
                        L.set(wx, wy, hexc(r.choice(win)))
                    elif blinkers is not None and roll < p_lit + 0.06:
                        blinkers[int(roll * 1000) % 2].set(wx, wy, hexc(r.choice(win)))
            x += w + r.choice((0, 0, 1))
        return L, tops

    far, _ = skyline(51, 52, 66, 4, 9, hexc("#26123e"), ["#4e2a6e", "#5a3478"], 0.22)
    S.add("rain_far", 10, Img("rain_far", falling(52, 80, 3, hexc("#6f7fc0", 110)), fall(20), preview=False,
                              weather="rain"))
    S.add("far", 11, Img("far", far))
    S.marker("wx_fog")

    car = sprite(["..aaaa...", "abbbbbbbc", ".d.....d."],
                 {"a": hexc("#3a3a58"), "b": hexc("#22223a"), "c": hexc("#7af0ff"), "d": hexc("#ff3a6a")})
    car2 = sprite(["...aaaa..", "cbbbbbbba", ".d.....d."],
                  {"a": hexc("#3a3a58"), "b": hexc("#22223a"), "c": hexc("#ffe08a"), "d": hexc("#ff3a6a")})
    S.add("cars", 13, Img("car", car, f'<Transform target="x" value="round(({T} % 20) * 32) - 50" />',
                          preview=False, y=250),
          Img("car2", car2, f'<Transform target="x" value="560 - round((({T} + 10) % 20) * 30)" />',
              preview=False, y=225))

    bl = [Layer(), Layer()]
    mid, tops = skyline(53, 50, 70, 5, 10, hexc("#140822"), ["#ffd27a", "#7af0ff", "#ff6ad5", "#ffb050"], 0.2, bl)
    ant = Layer()
    for x, w, top in sorted(tops, key=lambda t: t[2])[:3]:
        mid.rect(x + w // 2, top - 5, x + w // 2 + 1, top, hexc("#140822"))
        ant.set(x + w // 2, top - 6, hexc("#ff3050"))
    S.add("mid", 15, Img("mid", mid), Img("win_a", bl[0], blink(0, 4)), Img("win_b", bl[1], blink(1, 4)),
          Img("antenna", ant, blink(0, 1)))

    N = Layer()
    wall = hexc("#0b0516")
    N.rect(0, 22, 26, G, wall)
    N.rect(80, 30, G, G, wall)
    rnd = random.Random(55)
    for wy in range(26, 84, 4):
        for wx in list(range(2, 18, 4)) + list(range(84, 100, 4)):
            if rnd.random() < 0.18:
                N.rect(wx, wy, wx + 2, wy + 2, hexc(rnd.choice(["#3a1c4a", "#ffcf7a", "#2a1440"])))
    sign = Layer()
    sx0, sx1, sy0, sy1 = 19, 26, 32, 60
    sign.rect(sx0 - 2, sy0 - 2, sx1 + 2, sy1 + 2, hexc("#ff4fd8", 60))
    sign.rect(sx0, sy0, sx1, sy1, hexc("#ff4fd8"))
    sign.rect(sx0 + 1, sy0 + 1, sx1 - 1, sy1 - 1, hexc("#1a0626"))
    for y in range(sy0 + 3, sy1 - 3, 5):
        sign.rect(sx0 + 2, y, sx1 - 2, y + 3, hexc("#5ff6ff"))
    amber = Layer()
    amber.rect(82, 40, 98, 47, hexc("#ffb340", 60))
    amber.rect(83, 41, 97, 46, hexc("#2a1206"))
    for x in range(84, 96, 3):
        amber.rect(x, 42, x + 2, 45, hexc("#ffb340"))
    flicker = f'<Transform target="alpha" value="sin({T} * 7.3) * sin({T} * 3.1) &gt; 0.8 ? 70 : 255" />'
    S.add("near", 21, Img("near", N), Img("sign", sign, flicker), Img("amber", amber))

    st = Layer()
    for y in range(83, G):
        for x in range(G):
            st.set(x, y, ramp(pal("#140a24", "#0a0614", "#05030a"), (y - 83) / 16, x, y))
    st.rect(0, 83, G, 84, hexc("#2a1840"))
    rnd = random.Random(8)
    for xc, col in ((22, "#ff4fd8"), (90, "#ffb340"), (50, "#7af0ff"), (64, "#ff6ad5")):
        for y in range(85, G - 4, 2):
            if rnd.random() < 0.75:
                w = rnd.randint(1, 3)
                st.rect(xc - w + rnd.randint(-1, 1), y, xc + w, y + 1, hexc(col, max(40, 170 - (y - 85) * 7)))
    S.add("street", 24, Img("street", st))
    S.add("rain_near", 27, Img("rain_near", falling(54, 35, 5, hexc("#a8b8ff", 150)), fall(30), preview=False,
                               weather="rain"))
    return S


# ================================================================ scene 4: Dunes (desert sunset)
def scene_dunes():
    S = Scene("dunes", "Dunes")
    HZ = 62
    S.horizon, S.fog_col = 68, "#d88a7a"
    S.wx.discard("wind")   # the blowing sand is the wind
    S.add("sky", 3, Img("sky", sky(pal("#140726", "#2a0c40", "#4a1258", "#7a1a62", "#a82a62", "#d4465a",
                                       "#f06e4a", "#ff9a40", "#ffc35a"), HZ)))
    S.add("stars", 4, Img("stars_a", stars(61, n=14, ymax=24), twinkle(0)),
          Img("stars_b", stars(62, n=10, ymax=24), twinkle(3.1416)))

    SU = Layer()
    cx, cy, r = 51, 62, 17
    halo(SU, cx, cy, r, "#ffb060", 80, 6)
    cols = pal("#fff4a8", "#ffd060", "#ff9a48", "#ff5a6a", "#e83a8a")
    for x, y in disk(cx, cy, r):
        dy = y - cy
        if dy > 0 and (dy % 5) < 1 + min(3, dy // 5):   # retro horizontal slits
            continue
        SU.set(x, y, ramp(cols, (dy + r) / (2 * r), x, y))
    S.add("sun", 5, Img("sun", SU))

    S.add("clouds", 8, Img("clouds", clouds([(28, 1.2, 0.4, (2, 0.8), (5, 0.6)), (40, 1.4, 2.2, (3, 0.9), (7, 0.5))],
                                             hexc("#ffb08a", 230), hexc("#c25478", 220), hexc("#7a2a62", 210)),
                                      scroll_x(), w=2 * SIZE))
    S.marker("wx_sky")

    up = ["#.....#.......#.....#", ".#...#.........#.#..", "..#.#..#.....#..#...", "...#....#...#.......",
          ".........#.#........", "..........#........."]
    dn = ["....................", "...........#.....#..", "..###...............", ".#...#...###...###..",
          "#.....#.#...#.#...#.", "........#..........."]
    bird = {"#": hexc("#2a0f2e")}
    fly = (f'<Transform target="x" value="round(({T} % 30) * 22) - 100" />'
           f'<Transform target="y" value="round(120 + 4 * sin({T} * {K3}))" />')
    S.add("birds", 10, Img("bird_up", sprite(up, bird), fly + blink(0, 0.25), preview=False),
          Img("bird_dn", sprite(dn, bird), fly + blink(1, 0.25), preview=False))

    S.add("far", 12, Img("far", terrain(Layer(), [(3, 0.06, 0.5), (2, 0.13, 2.0)], 67, hexc("#b0446a"),
                                        rim=hexc("#ffa070"), shade=hexc("#8a3460"), span=14)))
    S.marker("wx_fog")
    S.add("mid", 17, Img("mid", terrain(Layer(), [(4, 0.05, 3.0), (2.5, 0.11, 1.0)], 77, hexc("#7a2a5c"),
                                        rim=hexc("#ff7a7a"), shade=hexc("#5a1e4c"), span=14,
                                        cap=lambda x, y, h, rising: hexc("#9a3666") if rising and y - h < 3 else None)))

    near = [(5, 0.045, 0.2), (2, 0.12, 1.5)]
    N = terrain(Layer(), near, 88, hexc("#3a1240"), rim=hexc("#c04a70"), shade=hexc("#24082c"), span=10)
    dark = hexc("#1a0620")
    for x0, h, arms in ((26, 20, ((-4, 8, 5), (3, 11, 4))), (78, 14, ((3, 6, 4),))):
        base = int(ridge(x0, near, 88)) + 2
        N.rect(x0 - 1, base - h + 1, x0 + 2, base, dark)
        N.set(x0, base - h, dark)
        for dx, at, ah in arms:
            y, ax = base - at, x0 + dx
            if dx > 0:
                N.rect(x0 + 2, y, ax + 1, y + 2, dark)
            else:
                N.rect(ax, y, x0 - 1, y + 2, dark)
            N.rect(ax, y - ah, ax + 2, y + 1, dark)
    S.add("near", 22, Img("near", N))
    S.add("sand", 26, Img("sand", falling(71, 40, 2, hexc("#ffc090", 140), wide=True, ymin=70), scroll_x(4),
                          preview=False))
    S.groups[-1][2][0].weather = "wind"
    return S


# ================================================================ helpers for the newer scenes
def blit(dst, src, ax, ay):
    """Copy src onto dst with its top-left at art (ax, ay)."""
    for y in range(src.h):
        for x in range(src.w):
            if src.px[y][x]:
                dst.set(ax + x, ay + y, src.px[y][x])
    return dst


def ring(r, col, alpha):
    L = Layer(2 * r + 3, 2 * r + 3)
    for y in range(L.h):
        for x in range(L.w):
            if abs(math.hypot(x - r - 1, y - r - 1) - r) < 0.6:
                L.set(x, y, hexc(col, alpha))
    return L


K6, K1_5 = "1.0472", "4.1888"   # 2*pi/6 s, 2*pi/1.5 s


# ================================================================ scene 5: Lighthouse (dusk coast)
def scene_lighthouse():
    S = Scene("lighthouse", "Lighthouse")
    HZ = 60
    S.horizon, S.rain_col, S.fog_col = HZ, "#9fb4e8", "#6a7aa0"
    S.add("sky", 3, Img("sky", sky(pal("#070a1e", "#0c1230", "#141c44", "#1f2858", "#2e3568", "#4a4478",
                                       "#74507e", "#a06482"), HZ, ("#3a4878", "#4a5888"), n=60)))
    mx, my, mr = 20, 42, 5
    S.add("stars", 5, Img("stars_a", stars(81, n=22, ymax=42, avoid=(mx, my, 9)), twinkle(0)),
          Img("stars_b", stars(82, n=22, ymax=42, avoid=(mx, my, 9)), twinkle(3.1416)))
    MO = Layer()
    halo(MO, mx, my, mr, "#d8d0ff", 50, 3)
    for x, y in disk(mx, my, mr):
        if (x - mx - 2) ** 2 + (y - my + 1) ** 2 > (mr - 0.5) ** 2:   # crescent: bite out of the disk
            MO.set(x, y, hexc("#f2eccc"))
    S.add("moon", 6, Img("moon", MO))
    S.marker("wx_sky")

    SEA = Layer()
    rnd = random.Random(83)
    for y in range(HZ, G):
        for x in range(G):
            SEA.set(x, y, ramp(pal("#2a3060", "#18204a", "#0e1434", "#080c22", "#04060f"), (y - HZ) / (G - HZ - 4), x, y))
    for _ in range(90):
        x, y = rnd.randrange(G), rnd.randrange(HZ + 1, G)
        SEA.rect(x, y, x + rnd.randint(2, 5), y + 1, hexc("#28305e"))
    for y in range(HZ + 1, G - 6, 2):                       # moonlight path
        w = rnd.randint(1, 3)
        SEA.rect(mx - w + rnd.randint(-1, 1), y, mx + w, y + 1, hexc("#c8c4e8" if y < HZ + 12 else "#5a5c94"))
    SEA.rect(0, HZ, G, HZ + 1, hexc("#4a4e86"))

    def glints(seed):
        L, r = Layer(), random.Random(seed)
        for _ in range(22):
            x, y = r.randrange(4, G - 4), r.randrange(HZ + 2, G - 8)
            L.rect(x, y, x + r.randint(1, 2), y + 1, hexc("#b8c0f0", 200))
        return L
    S.add("sea", 10, Img("sea", SEA), Img("glint_a", glints(84), blink(0, 0.6)),
          Img("glint_b", glints(85), blink(1, 0.6), preview=False, calm="hide"))
    S.marker("wx_fog")

    boat = sprite(["....#....", "....##...", "....###..", "....####.", "..y.#....", "#########", ".#######."],
                  {"#": hexc("#0a0c1c"), "y": hexc("#ffd070")})
    S.add("boat", 11, Img("boat", boat, f'<Transform target="x" value="{T} * 9 - 60" />'
                                        f'<Transform target="y" value="{(HZ - 6) * PX} + 2 * sin({T} * {K3})" />',
                          preview=False, calm="hide"))

    gull = {"#": hexc("#c8c8e0")}
    up = ["#...#", ".#.#.", "..#.."]
    dn = [".....", ".###.", "#...#"]
    fly = (f'<Transform target="x" value="{T} * 10 - 60" />'
           f'<Transform target="y" value="170 + 6 * sin({T} * {K3})" />')
    S.add("gulls", 13, Img("gull_up", sprite(up, gull), fly + blink(0, 0.3), preview=False, calm="hide"),
          Img("gull_dn", sprite(dn, gull), fly + blink(1, 0.3), preview=False, calm="hide"))

    # cliff + lighthouse
    CL = Layer()
    top = lambda x: int(66 + 7 * max(0.0, (G - x) / 46) ** 1.5 + 0.7 * math.sin(x * 0.8))
    for x in range(54, G):
        t = top(x)
        for y in range(t, G):
            CL.set(x, y, ramp(pal("#16162e", "#0c0c1c", "#06060e"), (y - t) / 30, x, y))
        CL.set(x, t, hexc("#34345a"))
        if x % 3 == 0:
            CL.set(x, t - 1, hexc("#1c2a30"))
    lx = 84
    base = top(lx)
    for i in range(17):
        y = base - 1 - i
        hw = 3 if i < 6 else 2
        col = hexc("#8a2a38") if (i // 4) % 2 else hexc("#c8c0c4")
        CL.rect(lx - hw, y, lx + hw + 1, y + 1, col)
        CL.set(lx + hw, y, hexc("#5a1a24") if (i // 4) % 2 else hexc("#8a8494"))   # shaded side
    gy = base - 18
    CL.rect(lx - 4, gy, lx + 5, gy + 1, hexc("#1a1a28"))      # gallery
    CL.rect(lx - 2, gy - 4, lx + 3, gy, hexc("#fff0a0"))      # lantern room
    CL.set(lx - 2, gy - 4, hexc("#1a1a28"))
    CL.set(lx + 2, gy - 4, hexc("#1a1a28"))
    for i in range(3):                                        # roof
        CL.rect(lx - 3 + i, gy - 5 - i, lx + 4 - i, gy - 4 - i, hexc("#5a1a22"))
    CL.set(lx, gy - 8, hexc("#5a1a22"))
    ly = gy - 2                                               # lantern centre (art)

    def beam(direction):
        L = Layer(60, 9)
        for d in range(60):
            hh = 0.5 + d * 0.07
            a = int(175 * (1 - d / 60) ** 1.3)
            for y in range(9):
                if abs(y - 4) <= hh and a > 6:
                    L.set(d if direction > 0 else 59 - d, y, hexc("#fff4c8", a))
        return L
    c = f"cos({T} * 0.6283)"   # the light turns once every 10 s
    bx, by = lx * PX, ly * PX - 22
    right = Img("beam_r", beam(1), f'<Transform target="scaleX" value="clamp({c}, 0.01, 1)" />'
                                   f'<Transform target="alpha" value="{c} &gt; 0 ? 220 : 0" />',
                x=bx, y=by, attrs=' pivotX="0" pivotY="0.5"', preview=False, calm="hide")
    left = Img("beam_l", beam(-1), f'<Transform target="scaleX" value="clamp(-{c}, 0.01, 1)" />'
                                   f'<Transform target="alpha" value="{c} &lt; 0 ? 220 : 0" />',
               x=bx - 300, y=by, attrs=' pivotX="1" pivotY="0.5"')
    flare = Layer()
    halo(flare, lx, ly, 2, "#fff0b0", 230, 5)
    foam = [Layer(), Layer()]
    rnd = random.Random(86)
    for i in (0, 1):
        for _ in range(10):
            x = rnd.randrange(54, 66)
            y = HZ + 1 + rnd.randrange(0, 5) + (x - 54) // 3
            foam[i].rect(x, y, x + rnd.randint(1, 3), y + 1, hexc("#d8e0ff", 210))
    S.add("cliff", 17, Img("cliff", CL), left, right,
          Img("flare", flare, f'<Transform target="alpha" value="round(70 + 185 * pow(1 - abs({c}), 4))" />'),
          Img("foam_a", foam[0], blink(0, 0.8)), Img("foam_b", foam[1], blink(1, 0.8), preview=False, calm="hide"))

    F = Layer()
    for x in range(0, 40):
        t = int(88 + (x / 40) ** 2 * 14 - 4 * math.exp(-((x - 14) / 7) ** 2))
        for y in range(t, G):
            F.set(x, y, hexc("#05050c"))
        F.set(x, t, hexc("#1c1c34"))
    S.add("rocks", 24, Img("rocks", F))
    return S


# ================================================================ scene 6: Koi pond (top-down)
def koi_sprite(body, patch, seed, facing=1):
    L = Layer(16, 7)
    rnd = random.Random(seed)
    for x in range(16):
        if x >= 3:
            hh = 2.9 * math.sin(math.pi * (x - 2.5) / 13.5)
            for y in range(7):
                if abs(y - 3) <= hh:
                    L.set(x, y, hexc(body))
        else:                                            # forked tail
            for y in (1 + x // 2, 5 - x // 2):
                L.set(x, y, hexc(body, 200))
    for _ in range(3):                                   # patches
        px, py = rnd.randrange(5, 12), rnd.randrange(2, 5)
        for x, y in disk(px, py, 1):
            if L.get(x, y):
                L.set(x, y, hexc(patch))
    L.set(8, 0, hexc(body, 170))                         # pectoral fins
    L.set(8, 6, hexc(body, 170))
    L.set(13, 2, hexc("#101010"))
    L.set(13, 4, hexc("#101010"))
    if facing < 0:
        L.px = [row[::-1] for row in L.px]
    return L


def scene_koi():
    S = Scene("koi", "Koi pond")
    S.horizon, S.fog_col = 50, "#c8e0dc"
    S.wx = {"snow", "fog", "storm", "wind"}   # seen from above: no sky clouds, and rain becomes ripples
    W = Layer()
    rnd = random.Random(91)
    cols = pal("#46a090", "#358a7c", "#287268", "#1d5c58", "#144448")
    for y in range(G):
        for x in range(G):
            d = math.hypot(x - G / 2, y - G / 2 - 4) / (G * 0.62)
            n = 0.07 * math.sin(x * 0.31 + y * 0.17) + 0.07 * math.sin(x * 0.13 - y * 0.29)
            W.set(x, y, ramp(cols, d + n, x, y))
    for _ in range(40):
        cx, cy, r = rnd.randrange(G), rnd.randrange(G), rnd.randint(1, 2)
        col = hexc(rnd.choice(["#2a6a5e", "#356e60", "#1e5850"]))
        for x, y in disk(cx, cy, r):
            W.set(x, y, col)
    S.add("water", 3, Img("water", W))

    def caustics(ph):
        L = Layer()
        for y in range(G):
            for x in range(G):
                if abs(math.sin(x * 0.45 + 2.2 * math.sin(y * 0.21 + ph))) < 0.07:
                    L.set(x, y, hexc("#9ae8d4", 70))
        return L
    S.add("caustics", 4, Img("caustic_a", caustics(0), twinkle(0, 0)),
          Img("caustic_b", caustics(2.0), twinkle(3.1416, 0), preview=False, calm="hide"))

    # pads (surface) and their shadows on the bottom: different depths, so tilting shows the gap
    pads, shadow = Layer(), Layer()
    for cx, cy, r, flower in [(16, 30, 7, True), (86, 28, 6, False), (12, 72, 8, False), (88, 76, 7, True),
                              (40, 92, 6, False), (70, 94, 5, False)]:
        for x, y in disk(cx, cy, r):
            ang = math.atan2(y - cy, x - cx)
            if abs(ang - 0.6) < 0.28:                    # the notch
                continue
            edge = math.hypot(x - cx, y - cy) > r - 1
            pads.set(x, y, hexc("#6ab85a" if edge else ("#3e8a3a" if (x + y) % 5 else "#357a32")))
            shadow.set(x + 2, y + 3, hexc("#0a2a28", 90))
        if flower:
            for x, y in disk(cx - 2, cy - 1, 2):
                pads.set(x, y, hexc("#ff9ac8"))
            pads.set(cx - 3, cy - 2, hexc("#ffd0e8"))
            pads.set(cx - 2, cy - 1, hexc("#ffe070"))
    S.add("shadows", 5, Img("shadows", shadow))

    # koi: each is a thin strip, pivoted at the pond centre, rotated round it
    cx, cy = 225 + PAD, 268 + PAD
    fish = [("#f4f0e8", "#e04a2a", 92, 1, 20, "0 + {T} * 6"), ("#ff8a2a", "#ffd890", 93, -1, 14, "180 - {T} * 12"),
            ("#2a2a30", "#f08a3a", 94, 1, 25, "90 + {T} * 18")]
    imgs, flat = [], Layer()
    for i, (body, patch, seed, facing, hs, ang) in enumerate(fish):
        F = Layer(16, hs)
        blit(F, koi_sprite(body, patch, seed, facing), 0, 0)
        wob = f"3 * sin({T} * {K1_5} + {i})"
        imgs.append(Img(f"koi_{i}", F, f'<Transform target="angle" value="{ang.format(T=T)} + {wob}" />',
                        x=cx - 40, y=cy - hs * PX, attrs=' pivotX="0.5" pivotY="1"'))
        ax, ay = [(26, 66), (60, 84), (66, 48)][i]          # always-on: spread round the pond, not stacked
        blit(flat, koi_sprite(body, patch, seed, facing), ax, ay)
    S.extra["fish"] = flat
    S.add("koi", 7, *imgs)

    rip = ring(12, "#d8f4ec", 170)

    def ripple(i, x, y, period, ph, **kw):
        p = f"((({T} + {ph}) % {period}) / {period})"
        return Img(f"ripple_{i}", rip, f'<Transform target="scaleX" value="0.15 + {p}" />'
                                       f'<Transform target="scaleY" value="0.15 + {p}" />'
                                       f'<Transform target="alpha" value="round(230 * (1 - {p}))" />',
                   x=x, y=y, attrs=' pivotX="0.5" pivotY="0.5"', preview=False, **kw)
    rnd = random.Random(95)
    rain = [ripple(f"r{i}", rnd.randrange(80, 380), rnd.randrange(120, 420), 1.5, rnd.random() * 1.5,
                   only="rain", calm="hide") for i in range(9)]
    S.add("surface", 18, Img("pads", pads), ripple("a", 300, 330, 4, 0, calm="hide"),
          ripple("b", 140, 220, 5, 2.5, calm="hide"), *rain)

    petals = Layer()
    for x, y in ((30, 50), (64, 40), (58, 70), (24, 58)):
        petals.set(x, y, hexc("#ffb0d4"))
        petals.set(x + 1, y, hexc("#ff9ac8", 200))
    S.add("petals", 21, Img("petals", petals, wander(0.7, 14, 10)))
    return S


# ================================================================ scene 7: Deep sea
def scene_deep():
    S = Scene("deep", "Deep sea")
    S.horizon, S.wx = 70, set()   # underwater: no weather
    W = Layer()
    for y in range(G):
        for x in range(G):
            W.set(x, y, ramp(pal("#4ab0c4", "#2a8aa8", "#1c6a92", "#14507a", "#0e3862", "#08244a", "#041632",
                                 "#020a1c"), y / (G - 4), x, y))
    S.add("water", 3, Img("water", W))

    def rays(seed):
        L, r = Layer(), random.Random(seed)
        for _ in range(5):
            x0 = r.randrange(-10, G - 20)
            for y in range(0, 72):
                w = 2 + y * 0.07
                a = int(65 * (1 - y / 72))
                for x in range(int(x0 + y * 0.35), int(x0 + y * 0.35 + w)):
                    if a > 4:
                        L.set(x, y, hexc("#c8f8ff", a))
        return L
    S.add("rays", 4, Img("rays_a", rays(111), twinkle(0, 60) + f'<Transform target="x" value="@BX@ + 5 * sin({T} * {K10})" />'),
          Img("rays_b", rays(112), twinkle(3.1416, 60), preview=False, calm="hide"))
    S.add("snow", 6, Img("marine_snow", falling(113, 60, 1, hexc("#b8dce8", 110)), fall(1), preview=False))

    S.add("reef", 9, Img("reef", terrain(Layer(), [(4, 0.09, 1.2), (2, 0.23, 0.4), (1, 0.6, 2.0)], 78,
                                         hexc("#0c2e4a"), rim=hexc("#1c4a6a"))))

    def jelly(bell, inner, glow):
        L = Layer(11, 15)
        for x, y in disk(5, 4, 4):
            if y <= 5:
                L.set(x, y, hexc(bell, 210))
        for x in range(2, 9):
            L.set(x, 4, hexc(inner, 220))
        L.set(3, 2, hexc("#ffffff", 230))
        for i, tx in enumerate((2, 4, 6, 8)):
            for y in range(6, 14 - i % 2):
                L.set(tx + (1 if (y + i) % 4 < 2 else 0), y, hexc(glow, 150 - (y - 6) * 12))
        return L
    jl = []
    for i, (col, inn, gl, x, y, ph) in enumerate([("#ff8ad8", "#c050b0", "#ffb0ec", 300, 250, 0),
                                                   ("#8af0ff", "#3aa8c8", "#c0f8ff", 120, 300, 2.3)]):
        J = jelly(col, inn, gl)
        jl.append(Img(f"jelly_{i}", J, f'<Transform target="x" value="{x} + 8 * sin({T} * {K15} + {ph})" />'
                                       f'<Transform target="y" value="{y} + 14 * sin({T} * {K10} + {ph})" />'
                                       f'<Transform target="scaleY" value="1 + 0.07 * sin({T} * {K3} + {ph})" />',
                      x=x, y=y, attrs=' pivotX="0.5" pivotY="0"'))
    S.extra["jellies"] = blit(blit(Layer(), jelly("#ff8ad8", "#c050b0", "#ffb0ec"), 60, 50),
                              jelly("#8af0ff", "#3aa8c8", "#c0f8ff"), 24, 60)
    S.add("jellies", 12, *jl)

    school = Layer(32, 9)
    rnd = random.Random(114)
    for _ in range(8):
        fx, fy = rnd.randrange(0, 28), rnd.randrange(0, 8)
        school.rect(fx + 1, fy, fx + 4, fy + 1, hexc("#c8dce6"))
        school.set(fx + 4, fy, hexc("#8aa8b8"))   # tail on the right: they swim left
        school.set(fx + 1, fy, hexc("#e8f4fa"))
    S.add("school", 14, Img("school", school, f'<Transform target="x" value="560 - ({T} % 30) * 25" />'
                                              f'<Transform target="y" value="190 + 10 * sin({T} * {K3})" />',
                            preview=False, calm="hide"))

    def kelp(seed, n):
        L, r = Layer(20, 62), random.Random(seed)
        for i in range(n):
            bx, h = r.randrange(2, 18), r.randrange(34, 60)
            for y in range(62 - h, 62):
                x = int(bx + 1.6 * math.sin((62 - y) * 0.22 + i))
                L.set(x, y, hexc("#1e6a3a"))
                if y % 4 == 0:
                    L.set(x + 1, y, hexc("#2e8a48"))
                    L.set(x + 2, y + 1, hexc("#4aaa5a"))
        return L
    kl, kr = kelp(115, 5), kelp(116, 4)
    S.extra["kelp"] = blit(blit(Layer(), kl, 2, G - 66), kr, 80, G - 66)
    sway = lambda ph: f'<Transform target="angle" value="3 * sin({T} * {K6} + {ph})" />'
    S.add("kelp", 18, Img("kelp_l", kl, sway(0), x=2 * PX, y=(G - 66) * PX, attrs=' pivotX="0.5" pivotY="1"'),
          Img("kelp_r", kr, sway(1.7), x=80 * PX, y=(G - 66) * PX, attrs=' pivotX="0.5" pivotY="1"'))

    FL = terrain(Layer(), [(3, 0.07, 2.0), (1.5, 0.21, 0.6)], 91, hexc("#1a3a4a"), rim=hexc("#2e5a6a"),
                 shade=hexc("#0a1c28"), span=10)
    rnd = random.Random(117)
    for cx, col in ((30, "#c84a7a"), (62, "#d8702a"), (74, "#7a4a9a"), (46, "#c84a7a")):
        b = int(ridge(cx, [(3, 0.07, 2.0), (1.5, 0.21, 0.6)], 91))
        for k in range(5):                                # branching coral
            x, y = cx, b
            for _ in range(rnd.randint(4, 8)):
                FL.set(x, y, hexc(col))
                y -= 1
                x += rnd.choice((-1, 0, 0, 1)) * (1 if k % 2 else -1)
    glow = Layer()
    for _ in range(14):
        x = rnd.randrange(20, 82)
        glow.set(x, int(ridge(x, [(3, 0.07, 2.0), (1.5, 0.21, 0.6)], 91)) + rnd.randrange(1, 6), hexc("#7af8ff"))
    angler = sprite(["....l.....", "...#......", "..##......", ".####.....", "######w###", "#########.",
                     ".#######..", "..####...."], {"#": hexc("#0c1824"), "w": hexc("#e8f0f0")})
    lure = Layer()
    halo(lure, 21, 81, 1, "#a8ffe8", 200, 3)
    lure.set(21, 81, hexc("#f0fff8"))
    S.add("floor", 22, Img("floor", FL), Img("biolum", glow, twinkle(1, 40)),
          Img("angler", angler, x=17 * PX, y=81 * PX), Img("lure", lure, twinkle(0.5, 30)))
    S.add("bubbles", 26, Img("bubbles", bubbles(118, 22),
                             f'<Transform target="y" value="-round(({T} * {SIZE * 4 / 60:g}) % {SIZE})" />',
                             preview=False, calm="hide"))
    return S


def bubbles(seed, n):
    L = Layer(G, 2 * G)
    rnd = random.Random(seed)
    for _ in range(n):
        x, y, big = rnd.randrange(4, G - 4), rnd.randrange(G), rnd.random() < 0.4
        for k in (0, G):
            if big:
                for dx, dy in ((0, -1), (1, 0), (0, 1), (-1, 0)):
                    L.set(x + dx, y + k + dy, hexc("#c8f4ff", 170))
                L.set(x - 1, y + k - 1, hexc("#ffffff", 200))
            else:
                L.set(x, y + k, hexc("#c8f4ff", 150))
    return L


# ================================================================ scene 8: Meadow (sunny morning)
def scene_meadow():
    S = Scene("meadow", "Meadow")
    HZ = 60
    S.horizon, S.rain_col, S.fog_col = HZ, "#d0dcff", "#e0e8f0"
    S.cloud_cols = ("#c8ccd8", "#9aa0b4", "#7a8098")
    S.add("sky", 3, Img("sky", sky(pal("#2e5aa8", "#3c6cbc", "#5284cc", "#6c9cda", "#8ab4e4", "#a8c8ea",
                                       "#cad8e8", "#ecd8c8", "#ffd2a8"), HZ)))
    SU = Layer()
    halo(SU, 20, 50, 7, "#fff0c0", 110, 7)
    for x, y in disk(20, 50, 7):
        SU.set(x, y, ramp(pal("#fffbe8", "#fff0a8", "#ffe080"), (y - 43) / 14, x, y))
    S.add("sun", 5, Img("sun", SU))
    S.add("clouds", 8, Img("clouds", clouds([(9, 1.8, 0.5, (2, 1.0), (5, 0.7)), (44, 1.6, 2.4, (3, 0.9), (6, 0.6))],
                                             hexc("#ffffff", 245), hexc("#eef2fa", 240), hexc("#c4cee4", 235)),
                                      scroll_x(), w=2 * SIZE))
    S.marker("wx_sky")
    balloon = sprite(["...rryrr...", "..rryyyrr..", ".rrbyyybrr.", ".rrbyyybrr.", "rrrbyyybrrr", "rrrbyyybrrr",
                      ".rrbyyybrr.", "..rrbybrr..", "...rrbrr...", "....|.|....", "....|.|....", "....kkk....",
                      "....kkk...."], {"r": hexc("#e0483a"), "y": hexc("#ffd04a"), "b": hexc("#3a6ad0"),
                                       "|": hexc("#6a4a30"), "k": hexc("#8a5a2a")})
    S.add("balloon", 9, Img("balloon", balloon, f'<Transform target="x" value="{T} * 9 - 60" />'
                                                f'<Transform target="y" value="150 + 8 * sin({T} * {K10})" />',
                            preview=False, calm="hide"))

    def blades(n=14):
        L = Layer(2 * n + 3, 2 * n + 3)
        c = n + 1
        for k in range(3):
            a = k * 2 * math.pi / 3
            for d in range(1, n + 1):
                x, y = c + round(math.cos(a) * d), c + round(math.sin(a) * d)
                L.set(x, y, hexc("#f4f8fc"))
                if d < n * 0.6:
                    L.set(x + round(-math.sin(a)), y + round(math.cos(a)), hexc("#c8d0dc"))
        L.set(c, c, hexc("#a8b0bc"))
        return L

    def turbine(L, x, base, h, col, nac):
        L.rect(x, base - h, x + 1, base, hexc(col))
        L.rect(x - 1, base - h - 1, x + 2, base - h + 1, hexc(nac))
        return base - h

    far_parts = [(2, 0.07, 0.4), (1.2, 0.18, 1.9)]
    FA = terrain(Layer(), far_parts, 59, hexc("#6a9ab0"), rim=hexc("#9ac8d0"), shade=hexc("#5a8aa0"), span=8)
    fx = 30
    fhub = turbine(FA, fx, int(ridge(fx, far_parts, 59)), 12, "#c8d8e4", "#b0c0cc")
    small = blades(8)
    S.add("far", 11, Img("far", FA), Img("blades_far", small, f'<Transform target="angle" value="{T} * 30" />',
                                         x=(fx - 9) * PX + 2, y=(fhub - 9) * PX, attrs=' pivotX="0.5" pivotY="0.5"'))
    S.marker("wx_fog")

    mid_parts = [(3, 0.06, 2.2), (1.5, 0.15, 0.8)]
    MI = terrain(Layer(), mid_parts, 70, hexc("#4a9a4a"), rim=hexc("#8ad06a"), shade=hexc("#3a7a3a"), span=12)
    rnd = random.Random(121)
    for tx in (12, 20, 46, 88):                               # round trees
        b = int(ridge(tx, mid_parts, 70))
        MI.rect(tx, b - 3, tx + 1, b + 1, hexc("#5a3a22"))
        for x, y in disk(tx, b - 6, 3):
            MI.set(x, y, hexc("#2e7a32" if (x + y) % 3 else "#3e9a3e"))
    mx = 70
    hub = turbine(MI, mx, int(ridge(mx, mid_parts, 70)), 20, "#e8eef4", "#d0d8e0")
    big = blades(14)
    S.extra["turbines"] = blit(blit(Layer(), big, mx - 15, hub - 15), small, fx - 9, fhub - 9)
    S.add("mid", 15, Img("mid", MI), Img("blades", big, f'<Transform target="angle" value="{T} * 24" />',
                                         x=(mx - 15) * PX + 2, y=(hub - 15) * PX, attrs=' pivotX="0.5" pivotY="0.5"'))

    near_parts = [(2.5, 0.05, 0.6), (1, 0.2, 2.0)]
    NE = terrain(Layer(), near_parts, 84, hexc("#2e7a32"), rim=hexc("#6ac04a"), shade=hexc("#1e5a24"), span=10)
    for _ in range(60):
        x = rnd.randrange(G)
        y = int(ridge(x, near_parts, 84)) + rnd.randrange(1, 12)
        NE.set(x, y, hexc(rnd.choice(["#ff5a5a", "#ffe04a", "#ffffff", "#c88aff", "#ff9ac8"])))

    def tufts(seed, lean):
        L, r = Layer(), random.Random(seed)
        for _ in range(40):
            x = r.randrange(G)
            b = int(ridge(x, near_parts, 84))
            for i in range(r.randint(2, 4)):
                L.set(x + (lean if i >= 2 else 0), b - i, hexc("#4aa040" if i < 2 else "#7ad05a"))
        return L
    S.add("near", 21, Img("near", NE), Img("grass_a", tufts(122, 1), blink(0, 0.9)),
          Img("grass_b", tufts(122, -1), blink(1, 0.9), preview=False, calm="hide"))

    def fly(col):
        o = sprite(["#.#", "###", "#.#"], {"#": hexc(col)})
        c = sprite(["...", ".#.", "..."], {"#": hexc(col)})
        return o, c
    bf = []
    for i, (col, x, y, ph) in enumerate([("#ff9a2a", 140, 330, 0), ("#5ab0ff", 320, 360, 2.0)]):
        o, c = fly(col)
        path = (f'<Transform target="x" value="{x} + 40 * sin({T} * {K15} + {ph})" />'
                f'<Transform target="y" value="{y} + 18 * sin({T} * {K10} + {ph} * 2)" />')
        bf += [Img(f"fly_{i}_o", o, path + blink(0, 0.15), x=x, y=y, preview=False, calm="hide"),
               Img(f"fly_{i}_c", c, path + blink(1, 0.15), x=x, y=y, preview=False, calm="hide")]
    S.add("butterflies", 23, *bf)
    return S


# ================================================================ live weather overlays
def fog_layer(S):
    L = Layer(2 * G, G)
    col = hexc(S.fog_col)
    for x in range(2 * G):
        u = 2 * math.pi * (x % G) / G
        for y in range(G):
            d = abs(y - (S.horizon + 2 + 2 * math.sin(3 * u))) / (7 + 2 * math.sin(2 * u + 1))
            a = max(0.0, 1 - d)
            if a > bayer(x, y) * 0.9:
                L.set(x, y, col[:3] + (int(60 + 110 * a),))
    return L


def add_weather(S):
    """Fill the scene's weather markers and add the front overlays (rain, snow, storm, wind)."""
    sky, fog, front = [], [], []
    k = S.key
    if "clouds" in S.wx:
        a, b, c = (hexc(S.cloud_cols[0], 235), hexc(S.cloud_cols[1], 230), hexc(S.cloud_cols[2], 225))
        sky.append(Img("wx_clouds", clouds([(14, 2.4, 0.3, (2, 1.0), (5, 0.8)), (27, 2.8, 1.9, (3, 1.2), (7, 0.7)),
                                            (40, 2.2, 3.3, (2, 1.0), (6, 0.8))], a, b, c),
                       scroll_x(), w=2 * SIZE, preview=False, only="clouds"))
        sky.append(Img("wx_overcast", clouds([(8, 3.0, 2.1, (2, 1.2), (4, 0.9)), (21, 3.2, 0.7, (3, 1.0), (5, 0.8)),
                                              (34, 3.0, 2.8, (2, 1.1), (7, 0.6))], b, c, c),
                       scroll_x(2), w=2 * SIZE, preview=False, only="overcast"))
    if "fog" in S.wx:
        fog.append(Img("wx_fog", fog_layer(S), scroll_x(), w=2 * SIZE, preview=False, only="fog"))
    rc = hexc(S.rain_col)
    if "rain" in S.wx:
        front.append(("wx_rain_far", 12, Img("wx_rain_far", falling(101, 90, 3, rc[:3] + (110,)), fall(20),
                                              preview=False, only="rain", calm="hide")))
        front.append(("wx_rain_near", 26, Img("wx_rain_near", falling(102, 40, 5, rc[:3] + (160,)), fall(30),
                                               preview=False, only="heavy", calm="hide")))
    if "snow" in S.wx:
        front.append(("wx_snow_far", 10, Img("wx_snow_far", falling(103, 70, 1, hexc("#e0ecff", 180)), fall(3),
                                              preview=False, only="snow", calm="hide")))
        front.append(("wx_snow_near", 26, Img("wx_snow_near", falling(104, 30, 2, hexc("#ffffff", 225), width=2),
                                               fall(6), preview=False, only="snow", calm="hide")))
    if "wind" in S.wx:
        front.append(("wx_wind", 24, Img("wx_wind", falling(105, 26, 7, hexc("#ffffff", 70), wide=True),
                                          scroll_x(10), w=2 * SIZE, preview=False, only="wind", calm="hide")))
    if "storm" in S.wx:
        flash = Layer()
        flash.rect(0, 0, G, G, hexc("#e8e8ff", 70))
        bolt = Layer()
        x, rnd = 30, random.Random(106)
        for y in range(4, S.horizon if S.horizon > 30 else 60):
            bolt.set(x, y, hexc("#ffffff"))
            bolt.set(x + 1, y, hexc("#c8d0ff", 160))
            if y % 4 == 0:
                x += rnd.choice((-2, -1, 1, 2))
        f = f"({T} % 9)"
        strike = f'<Transform target="alpha" value="({f} &lt; 0.12 || ({f} &gt; 0.3 &amp;&amp; {f} &lt; 0.4)) ? 255 : 0" />'
        front.append(("wx_storm", None, Img("wx_flash", flash, strike, preview=False, only="storm", calm="hide",
                                             x=-PAD, y=-PAD)))
        front.append(("wx_storm", None, Img("wx_bolt", bolt, strike, preview=False, only="storm", calm="hide",
                                             x=-PAD, y=-PAD)))
    groups = []
    for name, depth, imgs in S.groups:
        if name == "@wx_sky":
            groups.append(("wx_sky", 9, sky))
        elif name == "@wx_fog":
            groups.append(("wx_fog", 14, fog))
        else:
            groups.append((name, depth, imgs))
    merged = {}
    for name, depth, im in front:
        merged.setdefault((name, depth), []).append(im)
    S.groups = groups + [(n, d, ims) for (n, d), ims in merged.items()]


SCENES = [scene_lake, scene_frost, scene_neon, scene_dunes, scene_lighthouse, scene_koi, scene_deep, scene_meadow]


# ================================================================ pixel font
GLYPHS = {
    "0": [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "1": ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."],
    "2": [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
    "3": ["####.", "....#", "....#", ".###.", "....#", "....#", "####."],
    "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
    "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    "6": ["..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."],
    "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
    "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
    "9": [".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."],
    ":": [".", "#", ".", ".", ".", "#", "."],
    " ": ["..", "..", "..", "..", "..", "..", ".."],
    "%": ["##...", "##..#", "...#.", "..#..", ".#...", "#..##", "...##"],
    "A": [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "D": ["####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
    "F": ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
    "H": ["#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "I": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"],
    "M": ["#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"],
    "N": ["#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"],
    "O": [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "R": ["####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"],
    "S": [".####", "#....", "#....", ".###.", "....#", "....#", "####."],
    "T": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "U": ["#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "W": ["#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"],
}
NAMES = {":": "colon", " ": "space", "%": "pct"}
CLOCK_CHARS, BATT_CHARS = "0123456789: ", "0123456789%"
FACE, SHADE = hexc("#ffffff"), hexc("#14062a", 210)
CLOCK_PX, DATE_PX = 10, 4           # screen px per font pixel


def font_spec(scale, bold, color=FACE, shadow=True, dot=None, chars=None, shade=SHADE):
    return {"scale": scale, "bold": bold, "color": color, "shadow": shadow, "dot": dot,
            "chars": chars or "".join(GLYPHS), "shade": shade}


BATT_PX = 3
FONTS = {"clock": font_spec(CLOCK_PX, True, chars=CLOCK_CHARS), "date": font_spec(DATE_PX, False),
         # battery: tiny and see-through so it stays out of the way
         "batt": font_spec(BATT_PX, False, hexc("#ffffff", 140), chars=BATT_CHARS, shade=hexc("#14062a", 120))}   # AOD fonts are added per scene


def glyph_layer(ch, spec):
    """Glyph (+1px wider when bold) + 1px gap on the right (+ a hard drop shadow one pixel down)."""
    rows = GLYPHS[ch]
    b = 1 if spec["bold"] and ch != " " else 0
    L = Layer(len(rows[0]) + 1 + b, 8)
    for dy, col in ((1, spec["shade"]), (0, spec["color"])) if spec["shadow"] else ((0, spec["color"]),):
        for y, row in enumerate(rows):
            for x, c in enumerate(row):
                if c == "#":
                    for k in range(b + 1):
                        L.set(x + k, y + dy, col)
    return L


def fonts_xml():
    out = ["  <BitmapFonts>\n"]
    for font, spec in FONTS.items():
        s = spec["scale"]
        out.append(f'    <BitmapFont name="{font}">\n')
        for ch in spec["chars"]:
            L = glyph_layer(ch, spec)
            res = f"font_{font}_{NAMES.get(ch, ch.lower())}"
            L.save(res, s, spec["dot"])
            # "%" can't sit literally in a Template, so the battery template writes "#" and the font draws it as %.
            for name in [ch] + ([ch.lower()] if ch.isalpha() else []) + (["#"] if ch == "%" else []):
                out.append(f'      <Character name="{name}" resource="{res}" width="{L.w * s}" height="{L.h * s}" />\n')
        out.append("    </BitmapFont>\n")
    return "".join(out) + "  </BitmapFonts>\n"


def draw_text(buf, text, font, top):
    """Stamp text into a 450x450 RGB preview buffer, centred."""
    spec = FONTS[font]
    s, dot = spec["scale"], spec["dot"] or spec["scale"]
    lo = (s - dot) // 2
    ls = [glyph_layer(c, spec) for c in text]
    x = 225 - sum(L.w for L in ls) * s // 2 + s // 2   # centre the ink, not the trailing gap
    for L in ls:
        for gy in range(L.h):
            for gx in range(L.w):
                c = L.px[gy][gx]
                if not c:
                    continue
                a = c[3] / 255
                for yy in range(top + gy * s + lo, top + gy * s + lo + dot):
                    for xx in range(x + gx * s + lo, x + gx * s + lo + dot):
                        i = (yy * 450 + xx) * 3
                        for k in range(3):
                            buf[i + k] = int(c[k] * a + buf[i + k] * (1 - a))
        x += L.w * s


# ================================================================ always-on display
def dim(c, f, a=255):
    c = hexc(c) if isinstance(c, str) else c
    return (int(c[0] * f), int(c[1] * f), int(c[2] * f), a)


def outline(S, specs, extras=()):
    """Trace layer outlines nearest-first, skipping anything hidden behind a nearer layer.
    specs: [(layer suffix, colour)], nearest first. extras: [(suffix, keep(colour)->bool, colour, every_nth)]."""
    L = Layer()
    covered = [[False] * G for _ in range(G)]
    solid = lambda c: c is not None and c[3] >= 200
    for spec in specs:
        suffix, col = spec[0], hexc(spec[1])
        mode = spec[2] if len(spec) > 2 else "ridge"
        src = S.layer(suffix)
        if mode == "ridge":   # top silhouette only, joined into one continuous line
            tops = [next((y for y in range(G) if solid(src.get(x, y))), None) for x in range(G)]
            for x in range(1, G - 1):   # bridge 1px gaps (e.g. between buildings) so edges still get drawn
                if tops[x] is None and tops[x - 1] is not None and tops[x + 1] is not None:
                    tops[x] = max(tops[x - 1], tops[x + 1])
            prev = None
            for x in range(G):
                top = tops[x]
                if top is not None and not covered[top][x]:
                    L.set(x, top, col)
                    if prev is not None:
                        for y in range(min(prev, top) + 1, max(prev, top)):
                            if not covered[y][x if prev < top else x - 1]:
                                L.set(x if prev < top else x - 1, y, col)
                prev = top
        else:                 # full outline (round things: planet, sun, moon, signs)
            for y in range(G):
                for x in range(G):
                    if not solid(src.get(x, y)) or covered[y][x]:
                        continue
                    nbs = [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]
                    if any(0 <= nx < G and 0 <= ny < G and not solid(src.get(nx, ny)) for nx, ny in nbs):
                        L.set(x, y, col)
        for y in range(G):
            for x in range(G):
                if solid(src.get(x, y)):
                    covered[y][x] = True
    for suffix, keep, col, nth in extras:   # e.g. a few stars or lit windows, dimmed
        src, i = S.layer(suffix), 0
        for y in range(G):
            for x in range(G):
                c = src.get(x, y)
                if c and c[3] >= 200 and keep(c):
                    i += 1
                    if i % nth == 0 and (L.get(x, y) is None):
                        L.set(x, y, hexc(col) if col else dim(c, 0.45))
    return L


WHITEISH = lambda c: min(c[:3]) > 180
AOD_STYLE = {   # scene -> (clock tint, outline specs nearest-first, extras)
    "lake": ("#ffc8a0", [("near", "#3e2a64"), ("planet", "#b06e54", "outline"), ("vista", "#55367e"), ("far", "#6e3c8c")],
             [("stars_a", WHITEISH, "#5c5878", 2)]),
    "frost": ("#a8ffd0", [("near", "#4a6c8c"), ("far", "#5f84a6")],
              [("window", lambda c: c[0] > 250, "#b8862c", 1), ("stars_a", WHITEISH, "#4e6278", 2)]),
    "neon": ("#ff8ae0", [("sign", "#9c2e7c", "outline"), ("near", "#352654"), ("mid", "#5c3480"), ("far", "#3e2c58"),
                         ("moon", "#8a7cb0", "outline")],
             [("mid", lambda c: max(c[:3]) > 200, None, 3)]),
    "dunes": ("#ffc070", [("near", "#73304c"), ("mid", "#86385c"), ("far", "#9a4466"), ("sun", "#b8743c", "outline")],
              [("stars_a", WHITEISH, "#5c4a6a", 1)]),
    "lighthouse": ("#ffe6a0", [("cliff", "#35355e"), ("moon", "#8e88b8", "outline"), ("sea", "#2c3462")],
                   [("cliff", lambda c: c[0] > 240 and c[1] > 220 and c[2] < 200, "#b89448", 1),
                    ("stars_a", WHITEISH, "#4a5070", 2)]),
    "koi": ("#ffb08a", [("pads", "#2e6a3a", "outline"), ("fish", "#9a5838", "outline")], []),
    "deep": ("#8af0ff", [("kelp", "#1c5a3a", "outline"), ("floor", "#1a4a66"), ("jellies", "#8a4a8a", "outline")],
             [("biolum", lambda c: True, "#2a8a98", 1)]),
    "meadow": ("#fff0b0", [("near", "#2a5a2e"), ("turbines", "#76869a", "outline"), ("mid", "#2e5a3a"),
                           ("far", "#36566a"), ("sun", "#a89858", "outline")], []),
}


def add_aod(S):
    tint, specs, extras = AOD_STYLE[S.key]
    S.tint = tint
    S.aod = outline(S, specs, extras)
    FONTS[f"aod_{S.key}_clock"] = font_spec(CLOCK_PX, True, hexc(tint), shadow=False, dot=7, chars=CLOCK_CHARS)
    FONTS[f"aod_{S.key}_batt"] = font_spec(BATT_PX, False, dim(tint, 0.55), shadow=False, dot=2, chars=BATT_CHARS)
    FONTS[f"aod_{S.key}_date"] = font_spec(DATE_PX, False, dim(tint, 0.8), shadow=False, dot=3)


# ================================================================ assemble
CLOCK_Y, CLOCK_H = 84, 8 * CLOCK_PX
DATE_Y, DATE_H = 46, 8 * DATE_PX
BATT_Y, BATT_H = 170, 8 * BATT_PX
# The watch centres bitmap text about one font pixel left (measured on a Galaxy Watch 8), so nudge it back.


def date_xml(font):
    return (f'<PartText x="{25 + DATE_PX}" y="{DATE_Y}" width="400" height="{DATE_H}"><Text align="CENTER">'
            f'<BitmapFont family="{font}" size="{DATE_H}"><Template>%s %d<Parameter expression="[DAY_OF_WEEK_S]" />'
            f'<Parameter expression="[DAY]" /></Template></BitmapFont></Text></PartText>')


def batt_xml(font):
    return (f'<PartText x="{25 + BATT_PX}" y="{BATT_Y}" width="400" height="{BATT_H}"><Text align="CENTER">'
            f'<BitmapFont family="{font}" size="{BATT_H}"><Template>%d#<Parameter expression="[BATTERY_PERCENT]" />'
            f'</Template></BitmapFont></Text></PartText>')


def clock_xml(font):
    return (f'<DigitalClock x="{CLOCK_PX}" y="{CLOCK_Y}" width="450" height="{CLOCK_H}">'
            f'<TimeText format="h:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER" x="0" y="0" width="450" height="{CLOCK_H}">'
            f'<BitmapFont family="{font}" size="{CLOCK_H}" /></TimeText></DigitalClock>')


AUTO = '[CONFIGURATION.auto] == "TRUE"'
SCHEDULE = [   # Auto scene: (scene, from hour, to hour) - local time, 24h
    ("meadow", 5, 8), ("koi", 8, 12), ("deep", 12, 16), ("dunes", 16, 18),
    ("lighthouse", 18, 20), ("neon", 20, 22), ("lake", 22, 2), ("frost", 2, 5),
]


def watchface_xml(scenes):
    opts = "".join(f'      <ListOption id="{i}" displayName="@string/scene_{s.key}" icon="@drawable/icon_{s.key}" />\n'
                   for i, s in enumerate(scenes))
    body = "".join(f'          <ListOption id="{i}">\n{s.xml("            ")}          </ListOption>\n'
                   for i, s in enumerate(scenes))
    by_key = {s.key: s for s in scenes}
    hour = "[HOUR_0_23]"
    exprs = "".join(
        f'<Expression name="auto_{k}">{cdata(AUTO + " && " + (f"({hour} >= {a} && {hour} < {b})" if a < b else f"({hour} >= {a} || {hour} < {b})"))}</Expression>'
        for k, a, b in SCHEDULE)
    autos = "".join(f'      <Compare expression="auto_{k}"><Group name="auto_{k}" x="0" y="0" width="450" height="450">\n'
                    f'{by_key[k].xml("        ", prefix="auto_")}      </Group></Compare>\n' for k, _, _ in SCHEDULE)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by gen/gen.py - edit the generator, not this file. Watch Face Format v2. -->\n"
        '<WatchFace width="450" height="450">\n'
        '  <Metadata key="CLOCK_TYPE" value="DIGITAL" />\n'
        '  <Metadata key="PREVIEW_TIME" value="10:09:30" />\n'
        "  <UserConfigurations>\n"
        '    <ListConfiguration id="scene" displayName="@string/config_scene" defaultValue="0">\n'
        + opts + "    </ListConfiguration>\n"
        '    <BooleanConfiguration id="calm" displayName="@string/config_calm" defaultValue="FALSE" />\n'
        '    <BooleanConfiguration id="weather" displayName="@string/config_weather" defaultValue="TRUE" />\n'
        '    <BooleanConfiguration id="auto" displayName="@string/config_auto" defaultValue="FALSE" />\n'
        "  </UserConfigurations>\n"
        + fonts_xml()
        + '  <Scene backgroundColor="#ff000000">\n'
        "    <!-- Auto scene picks by the hour; otherwise the scene chosen in the editor -->\n"
        f"    <Condition><Expressions>{exprs}</Expressions>\n" + autos +
        '      <Default><Group name="picked" x="0" y="0" width="450" height="450">\n'
        '        <ListConfiguration id="scene">\n' + body + "        </ListConfiguration>\n"
        "      </Group></Default>\n    </Condition>\n"
        "    <!-- awake: date + time in the pixel font (shadow baked into the glyphs); hidden in ambient -->\n"
        '    <Group name="awake_text" x="0" y="0" width="450" height="450">\n'
        f"      {date_xml('date')}\n      {clock_xml('clock')}\n      {batt_xml('batt')}\n"
        '      <Variant mode="AMBIENT" target="alpha" value="0" />\n    </Group>\n'
        "  </Scene>\n</WatchFace>\n")


def to_png(buf, path, size=450):
    step = 450 // size
    raw = bytearray()
    for y in range(0, 450, step):
        raw.append(0)
        for x in range(0, 450, step):
            inside = math.hypot(x + step / 2 - 225, y + step / 2 - 225) <= 225
            raw += buf[(y * 450 + x) * 3:(y * 450 + x) * 3 + 3] if inside else b"\x00\x00\x00"
    write_png(path, size, size, bytes(raw), rgb=True)


def grid_buf(grid):
    buf = bytearray(450 * 450 * 3)
    for y in range(450):
        row = grid[y // PX]
        for x in range(450):
            buf[(y * 450 + x) * 3:(y * 450 + x) * 3 + 3] = bytes(row[x // PX])
    return buf


def render(scene, path, tilt=(0, 0), text=True, size=450):
    buf = grid_buf(scene.flatten(tilt))
    if text:
        draw_text(buf, "THU 19", "date", DATE_Y)
        draw_text(buf, "10:09", "clock", CLOCK_Y)
        draw_text(buf, "87%", "batt", BATT_Y)
    to_png(buf, path, size)


def render_aod(scene, path):
    c0 = PAD // PX
    grid = [[(scene.aod.px[y][x] or (0, 0, 0))[:3] for x in range(c0, c0 + 90)] for y in range(c0, c0 + 90)]
    buf = grid_buf(grid)
    draw_text(buf, "THU 19", f"aod_{scene.key}_date", DATE_Y)
    draw_text(buf, "10:09", f"aod_{scene.key}_clock", CLOCK_Y)
    draw_text(buf, "87%", f"aod_{scene.key}_batt", BATT_Y)
    lit = sum(1 for i in range(0, len(buf), 3) if max(buf[i:i + 3]) > 20) / (math.pi * 225 * 225)
    to_png(buf, path)
    return lit


def main():
    OUT.mkdir(exist_ok=True)
    for old in list(DRAW.glob("*.png")) + list(OUT.glob("*.png")) + list(OUT.glob("*.svg")):
        old.unlink()
    scenes = [f() for f in SCENES]
    Layer(1, 1).save("blank", 1)
    for s in scenes:
        add_weather(s)
        add_aod(s)
        s.save()
        render(s, DRAW / f"icon_{s.key}.png", text=False, size=225)
        render(s, OUT / f"preview_{s.key}.png")
        render(s, OUT / f"preview_{s.key}_tilt.png", tilt=(1, -0.3))
        print(f"  {s.key}: always-on lights {render_aod(s, OUT / f'aod_{s.key}.png'):.1%} of the screen")
    render(scenes[0], DRAW / "preview.png")
    (RES / "raw").mkdir(parents=True, exist_ok=True)
    (RES / "raw/watchface.xml").write_text(watchface_xml(scenes))
    (RES / "values").mkdir(parents=True, exist_ok=True)
    (RES / "values/strings.xml").write_text(
        "<resources>\n    <string name=\"face_name\">Nightfall</string>\n"
        "    <string name=\"config_scene\">Scene</string>\n"
        "    <string name=\"config_calm\">Calm (fewer animations)</string>\n"
        "    <string name=\"config_weather\">Live weather</string>\n"
        "    <string name=\"config_auto\">Auto scene (time of day)</string>\n"
        + "".join(f'    <string name="scene_{s.key}">{s.label}</string>\n' for s in scenes) + "</resources>\n")
    print(f"generated {len(scenes)} scenes, {len(list(DRAW.glob('*.png')))} images, watchface.xml")


if __name__ == "__main__":
    main()
