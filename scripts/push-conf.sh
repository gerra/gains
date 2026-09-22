#!/usr/bin/env bash
# Push the nginx vhost for gains.gerra.sh to the server and reload nginx
# (taxes pattern: skipped unless its certificate already exists).
# Usage: scripts/push-conf.sh   (REMOTE=hetzner_gb by default)
set -euo pipefail

REMOTE="${REMOTE:-hetzner_gb}"
NAME="gains.gerra.sh"
FILE="$(cd "$(dirname "$0")/.." && pwd)/deploy/nginx/$NAME.conf"

if ! ssh "$REMOTE" "test -f /etc/letsencrypt/live/$NAME/fullchain.pem"; then
    echo "SKIP $NAME — no certificate. Issue it first (needs DNS record):"
    echo "  ssh $REMOTE 'certbot certonly --nginx -d $NAME'"
    exit 1
fi

scp -q "$FILE" "$REMOTE:/etc/nginx/sites-available/$NAME"
ssh "$REMOTE" "ln -sfn /etc/nginx/sites-available/$NAME /etc/nginx/sites-enabled/$NAME && nginx -t && systemctl reload nginx"
echo "pushed $NAME; nginx reloaded."
