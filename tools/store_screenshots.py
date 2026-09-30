#!/usr/bin/env python3
"""Turn the app's renders into App Store screenshots and the site's pictures.

Run by the Screenshots workflow after ScreenshotTest, from the repository root:

  python3 tools/store_screenshots.py      (needs Pillow: pip install pillow, or apt python3-pil)

It reads docs/screenshots (the app rendered from samples/liftoff-export.csv) and writes:

  docs/store/*.png       App Store screenshots, 1290 × 2796 (the 6.7" and 6.9" iPhone size):
                         a headline, a green glow and the screen in a phone with a status bar.
  site/img/shot-*.webp   Five of them at 600 wide, for the site's "A look inside".
  site/img/hero.webp     The Lifts screen in the phone alone, on a transparent background.

Everything here is drawn by this script, the phone and its status bar included, so the pictures
are Gains' own and show only the sample export (docs/launch-plan.md, item 39). The type is
DejaVu Sans, the face the app itself renders in on the Linux runner.
"""

import pathlib
import sys

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

BACKGROUND = (11, 13, 18)          # the app's dark background
VOLT = (200, 255, 77)              # GainsColors volt, dark theme
MUTED = (138, 147, 166)            # GainsColors muted
FRAME = (30, 33, 41)
FRAME_EDGE = (62, 67, 79)
WHITE = (255, 255, 255)

FONT_DIR = pathlib.Path("/usr/share/fonts/truetype/dejavu")
BOLD = FONT_DIR / "DejaVuSans-Bold.ttf"
REGULAR = FONT_DIR / "DejaVuSans.ttf"

MARGIN = 96           # left edge of the headline
HEADLINE_TOP = 200
PHONE_TOP = 660
PHONE_WIDTH = 1060    # outside of the frame
SS = 3                # drawn this many times larger, then scaled down: smooth edges


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


def phone(render):
    """`render` on the screen of a phone, with a status bar above it. RGBA, PHONE_WIDTH wide."""
    from PIL import Image, ImageDraw

    bezel = 30
    w = PHONE_WIDTH - 2 * bezel                   # the screen
    shown = render.convert("RGB").resize((w, round(render.height * w / render.width)), Image.LANCZOS)
    bar = round(0.137 * w)                        # 54 pt of a 393 pt wide iPhone
    h = bar + shown.height
    pw, ph = PHONE_WIDTH, h + 2 * bezel
    outer = round(0.155 * w)
    inner = outer - bezel

    def s(v):
        return round(v * SS)

    art = Image.new("RGBA", (s(pw), s(ph)), (0, 0, 0, 0))
    d = ImageDraw.Draw(art)
    d.rounded_rectangle((0, 0, s(pw) - 1, s(ph) - 1), s(outer), fill=FRAME_EDGE)
    d.rounded_rectangle((s(3), s(3), s(pw - 3) - 1, s(ph - 3) - 1), s(outer - 3), fill=FRAME)
    d.rounded_rectangle((s(bezel), s(bezel), s(bezel + w) - 1, s(bezel + h) - 1), s(inner), fill=BACKGROUND + (255,))

    # Dynamic Island, then the status bar either side of it: 9:41, signal, Wi-Fi, battery.
    top = bezel + 0.03 * w
    island_w, island_h = 0.32 * w, 0.094 * w
    cx = bezel + w / 2
    d.rounded_rectangle((s(cx - island_w / 2), s(top), s(cx + island_w / 2), s(top + island_h)), s(island_h / 2), fill=(0, 0, 0, 255))
    mid = top + island_h / 2

    clock = font(BOLD, s(0.044 * w))
    d.text((s(bezel + 0.19 * w), s(mid)), "9:41", font=clock, fill=WHITE, anchor="mm")

    x = bezel + 0.705 * w                         # signal: four bars, rising
    unit = 0.0105 * w
    for i in range(4):
        bh = unit * (1.1 + 0.62 * i)
        d.rounded_rectangle((s(x), s(mid + 1.5 * unit - bh), s(x + unit * 0.95), s(mid + 1.5 * unit)), s(unit * 0.3), fill=WHITE)
        x += unit * 1.45
    x += unit * 0.9                               # Wi-Fi: three arcs and a dot, fanning up
    wx, wy = x + 1.45 * unit, mid + 1.45 * unit
    for i, r in enumerate((3.0, 2.0)):
        rr = r * unit
        d.arc((s(wx - rr), s(wy - rr), s(wx + rr), s(wy + rr)), 225, 315, fill=WHITE, width=s(unit * 0.72))
    d.pieslice((s(wx - unit), s(wy - unit), s(wx + unit), s(wy + unit)), 225, 315, fill=WHITE)
    x = wx + 3.0 * unit + unit * 0.9              # battery: outline, charge, nub
    bw, bh = 4.4 * unit, 2.2 * unit
    d.rounded_rectangle((s(x), s(mid - bh / 2), s(x + bw), s(mid + bh / 2)), s(unit * 0.7), outline=WHITE + (140,), width=s(unit * 0.2))
    pad = unit * 0.38
    d.rounded_rectangle((s(x + pad), s(mid - bh / 2 + pad), s(x + bw - pad), s(mid + bh / 2 - pad)), s(unit * 0.4), fill=WHITE)
    d.rounded_rectangle((s(x + bw + unit * 0.25), s(mid - unit * 0.4), s(x + bw + unit * 0.6), s(mid + unit * 0.4)), s(unit * 0.2), fill=WHITE + (140,))

    frame = art.resize((pw, ph), Image.LANCZOS)

    # The render fills the screen below the bar, its bottom corners rounded with the screen's.
    mask = Image.new("L", (s(pw), s(ph)), 0)
    ImageDraw.Draw(mask).rounded_rectangle((s(bezel), s(bezel), s(bezel + w) - 1, s(bezel + h) - 1), s(inner), fill=255)
    mask = mask.resize((pw, ph), Image.LANCZOS).crop((bezel, bezel + bar, bezel + w, bezel + h))
    frame.paste(shown, (bezel, bezel + bar), mask)
    return frame


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


def store_shot(render, white, volt, subtitle):
    from PIL import Image, ImageDraw

    canvas = Image.new("RGBA", (WIDTH, HEIGHT), BACKGROUND + (255,))
    canvas.alpha_composite(glow(canvas.size, (WIDTH * 0.45, PHONE_TOP - 60), 820, VOLT, 0.17))
    d = ImageDraw.Draw(canvas)
    text_width = WIDTH - 2 * MARGIN
    headline = fitted(BOLD, 128, max(white, volt, key=len), text_width)
    line = round(headline.size * 1.1)
    d.text((MARGIN, HEADLINE_TOP), white, font=headline, fill=WHITE)
    d.text((MARGIN, HEADLINE_TOP + line), volt, font=headline, fill=VOLT)
    small = fitted(REGULAR, 46, subtitle, text_width)
    d.text((MARGIN, HEADLINE_TOP + 2 * line + 40), subtitle, font=small, fill=MUTED)

    device = phone(render)
    canvas.alpha_composite(device, ((WIDTH - device.width) // 2, PHONE_TOP))
    return canvas.convert("RGB")


def main():
    from PIL import Image

    for path in (BOLD, REGULAR):
        if not path.exists():
            sys.exit(f"{path} is missing: install fonts-dejavu-core")
    STORE.mkdir(parents=True, exist_ok=True)
    for name, render, white, volt, subtitle, site in SHOTS:
        shot = store_shot(Image.open(RENDERS / f"{render}.png"), white, volt, subtitle)
        shot.save(STORE / f"{name}.png", optimize=True)
        if site:
            small = shot.resize((SITE_WIDTH, round(HEIGHT * SITE_WIDTH / WIDTH)), Image.LANCZOS)
            small.save(SITE_IMG / f"{site}.webp", quality=82, method=6)
        print(f"{name}: {render}" + (f", {site}" if site else ""))
    hero = phone(Image.open(RENDERS / f"{HERO_RENDER}.png"))
    hero = hero.resize((SITE_WIDTH, round(hero.height * SITE_WIDTH / hero.width)), Image.LANCZOS)
    hero.save(SITE_IMG / "hero.webp", quality=85, method=6)
    print(f"hero: {HERO_RENDER}, {hero.width} × {hero.height}")


if __name__ == "__main__":
    main()
