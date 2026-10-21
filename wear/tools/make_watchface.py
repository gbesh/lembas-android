"""Build the Lembas watch face (Watch Face Format) for round Wear OS watches.

Renders the still layers and sprites from wear/art/watch.html, then writes
watchface/src/main/res/raw/watchface.xml. Everything that moves is described declaratively, so
the watch's own renderer animates it (and stops in ambient mode); there is no code on the watch.

Usage: python make_watchface.py [watchface_res_dir] [wear_app_res_dir]
"""
import asyncio
import math
import random
import sys
from pathlib import Path
from xml.sax.saxutils import escape

from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "wear" / "art" / "watch.html"
RES = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "watchface" / "src" / "main" / "res"
S = 450  # the face's virtual canvas

# part -> (width, height) of the rendered PNG
PARTS = {
    "starlit-back": (450, 450), "moonglow": (120, 120),
    **{f"moon{i}": (44, 44) for i in range(8)},
    "shire-back": (450, 450), "rays": (160, 160), "sun": (180, 180), "cloud-a": (100, 40), "cloud-b": (70, 28),
    "shire-mid": (450, 450), "shire-front": (450, 450),
    "redbook-back": (450, 450), "needle": (20, 56), "fireglow": (120, 72), "flame1": (24, 40), "flame2": (24, 40),
    "wisp": (16, 56), "logs": (60, 34),
    "elven-back": (450, 450), "halo": (80, 80), "glint": (24, 24), "glyph": (32, 32),
}


def res_name(part):
    return "wf_" + part.replace("-", "_")


async def render():
    out = RES / "drawable-nodpi"
    out.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await b.new_page(viewport={"width": 450, "height": 450})
        arch = None
        for part, (w, h) in PARTS.items():
            await pg.set_viewport_size({"width": w, "height": h})
            await pg.goto(f"{ART.as_uri()}?part={part}&w={w}&h={h}")
            await pg.wait_for_timeout(120)
            await pg.screenshot(path=str(out / f"{res_name(part)}.png"), omit_background=True,
                                clip={"x": 0, "y": 0, "width": w, "height": h})
            if arch is None:
                arch = await pg.evaluate("window.ARCH")
        # the picker preview: a composed Starlit Night face is drawn by the system from the XML,
        # but Wear OS also wants a static preview image
        await pg.set_viewport_size({"width": 450, "height": 450})
        await pg.goto(f"{ART.as_uri()}?part=preview")
        await pg.wait_for_timeout(120)
        await pg.screenshot(path=str(out / "wf_preview.png"))
        await b.close()
    return arch


# ------------------------------------------------------------------ expressions
T = "([SECONDS_IN_DAY] + fract([SECOND_MILLISECOND]))"   # seconds today, smoothly


def phase(dur, off=0.0):
    return f"fract(({T} + {off:.2f}) / {dur})"


def wave(dur, off=0.0):  # 0..1..0, smoothly
    return f"(0.5 - 0.5 * cos(6.28319 * {phase(dur, off)}))"


def ease_out(p):
    return f"(1 - (1 - {p}) * (1 - {p}))"


def chain(cases, default):
    """Nested ternary: cases = [(condition, value), ...]."""
    e = default
    for cond, val in reversed(cases):
        e = f"({cond} ? {val} : {e})"
    return e


def q(s):
    return '"' + s + '"'


H = "[HOUR_0_23]"
WEEK = ["Sunday", "Monday", "Trewsday", "Hevensday", "Mersday", "Highday", "Sterday"]
MONTHS = ["Afteryule", "Solmath", "Rethe", "Astron", "Thrimidge", "Forelithe",
          "Afterlithe", "Wedmath", "Halimath", "Winterfilth", "Blotmath", "Foreyule"]
WD = chain([(f"[DAY_OF_WEEK] == {i + 1}", q(n)) for i, n in enumerate(WEEK[:-1])], q(WEEK[-1]))
QUENYA = chain([(f"({H} >= 4 && {H} <= 6)", q("Tindómë")), (f"({H} >= 7 && {H} <= 17)", q("Aurë")),
                (f"({H} >= 18 && {H} <= 20)", q("Undómë"))], q("Lómë"))
QUENYA_UP = chain([(f"({H} >= 4 && {H} <= 6)", q("TINDÓMË")), (f"({H} >= 7 && {H} <= 17)", q("AURË")),
                   (f"({H} >= 18 && {H} <= 20)", q("UNDÓMË"))], q("LÓMË"))
QUENYA_EN = chain([(f"({H} >= 4 && {H} <= 6)", q("THE STAR-DAWN")), (f"({H} >= 7 && {H} <= 17)", q("THE DAY")),
                   (f"({H} >= 18 && {H} <= 20)", q("EVENTIDE"))], q("THE NIGHT"))
WD_UP = chain([(f"[DAY_OF_WEEK] == {i + 1}", q(n.upper())) for i, n in enumerate(WEEK[:-1])], q(WEEK[-1].upper()))
MEAL = chain([(f"({H} == 5 || {H} == 6)", q("Kettle on")), (f"({H} == 7 || {H} == 8)", q("Breakfast")),
              (f"({H} == 9 || {H} == 10)", q("Second breakfast")), (f"{H} == 11", q("Elevenses")),
              (f"({H} == 12 || {H} == 13)", q("Luncheon")), (f"{H} == 14", q("A stroll in the garden")),
              (f"({H} == 15 || {H} == 16)", q("Afternoon tea")), (f"{H} == 17", q("A pint at the Green Dragon")),
              (f"({H} == 18 || {H} == 19)", q("Dinner")), (f"({H} == 20 || {H} == 21)", q("Supper"))],
             q("The Shire sleeps"))
MEAL_RB = chain([(f"({H} == 5 || {H} == 6)", q("Kettle on")), (f"({H} == 7 || {H} == 8)", q("Time for breakfast")),
                 (f"({H} == 9 || {H} == 10)", q("Time for second breakfast")), (f"{H} == 11", q("Time for elevenses")),
                 (f"({H} == 12 || {H} == 13)", q("Time for luncheon")), (f"{H} == 14", q("A stroll in the garden")),
                 (f"({H} == 15 || {H} == 16)", q("Time for afternoon tea")), (f"{H} == 17", q("A pint at the Green Dragon")),
                 (f"({H} == 18 || {H} == 19)", q("Time for dinner")), (f"({H} == 20 || {H} == 21)", q("Time for supper"))],
                q("The Shire sleeps"))

# The Shire calendar: the year starts on 1 Yule, 21 December; twelve 30-day months, two Yule
# days, and three Lithe days (four in leap years) around Mid-year's Day.
LEAP = "((([YEAR] % 4 == 0) && ([YEAR] % 100 != 0)) || ([YEAR] % 400 == 0))"
E = "(([MONTH] == 12 && [DAY] >= 21) ? ([DAY] - 21) : ([DAY_OF_YEAR] + 10))"
K = f"({E} - 2)"
L = f"({LEAP} ? 4 : 3)"
K2 = f"({K} - 180 - {L})"


def month_name(idx):
    return chain([(f"{idx} == {i}", q(n)) for i, n in enumerate(MONTHS[:-1])], q(MONTHS[-1]))


SHIRE_DAY = chain([(f"{E} == 0", "1"), (f"{E} == 1", "2"), (f"{K} < 180", f"({K} % 30 + 1)"),
                   (f"{K} < 180 + {L}", f"(({K} - 180 == 0) ? 1 : (({K} - 180 == {L} - 1) ? 2 : {q('')}))")],
                  f"({K2} % 30 + 1)")
SHIRE_NAME = chain([(f"{E} <= 1", q("Yule")), (f"{K} < 180", month_name(f"floor({K} / 30)")),
                    (f"{K} < 180 + {L}", chain([(f"({K} - 180 == 0 || {K} - 180 == {L} - 1)", q("Lithe")),
                                                (f"{K} - 180 == 1", q("Mid-year’s Day"))], q("Overlithe")))],
                   month_name(f"(6 + floor({K2} / 30))"))
HOUR = "([IS_24_HOUR_MODE] ? [HOUR_0_23] : [HOUR_1_12])"

# Which look: day (from the watch's weather, or 6am-6pm without it) before or after midday, and night
IS_DAY = "([WEATHER.IS_AVAILABLE] ? ([WEATHER.IS_DAY] ? 1 : 0) : (([HOUR_0_23] >= 6 && [HOUR_0_23] < 18) ? 1 : 0))"
MINS = "([HOUR_0_23] * 60 + [MINUTE])"
NOON = "(720 + ([IS_DAYLIGHT_SAVING_TIME] ? 60 : 0))"
LOOK_EXPR = {
    "shire": f"{IS_DAY} == 1 && {MINS} < {NOON}",
    "redbook": f"{IS_DAY} == 1 && {MINS} >= {NOON}",
    "elven": f"{IS_DAY} == 0 && ([HOUR_0_23] >= 23 || [HOUR_0_23] < 4)",
}


# ------------------------------------------------------------------ XML helpers
def a(v):
    return escape(str(v), {'"': "&quot;"})


def tr(target, value):
    return f'<Transform target="{target}" value="{a(value)}"/>'


AMBIENT_OFF = '<Variant mode="AMBIENT" target="alpha" value="0"/>'


def image(name, x, y, w, h, transforms="", extra="", pivot_y=0.5):
    return (f'<PartImage x="{round(x)}" y="{round(y)}" width="{w}" height="{h}" pivotX="0.5" pivotY="{pivot_y:g}"{extra}>'
            f'{transforms}<Image resource="{res_name(name)}"/></PartImage>')


def dot(x, y, r, color, transforms=""):
    s = math.ceil(r * 2 + 2)              # parts sit on whole pixels; the ellipse inside can be fractional
    pad = (s - r * 2) / 2
    return (f'<PartDraw x="{round(x - s / 2)}" y="{round(y - s / 2)}" width="{s}" height="{s}" pivotX="0.5" pivotY="0.5">{transforms}'
            f'<Ellipse x="{pad:.2f}" y="{pad:.2f}" width="{r * 2:.2f}" height="{r * 2:.2f}"><Fill color="{color}"/></Ellipse></PartDraw>')


def text(x, y, w, h, family, size, color, template, params, slant="NORMAL", spacing=None, weight="NORMAL", shadow=None):
    sp = f' letterSpacing="{spacing}"' if spacing is not None else ""
    deco = f'<Shadow color="{shadow}" offsetX="0" offsetY="2" radius="7"/>' if shadow else ""
    ps = "".join(f'<Parameter expression="{a(p)}"/>' for p in params)
    return (f'<PartText x="{x}" y="{y}" width="{w}" height="{h}"><Text align="CENTER" ellipsis="TRUE">'
            f'<Font family="{family}" size="{size}" color="{color}" slant="{slant}" weight="{weight}"{sp}>'
            f'{deco}<Template>{a(template)}{ps}</Template></Font></Text></PartText>')


def group(name, body, extra=""):
    return f'<Group name="{name}" x="0" y="0" width="{S}" height="{S}"{extra}>{body}</Group>'


# ------------------------------------------------------------------ the four looks
STYLE = {
    "starlit": dict(serif="cormorant", italic="cormorant_italic", label="cinzel", date="cormorant",
                    time="#FFF3EBD6", accent="#FFD9BC7A", soft="#FFB9B19C", meal="#FFF3EBD6", shadow="#99000000"),
    "redbook": dict(serif="fell", italic="fell_italic", label="fell_sc", date="fell",
                    time="#FF2B1E12", accent="#FF9A2A1C", soft="#FF6A5236", meal="#FF9A2A1C", shadow=None),
    "elven": dict(serif="cormorant", italic="cormorant_italic", label="cinzel", date="cinzel",
                  time="#FFE6ECEE", accent="#FF8E9A9E", soft="#FF8E9A9E", meal="#FFA9C6D6", shadow=None),
    "shire": dict(serif="fraunces", italic="fraunces_italic", label="alegreya_sans", date="alegreya_sans",
                  time="#FF2F4A2A", accent="#FF6A7D4E", soft="#FF6A7D4E", meal="#FF2F4A2A", shadow=None),
}


def texts(look):
    st = STYLE[look]
    label = {"starlit": ("%s · %s", [QUENYA_UP, QUENYA_EN]), "elven": ("%s", [QUENYA_UP]),
             "shire": ("%s · %s", [WD_UP, QUENYA_UP]), "redbook": ("%s", [WD_UP])}[look]
    shire = {"starlit": ("%s, %s %s", [WD, SHIRE_DAY, SHIRE_NAME]), "elven": ("%s · %s %s", [WD, SHIRE_DAY, SHIRE_NAME]),
             "shire": ("%s %s", [SHIRE_DAY, SHIRE_NAME]), "redbook": ("%s %s", [SHIRE_DAY, SHIRE_NAME])}[look]
    out = text(65, 90, 320, 26, st["label"], 14 if look != "redbook" else 17, st["accent"], label[0], label[1],
               spacing=0.22 if look != "redbook" else 0.12)
    out += text(50, 112, 350, 118, st["serif"], 98 if look != "elven" else 92, st["time"], "%s:%s", [HOUR, "[MINUTE_Z]"],
                weight="LIGHT", shadow=st["shadow"])
    out += text(35, 232, 380, 34, st["italic"], 24 if look != "elven" else 19, st["accent"] if look in ("starlit", "elven") else st["time"],
                shire[0], shire[1], slant="ITALIC")
    out += text(40, 266, 370, 24, st["date"], 16 if look != "elven" else 13, st["soft"], "%s %s %s",
                ["[DAY_OF_WEEK_F]", "[DAY]", "[MONTH_F]"], spacing=0.04 if look != "elven" else 0.16)
    out += text(60, 294, 330, 28, st["italic"], 19, st["meal"], "%s", [MEAL_RB if look == "redbook" else MEAL], slant="ITALIC")
    return group(f"{look}-text", AMBIENT_OFF + out)


def starlit(arch, southern):
    rnd = random.Random(1954)
    s = image("starlit-back", 0, 0, 450, 450)
    for i in range(16):
        while True:
            x, y = rnd.uniform(20, 430), rnd.uniform(18, 300)
            if not (90 < x < 360 and 100 < y < 330) and (x - 225) ** 2 + (y - 225) ** 2 < 205 ** 2:
                break
        r = rnd.uniform(1.0, 2.2)
        col = rnd.choice(["#FFF4F1E8", "#FFF4F1E8", "#FFFFE2B0", "#FFBFD4FF"])
        dur, off = rnd.uniform(1.8, 5.0), rnd.uniform(0, 6)
        w = wave(dur, off)
        s += dot(x, y, r, col, tr("alpha", f"30 + 225 * {w}") + tr("scaleX", f"0.6 + 0.7 * {w}") + tr("scaleY", f"0.6 + 0.7 * {w}"))
    # moon: glow, then the phase picked from the moon's age (0 new .. 4 full .. 7)
    s += image("moonglow", 280, 24, 120, 120, tr("alpha", f"190 + 65 * {wave(7)}"))
    idx = "(floor(([MOON_PHASE_POSITION] + 1.75) / 3.5) % 8)"
    flip = ' scaleX="-1"' if southern else ""
    cases = "".join(f'<Compare expression="m{i}">{image(f"moon{i}", 318, 62, 44, 44, extra=flip)}</Compare>' for i in range(1, 8))
    exprs = "".join(f'<Expression name="m{i}"><![CDATA[{idx} == {i}]]></Expression>' for i in range(1, 8))
    s += f'<Condition><Expressions>{exprs}</Expressions>{cases}<Default>{image("moon0", 318, 62, 44, 44, extra=flip)}</Default></Condition>'
    # a shooting star now and then (visible for about a second every 13 seconds)
    p = phase(13, 3)
    vis = f"(clamp(({p} - 0.86) / 0.02, 0, 1) * clamp((0.96 - {p}) / 0.08, 0, 1))"
    mv = f"clamp(({p} - 0.86) / 0.10, 0, 1)"
    s += (f'<PartDraw x="250" y="44" width="110" height="4" pivotX="0" pivotY="0.5" angle="-24">'
          f'{tr("alpha", f"255 * {vis}")}{tr("x", f"250 - 150 * {mv}")}{tr("y", f"44 + 66 * {mv}")}'
          f'<Line startX="0" startY="2" endX="110" endY="2"><Stroke color="#FFFFF4D8" thickness="1.6" cap="ROUND">'
          f'<LinearGradient startX="0" startY="2" endX="110" endY="2" colors="#F2FFF4D8 #00FFF4D8" positions="0 1"/></Stroke></Line></PartDraw>')
    return s


def shire(arch):
    s = image("shire-back", 0, 0, 450, 450)
    s += image("rays", 250, 12, 160, 160, tr("angle", f"360 * {phase(140)}"))
    s += image("sun", 240, 2, 180, 180)
    for name, y, w, h, dur, off in (("cloud-a", 150, 100, 40, 70, 20), ("cloud-b", 206, 70, 28, 95, 70), ("cloud-b", 64, 70, 28, 120, 10)):
        s += image(name, -110, y, w, h, tr("x", f"-110 + 570 * {phase(dur, off)}"))
    s += image("shire-mid", 0, 0, 450, 450)
    for i in range(3):
        p = phase(4.5, i * 1.5)
        k = ease_out(p)
        s += dot(305.5, 338, 5, "#FFFFFFFF", tr("x", f"300 + 22 * {k}") + tr("y", f"332 - 84 * {k}") +
                 tr("scaleX", f"0.6 + 1.3 * {k}") + tr("scaleY", f"0.6 + 1.3 * {k}") +
                 tr("alpha", f"150 * clamp({p} / 0.15, 0, 1) * (1 - {p})"))
    s += image("shire-front", 0, 0, 450, 450)
    return s


def redbook(arch):
    rnd = random.Random(1937)
    s = image("redbook-back", 0, 0, 450, 450)
    s += image("needle", 215, 30, 20, 56, tr("angle", f"-0.5 + 5.5 * sin(6.28319 * {phase(6)})"))
    s += image("fireglow", 165, 352, 120, 72, tr("alpha", f"150 + 80 * {wave(2.2)}"))
    for i, dx in enumerate((0, -3, 3)):
        p = phase(4.2, i * 1.4)
        k = ease_out(p)
        s += image("wisp", 217 + dx, 336, 16, 56, tr("x", f"{217 + dx} + 10 * {k}") + tr("y", f"336 - 70 * {k}") +
                   tr("scaleX", f"0.6 + 0.7 * {k}") +
                   tr("alpha", f"190 * clamp({p} / 0.18, 0, 1) * (1 - {k})"))
    for name, dur, off in (("flame1", 1.4, 0), ("flame2", 1.1, 0.5)):
        w = f"sin(6.28319 * {phase(dur, off)})"
        s += image(name, 213, 380, 24, 40, tr("scaleX", f"1 + 0.05 * {w}") + tr("scaleY", f"1 - 0.07 * {w}") +
                   tr("angle", f"-2.5 * sin(6.28319 * {phase(dur, off + 0.3)})"), pivot_y=1)
    s += image("logs", 195, 390, 60, 34)
    for i in range(9):
        x, y = rnd.uniform(70, 380), rnd.uniform(250, 420)
        dur, off = rnd.uniform(10, 19), rnd.uniform(0, 16)
        p = phase(dur, off)
        s += dot(x, y, 1.6, "#FFB08A55", tr("x", f"{x - 2.6:.1f} + 28 * {p}") + tr("y", f"{y - 2.6:.1f} - 140 * {p}") +
                 tr("alpha", f"180 * clamp({p} / 0.15, 0, 1) * clamp((1 - {p}) / 0.15, 0, 1)"))
    return s


def elven(arch):
    rnd = random.Random(1955)
    s = image("elven-back", 0, 0, 450, 450)
    ax, ay = 225, arch["apexY"] - 2
    s += image("halo", ax - 40, ay - 40, 80, 80, tr("alpha", f"140 + 115 * {wave(5)}") + tr("scaleX", f"1 + 0.18 * {wave(5)}") + tr("scaleY", f"1 + 0.18 * {wave(5)}"))
    s += image("glyph", ax - 16, ay - 16, 32, 32)
    # the glint climbs the left side to the apex and comes down the right, then rests
    R, cxL, cxR, by, sw = arch["R"], arch["cxL"], arch["cxR"], arch["baseY"], arch["sweep"]
    p = phase(8, 0)
    u = f"clamp({p} / 0.72, 0, 1)"
    left = f"({u} < 0.5)"
    angL = f"rad(180 + {sw:.3f} * ({u} * 2))"
    angR = f"rad(360 - {sw:.3f} + {sw:.3f} * ({u} * 2 - 1))"
    gx = f"({left} ? ({cxL:.2f} + {R:.2f} * cos({angL})) : ({cxR:.2f} + {R:.2f} * cos({angR}))) - 12"
    gy = f"({left} ? ({by} + {R:.2f} * sin({angL})) : ({by} + {R:.2f} * sin({angR}))) - 12"
    fade = f"(clamp({p} / 0.04, 0, 1) * clamp((0.72 - {p}) / 0.04, 0, 1))"
    s += image("glint", 0, 0, 24, 24, tr("x", gx) + tr("y", gy) + tr("alpha", f"255 * {fade}"))
    for i in range(11):
        x, y = rnd.uniform(140, 310), rnd.uniform(250, 390)
        dur, off = rnd.uniform(9, 18), rnd.uniform(0, 16)
        pp = phase(dur, off)
        al = f"clamp({pp} / 0.2, 0, 1) * clamp((1 - {pp}) / 0.2, 0, 1)"
        s += dot(x, y, 4, "#38C8E1EB", tr("y", f"{y - 5:.1f} - 170 * {pp}") + tr("alpha", f"255 * {al}"))
        s += dot(x, y, 1.4, "#FFDCEBF0", tr("y", f"{y - 2.4:.1f} - 170 * {pp}") + tr("alpha", f"230 * {al}"))
    return s


def look_group(look, arch):
    if look == "starlit":
        scene = (f'<BooleanConfiguration id="southern"><BooleanOption id="TRUE">{group("starlit-s", starlit(arch, True))}</BooleanOption>'
                 f'<BooleanOption id="FALSE">{group("starlit-n", starlit(arch, False))}</BooleanOption></BooleanConfiguration>')
    else:
        scene = {"shire": shire, "redbook": redbook, "elven": elven}[look](arch)
    scene_g = f'<Group name="{look}-scene" x="0" y="0" width="{S}" height="{S}">{AMBIENT_OFF}{scene}</Group>'
    return group(look, scene_g + texts(look))


def ambient_texts():
    """The always-on display: just the time and the day, quiet grey on black."""
    out = text(65, 96, 320, 24, "cinzel", 13, "#FF8A8A8A", "%s", [QUENYA_UP], spacing=0.22)
    out += text(50, 112, 350, 118, "cormorant", 98, "#FFC8C8C8", "%s:%s", [HOUR, "[MINUTE_Z]"], weight="LIGHT")
    out += text(35, 232, 380, 34, "cormorant_italic", 22, "#FF8A8A8A", "%s %s", [SHIRE_DAY, SHIRE_NAME], slant="ITALIC")
    return f'<Group name="ambient" x="0" y="0" width="{S}" height="{S}" alpha="0"><Variant mode="AMBIENT" target="alpha" value="255"/>{out}</Group>'


def complication(slot, x, y, provider):
    w = h = 64
    pill = (f'<PartDraw x="0" y="0" width="{w}" height="{h}"><Ellipse x="1" y="1" width="{w - 2}" height="{h - 2}">'
            f'<Stroke color="#66FFFFFF" thickness="1"/><Fill color="#59101418"/></Ellipse></PartDraw>')
    short = (pill + f'<PartImage x="22" y="9" width="20" height="20" tintColor="#FFF3EBD6"><Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/></PartImage>'
             + text(4, 28, 56, 24, "alegreya_sans", 17, "#FFF3EBD6", "%s", ["[COMPLICATION.TEXT]"]))
    ranged = (pill + f'<PartDraw x="0" y="0" width="{w}" height="{h}"><Arc centerX="32" centerY="32" width="58" height="58" startAngle="0" endAngle="360">'
              f'<Stroke color="#33FFFFFF" thickness="3" cap="ROUND"/></Arc></PartDraw>'
              f'<PartDraw x="0" y="0" width="{w}" height="{h}"><Arc centerX="32" centerY="32" width="58" height="58" startAngle="0" endAngle="360">'
              f'{tr("endAngle", "360 * clamp(([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / ([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]), 0, 1)")}'
              f'<Stroke color="#FFD9BC7A" thickness="3" cap="ROUND"/></Arc></PartDraw>'
              + text(4, 20, 56, 24, "alegreya_sans", 17, "#FFF3EBD6", "%s", ["[COMPLICATION.TEXT]"]))
    return (f'<ComplicationSlot slotId="{slot}" name="slot{slot}" displayName="{"Left" if slot == 0 else "Right"}" x="{x}" y="{y}" width="{w}" height="{h}" '
            f'supportedTypes="SHORT_TEXT RANGED_VALUE EMPTY" isCustomizable="TRUE">'
            f'<DefaultProviderPolicy defaultSystemProvider="{provider}" defaultSystemProviderType="SHORT_TEXT"/>'
            f'<BoundingOval x="0" y="0" width="{w}" height="{h}"/>'
            f'<Complication type="SHORT_TEXT">{short}</Complication><Complication type="RANGED_VALUE">{ranged}</Complication>'
            f'<Complication type="EMPTY"/></ComplicationSlot>')


def watchface_xml(arch):
    auto_exprs = "".join(f'<Expression name="{k}"><![CDATA[{v}]]></Expression>' for k, v in LOOK_EXPR.items())
    auto = (f'<Condition><Expressions>{auto_exprs}</Expressions>'
            + "".join(f'<Compare expression="{k}">{look_group(k, arch)}</Compare>' for k in LOOK_EXPR)
            + f'<Default>{look_group("starlit", arch)}</Default></Condition>')
    options = f'<ListOption id="auto">{group("auto", auto)}</ListOption>'
    for k in ("shire", "redbook", "starlit", "elven"):
        options += f'<ListOption id="{k}">{look_group(k, arch)}</ListOption>'
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<WatchFace width="{S}" height="{S}" clipShape="CIRCLE">'
            '<Metadata key="CLOCK_TYPE" value="DIGITAL"/><Metadata key="PREVIEW_TIME" value="10:08:32"/>'
            '<UserConfigurations>'
            '<ListConfiguration id="look" displayName="Look" defaultValue="auto">'
            '<ListOption id="auto" displayName="Follow the sun"/><ListOption id="shire" displayName="Shire Morning"/>'
            '<ListOption id="redbook" displayName="Red Book"/><ListOption id="starlit" displayName="Starlit Night"/>'
            '<ListOption id="elven" displayName="Elven Silver"/></ListConfiguration>'
            '<BooleanConfiguration id="southern" displayName="Southern hemisphere moon" defaultValue="FALSE"/>'
            '</UserConfigurations>'
            '<Scene backgroundColor="#FF000000">'
            f'<ListConfiguration id="look">{options}</ListConfiguration>'
            + ambient_texts()
            + complication(0, 22, 140, "WATCH_BATTERY") + complication(1, 364, 140, "STEP_COUNT")
            + '</Scene></WatchFace>\n')


def compose_app_backgrounds(wear_res):
    """Whole still scenes for the watch app and tile: the face's layers and sprites, flattened."""
    from PIL import Image
    src = RES / "drawable-nodpi"
    out = Path(wear_res) / "drawable-nodpi"
    out.mkdir(parents=True, exist_ok=True)
    layouts = {
        "shire": [("shire-back", 0, 0), ("rays", 250, 12), ("sun", 240, 2), ("cloud-a", 30, 150), ("cloud-b", 300, 206),
                  ("shire-mid", 0, 0), ("shire-front", 0, 0)],
        "redbook": [("redbook-back", 0, 0), ("needle", 215, 30), ("fireglow", 165, 352), ("flame1", 213, 380),
                    ("flame2", 213, 380), ("logs", 195, 390)],
        "starlit": [("starlit-back", 0, 0), ("moonglow", 280, 24), ("moon3", 318, 62)],
        "elven": [("elven-back", 0, 0), ("halo", 185, 2), ("glyph", 209, 26)],
    }
    for look, parts in layouts.items():
        canvas = Image.new("RGBA", (S, S), (0, 0, 0, 255))
        for name, x, y in parts:
            canvas.alpha_composite(Image.open(src / f"{res_name(name)}.png").convert("RGBA"), (x, y))
        canvas.convert("RGB").save(out / f"wl_{look}.png", optimize=True)
    Image.open(out / "wl_starlit.png").save(out / "tile_preview.png")


async def main():
    arch = await render()
    if len(sys.argv) > 2:
        compose_app_backgrounds(sys.argv[2])
    raw = RES / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    xml = watchface_xml(arch)
    (raw / "watchface.xml").write_text(xml, encoding="utf-8")
    print(f"watchface.xml: {len(xml) // 1024} KB, {len(PARTS)} images")


if __name__ == "__main__":
    asyncio.run(main())
