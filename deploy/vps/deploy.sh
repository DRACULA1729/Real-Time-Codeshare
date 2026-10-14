#!/usr/bin/env bash
# Deploy a jar to the VPS: refuses while anyone is connected unless --force.
# Usage: deploy/vps/deploy.sh [--force] [path/to/app.jar]
# Rooms survive the restart (session_state + document_snapshot in Postgres), but
# everyone still sees ~20 s of "Reconnecting", so prefer a quiet moment.
set -euo pipefail
HOST=${CODESHARE_VPS:-codeshare-vps}
force=0; [[ "${1:-}" == "--force" ]] && { force=1; shift; }
jar=${1:-"$(dirname "$0")/../../target/realtime-codeshare-0.0.1-SNAPSHOT.jar"}
[[ -f "$jar" ]] || { echo "no jar at $jar (run ./mvnw -DskipTests package)"; exit 1; }

conns=$(ssh "$HOST" "curl -s 127.0.0.1:9091/actuator/prometheus | awk '/^codeshare_ws_connections/ {print int(\$2)}'" || echo "?")
if [[ "$conns" != "0" && $force -eq 0 ]]; then
  echo "refusing: $conns WebSocket connection(s) open on prod. Re-run with --force to restart anyway."
  exit 2
fi

scp -q "$jar" "$HOST:/opt/codeshare/app.jar.new"
ssh "$HOST" 'set -e
chown codeshare:codeshare /opt/codeshare/app.jar.new
cp -p /opt/codeshare/app.jar /opt/codeshare/app.jar.prev 2>/dev/null || true
mv /opt/codeshare/app.jar.new /opt/codeshare/app.jar
systemctl restart codeshare
for i in $(seq 1 30); do sleep 3; curl -sf 127.0.0.1:9091/actuator/health && exit 0; done
echo "health check failed; roll back with: mv /opt/codeshare/app.jar.prev /opt/codeshare/app.jar && systemctl restart codeshare"; exit 1'
echo; echo "deployed $(basename "$jar") ($(date +%H:%M))"
