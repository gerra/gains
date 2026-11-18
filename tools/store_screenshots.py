#!/usr/bin/env python3
"""Turn the app's renders into App Store screenshots and the site's pictures.

Run by the Screenshots workflow after ScreenshotTest, from the repository root:

  python3 tools/store_screenshots.py      (needs Pillow, and 7-Zip to unpack Apple's bezel)

It reads docs/screenshots (the app rendered from samples/liftoff-export.csv) and writes:

  docs/store/*.png       App Store screenshots, 1290 × 2796 (the 6.7" and 6.9" iPhone size):
                         a headline, a green glow and the screen in an iPhone 17 Pro.
  site/img/shot-*.webp   Five of them at 600 wide, for the site's "A look inside".
  site/img/hero.webp     The Lifts screen in the phone alone, on a transparent background.

The phone is Apple's own product bezel (developer.apple.com/design/resources), which Apple's
marketing guidelines ask for in place of a drawn one and which must be used as it is: no tilt,
crop, shadow or reflection. Its license doesn't allow the bezel itself to be redistributed, so it
is never committed: the first run downloads Apple's disk image into build/bezel and unpacks the
one PNG used. Set GAINS_BEZEL to a PNG of the bezel to skip the download.

ScreenshotTest renders the iPhone 17 Pro's screen less its status bar and home indicator
(402 × 778 pt at 3×); this script draws those back around the render: the time, full signal,
full Wi-Fi and a full battery, as the guidelines ask. The renders show only the sample export
(docs/launch-plan.md, item 39). The type is the app's own, from composeApp's resources: Oswald
for the headline, Onest for the line under it and the clock.
"""

import os
import pathlib
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
RENDERS = ROOT / "docs/screenshots"
STORE = ROOT / "docs/store"
SITE_IMG = ROOT / "site/img"

# (store file, render, headline in white, headline in volt, subtitle, site file or None)
SHOTS = [
    ("01-lifts", "05-lifts", "Every lift,", "trended.",
     "Best set, sessions and % change at a glance.", None),
    ("02-progress", "03-home", "Keep the", "streak going.",
     "Your week, your level and what's moving.", "shot-home"),
    ("03-workout", "14-program-day", "Tap. Rest.", "Repeat.",
     "Warm-ups, working sets and the rest timer on one card.", "shot-workout"),
    ("04-programs", "12-programs", "A program", "that fits.",
     "GZCLP, 5/3/1 for Beginners and more, best fit first.", "shot-programs"),
    ("05-volume", "07-volume", "Volume on", "the body.",
     "Working sets per muscle group, per week.", "shot-volume"),
    ("06-summary", "17-summary", "Every workout,", "scored.",
     "Records, points and your level when you finish.", "shot-summary"),
]
HERO_RENDER = "05-lifts"

WIDTH, HEIGHT = 1290, 2796
SITE_WIDTH = 600

BACKGROUND = (18, 20, 22)          # the app's dark background (Theme.kt, Graphite)
VOLT = (98, 217, 154)              # GainsColors volt, dark theme: the progress green
MUTED = (167, 172, 179)            # GainsColors muted
WHITE = (242, 240, 235)            # Chalk

FONT_DIR = ROOT / "composeApp/src/commonMain/composeResources/font"
BOLD = FONT_DIR / "oswald_semibold.ttf"
REGULAR = FONT_DIR / "onest_regular.ttf"
SEMIBOLD = FONT_DIR / "onest_semibold.ttf"

MARGIN = 96           # left edge of the headline
HEADLINE_TOP = 170
PHONE_TOP = 690
PHONE_WIDTH = 980     # the bezel's width on the store screenshot; it stands 2004 tall, 102 px off the bottom
SS = 4                # the status bar is drawn this many times larger, then scaled down: smooth edges

# Apple's iPhone 17 bezels and the one used. The PNG's screen opening is the 17 Pro's 1206 × 2622.
BEZEL_URL = "https://devimages-cdn.apple.com/design/resources/download/Bezel-iPhone-17.dmg"
BEZEL_MEMBER = "Bezel-iPhone-17/PNG/iPhone 17 Pro/iPhone 17 Pro - Silver - Portrait.png"
BEZEL_CACHE = ROOT / "build/bezel"
SCALE = 3             # pixels per point on the 17 Pro
SCREEN_W, SCREEN_H = 402 * SCALE, 874 * SCALE
STATUS_BAR = 62 * SCALE          # the top safe area, which ScreenshotTest leaves out of the render
HOME_INDICATOR = 34 * SCALE      # the bottom one


def font(path, size):
    from PIL import ImageFont
    return ImageFont.truetype(str(path), size)


def fitted(path, size, text, width):
    """The font at `size`, or smaller until `text` fits in `width`."""
    while True:
        f = font(path, size)
        if f.getlength(text) <= width or size <= 12:
            return f
        size -= 2


def bezel_path():
    """Apple's bezel PNG: $GAINS_BEZEL, or downloaded once into build/bezel and unpacked with 7-Zip."""
    if os.environ.get("GAINS_BEZEL"):
        return pathlib.Path(os.environ["GAINS_BEZEL"])
    png = BEZEL_CACHE / BEZEL_MEMBER
    if png.exists():
        return png
    BEZEL_CACHE.mkdir(parents=True, exist_ok=True)
    dmg = BEZEL_CACHE / "bezel.dmg"
    if not dmg.exists():
        print(f"downloading {BEZEL_URL}")
        urllib.request.urlretrieve(BEZEL_URL, dmg)
    subprocess.run(["7z", "x", "-y", f"-o{BEZEL_CACHE}", str(dmg), BEZEL_MEMBER],
                   check=True, stdout=subprocess.DEVNULL)
    if not png.exists():
        sys.exit(f"{BEZEL_MEMBER} is not in {BEZEL_URL}")
    return png


class Bezel:
    """The bezel and where its screen is: the transparent opening around the middle of the PNG."""

    def __init__(self, path):
        from PIL import Image, ImageDraw

        self.image = Image.open(path).convert("RGBA")
        w, h = self.image.size
        # The opening is the transparent region joined to the middle; everything outside the phone is
        # transparent too, but the frame keeps it apart. Flood it to find the opening and its mask.
        alpha = self.image.getchannel("A").point(lambda a: 255 if a > 0 else 0)
        ImageDraw.floodfill(alpha, (w // 2, h // 2), 128)
        self.mask = alpha.point(lambda a: 255 if a == 128 else 0)
        x0, y0, x1, y1 = self.mask.getbbox()
        if (x1 - x0, y1 - y0) != (SCREEN_W, SCREEN_H):
            sys.exit(f"the bezel's screen is {x1 - x0} × {y1 - y0}, not the 17 Pro's {SCREEN_W} × {SCREEN_H}")
        self.origin = (x0, y0)
        # The Dynamic Island is the opaque shape at the top of the opening; the status bar centres on it.
        # Walk out from the middle of the screen's top, across and down, to the island's edges.
        top = self.mask.crop((x0, y0, x1, y0 + STATUS_BAR))
        mid = SCREEN_W // 2
        rows = [y for y in range(STATUS_BAR) if top.getpixel((mid, y)) == 0]
        if not rows:
            sys.exit("found no Dynamic Island at the top of the bezel's screen")
        y = (rows[0] + rows[-1]) // 2
        left = right = mid
        while left > 0 and top.getpixel((left - 1, y)) == 0:
            left -= 1
        while right < SCREEN_W - 1 and top.getpixel((right + 1, y)) == 0:
            right += 1
        self.island = (left, rows[0], right, rows[-1])


def status_bar(color, island):
    """The status bar at 9:41 with full signal, Wi-Fi and battery, in `color`: RGBA, SCREEN_W × STATUS_BAR.

    Laid out as iOS lays it out on the 17 Pro: the time centred in the space left of the Dynamic
    Island, the three indicators centred in the space right of it, both on the island's middle.
    """
    from PIL import Image, ImageDraw

    def s(v):                          # points to pixels on the supersampled layer
        return round(v * SCALE * SS)

    layer = Image.new("L", (SCREEN_W * SS, STATUS_BAR * SS), 0)
    d = ImageDraw.Draw(layer)
    il, it, ir, ib = island            # in pixels on the screen
    mid = (it + ib) / 2 / SCALE        # in points from the top of the screen
    left_centre, right_centre = il / 2 / SCALE, (ir + SCREEN_W) / 2 / SCALE

    clock = font(SEMIBOLD, s(17.5))
    d.text((s(left_centre), s(mid)), "9:41", font=clock, fill=255, anchor="mm")

    # Signal, Wi-Fi and battery: 17 + 6 + 15.5 + 6 + 27.5 points wide.
    x = right_centre - (17 + 6 + 15.5 + 6 + 27.5) / 2
    bottom = mid + 5.6
    for i, bh in enumerate((4.2, 6.6, 9.0, 11.2)):        # four rising bars, all lit
        bx = x + i * 4.6
        d.rounded_rectangle((s(bx), s(bottom - bh), s(bx + 3.2), s(bottom)), s(1.0), fill=255)
    x += 17 + 6

    # Wi-Fi: a dot and two arcs fanning up from one point, cut from a 90° wedge.
    cx, cy = x + 7.75, mid + 5.6
    wedge = Image.new("L", layer.size, 0)
    w = ImageDraw.Draw(wedge)
    for r, fill in ((11.0, 255), (8.7, 0), (7.2, 255), (4.9, 0), (3.4, 255)):
        w.pieslice((s(cx - r), s(cy - r), s(cx + r), s(cy + r)), 225, 315, fill=fill)
    layer.paste(255, (0, 0), wedge)
    x += 15.5 + 6

    # Battery: the outline at 35 %, a full charge inside it and the cap on the right.
    top, bh, bw = mid - 6.25, 12.5, 25.0
    outline = Image.new("L", layer.size, 0)
    o = ImageDraw.Draw(outline)
    o.rounded_rectangle((s(x), s(top), s(x + bw), s(top + bh)), s(4.0), outline=90, width=s(1.1))
    o.rounded_rectangle((s(x + bw + 1.2), s(mid - 2.2), s(x + bw + 2.6), s(mid + 2.2)), s(0.8), fill=90)
    layer.paste(outline, (0, 0), outline)
    d.rounded_rectangle((s(x + 2.1), s(top + 2.1), s(x + bw - 2.1), s(top + bh - 2.1)), s(2.2), fill=255)

    layer = layer.resize((SCREEN_W, STATUS_BAR), Image.LANCZOS)
    out = Image.new("RGBA", layer.size, color + (0,))
    out.putalpha(layer)
    return out


def screen(render, island):
    """`render` as the 17 Pro's whole screen: the status bar above it and the home indicator below."""
    from PIL import Image, ImageDraw

    render = render.convert("RGB")
    if render.size != (SCREEN_W, SCREEN_H - STATUS_BAR - HOME_INDICATOR):
        sys.exit(f"a render is {render.width} × {render.height}: ScreenshotTest should render the 17 Pro's "
                 f"{SCREEN_W} × {SCREEN_H - STATUS_BAR - HOME_INDICATOR} (402 × 778 pt at 3×)")
    # The app pads its top bar and its tabs clear of the system's, so the safe areas show its background.
    background = render.getpixel((2, 2))
    ink = (255, 255, 255) if sum(background) < 384 else (0, 0, 0)
    out = Image.new("RGBA", (SCREEN_W, SCREEN_H), background + (255,))
    out.paste(render, (0, STATUS_BAR))
    out.alpha_composite(status_bar(ink, island))
    # The home indicator: 139 × 5 pt, 8 pt off the bottom.
    pill = Image.new("L", (SCREEN_W * SS, HOME_INDICATOR * SS), 0)
    pw, ph, gap = 139 * SCALE * SS, 5 * SCALE * SS, 8 * SCALE * SS
    left, top = (pill.width - pw) // 2, pill.height - gap - ph
    ImageDraw.Draw(pill).rounded_rectangle((left, top, left + pw, top + ph), ph // 2, fill=255)
    pill = pill.resize((SCREEN_W, HOME_INDICATOR), Image.LANCZOS)
    out.paste(ink + (255,), (0, SCREEN_H - HOME_INDICATOR), pill)
    return out


def phone(render, bezel):
    """`render` on the screen of Apple's iPhone 17 Pro bezel, the bezel as Apple draws it. RGBA."""
    from PIL import Image

    art = Image.new("RGBA", bezel.image.size, (0, 0, 0, 0))
    shown = screen(render, bezel.island)
    x0, y0 = bezel.origin
    art.paste(shown, (x0, y0), bezel.mask.crop((x0, y0, x0 + SCREEN_W, y0 + SCREEN_H)))
    art.alpha_composite(bezel.image)
    return art


def glow(size, center, radius, color, strength):
    """A soft round light of `color`, as an RGBA layer."""
    from PIL import Image, ImageDraw, ImageFilter

    small = 8                                     # blur a small image and scale it up: fast and smooth
    layer = Image.new("L", (size[0] // small, size[1] // small), 0)
    cx, cy, r = center[0] / small, center[1] / small, radius / small
    ImageDraw.Draw(layer).ellipse((cx - r, cy - r * 0.6, cx + r, cy + r * 0.6), fill=round(255 * strength))
    layer = layer.filter(ImageFilter.GaussianBlur(r * 0.55)).resize(size, Image.BICUBIC)
    out = Image.new("RGBA", size, color + (0,))
    out.putalpha(layer)
    return out


def store_shot(render, bezel, white, volt, subtitle):
    from PIL import Image, ImageDraw

    canvas = Image.new("RGBA", (WIDTH, HEIGHT), BACKGROUND + (255,))
    canvas.alpha_composite(glow(canvas.size, (WIDTH * 0.45, PHONE_TOP - 60), 820, VOLT, 0.17))
    d = ImageDraw.Draw(canvas)
    text_width = WIDTH - 2 * MARGIN
    headline = fitted(BOLD, 150, max(white, volt, key=len), text_width)
    line = round(headline.size * 1.1)
    d.text((MARGIN, HEADLINE_TOP), white, font=headline, fill=WHITE)
    d.text((MARGIN, HEADLINE_TOP + line), volt, font=headline, fill=VOLT)
    small = fitted(REGULAR, 46, subtitle, text_width)
    d.text((MARGIN, HEADLINE_TOP + 2 * line + 64), subtitle, font=small, fill=MUTED)

    device = phone(render, bezel)
    device = device.resize((PHONE_WIDTH, round(device.height * PHONE_WIDTH / device.width)), Image.LANCZOS)
    canvas.alpha_composite(device, ((WIDTH - device.width) // 2, PHONE_TOP))
    return canvas.convert("RGB")


def main():
    from PIL import Image

    for path in (BOLD, REGULAR, SEMIBOLD):
        if not path.exists():
            sys.exit(f"{path} is missing")
    bezel = Bezel(bezel_path())
    STORE.mkdir(parents=True, exist_ok=True)
    for name, render, white, volt, subtitle, site in SHOTS:
        shot = store_shot(Image.open(RENDERS / f"{render}.png"), bezel, white, volt, subtitle)
        shot.save(STORE / f"{name}.png", optimize=True)
        if site:
            small = shot.resize((SITE_WIDTH, round(HEIGHT * SITE_WIDTH / WIDTH)), Image.LANCZOS)
            small.save(SITE_IMG / f"{site}.webp", quality=82, method=6)
        print(f"{name}: {render}" + (f", {site}" if site else ""))
    hero = phone(Image.open(RENDERS / f"{HERO_RENDER}.png"), bezel)
    hero = hero.resize((SITE_WIDTH, round(hero.height * SITE_WIDTH / hero.width)), Image.LANCZOS)
    hero.save(SITE_IMG / "hero.webp", quality=85, method=6)
    print(f"hero: {HERO_RENDER}, {hero.width} × {hero.height}")


if __name__ == "__main__":
    main()
