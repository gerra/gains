"""The parts of deploy_server.py that need no box: where it connects, what it accepts as healthy,
the server's user and file modes, which certificates each nginx site needs, and that the site it
ships has no broken links."""

import html.parser
import pathlib
import re
import subprocess
import tempfile
import unittest
import unittest.mock

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


class UnprivilegedServerTest(unittest.TestCase):
    """The server runs as its own user, from /opt, sandboxed (docs/launch-plan.md, item 22)."""

    def unit(self):
        return deploy_server.UNIT_FILE.read_text()

    def directives(self, name):
        return re.findall(rf"^{name}=(.*)$", self.unit(), re.MULTILINE)

    def test_the_unit_runs_from_the_install_the_deploy_writes(self):
        self.assertEqual(["/opt/gains-server"], self.directives("WorkingDirectory"))
        self.assertEqual(str(deploy_server.HOME), self.directives("WorkingDirectory")[0])
        self.assertEqual([f"{deploy_server.CURRENT}/bin/gains-server"], self.directives("ExecStart"))
        self.assertNotIn("/root", "\n".join(self.directives("WorkingDirectory") + self.directives("ExecStart")))

    def test_the_unit_runs_as_the_user_the_deploy_creates(self):
        self.assertEqual([deploy_server.USER], self.directives("User"))
        self.assertEqual([deploy_server.USER], self.directives("Group"))
        self.assertEqual(deploy_server.USER, deploy_server.create_user_command()[-1])

    def test_the_data_directory_is_the_only_writable_one(self):
        self.assertEqual([str(deploy_server.DATA_DIR)], self.directives("ReadWritePaths"))
        self.assertIn(f"GAINS_DATA_DIR={deploy_server.DATA_DIR}", self.directives("Environment"))
        self.assertEqual(["strict"], self.directives("ProtectSystem"))

    def test_the_unit_is_sandboxed(self):
        for name, value in [
            ("UMask", "0077"), ("NoNewPrivileges", "true"), ("PrivateTmp", "true"), ("ProtectHome", "true"),
            ("ProtectKernelTunables", "true"), ("ProtectKernelModules", "true"), ("ProtectControlGroups", "true"),
            ("RestrictAddressFamilies", "AF_INET AF_INET6 AF_UNIX"), ("LockPersonality", "true"),
            ("RestrictRealtime", "true"), ("CapabilityBoundingSet", ""), ("RestrictSUIDSGID", "true"),
            ("RestrictNamespaces", "true"), ("PrivateDevices", "true"), ("SystemCallArchitectures", "native"),
        ]:
            self.assertEqual([value], self.directives(name), name)
        # The JIT writes code it then runs, so this one would stop the JVM.
        self.assertEqual([], self.directives("MemoryDenyWriteExecute"))

    def test_a_missing_user_is_created_as_a_system_user_without_a_login(self):
        commands = []

        def fake_run(*command, check=True, capture=False):
            commands.append(list(command))
            return subprocess.CompletedProcess(command, 1 if command[0] == "id" else 0, "", "")

        with unittest.mock.patch.object(deploy_server, "run", fake_run):
            deploy_server.ensure_user()
        self.assertEqual(
            [["id", "-u", "gains-server"],
             ["useradd", "--system", "--user-group", "--home-dir", "/var/lib/gains", "--no-create-home",
              "--shell", "/usr/sbin/nologin", "gains-server"]],
            commands,
        )

    def test_an_existing_user_is_left_alone(self):
        commands = []

        def fake_run(*command, check=True, capture=False):
            commands.append(list(command))
            return subprocess.CompletedProcess(command, 0, "999\n", "")

        with unittest.mock.patch.object(deploy_server, "run", fake_run):
            deploy_server.ensure_user()
        self.assertEqual([["id", "-u", "gains-server"]], commands)

    def test_the_server_owns_its_data_and_only_reads_its_secrets(self):
        self.assertEqual(
            [["chown", "-R", "gains-server:gains-server", "/var/lib/gains"], ["chmod", "750", "/var/lib/gains"]],
            deploy_server.data_permissions(),
        )
        # The database files from before the unit's UMask were world-readable (item 24).
        self.assertEqual(
            [["chown", "-R", "gains-server:gains-server", "/var/lib/gains"], ["chmod", "750", "/var/lib/gains"],
             ["chmod", "600", "/var/lib/gains/gains-server.db", "/var/lib/gains/gains-server.db-wal"]],
            deploy_server.data_permissions(deploy_server.DATABASE_FILES[:2]),
        )
        self.assertEqual(
            [["chown", "gains-server:gains-server", "/var/backups/gains"], ["chmod", "700", "/var/backups/gains"]],
            deploy_server.backup_permissions(),
        )
        self.assertEqual(
            [["chown", "root:gains-server", "/opt/gains-server/secrets"], ["chmod", "750", "/opt/gains-server/secrets"],
             ["chown", "root:gains-server", "/opt/gains-server/secrets/.env"], ["chmod", "640", "/opt/gains-server/secrets/.env"]],
            deploy_server.secrets_permissions(),
        )
        self.assertEqual(
            [["chown", "root:gains-server", "/opt/gains-server/secrets"], ["chmod", "750", "/opt/gains-server/secrets"]],
            deploy_server.secrets_permissions(include_file=False),
        )

    def test_the_first_install_at_the_new_path_carries_the_secrets_over(self):
        with tempfile.TemporaryDirectory() as temp:
            old = pathlib.Path(temp) / "root" / "secrets" / ".env"
            new = pathlib.Path(temp) / "opt" / "secrets" / ".env"
            old.parent.mkdir(parents=True)
            old.write_text("JWT_SECRET=old\n")
            self.assertTrue(deploy_server.carry_over_secrets(old, new))
            self.assertEqual("JWT_SECRET=old\n", new.read_text())
            self.assertTrue(old.is_file())

    def test_secrets_already_at_the_new_path_win(self):
        with tempfile.TemporaryDirectory() as temp:
            old = pathlib.Path(temp) / "old.env"
            new = pathlib.Path(temp) / "new.env"
            old.write_text("JWT_SECRET=old\n")
            new.write_text("JWT_SECRET=new\n")
            self.assertFalse(deploy_server.carry_over_secrets(old, new))
            self.assertEqual("JWT_SECRET=new\n", new.read_text())

    def test_nothing_to_carry_over_on_a_new_box(self):
        with tempfile.TemporaryDirectory() as temp:
            new = pathlib.Path(temp) / "secrets" / ".env"
            self.assertFalse(deploy_server.carry_over_secrets(pathlib.Path(temp) / "missing.env", new))
            self.assertFalse(new.exists())


class BackupTest(unittest.TestCase):
    """The nightly copy of the database (docs/launch-plan.md, item 24)."""

    def directives(self, path, name):
        return re.findall(rf"^{name}=(.*)$", path.read_text(), re.MULTILINE)

    def test_the_timer_runs_this_script_as_the_servers_user(self):
        service, timer = deploy_server.BACKUP_UNIT_FILES
        self.assertEqual(f"{deploy_server.BACKUP_UNIT}.service", service.name)
        self.assertEqual(f"{deploy_server.BACKUP_UNIT}.timer", timer.name)
        self.assertEqual([f"/usr/bin/python3 {deploy_server.HOME}/deploy_server.py backup"], self.directives(service, "ExecStart"))
        self.assertEqual([deploy_server.USER], self.directives(service, "User"))
        self.assertEqual(["oneshot"], self.directives(service, "Type"))
        self.assertEqual(["0077"], self.directives(service, "UMask"))
        self.assertEqual([f"{deploy_server.DATA_DIR} {deploy_server.BACKUP_DIR}"], self.directives(service, "ReadWritePaths"))
        self.assertEqual(["true"], self.directives(service, "PrivateNetwork"))
        self.assertEqual(["*-*-* 03:30:00"], self.directives(timer, "OnCalendar"))
        self.assertEqual(["true"], self.directives(timer, "Persistent"))

    def test_the_deploy_workflow_ships_a_change_to_the_units(self):
        workflow = (deploy_server.ROOT / ".github" / "workflows" / "deploy.yml").read_text()
        self.assertIn('"deploy/gains-server-backup.*"', workflow)

    def test_a_copy_is_the_database_as_it_is_while_the_server_writes(self):
        with tempfile.TemporaryDirectory() as temp:
            data, backups = pathlib.Path(temp) / "data", pathlib.Path(temp) / "backups"
            data.mkdir()
            backups.mkdir()
            database = data / "gains-server.db"
            server = deploy_server.sqlite3.connect(database)
            server.execute("PRAGMA journal_mode=WAL")
            server.execute("CREATE TABLE user (id INTEGER PRIMARY KEY, email TEXT)")
            server.execute("INSERT INTO user(email) VALUES ('ada@example.com')")
            server.commit()
            # Still open, with the row only in the -wal file: a plain file copy would miss it.
            made = deploy_server.backup_database(database, backups, "2026-09-29")
            server.close()

            self.assertEqual(backups / "gains-server-2026-09-29.db", made)
            self.assertEqual(0o600, made.stat().st_mode & 0o777)
            copy = deploy_server.sqlite3.connect(made)
            self.assertEqual([("ada@example.com",)], copy.execute("SELECT email FROM user").fetchall())
            copy.close()
            self.assertEqual(["gains-server-2026-09-29.db"], sorted(p.name for p in backups.iterdir()))

    def test_no_database_no_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp)
            self.assertIsNone(deploy_server.backup_database(directory / "gains-server.db", directory, "2026-09-29"))
            self.assertEqual([], list(directory.iterdir()), "connecting would have created an empty database")

    def test_the_last_seven_days_are_kept(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp)
            days = [f"2026-09-{day:02d}" for day in range(20, 30)]
            for day in days:
                (directory / f"gains-server-{day}.db").write_text(day)
            (directory / "unrelated.txt").write_text("left alone")
            pruned = deploy_server.prune_backups(directory)
            self.assertEqual([f"gains-server-{day}.db" for day in days[:3]], [p.name for p in pruned])
            self.assertEqual(
                sorted([f"gains-server-{day}.db" for day in days[3:]] + ["unrelated.txt"]),
                sorted(p.name for p in directory.iterdir()),
            )


class SiteSyncTest(unittest.TestCase):
    def test_the_workflow_rsyncs_with_the_deploy_key(self):
        with tempfile.TemporaryDirectory() as temp:
            remote = deploy_server.Remote.from_environment({
                "DEPLOY_HOST": "box.example", "DEPLOY_USER": "root", "DEPLOY_KEY": "KEY", "RUNNER_TEMP": temp,
            })
            command = deploy_server.rsync_command(remote, pathlib.Path("/repo/site"), pathlib.Path("/var/www/x"), deploy_server.SITE_SWITCHES)
            key = pathlib.Path(temp) / "deploy_key"
            self.assertEqual(
                ["rsync", "-rltvz", "--delete", "--chmod=D755,F644",
                 "-e", f"ssh -i {key} -o StrictHostKeyChecking=no -o IdentitiesOnly=yes",
                 "/repo/site/", "root@box.example:/var/www/x/"],
                command,
            )

    def test_a_laptop_rsyncs_through_its_host_alias(self):
        remote = deploy_server.Remote.from_environment({})
        self.assertEqual(
            ["rsync", "-rltvz", "--delete", "--chmod=D755,F644", "/repo/site/", "hetzner_gb:/var/www/x/"],
            deploy_server.rsync_command(remote, pathlib.Path("/repo/site"), pathlib.Path("/var/www/x"), deploy_server.SITE_SWITCHES),
        )


class BuildSyncTest(unittest.TestCase):
    """The server build goes up owned by the box's root, read-only to the server's user, stale jars deleted."""

    def test_the_workflow_rsyncs_the_install_directory_into_current(self):
        with tempfile.TemporaryDirectory() as temp:
            remote = deploy_server.Remote.from_environment({
                "DEPLOY_HOST": "box.example", "DEPLOY_USER": "root", "DEPLOY_KEY": "KEY", "RUNNER_TEMP": temp,
            })
            command = deploy_server.rsync_command(
                remote, deploy_server.INSTALL_DIR, deploy_server.CURRENT, deploy_server.BUILD_SWITCHES,
            )
            key = pathlib.Path(temp) / "deploy_key"
            self.assertEqual(
                ["rsync", "-rlptvz", "--delete", "--chmod=go-w",
                 "-e", f"ssh -i {key} -o StrictHostKeyChecking=no -o IdentitiesOnly=yes",
                 f"{deploy_server.ROOT}/server/build/install/gains-server/",
                 "root@box.example:/opt/gains-server/current/"],
                command,
            )

    def test_the_unit_runs_what_is_synced(self):
        unit = deploy_server.UNIT_FILE.read_text()
        self.assertIn(f"{deploy_server.CURRENT}/bin/{deploy_server.UNIT}", unit)

    def test_without_an_install_directory_nothing_is_sent(self):
        with tempfile.TemporaryDirectory() as temp, \
                unittest.mock.patch.object(deploy_server, "INSTALL_DIR", pathlib.Path(temp)), \
                unittest.mock.patch.object(deploy_server, "run") as run, \
                self.assertRaises(SystemExit):
            deploy_server.build(None)
        run.assert_not_called()


class NginxTest(unittest.TestCase):
    def test_each_site_names_the_certificates_it_needs(self):
        sites = {path.stem: path.read_text() for path in deploy_server.NGINX_DIR.glob("*.conf")}
        self.assertEqual(["api.gains.gerra.sh"], deploy_server.certificates(sites["api.gains.gerra.sh"]))
        self.assertEqual(["gains.gerra.sh"], deploy_server.certificates(sites["gains.gerra.sh"]))
        self.assertEqual([], deploy_server.certificates("# ssl_certificate /etc/letsencrypt/live/x/fullchain.pem"))

    def test_the_site_is_served_from_where_it_is_synced_to(self):
        config = (deploy_server.NGINX_DIR / f"{deploy_server.SITE_HOST}.conf").read_text()
        self.assertIn(f"root {deploy_server.WEB_ROOT};", config)

    def test_the_site_proxies_what_its_pages_post(self):
        """Each fetch() in a page goes to a path the vhost hands to the server."""
        config = (deploy_server.NGINX_DIR / f"{deploy_server.SITE_HOST}.conf").read_text()
        proxied = [
            re.compile(pattern.lstrip("^") if modifier == "~" else "^" + re.escape(pattern) + "$")
            for modifier, pattern in re.findall(r"location\s+(=|~)\s+(\S+)\s*\{[^}]*proxy_pass", config)
        ]
        for path in sorted(deploy_server.SITE_DIR.glob("*.html")):
            for url in re.findall(r'fetch\("([^"]+)"', path.read_text()):
                self.assertTrue(any(p.search(url) for p in proxied), f"{path.name} posts to {url}, which the vhost does not proxy")


class SitePagesTest(unittest.TestCase):
    """Every local link and image in site/ resolves the way nginx's try_files does."""

    class Links(html.parser.HTMLParser):
        def __init__(self):
            super().__init__()
            self.urls, self.ids = [], set()

        def handle_starttag(self, tag, attrs):
            attrs = dict(attrs)
            if "id" in attrs:
                self.ids.add(attrs["id"])
            for key in ("href", "src", "srcset"):
                if attrs.get(key):
                    self.urls.append(attrs[key])

    def pages(self):
        parsed = {}
        for path in sorted(deploy_server.SITE_DIR.glob("*.html")):
            links = self.Links()
            links.feed(path.read_text())
            parsed[path] = links
        return parsed

    def resolve(self, url):
        """The file nginx serves for a local path: the file itself, `<path>.html`, or an index."""
        relative = url.lstrip("/")
        candidates = [relative, f"{relative}.html", f"{relative}/index.html".lstrip("/")]
        for candidate in candidates:
            path = deploy_server.SITE_DIR / candidate
            if candidate and path.is_file():
                return path
        return None

    def test_local_links_resolve(self):
        pages = self.pages()
        self.assertIn(deploy_server.SITE_DIR / "privacy.html", pages)
        self.assertIn(deploy_server.SITE_DIR / "support.html", pages)
        # The pages the mails of email accounts link to (docs/launch-plan.md, item 18).
        self.assertIn(deploy_server.SITE_DIR / "verify.html", pages)
        self.assertIn(deploy_server.SITE_DIR / "reset.html", pages)
        for page, links in pages.items():
            for url in links.urls:
                if not url.startswith("/"):
                    continue
                path, _, fragment = url.partition("#")
                target = self.resolve(path) or self.fail(f"{page.name}: {url} does not exist")
                if fragment:
                    self.assertIn(fragment, pages[target].ids, f"{page.name}: {url}")

    def test_every_page_gives_the_contact_address(self):
        for page in ("index.html", "privacy.html", "support.html", "verify.html", "reset.html"):
            self.assertIn("mailto:gains@gerra.sh", (deploy_server.SITE_DIR / page).read_text(), page)


if __name__ == "__main__":
    unittest.main()
