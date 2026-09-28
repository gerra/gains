#!/usr/bin/env python3
"""Tests for tools/testflight.py: the xcodebuild lines, and the iOS workflow that runs one.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

No Xcode here: the commands are recorded with a fake `gha.run`, and the workflows are read as
text. The build itself is checked by the `ios` job on a macOS runner.
"""

import os
import pathlib
import re
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import testflight

ROOT = pathlib.Path(__file__).resolve().parent.parent
IOS_WORKFLOW = ROOT / ".github/workflows/ios.yml"
TESTFLIGHT_WORKFLOW = ROOT / ".github/workflows/testflight.yml"
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"


def recorded(command, environment=None):
    """Run one testflight.py command with gha.run faked, and return what it would have run."""
    calls = []
    with mock.patch.object(testflight.gha, "run", lambda *args, **kwargs: calls.append((args, kwargs))):
        with mock.patch.dict(os.environ, environment or {}):
            testflight.main([command])
    return calls


def flag(command, name):
    """The value after `name` in an argument list."""
    return command[command.index(name) + 1]


def path_filters(workflow, event):
    """The `paths:` list under one trigger of a workflow, read as text."""
    lines = workflow.splitlines()
    start = lines.index(f"  {event}:")
    filters = []
    for line in lines[start + 1:]:
        if not line.startswith("    "):
            break
        match = re.match(r'\s+- "(.*)"$', line)
        if match:
            filters.append(match.group(1))
    return filters


class SimulatorBuildTest(unittest.TestCase):
    def test_it_builds_the_archived_project_and_scheme_for_the_simulator(self):
        command = testflight.simulator_build_command()
        self.assertEqual(["xcodebuild", "build"], command[:2])
        self.assertEqual(testflight.PROJECT, flag(command, "-project"))
        self.assertEqual(testflight.SCHEME, flag(command, "-scheme"))
        self.assertEqual("Debug", flag(command, "-configuration"))
        # Generic: no simulator device has to exist, and none is booted.
        self.assertEqual("generic/platform=iOS Simulator", flag(command, "-destination"))

    def test_it_needs_no_signing_material(self):
        command = testflight.simulator_build_command()
        self.assertIn("CODE_SIGNING_ALLOWED=NO", command)
        for setting in ("CODE_SIGN_IDENTITY", "DEVELOPMENT_TEAM", "PROVISIONING_PROFILE_SPECIFIER", "-allowProvisioningUpdates"):
            self.assertEqual([], [argument for argument in command if argument.startswith(setting)])

    def test_the_project_and_the_shared_scheme_exist(self):
        project = ROOT / testflight.PROJECT
        self.assertTrue((project / "project.pbxproj").is_file())
        self.assertTrue((project / f"xcshareddata/xcschemes/{testflight.SCHEME}.xcscheme").is_file())

    def test_the_command_runs_from_the_repository_root(self):
        calls = recorded("build-simulator")
        self.assertEqual((("xcodebuild", "-version"), {}), calls[0])
        args, kwargs = calls[-1]
        self.assertEqual(testflight.simulator_build_command(), list(args))
        self.assertEqual(testflight.ROOT, kwargs["cwd"])


class ArchiveTest(unittest.TestCase):
    ENVIRONMENT = {
        "BUILD_NUMBER": "57",
        "MARKETING_VERSION": "1.9",
        "BUNDLE_ID": "app.gains.Gains",
        "PROFILE_NAME": "Gains App Store",
        "TEAM_ID": "V5Y8M5GKZ6",
    }

    def test_the_archive_builds_the_same_project_and_scheme_as_ci(self):
        [(args, kwargs)] = [call for call in recorded("archive", self.ENVIRONMENT) if call[0][:2] == ("xcodebuild", "archive")]
        command = list(args)
        self.assertEqual(testflight.PROJECT, flag(command, "-project"))
        self.assertEqual(testflight.SCHEME, flag(command, "-scheme"))
        self.assertEqual("Release", flag(command, "-configuration"))
        self.assertIn("CURRENT_PROJECT_VERSION=57", command)
        self.assertIn("PROVISIONING_PROFILE_SPECIFIER=Gains App Store", command)


class WorkflowTest(unittest.TestCase):
    """The iOS workflow runs the build on the TestFlight image, and skips what can't break it."""

    def test_the_workflow_runs_the_simulator_build(self):
        self.assertIn("run: python3 tools/testflight.py build-simulator", IOS_WORKFLOW.read_text())

    def test_it_builds_on_the_image_testflight_archives_with(self):
        image = re.compile(r"^\s+runs-on: (\S+)$", re.MULTILINE)
        self.assertEqual(image.findall(TESTFLIGHT_WORKFLOW.read_text()), image.findall(IOS_WORKFLOW.read_text()))

    def test_pushes_and_pull_requests_filter_the_same_paths(self):
        workflow = IOS_WORKFLOW.read_text()
        self.assertEqual(path_filters(workflow, "push"), path_filters(workflow, "pull_request"))

    def test_only_what_cannot_break_the_xcode_build_is_skipped(self):
        filters = path_filters(IOS_WORKFLOW.read_text(), "push")
        self.assertEqual("**", filters[0])
        self.assertEqual(
            {"docs/**", "site/**", "server/**", "deploy/**", "tools/**"},
            {pattern[1:] for pattern in filters if pattern.startswith("!")},
        )
        # Later patterns win, so the script the job runs comes back after tools/** is dropped.
        for script in ("tools/testflight.py", "tools/gha.py"):
            self.assertGreater(filters.index(script), filters.index("!tools/**"))

    def test_the_fast_klib_compile_stays_in_ci(self):
        self.assertIn(":composeApp:compileKotlinIosArm64", CI_WORKFLOW.read_text())


if __name__ == "__main__":
    unittest.main()
