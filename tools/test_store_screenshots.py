#!/usr/bin/env python3
"""Tests for tools/store_screenshots.py: what it reads and what the site expects of it.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

Drawing needs Pillow, which this job doesn't install, so these check the table the drawing
works from: every render it names exists, and site/index.html asks for the files it writes
at the sizes it writes them.
"""

import pathlib
import re
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import store_screenshots as shots

ROOT = pathlib.Path(__file__).resolve().parent.parent
INDEX = (ROOT / "site/index.html").read_text()


def img_size(name):
    m = re.search(rf'src="/img/{re.escape(name)}\.webp"[^>]*width="(\d+)" height="(\d+)"', INDEX)
    return (int(m.group(1)), int(m.group(2))) if m else None


class Table(unittest.TestCase):
    def test_every_render_is_one_the_screenshot_test_writes(self):
        for render in [s[1] for s in shots.SHOTS] + [shots.HERO_RENDER]:
            self.assertTrue((shots.RENDERS / f"{render}.png").exists(), render)

    def test_store_names_are_unique_and_ordered(self):
        names = [s[0] for s in shots.SHOTS]
        self.assertEqual(names, sorted(set(names)))

    def test_store_size_is_apples_6_9_inch_portrait(self):
        self.assertEqual((shots.WIDTH, shots.HEIGHT), (1290, 2796))


class Site(unittest.TestCase):
    def test_the_site_shows_every_shot_at_the_size_written(self):
        height = round(shots.HEIGHT * shots.SITE_WIDTH / shots.WIDTH)
        for site in [s[5] for s in shots.SHOTS if s[5]]:
            self.assertEqual(img_size(site), (shots.SITE_WIDTH, height), site)

    def test_the_site_shows_no_shot_the_script_does_not_write(self):
        written = {s[5] for s in shots.SHOTS if s[5]}
        self.assertEqual(set(re.findall(r'src="/img/(shot-[\w-]+)\.webp"', INDEX)), written)

    def test_the_hero_is_the_width_written(self):
        self.assertEqual(img_size("hero")[0], shots.SITE_WIDTH)


if __name__ == "__main__":
    unittest.main()
