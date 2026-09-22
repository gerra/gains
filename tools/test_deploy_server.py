"""The parts of deploy_server.py that need no box: where it connects, and what it accepts as healthy."""

import pathlib
import tempfile
import unittest

import deploy_server


class RemoteTest(unittest.TestCase):
    def test_the_workflow_connects_with_the_deploy_secrets(self):
        with tempfile.TemporaryDirectory() as temp:
            remote = deploy_server.Remote.from_environment({
                "DEPLOY_HOST": "box.example", "DEPLOY_USER": "root", "DEPLOY_KEY": "KEY", "RUNNER_TEMP": temp,
            })
            self.assertEqual("root@box.example", remote.target)
            key = pathlib.Path(temp) / "deploy_key"
            self.assertEqual("KEY\n", key.read_text())
            self.assertEqual(0o600, key.stat().st_mode & 0o777)
            self.assertEqual(["-i", str(key), "-o", "StrictHostKeyChecking=no", "-o", "IdentitiesOnly=yes"], remote.options())

    def test_a_laptop_connects_through_its_host_alias(self):
        self.assertEqual("hetzner_gb", deploy_server.Remote.from_environment({}).target)
        remote = deploy_server.Remote.from_environment({"REMOTE": "elsewhere"})
        self.assertEqual("elsewhere", remote.target)
        self.assertEqual([], remote.options())

    def test_an_incomplete_set_of_secrets_is_not_a_box(self):
        remote = deploy_server.Remote.from_environment({"DEPLOY_HOST": "box.example", "DEPLOY_USER": "root"})
        self.assertEqual("hetzner_gb", remote.target)


class HealthTest(unittest.TestCase):
    def test_only_the_servers_own_answer_passes(self):
        self.assertTrue(deploy_server.healthy(200, '{"status":"ok"}'))
        self.assertFalse(deploy_server.healthy(200, "<html>nginx</html>"))
        self.assertFalse(deploy_server.healthy(502, '{"status":"ok"}'))
        self.assertFalse(deploy_server.healthy(0, "connection refused"))


if __name__ == "__main__":
    unittest.main()
