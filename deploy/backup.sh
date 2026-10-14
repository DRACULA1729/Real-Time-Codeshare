#!/usr/bin/env bash
# Nightly pg_dump; keeps 14 local copies and, if rclone has an "r2" remote, pushes off-site.
# cron: 30 2 * * * /opt/codeshare/deploy/backup.sh >> /var/log/codeshare-backup.log 2>&1
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p backups
f="backups/codeshare-$(date +%F).sql.gz"
docker compose exec -T db pg_dump -U codeshare codeshare | gzip > "$f"
ls -1t backups/*.sql.gz | tail -n +15 | xargs -r rm --
if command -v rclone >/dev/null && rclone listremotes | grep -q '^r2:'; then
  rclone copy "$f" r2:codeshare-backups/
fi
echo "$(date -Is) ok $f $(du -h "$f" | cut -f1)"
