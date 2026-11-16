#!/usr/bin/env python3
"""Tests for tools/beta.py: the weekly round for external testers, without App Store Connect.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

The token is signed with a throwaway key and checked with openssl, since a wrong signature
would only show up as a 401 from Apple; the API calls and their order are checked with a fake.
"""

import base64
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import beta

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/external-testing.yml"


def decode(segment):
    return json.loads(base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4)))


def der(raw):
    """The raw `r || s` signature back into DER, for openssl to verify."""
    def integer(value):
        value = value.lstrip(b"\0") or b"\0"
        if value[0] & 0x80:
            value = b"\0" + value
        return b"\x02" + bytes([len(value)]) + value
    body = integer(raw[:32]) + integer(raw[32:])
    return b"\x30" + bytes([len(body)]) + body


class TokenTest(unittest.TestCase):
    def test_the_token_is_es256_for_app_store_connect_and_verifies(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            key, public = directory / "AuthKey.p8", directory / "public.pem"
            subprocess.run(
                ["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt",
                 "ec_paramgen_curve:P-256", "-out", key], check=True, capture_output=True,
            )
            subprocess.run(
                ["openssl", "pkey", "-in", key, "-pubout", "-out", public],
                check=True, capture_output=True,
            )
            with mock.patch.object(beta.gha, "temp", lambda *parts: directory.joinpath(*parts)):
                jwt = beta.token(key, "KEY123", "issuer-id", now=1_000)

            header, claims, signature = jwt.split(".")
            self.assertEqual({"alg": "ES256", "kid": "KEY123", "typ": "JWT"}, decode(header))
            self.assertEqual(
                {"iss": "issuer-id", "iat": 1_000, "exp": 2_200, "aud": "appstoreconnect-v1"},
                decode(claims),
            )
            raw = base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4))
            self.assertEqual(64, len(raw))
            (directory / "signed").write_bytes(f"{header}.{claims}".encode())
            (directory / "sig").write_bytes(der(raw))
            verified = subprocess.run(
                ["openssl", "dgst", "-sha256", "-verify", public, "-signature",
                 directory / "sig", directory / "signed"], capture_output=True,
            )
            self.assertEqual(0, verified.returncode, verified.stdout + verified.stderr)

    def test_short_and_padded_integers_come_out_32_bytes_each(self):
        r, s = b"\x00" * 2 + b"\x01" * 30, b"\xff" * 32
        self.assertEqual(r + s, beta.raw_signature(der(r + s)))


class WhatToTestTest(unittest.TestCase):
    def test_lists_the_changes_since_the_last_build(self):
        self.assertEqual(
            "Gains 1.20. New since 1.12 (40):\n- Remove a set with a swipe\n- Ease back in",
            beta.what_to_test("1.20", "1.12 (40)", "- Remove a set with a swipe\n- Ease back in"),
        )

    def test_without_a_previous_build_it_names_the_version(self):
        self.assertEqual("Gains 1.20.", beta.what_to_test("1.20", None, ""))

    def test_stays_inside_testflights_limit(self):
        changes = "\n".join(f"- Change number {n} " + "x" * 80 for n in range(100))
        text = beta.what_to_test("1.20", "1.12 (40)", changes)
        self.assertLessEqual(len(text), beta.WHAT_TO_TEST_LIMIT)
        self.assertTrue(text.endswith("…and more"))


def build(build_id, number, version_id):
    return {
        "id": build_id, "type": "builds",
        "attributes": {"version": number, "processingState": "VALID", "expired": False},
        "relationships": {"preReleaseVersion": {"data": {"id": version_id}}},
    }


def version(version_id, value):
    return {"id": version_id, "type": "preReleaseVersions", "attributes": {"version": value}}


class Fake:
    """App Store Connect as a list of (method, path prefix) -> answer, recording every call."""

    def __init__(self, answers):
        self.answers = answers
        self.calls = []

    def __call__(self, method, url, body=None, token=None):
        path = url.removeprefix(beta.API)
        self.calls.append((method, path, body))
        for (m, prefix), answer in self.answers.items():
            if m == method and path.startswith(prefix):
                if isinstance(answer, Exception):
                    raise answer
                return answer(path) if callable(answer) else answer
        raise AssertionError(f"unexpected {method} {path}")


def answers(in_group, review=None):
    def builds(path):
        if "filter[betaGroups]" in path:
            return {"data": [build(*in_group[0])], "included": [version(*in_group[1])]}
        return {"data": [build("b2", "57", "v2")], "included": [version("v2", "1.20")]}

    return {
        ("GET", "/apps?"): {"data": [{"id": "app", "attributes": {"primaryLocale": "en-GB"}}]},
        ("GET", "/apps/app/betaGroups"): {"data": [
            {"id": "internal", "attributes": {"name": "Team", "isInternalGroup": True}},
            {"id": "ff", "attributes": {"name": "Friends & Family", "isInternalGroup": False}},
        ]},
        ("GET", "/builds?"): builds,
        ("GET", "/builds/b2/betaBuildLocalizations"): {"data": []},
        ("POST", "/betaBuildLocalizations"): {},
        ("POST", "/betaGroups/ff/relationships/builds"): {},
        ("GET", "/builds/b2/betaAppReviewSubmission"):
            review or beta.ApiError("GET", "x", 404, "none"),
        ("POST", "/betaAppReviewSubmissions"):
            {"data": {"attributes": {"betaReviewState": "WAITING_FOR_REVIEW"}}},
    }


def run(fake, **options):
    args = types.SimpleNamespace(group="Friends & Family", build="", what_to_test="", **options)
    with mock.patch.object(beta, "tag_exists", lambda tag: True), \
            mock.patch.object(beta, "changes_between", lambda old, new: f"- {old}..{new}"), \
            mock.patch.object(beta.gha, "summary", lambda *a, **k: None):
        beta.distribute(args, api=beta.AppStoreConnect("token", request=fake))


class DistributeTest(unittest.TestCase):
    def test_a_new_build_gets_its_notes_joins_the_group_and_goes_to_review(self):
        fake = Fake(answers(in_group=(("b1", "40", "v1"), ("v1", "1.12"))))
        run(fake)
        writes = [(m, p) for m, p, _ in fake.calls if m != "GET"]
        self.assertEqual(
            [("POST", "/betaBuildLocalizations"),
             ("POST", "/betaGroups/ff/relationships/builds"),
             ("POST", "/betaAppReviewSubmissions")],
            writes,
        )
        localization = fake.calls[[p for _, p, _ in fake.calls].index("/betaBuildLocalizations")][2]
        self.assertEqual(
            {"locale": "en-GB",
             "whatsNew": "Gains 1.20. New since 1.12 (40):\n- testflight/1.12/40..testflight/1.20/57"},
            localization["data"]["attributes"],
        )
        newest = next(p for m, p, _ in fake.calls if p.startswith("/builds?filter[app]"))
        self.assertIn("filter[processingState]=VALID", newest)
        self.assertIn("sort=-uploadedDate", newest)

    def test_a_build_already_in_the_group_is_not_added_again(self):
        fake = Fake(answers(
            in_group=(("b2", "57", "v2"), ("v2", "1.20")),
            review={"data": {"attributes": {"betaReviewState": "APPROVED"}}},
        ))
        run(fake)
        self.assertEqual([], [(m, p) for m, p, _ in fake.calls if m != "GET"])

    def test_a_chosen_build_is_looked_up_by_number(self):
        fake = Fake(answers(in_group=(("b1", "40", "v1"), ("v1", "1.12"))))
        chosen = types.SimpleNamespace(group="Friends & Family", build="57", what_to_test="Hi")
        with mock.patch.object(beta.gha, "summary", lambda *a, **k: None):
            beta.distribute(chosen, api=beta.AppStoreConnect("token", request=fake))
        lookup = next(p for m, p, _ in fake.calls if p.startswith("/builds?filter[app]"))
        self.assertIn("filter[version]=57", lookup)
        notes = next(b for m, p, b in fake.calls if p == "/betaBuildLocalizations")
        self.assertEqual("Hi", notes["data"]["attributes"]["whatsNew"])

    def test_an_unknown_group_stops_the_round(self):
        fake = Fake(answers(in_group=(("b1", "40", "v1"), ("v1", "1.12"))))
        nobody = types.SimpleNamespace(group="Nobody", build="", what_to_test="")
        with self.assertRaises(SystemExit):
            beta.distribute(nobody, api=beta.AppStoreConnect("token", request=fake))


class WorkflowTest(unittest.TestCase):
    def test_runs_weekly_with_the_upload_key_and_no_write_access(self):
        text = WORKFLOW.read_text()
        self.assertRegex(text, r'cron: "\d+ \d+ \* \* \d"')
        self.assertIn("contents: read", text)
        self.assertNotIn("write", re.sub(r"#.*", "", text))
        for name in beta.SECRETS:
            self.assertIn(f"{name}: ${{{{ secrets.{name} }}}}", text)
        self.assertIn("python3 tools/beta.py distribute", text)
        # Inputs reach the script through the environment, never pasted into the command.
        self.assertNotRegex(text, r"run: .*\$\{\{")


if __name__ == "__main__":
    unittest.main()
