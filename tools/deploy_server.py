#!/usr/bin/env python3
"""Putting the sync server on its box (docs/sync.md, "Deploying").

Nine commands, in three groups. From the deploy workflows, with the DEPLOY_HOST, DEPLOY_USER and
DEPLOY_KEY secrets in the environment:

  prepare   Write the key file and make sure the target directories exist on the box.
            The workflow's rsync step then copies the install directory into current/.
  install   Copy the systemd unit and this script to the box and run `remote` there.
  logs      Fetch the unit's journal into a file, for the failure artifact.
  site      Rsync site/ (the pages of gains.gerra.sh) to the web root on the box.

On the box itself, run over ssh by `install` and `nginx`:

  remote        Install a JDK if there is none, install the unit, restart, smoke test /health.
  remote-nginx  Install the staged nginx sites, test the configuration, reload or roll back.

From a laptop, against the host alias in REMOTE (hetzner_gb by default):

  secrets   Push secrets/.env (never in git, never in the rsync) and restart the unit.
  nginx     Push every site in deploy/nginx/ whose certificate exists and reload nginx.
            A site whose certificate is missing is skipped, with the certbot line to run.

Nothing here goes through a shell: every command is a list of arguments, so paths and secrets
are never re-parsed. The remote side is this same file, run with the box's python3.
"""

import argparse
import json
import os
import pathlib
import re
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent

HOME = pathlib.Path("/root/Projects/gains-server")
CURRENT = HOME / "current"
SECRETS = HOME / "secrets"
UNIT = "gains-server"
UNIT_FILE = ROOT / "deploy" / f"{UNIT}.service"
DATA_DIR = pathlib.Path("/var/lib/gains")
HEALTH = "http://127.0.0.1:5003/health"

# nginx: each deploy/nginx/<name>.conf becomes sites-available/<name>, linked from sites-enabled.
NGINX_DIR = ROOT / "deploy" / "nginx"
NGINX_STAGING = HOME / "nginx"
SITES_AVAILABLE = pathlib.Path("/etc/nginx/sites-available")
SITES_ENABLED = pathlib.Path("/etc/nginx/sites-enabled")
LETSENCRYPT_LIVE = pathlib.Path("/etc/letsencrypt/live")
# The catch-all (deploy/nginx/default.conf) needs some certificate to listen on 443; a
# self-signed one for no real name, made once on the box.
FALLBACK_CERTIFICATE = pathlib.Path("/etc/nginx/ssl")
CERTIFICATE = re.compile(r"^\s*ssl_certificate\s+/etc/letsencrypt/live/([^/\s]+)/", re.MULTILINE)

# The site: static files, no build step. nginx (www-data) reads them, so not under /root.
SITE_DIR = ROOT / "site"
SITE_HOST = "gains.gerra.sh"
WEB_ROOT = pathlib.Path("/var/www") / SITE_HOST


# --- talking to the box ------------------------------------------------------


class Remote:
    """One box, reached either through a host alias (laptop) or user@host with a key (CI)."""

    def __init__(self, target, key=None):
        self.target = target
        self.key = key

    @classmethod
    def from_environment(cls, env=None):
        """The deploy secrets when they are set (CI), else the REMOTE host alias (laptop)."""
        env = os.environ if env is None else env
        host, user, key = env.get("DEPLOY_HOST"), env.get("DEPLOY_USER"), env.get("DEPLOY_KEY")
        if host and user and key:
            path = pathlib.Path(env.get("RUNNER_TEMP", "/tmp")) / "deploy_key"
            path.write_text(key if key.endswith("\n") else key + "\n")
            path.chmod(0o600)
            return cls(f"{user}@{host}", key=path)
        return cls(env.get("REMOTE", "hetzner_gb"))

    def options(self):
        if self.key is None:
            return []
        return ["-i", str(self.key), "-o", "StrictHostKeyChecking=no", "-o", "IdentitiesOnly=yes"]

    def ssh(self, *command, check=True, capture=False):
        """Run a command on the box. Arguments are joined for the remote shell, so keep them simple."""
        return run("ssh", *self.options(), self.target, *command, check=check, capture=capture)

    def scp(self, source, destination):
        run("scp", "-q", *self.options(), str(source), f"{self.target}:{destination}")


def run(*command, check=True, capture=False):
    print("$ " + " ".join(str(c) for c in command), flush=True)
    return subprocess.run(
        [str(c) for c in command], check=check, text=True, capture_output=capture
    )


def fail(message):
    print(f"::error::{message}" if os.environ.get("GITHUB_ACTIONS") else f"error: {message}")
    sys.exit(1)


# --- from the workflow ---------------------------------------------------------


def prepare(args):
    remote = Remote.from_environment()
    remote.ssh("mkdir", "-p", str(CURRENT), str(SECRETS))


def install(args):
    remote = Remote.from_environment()
    remote.scp(UNIT_FILE, HOME / UNIT_FILE.name)
    remote.scp(pathlib.Path(__file__), HOME / "deploy_server.py")
    remote.ssh("python3", str(HOME / "deploy_server.py"), "remote")


def logs(args):
    remote = Remote.from_environment()
    result = remote.ssh("journalctl", "-u", UNIT, "-n", "500", "--no-pager", check=False, capture=True)
    pathlib.Path(args.into).write_text(result.stdout + result.stderr)


def site(args):
    remote = Remote.from_environment()
    remote.ssh("mkdir", "-p", str(WEB_ROOT))
    run(*rsync_command(remote, SITE_DIR, WEB_ROOT))
    status = fetch_status(f"https://{SITE_HOST}/privacy")
    if status == 200:
        print(f"https://{SITE_HOST}/privacy answers 200.")
    else:
        # Not fatal: the files are in place before the certificate and the vhost exist, and
        # `deploy_server.py nginx` is what makes them reachable (docs/launch-plan.md, item 3).
        warning = f"https://{SITE_HOST}/privacy answered {status or 'nothing'}; is the vhost pushed?"
        print(f"::warning::{warning}" if os.environ.get("GITHUB_ACTIONS") else f"warning: {warning}")


def rsync_command(remote, source, destination):
    """rsync of a directory's contents, deleting what is gone, readable by nginx's worker."""
    command = ["rsync", "-rltvz", "--delete", "--chmod=D755,F644"]
    if remote.options():
        command += ["-e", shlex.join(["ssh", *remote.options()])]
    return command + [f"{source}/", f"{remote.target}:{destination}/"]


def fetch_status(url):
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except (urllib.error.URLError, OSError):
        return 0


# --- on the box ----------------------------------------------------------------


def remote(args):
    """Runs as root on the box: the unit, the JDK, the restart and the smoke test."""
    try:
        if shutil.which("java") is None:
            print("--- installing a JDK")
            run("apt-get", "update", "-qq")
            run("apt-get", "install", "-y", "-qq", "openjdk-17-jre-headless")
        run("java", "-version")
        if not (SECRETS / ".env").is_file():
            print(
                "WARNING: secrets/.env is missing; run `python3 tools/deploy_server.py secrets` "
                "(the service will not start without JWT_SECRET)"
            )
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        run("install", "-m", "644", str(HOME / UNIT_FILE.name), f"/etc/systemd/system/{UNIT}.service")
        run("systemctl", "daemon-reload")
        run("systemctl", "enable", UNIT, check=False)
        run("systemctl", "restart", UNIT)
        status, body = wait_for_health()
        print(f"smoke test: HTTP {status} {body}")
        if not healthy(status, body):
            raise RuntimeError(f"smoke test failed: HTTP {status} {body}")
    except Exception as error:  # noqa: BLE001 - whatever failed, show the journal before leaving
        print(f"--- deploy failed: {error}")
        print("--- recent journal:")
        run("journalctl", "-u", UNIT, "-n", "150", "--no-pager", check=False)
        run("systemctl", "status", UNIT, "--no-pager", check=False)
        sys.exit(1)


def wait_for_health(attempts=10, pause=1.0):
    """The status and body of /health, tried for a few seconds while the JVM comes up."""
    status, body = 0, ""
    for _ in range(attempts):
        time.sleep(pause)
        try:
            with urllib.request.urlopen(HEALTH, timeout=5) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read().decode(errors="replace")
        except (urllib.error.URLError, OSError) as error:
            status, body = 0, str(error)
    return status, body


def healthy(status, body):
    """What the smoke test accepts: 200 and the server's own answer."""
    if status != 200:
        return False
    try:
        return json.loads(body).get("status") == "ok"
    except (ValueError, AttributeError):
        return False


# --- from a laptop -------------------------------------------------------------


def secrets(args):
    remote = Remote.from_environment()
    source = ROOT / "secrets" / ".env"
    if not source.is_file():
        fail("secrets/.env not found — copy secrets/.env.example first (secrets/README.md)")
    destination = SECRETS / ".env"
    stamp = time.strftime("%Y%m%d%H%M%S")
    remote.ssh("mkdir", "-p", str(SECRETS))
    remote.ssh("test", "!", "-f", str(destination), "||", "cp", str(destination), f"{destination}.bak.{stamp}")
    remote.scp(source, destination)
    remote.ssh("systemctl", "restart", UNIT, check=False)
    print("Secrets deployed; service restarted (if installed).")


def nginx(args):
    """Stage every site on the box, then let `remote-nginx` install them there."""
    remote = Remote.from_environment()
    remote.ssh("rm", "-rf", str(NGINX_STAGING / "sites"))
    remote.ssh("mkdir", "-p", str(NGINX_STAGING / "sites"))
    for path in sorted(NGINX_DIR.glob("*.conf")):
        remote.scp(path, NGINX_STAGING / "sites" / path.name)
    remote.scp(pathlib.Path(__file__), HOME / "deploy_server.py")
    result = remote.ssh("python3", str(HOME / "deploy_server.py"), "remote-nginx", check=False)
    if result.returncode != 0:
        fail("nginx was not changed; see the output above")


def remote_nginx(args):
    """Runs as root on the box: install the staged sites, `nginx -t`, then reload or undo."""
    ensure_fallback_certificate(FALLBACK_CERTIFICATE)
    try:
        installed, skipped = install_sites(
            NGINX_STAGING / "sites", SITES_AVAILABLE, SITES_ENABLED, LETSENCRYPT_LIVE,
            config_ok=lambda: run("nginx", "-t", check=False).returncode == 0,
            backups=NGINX_STAGING / "backup",
        )
    except RuntimeError as error:
        print(f"error: {error}")
        sys.exit(1)
    for name, missing in skipped.items():
        print(
            f"SKIPPED {name}: no certificate for {', '.join(missing)}. Issue it (needs the DNS "
            f"record), then run `deploy_server.py nginx` again:\n"
            + "\n".join(f"  certbot certonly --nginx -d {domain}" for domain in missing)
        )
    if installed:
        run("systemctl", "reload", "nginx")
        print(f"installed {', '.join(installed)}; nginx reloaded.")


def ensure_fallback_certificate(directory):
    """A self-signed default.crt / default.key in `directory`, unless they are already there."""
    certificate, key = directory / "default.crt", directory / "default.key"
    if certificate.is_file() and key.is_file():
        return
    directory.mkdir(parents=True, exist_ok=True)
    run(
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
        "-subj", "/CN=invalid", "-keyout", key, "-out", certificate,
    )
    key.chmod(0o600)


def certificates(config):
    """The Let's Encrypt names a site's configuration needs a certificate for."""
    return sorted(set(CERTIFICATE.findall(config)))


def install_sites(staging, available, enabled, live, config_ok, backups):
    """Copy each staged `<name>.conf` to `available/<name>` and link it from `enabled`.

    A site whose certificate isn't in `live` yet is skipped rather than installed, since nginx
    refuses to start with a missing certificate file. When `config_ok` (nginx -t) fails
    afterwards, every file and link is put back as it was, so a bad push never leaves nginx
    unable to reload. What a site replaces is also kept in `backups`, because the catch-all
    replaces the stock `default` page, which is not in the repository.

    Returns the names installed and, for each skipped one, the certificates it lacks.
    """
    stamp = time.strftime("%Y%m%d%H%M%S")
    installed, skipped, undo = [], {}, []
    for source in sorted(staging.glob("*.conf")):
        name = source.stem
        missing = [domain for domain in certificates(source.read_text())
                   if not (live / domain / "fullchain.pem").is_file()]
        if missing:
            skipped[name] = missing
            continue
        target, link = available / name, enabled / name
        undo.append((target, snapshot(target), link, snapshot(link)))
        if target.is_file():
            backups.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(target, backups / f"{name}.{stamp}")
        shutil.copyfile(source, target)
        if not (link.is_symlink() and pathlib.Path(os.readlink(link)) == target):
            if link.is_symlink() or link.exists():
                link.unlink()
            link.symlink_to(target)
        installed.append(name)
    if installed and not config_ok():
        for target, target_before, link, link_before in reversed(undo):
            restore(link, link_before)
            restore(target, target_before)
        raise RuntimeError("nginx -t failed; every site is back as it was and nginx was not reloaded")
    return installed, skipped


def snapshot(path):
    """What is at `path` now, for `restore`: nothing, a symlink's target, or a file's bytes."""
    if path.is_symlink():
        return ("link", os.readlink(path))
    if path.is_file():
        return ("file", path.read_bytes())
    return None


def restore(path, before):
    if path.is_symlink() or path.exists():
        path.unlink()
    if before is None:
        return
    kind, value = before
    if kind == "link":
        path.symlink_to(value)
    else:
        path.write_bytes(value)


# --- entry point ---------------------------------------------------------------


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    for name, handler, help_text in [
        ("prepare", prepare, "CI: write the key and create the target directories on the box"),
        ("install", install, "CI: copy the unit and this script to the box and run `remote` there"),
        ("remote", remote, "on the box: JDK, unit, restart, smoke test"),
        ("secrets", secrets, "laptop: push secrets/.env and restart the unit"),
        ("nginx", nginx, "laptop: push every site in deploy/nginx/ and reload nginx"),
        ("remote-nginx", remote_nginx, "on the box: install the staged sites, test, reload or undo"),
        ("site", site, "CI or laptop: rsync site/ to the web root on the box"),
    ]:
        commands.add_parser(name, help=help_text).set_defaults(handler=handler)
    logs_parser = commands.add_parser("logs", help="CI: fetch the unit's journal into a file")
    logs_parser.add_argument("--into", default="gains-server-journal.log")
    logs_parser.set_defaults(handler=logs)

    args = parser.parse_args(argv)
    args.handler(args)


if __name__ == "__main__":
    main()
