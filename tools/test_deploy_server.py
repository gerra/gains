"""The parts of deploy_server.py that need no box: where it connects, what it accepts as healthy,
how it installs nginx sites, and that the site it ships has no broken links."""

import html.parser
import os
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


class SiteSyncTest(unittest.TestCase):
    def test_the_workflow_rsyncs_with_the_deploy_key(self):
        with tempfile.TemporaryDirectory() as temp:
            remote = deploy_server.Remote.from_environment({
                "DEPLOY_HOST": "box.example", "DEPLOY_USER": "root", "DEPLOY_KEY": "KEY", "RUNNER_TEMP": temp,
            })
            command = deploy_server.rsync_command(remote, pathlib.Path("/repo/site"), pathlib.Path("/var/www/x"))
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
            deploy_server.rsync_command(remote, pathlib.Path("/repo/site"), pathlib.Path("/var/www/x")),
        )


class NginxTest(unittest.TestCase):
    def test_each_site_names_the_certificates_it_needs(self):
        sites = {path.stem: path.read_text() for path in deploy_server.NGINX_DIR.glob("*.conf")}
        self.assertEqual(["api.gains.gerra.sh"], deploy_server.certificates(sites["api.gains.gerra.sh"]))
        self.assertEqual(["gains.gerra.sh"], deploy_server.certificates(sites["gains.gerra.sh"]))
        self.assertEqual([], deploy_server.certificates(sites["default"]))

    def test_the_catch_all_is_the_only_default_server(self):
        for path in deploy_server.NGINX_DIR.glob("*.conf"):
            lines = [line for line in path.read_text().splitlines() if not line.lstrip().startswith("#")]
            says_default = any("default_server" in line for line in lines)
            self.assertEqual(path.stem == "default", says_default, path.name)

    def test_the_site_is_served_from_where_it_is_synced_to(self):
        config = (deploy_server.NGINX_DIR / f"{deploy_server.SITE_HOST}.conf").read_text()
        self.assertIn(f"root {deploy_server.WEB_ROOT};", config)


class InstallSitesTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = pathlib.Path(temp.name)
        self.staging, self.available, self.enabled, self.live, self.backups = (
            root / "staging", root / "available", root / "enabled", root / "live", root / "backup"
        )
        for directory in (self.staging, self.available, self.enabled, self.live):
            directory.mkdir()

    def stage(self, name, certificate=None):
        text = f"server {{ server_name {name}; }}\n"
        if certificate:
            text += f"    ssl_certificate /etc/letsencrypt/live/{certificate}/fullchain.pem;\n"
        (self.staging / f"{name}.conf").write_text(text)
        return text

    def issue(self, domain):
        (self.live / domain).mkdir()
        (self.live / domain / "fullchain.pem").write_text("cert")

    def install(self, config_ok=True):
        return deploy_server.install_sites(
            self.staging, self.available, self.enabled, self.live, lambda: config_ok, self.backups
        )

    def test_installs_and_links_every_site_with_a_certificate(self):
        api = self.stage("api.example", certificate="api.example")
        catch_all = self.stage("default")
        self.issue("api.example")
        installed, skipped = self.install()
        self.assertEqual(["api.example", "default"], installed)
        self.assertEqual({}, skipped)
        self.assertEqual(api, (self.available / "api.example").read_text())
        self.assertEqual(catch_all, (self.enabled / "default").read_text())
        self.assertEqual(self.available / "default", pathlib.Path(os.readlink(self.enabled / "default")))

    def test_a_site_without_its_certificate_is_skipped(self):
        self.stage("site.example", certificate="site.example")
        installed, skipped = self.install()
        self.assertEqual([], installed)
        self.assertEqual({"site.example": ["site.example"]}, skipped)
        self.assertFalse((self.available / "site.example").exists())
        self.assertFalse((self.enabled / "site.example").is_symlink())

    def test_a_replaced_site_is_backed_up(self):
        (self.available / "default").write_text("stock page")
        (self.enabled / "default").symlink_to(self.available / "default")
        self.stage("default")
        self.install()
        [backup] = self.backups.iterdir()
        self.assertEqual("stock page", backup.read_text())
        self.assertTrue(backup.name.startswith("default."))

    def test_a_failed_config_test_puts_everything_back(self):
        (self.available / "default").write_text("stock page")
        (self.enabled / "default").write_text("a plain file, not a link")
        self.stage("default")
        self.stage("new.example")
        with self.assertRaises(RuntimeError):
            self.install(config_ok=False)
        self.assertEqual("stock page", (self.available / "default").read_text())
        self.assertFalse((self.enabled / "default").is_symlink())
        self.assertEqual("a plain file, not a link", (self.enabled / "default").read_text())
        self.assertFalse((self.available / "new.example").exists())
        self.assertFalse((self.enabled / "new.example").is_symlink())


class FallbackCertificateTest(unittest.TestCase):
    def test_made_once_and_then_left_alone(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = pathlib.Path(temp) / "ssl"
            deploy_server.ensure_fallback_certificate(directory)
            certificate = (directory / "default.crt").read_bytes()
            self.assertIn(b"BEGIN CERTIFICATE", certificate)
            self.assertEqual(0o600, (directory / "default.key").stat().st_mode & 0o777)
            deploy_server.ensure_fallback_certificate(directory)
            self.assertEqual(certificate, (directory / "default.crt").read_bytes())

    def test_the_catch_all_uses_it(self):
        config = (deploy_server.NGINX_DIR / "default.conf").read_text()
        self.assertIn(f"ssl_certificate {deploy_server.FALLBACK_CERTIFICATE}/default.crt;", config)
        self.assertIn(f"ssl_certificate_key {deploy_server.FALLBACK_CERTIFICATE}/default.key;", config)


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
        for page, links in pages.items():
            for url in links.urls:
                if not url.startswith("/"):
                    continue
                path, _, fragment = url.partition("#")
                target = self.resolve(path) or self.fail(f"{page.name}: {url} does not exist")
                if fragment:
                    self.assertIn(fragment, pages[target].ids, f"{page.name}: {url}")

    def test_every_page_gives_the_contact_address(self):
        for page in ("index.html", "privacy.html", "support.html"):
            self.assertIn("mailto:gains@gerra.sh", (deploy_server.SITE_DIR / page).read_text(), page)


if __name__ == "__main__":
    unittest.main()
