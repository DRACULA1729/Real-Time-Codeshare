#!/usr/bin/env bash
# Nightly pg_dump; keeps 14 local copies.
# cron: 30 2 * * * /opt/codeshare/deploy/backup.sh >> /var/log/codeshare-backup.log 2>&1
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p backups
f="backups/codeshare-$(date +%F).sql.gz"
docker compose exec -T db pg_dump -U codeshare codeshare | gzip > "$f"
ls -1t backups/*.sql.gz | tail -n +15 | xargs -r rm --
echo "$(date -Is) ok $f $(du -h "$f" | cut -f1)"
