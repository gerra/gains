#!/usr/bin/env bash
# Push secrets/.env to the server (never in git, never in the CI rsync) and
# restart the service. Backs up the previous remote .env first.
set -euo pipefail

REMOTE="${REMOTE:-hetzner_gb}"
SRC="$(cd "$(dirname "$0")/.." && pwd)/secrets/.env"
DEST=/root/Projects/gains-server/secrets/.env

[ -f "$SRC" ] || { echo "secrets/.env not found — copy secrets/.env.example first"; exit 1; }

ssh "$REMOTE" "mkdir -p /root/Projects/gains-server/secrets && if [ -f $DEST ]; then cp $DEST $DEST.bak.\$(date +%Y%m%d%H%M%S); fi"
scp "$SRC" "$REMOTE:$DEST"
ssh "$REMOTE" "systemctl restart gains-server 2>/dev/null || true"
echo "Secrets deployed; service restarted (if installed)."
