#!/usr/bin/env python3
"""Tests for tools/exercise_demos.py: the parts that need neither the network nor a renderer.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

Rendering needs Pillow and CairoSVG, which this job doesn't install, so these check what is
decided before a drawing is fetched: which of Everkinetic's files an exercise is drawn from, and
what its SVG is turned into before it is rendered.
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import exercise_demos as demos

SVG = (
    '<svg width="216" height="275pt" viewBox="0 0 162 275" xmlns="http://www.w3.org/2000/svg">'
    '<g fill="#FFF"><path d="M0 0h162v275H0z"/></g>'
    '<g fill="#333"><path d="M10 10h5v5h-5z"/></g>'
    '<g fill="#2e2e2c"><path d="M20 20h5v5h-5z" fill="#ffffff"/></g>'
    "</svg>"
)


class Sources(unittest.TestCase):
    def test_an_svg_pair_is_preferred(self):
        entry = {"svg": ["svg/a.svg", "svg/b.svg"], "png": ["png/a.png", "png/b.png"]}
        self.assertEqual(("svg", ["svg/a.svg", "svg/b.svg"]), demos.sources(entry))

    def test_pngs_stand_in_where_there_is_no_svg_pair(self):
        pngs = ["png/1.png", "png/2.png", "png/3.png"]
        self.assertEqual(("png", pngs), demos.sources({"png": pngs}))
        self.assertEqual(("png", pngs), demos.sources({"svg": ["svg/a.svg"], "png": pngs}))
        self.assertEqual(("png", []), demos.sources({}))

    def test_every_match_names_an_everkinetic_drawing(self):
        for id_, source in demos.MATCHES.items():
            self.assertRegex(source, r"^\d{4}$", id_)


class InkSvg(unittest.TestCase):
    def test_the_render_is_sized_by_the_viewbox_alone(self):
        tag = demos.ink_svg(SVG).split(">", 1)[0]
        self.assertEqual('<svg viewBox="0 0 162 275" xmlns="http://www.w3.org/2000/svg"', tag)

    def test_every_fill_but_white_turns_black(self):
        out = demos.ink_svg(SVG)
        self.assertIn('<g fill="#FFF">', out)
        self.assertIn('fill="#ffffff"', out)
        self.assertNotIn("#333", out)
        self.assertNotIn("#2e2e2c", out)
        self.assertEqual(2, out.count('fill="#000"'))

    def test_only_the_svg_tag_loses_its_size(self):
        svg = SVG.replace('<path d="M10 10h5v5h-5z"/>', '<rect width="5" height="5"/>')
        self.assertIn('<rect width="5" height="5"/>', demos.ink_svg(svg))


if __name__ == "__main__":
    unittest.main()
