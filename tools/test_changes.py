#!/usr/bin/env python3
"""Tests for tools/changes.py: which pull requests skip CI's Gradle jobs.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

Skipping a job that should have run lets a broken change merge green, so the cases worth
pinning are the near misses: files that look like docs but that Gradle reads, and the
workflows that run Gradle themselves. The workflows are read as text.
"""

import pathlib
import re
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from changes import classify

ROOT = pathlib.Path(__file__).resolve().parent.parent
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
CODEQL_WORKFLOW = ROOT / ".github/workflows/codeql.yml"


class Build(unittest.TestCase):
    def test_text_only_changes_skip_it(self):
        for paths in (
            ["docs/launch-plan.md"],
            ["README.md", "secrets/README.md"],
            ["site/privacy.html", "site/img/hero.webp"],
            ["tools/release.py", "tools/test_release.py"],
            ["deploy/nginx/gains.conf", "deploy/gains-server.service"],
            [".github/workflows/testflight.yml", ".github/dependabot.yml"],
            ["docs/screenshots/today.png", "LICENSE"],
        ):
            with self.subTest(paths=paths):
                self.assertFalse(classify(paths)["build"])

    def test_one_source_file_among_docs_runs_it(self):
        self.assertTrue(classify(["docs/sync.md", "server/src/main/kotlin/app/gains/server/Routes.kt"])["build"])

    def test_files_gradle_reads_outside_the_source_sets_run_it(self):
        for path in (
            "samples/liftoff-export.csv",  # ScreenshotTest reads it.
            "iosApp/Configuration/Config.xcconfig",  # androidApp/build.gradle.kts reads it.
            "composeApp/src/commonMain/composeResources/values/strings.xml",
            "shared/src/commonMain/sqldelight/app/gains/db/Session.sq",
            "gradle/libs.versions.toml",
        ):
            with self.subTest(path=path):
                self.assertTrue(classify([path])["build"])

    def test_the_workflows_that_run_gradle_run_it(self):
        self.assertTrue(classify([".github/workflows/ci.yml"])["build"])
        self.assertTrue(classify([".github/workflows/codeql.yml"])["build"])

    def test_no_changed_files_runs_everything(self):
        self.assertEqual({"build": True, "dependencies": True}, classify([]))


class Dependencies(unittest.TestCase):
    def test_build_files_run_the_review(self):
        for path in (
            "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties",
            "build.gradle.kts",
            "settings.gradle.kts",
            "server/build.gradle.kts",
            "composeApp/android.gradle",
            "gradle.properties",
            ".github/workflows/ci.yml",
        ):
            with self.subTest(path=path):
                self.assertTrue(classify([path])["dependencies"])

    def test_source_changes_skip_it(self):
        self.assertFalse(classify(["shared/src/commonMain/kotlin/app/gains/App.kt"])["dependencies"])
        self.assertFalse(classify(["docs/launch-plan.md"])["dependencies"])


class Workflows(unittest.TestCase):
    """The Gradle jobs skip on the outputs, and only in a way a required check survives."""

    def test_neither_workflow_filters_paths(self):
        # A workflow skipped by `paths:` never reports, and a required check waits on it forever.
        for workflow in (CI_WORKFLOW, CODEQL_WORKFLOW):
            with self.subTest(workflow=workflow.name):
                self.assertNotRegex(workflow.read_text(), r"^\s+paths(-ignore)?:", )

    def test_the_gradle_jobs_wait_for_the_outputs(self):
        ci = CI_WORKFLOW.read_text()
        for job in ("test", "android"):
            with self.subTest(job=job):
                self.assertIn("needs.changes.outputs.build != 'false'", job_text(ci, job))
        self.assertIn("needs.changes.outputs.dependencies != 'false'", job_text(ci, "dependency-review"))
        self.assertIn(
            "needs.changes.outputs.build != 'false'", job_text(CODEQL_WORKFLOW.read_text(), "analyze")
        )

    def test_the_script_tests_always_run(self):
        self.assertNotIn("if:", job_text(CI_WORKFLOW.read_text(), "scripts").split("steps:")[0])

    def test_the_checkout_has_the_merge_commits_parent(self):
        for workflow in (CI_WORKFLOW, CODEQL_WORKFLOW):
            with self.subTest(workflow=workflow.name):
                self.assertIn("fetch-depth: 2", job_text(workflow.read_text(), "changes"))


def job_text(workflow, job):
    """The lines of one job in a workflow, from its key to the next job's."""
    match = re.search(rf"^  {re.escape(job)}:\n(.*?)(?=^  \S|\Z)", workflow, re.MULTILINE | re.DOTALL)
    if not match:
        raise AssertionError(f"no job {job}")
    return match.group(1)


if __name__ == "__main__":
    unittest.main()
