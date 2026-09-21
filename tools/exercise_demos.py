#!/usr/bin/env python3
"""
Fetches the start/end photos for every built-in exercise from free-exercise-db (public domain,
https://github.com/yuhonas/free-exercise-db), scales them down and writes them under
composeApp/src/commonMain/composeResources/files/exercises/<catalogue id>/{0,1}.webp, plus the
Kotlin table (ExerciseDemos.kt) that says which exercises have a demo and where it came from.

Run from the repository root:  python3 tools/exercise_demos.py   (needs Pillow: pip install pillow)

Exercises are matched to the database by the catalogue's `// src:` comment, then by name and
aliases; OVERRIDES pins the ones where the automatic match is not the best photo set, and
SKIP lists catalogue exercises the database has no photo for (the app falls back to a video search).
"""
import http.client, io, json, os, re, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOGUE = os.path.join(ROOT, "shared/src/commonMain/kotlin/app/gains/catalogue/ExerciseCatalogue.kt")
OUT_DIR = os.path.join(ROOT, "composeApp/src/commonMain/composeResources/files/exercises")
KOTLIN = os.path.join(ROOT, "composeApp/src/commonMain/kotlin/app/gains/ui/demo/ExerciseDemos.kt")
DB_URL = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json"
IMAGE_URL = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/{id}/{n}.jpg"
WIDTH = 480
QUALITY = 70

# catalogue id -> free-exercise-db exercise name, where the name match picks a worse photo set.
OVERRIDES = {
    "chest_fly": "Dumbbell Flyes",
    "overhead_press": "Standing Military Press",
    "front_raise": "Front Dumbbell Raise",
    "rear_delt_fly": "Reverse Flyes",
    "upright_row": "Upright Barbell Row",
    "seated_cable_row": "Seated Cable Rows",
    "row_machine": "Leverage Iso Row",
    "back_extension": "Hyperextensions (Back Extensions)",
    "hammer_curl": "Hammer Curls",
    "cable_curl": "Standing Biceps Cable Curl",
    "overhead_triceps_extension": "Cable Rope Overhead Triceps Extension",
    "skull_crusher": "EZ-Bar Skullcrusher",
    "triceps_kickback": "Tricep Dumbbell Kickback",
    "wrist_curl": "Palms-Up Barbell Wrist Curl Over A Bench",
    "bulgarian_split_squat": "Split Squat with Dumbbells",
    "lunge": "Dumbbell Lunges",
    "step_up": "Dumbbell Step Ups",
    "leg_curl": "Lying Leg Curls",
    "hip_abduction": "Thigh Abductor",
    "calf_raise": "Standing Calf Raises",
    "cycling": "Bicycling, Stationary",
    "neck_side": "Isometric Neck Exercise - Sides",
    "high_to_low_cable_fly": "Cable Crossover",
    "landmine_press": "Landmine Linear Jammer",
}
# Nothing in the database shows these (kettlebell_halo has an entry without photos); the app offers a video search instead.
SKIP = {
    "lateral_raise_machine", "dead_hang", "wall_sit", "hollow_hold", "l_sit", "pike_push_ups",
    "pseudo_planche_push_ups", "handstand_hold", "face_to_wall_handstand_45", "abdominal_set",
    "wrist_prep", "shoulders_prep", "core_prep", "boxing", "hiit", "swimming", "barbell_thruster",
    "burpee", "bird_dog", "kettlebell_halo",
}


def norm(s):
    return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()


def read_catalogue():
    src = open(CATALOGUE).read()
    exercises = []
    for m in re.finditer(r'^\s*ex\("([^"]+)",\s*"([^"]+)"(.*)$', src, re.M):
        id_, name, rest = m.groups()
        alias_block = re.search(r"alias = arrayOf\((.*?)\)\)", rest)
        aliases = re.findall(r'"([^"]+)"', alias_block.group(1)) if alias_block else []
        source = re.search(r"// src: ([^|]+)\|", rest)
        exercises.append((id_, name, aliases, source.group(1).strip() if source else None))
    extra = {}
    for m in re.finditer(r'^\s*alias\("([^"]+)",\s*(.*)\)$', src, re.M):
        extra.setdefault(m.group(1), []).extend(re.findall(r'"([^"]+)"', m.group(2)))
    return [(i, n, a + extra.get(i, []), s) for i, n, a, s in exercises]


def fetch(url, attempts=4):
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read()
        except (OSError, http.client.HTTPException):
            if attempt == attempts - 1:
                raise
            time.sleep(2 ** attempt)


def main():
    db = json.loads(fetch(DB_URL))
    by_name = {norm(d["name"]): d for d in db}
    mapping = {}  # catalogue id -> db entry
    for id_, name, aliases, source in read_catalogue():
        if id_ in SKIP:
            continue
        if id_ in OVERRIDES:
            mapping[id_] = by_name[norm(OVERRIDES[id_])]
            continue
        for candidate in [source, name, *aliases]:
            if candidate and norm(candidate) in by_name:
                mapping[id_] = by_name[norm(candidate)]
                break
        else:
            sys.exit(f"no photo set for {id_} ({name}): add it to OVERRIDES or SKIP")
    print(f"{len(mapping)} exercises with photos, {len(SKIP)} without")

    def process(item):
        id_, entry = item
        target = os.path.join(OUT_DIR, id_)
        os.makedirs(target, exist_ok=True)
        for n, image in enumerate(entry["images"][:2]):
            out = os.path.join(target, f"{n}.webp")
            if os.path.exists(out):
                continue
            im = Image.open(io.BytesIO(fetch(IMAGE_URL.format(id=entry["id"], n=n)))).convert("RGB")
            im = im.resize((WIDTH, round(im.height * WIDTH / im.width)), Image.LANCZOS)
            im.save(out, "WEBP", quality=QUALITY, method=6)
        return id_

    with ThreadPoolExecutor(16) as pool:
        for _ in pool.map(process, sorted(mapping.items())):
            pass

    total = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(OUT_DIR) for f in fs)
    print(f"{total / 1e6:.1f} MB of photos under {os.path.relpath(OUT_DIR, ROOT)}")

    os.makedirs(os.path.dirname(KOTLIN), exist_ok=True)
    with open(KOTLIN, "w") as k:
        k.write("package app.gains.ui.demo\n\n")
        k.write("// Generated by tools/exercise_demos.py. Do not edit by hand.\n")
        k.write("/** Built-in exercises with start/end photos under composeResources/files/exercises, and the free-exercise-db entry each came from. */\n")
        k.write("internal object ExerciseDemos {\n")
        k.write("    val sources: Map<String, String> = mapOf(\n")
        for id_, entry in sorted(mapping.items()):
            k.write(f'        "{id_}" to "{entry["name"]}",\n')
        k.write("    )\n")
        k.write("}\n")
    print(f"wrote {os.path.relpath(KOTLIN, ROOT)}")


if __name__ == "__main__":
    main()
