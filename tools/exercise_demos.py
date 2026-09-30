#!/usr/bin/env python3
"""
Fetches the start/end drawings for the built-in exercises from Everkinetic (CC BY-SA 4.0,
https://github.com/everkinetic/data), turns them into ink on a transparent canvas so the app can
tint them to its theme, and writes them under
composeApp/src/commonMain/composeResources/files/exercises/<catalogue id>/{0,1}.webp, plus the
Kotlin table (ExerciseDemos.kt) that says which exercises have a demo and which drawing it is.

Run from the repository root:  python3 tools/exercise_demos.py   (needs Pillow: pip install pillow)

MATCHES is written by hand: an exercise gets a drawing only when Everkinetic draws that exercise,
not a near relative, so most of the catalogue keeps the video search alone. The drawings are
adapted (cropped together, scaled, their white turned transparent), so the files written here are
under CC BY-SA 4.0 like their source; NOTICE.md and the Open-source licenses screen say so.
"""
import http.client, io, json, os, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageChops, ImageStat

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "composeApp/src/commonMain/composeResources/files/exercises")
KOTLIN = os.path.join(ROOT, "composeApp/src/commonMain/kotlin/app/gains/ui/demo/ExerciseDemos.kt")
BASE = "https://raw.githubusercontent.com/everkinetic/data/main/dist"
WIDTH, HEIGHT = 480, 360
MARGIN = 12
QUALITY = 80
IN_PLACE = 0.8  # share of the best overlap at which a pair counts as drawn in place

# catalogue id -> Everkinetic id.
MATCHES = {
    "ab_wheel": "0286",
    "back_extension": "0103",
    "back_extension_iso": "0105",
    "band_calf_raise": "0274",
    "band_chest_fly": "0050",
    "band_reverse_fly": "0020",
    "band_squat": "0155",
    "band_upright_row": "0094",
    "barbell_curl": "0211",
    "barbell_floor_press": "0070",
    "barbell_hack_squat": "0125",
    "barbell_overhead_triceps_extension": "0201",
    "barbell_pullover": "0045",
    "barbell_rear_delt_row": "0028",
    "bench_dip": "0162",
    "bench_press": "0042",
    "bicep_curl_machine": "0253",
    "bodyweight_glute_bridge": "0109",
    "box_squat": "0133",
    "cable_curl": "0212",
    "cable_fly": "0048",
    "cable_hip_adduction": "0135",
    "cable_internal_rotation": "0034",
    "cable_kickback": "0112",
    "cable_shrug": "0009",
    "calf_raise": "0282",
    "chest_fly": "0056",
    "chest_press_machine": "0066",
    "chest_supported_rear_delt_raise": "0032",
    "close_grip_bench_press": "0049",
    "close_grip_push_up": "0188",
    "concentration_curl": "0220",
    "crunch": "0291",
    "cuban_press": "0300",
    "db_bench_press": "0055",
    "db_curl": "0224",
    "db_incline_bench_press": "0061",
    "db_pullover": "0079",
    "deadlift": "0099",
    "decline_bench_press": "0051",
    "decline_chest_press_machine": "0085",
    "decline_dumbbell_bench_press": "0052",
    "decline_dumbbell_fly": "0053",
    "decline_push_up": "0075",
    "dip": "0054",
    "dip_machine": "0171",
    "donkey_calf_raise": "0275",
    "drag_curl": "0222",
    "dumbbell_side_bend": "0294",
    "dumbbell_skull_crusher": "0181",
    "dumbbell_upright_row": "0016",
    "flutter_kicks": "0116",
    "front_raise": "0033",
    "front_squat": "0138",
    "good_morning": "0101",
    "hack_squat": "0123",
    "hammer_curl": "0227",
    "hip_abduction": "0156",
    "hip_adduction": "0157",
    "incline_bench_press": "0043",
    "incline_cable_fly": "0060",
    "incline_curl": "0214",
    "incline_db_fly": "0062",
    "inverted_row": "0086",
    "isometric_neck_hold": "0001",
    "jefferson_squat": "0141",
    "jm_press": "0175",
    "lateral_raise": "0018",
    "leg_curl": "0117",
    "leg_extension": "0142",
    "leg_press": "0127",
    "lunge": "0115",
    "neck_side": "0002",
    "neutral_grip_incline_dumbbell_press": "0059",
    "neutral_grip_pull_up": "0090",
    "oblique_crunch": "0293",
    "overhead_squat": "0151",
    "overhead_triceps_extension": "0198",
    "preacher_curl": "0239",
    "pull_up": "0087",
    "push_up": "0077",
    "reverse_calf_raise": "0280",
    "reverse_crunch": "0287",
    "reverse_curl": "0257",
    "reverse_grip_barbell_row": "0026",
    "reverse_grip_bench_press": "0190",
    "reverse_grip_lat_pulldown": "0093",
    "reverse_grip_pushdown": "0189",
    "romanian_deadlift": "0118",
    "seated_barbell_overhead_press": "0004",
    "seated_cable_row": "0025",
    "seated_calf_raise": "0279",
    "shrug": "0005",
    "side_plank": "0113",
    "side_split_squat": "0131",
    "sissy_squat": "0158",
    "skull_crusher": "0183",
    "smith_machine_bench_press": "0078",
    "smith_machine_close_grip_bench_press": "0195",
    "smith_machine_incline_bench_press": "0081",
    "smith_squat": "0124",
    "spider_curl": "0225",
    "squat": "0122",
    "step_up": "0137",
    "straight_arm_pulldown": "0092",
    "sumo_squat": "0152",
    "t_bar_row": "0029",
    "tate_press": "0203",
    "triceps_extension_machine": "0210",
    "triceps_kickback": "0204",
    "triceps_pushdown": "0205",
    "upright_row": "0014",
    "v_bar_pulldown": "0096",
    "wide_grip_bench_press": "0082",
    "zercher_squat": "0161",
    "zottman_curl": "0251",
}


def fetch(url, attempts=4):
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read()
        except (OSError, http.client.HTTPException):
            if attempt == attempts - 1:
                raise
            time.sleep(2 ** attempt)


def ink(image):
    """Black lines on white -> the lines' darkness as alpha, so the app can draw them in any colour."""
    gray = image.convert("L")
    alpha = gray.point(lambda v: 0 if v > 245 else 255 - v)
    out = Image.new("LA", gray.size, 0)
    out.putalpha(alpha)
    return out


def adapt(frames):
    """Crops every frame to the box that holds all of them, so the figure stays put between frames, and fits it on one canvas."""
    frames = [ink(f) for f in frames]
    size = (max(f.width for f in frames), max(f.height for f in frames))
    frames = [f if f.size == size else _place(f, size, 0, 0) for f in frames]
    # Some pairs are drawn at different places on their canvas; line the later frames up with the first,
    # on a canvas grown by the moves so that nothing is pushed off its edge.
    moves = [(0, 0)] + [_offset(frames[0], f) for f in frames[1:]]
    left, top = max(0, -min(dx for dx, _ in moves)), max(0, -min(dy for _, dy in moves))
    grown = (size[0] + left + max(0, max(dx for dx, _ in moves)), size[1] + top + max(0, max(dy for _, dy in moves)))
    frames = [_place(f, grown, left + dx, top + dy) for f, (dx, dy) in zip(frames, moves)]
    box = None
    for f in frames:
        b = f.getchannel("A").getbbox()
        box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
    scale = min((WIDTH - 2 * MARGIN) / (box[2] - box[0]), (HEIGHT - 2 * MARGIN) / (box[3] - box[1]))
    result = []
    for f in frames:
        f = f.crop(box).convert("RGBA")
        f = f.resize((round(f.width * scale), round(f.height * scale)), Image.LANCZOS)
        canvas = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        canvas.paste(f, ((WIDTH - f.width) // 2, (HEIGHT - f.height) // 2))
        result.append(canvas)
    return result


def _overlap(a, b, dx, dy):
    """How much ink b, moved by (dx, dy), shares with a."""
    moved = b.crop((-dx, -dy, b.width - dx, b.height - dy))
    return ImageStat.Stat(ImageChops.multiply(a, moved)).sum[0]


def _offset(first, other):
    """
    The move that puts the most of other's ink on first's: searched on small copies, then refined at
    full size. Most pairs are drawn in place already, and there the body moving would pull the
    search off the floor or the bar, so a pair that already shares most of its best overlap stays put.
    """
    a, b = first.getchannel("A"), other.getchannel("A")
    step = max(1, a.width // 100)
    small = (a.width // step, a.height // step)
    sa, sb = a.resize(small, Image.BOX), b.resize(small, Image.BOX)
    reach_x, reach_y = small[0] // 3, small[1] // 3
    best, dx, dy = max((_overlap(sa, sb, x, y), x, y) for x in range(-reach_x, reach_x + 1) for y in range(-reach_y, reach_y + 1))
    if _overlap(sa, sb, 0, 0) >= IN_PLACE * best:
        return 0, 0
    _, dx, dy = max(
        (_overlap(a, b, dx * step + x, dy * step + y), dx * step + x, dy * step + y)
        for x in range(-step, step + 1, max(1, step // 4)) for y in range(-step, step + 1, max(1, step // 4))
    )
    return dx, dy


def _place(image, size, x, y):
    canvas = Image.new("LA", size, 0)
    canvas.paste(image, (x, y))
    return canvas


def main():
    db = {e["id"]: e for e in json.loads(fetch(f"{BASE}/exercises.json"))}
    for id_, source in MATCHES.items():
        if source not in db or len(db[source]["png"]) < 2:
            sys.exit(f"{id_}: Everkinetic {source} has no start and end drawing")

    def process(item):
        id_, source = item
        pngs = db[source]["png"]
        # Start and end: Cuban press has a middle drawing too, and the loop shows only two.
        frames = adapt([Image.open(io.BytesIO(fetch(f"{BASE}/{p}"))) for p in (pngs[0], pngs[-1])])
        target = os.path.join(OUT_DIR, id_)
        os.makedirs(target, exist_ok=True)
        for n, frame in enumerate(frames):
            frame.save(os.path.join(target, f"{n}.webp"), "WEBP", quality=QUALITY, method=6)
        return id_

    with ThreadPoolExecutor(16) as pool:
        for _ in pool.map(process, sorted(MATCHES.items())):
            pass

    total = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(OUT_DIR) for f in fs)
    print(f"{len(MATCHES)} exercises, {total / 1e6:.1f} MB of drawings under {os.path.relpath(OUT_DIR, ROOT)}")

    with open(KOTLIN, "w") as k:
        k.write("package app.gains.ui.demo\n\n")
        k.write("// Generated by tools/exercise_demos.py. Do not edit by hand.\n")
        k.write("/**\n")
        k.write(" * Built-in exercises with start/end drawings under composeResources/files/exercises, and the\n")
        k.write(" * Everkinetic drawing each was adapted from (CC BY-SA 4.0, https://github.com/everkinetic/data).\n")
        k.write(" */\n")
        k.write("internal object ExerciseDemos {\n")
        k.write("    val sources: Map<String, String> = mapOf(\n")
        for id_, source in sorted(MATCHES.items()):
            k.write(f'        "{id_}" to "{source} {db[source]["title"].strip()}",\n')
        k.write("    )\n")
        k.write("}\n")
    print(f"wrote {os.path.relpath(KOTLIN, ROOT)}")


if __name__ == "__main__":
    main()
