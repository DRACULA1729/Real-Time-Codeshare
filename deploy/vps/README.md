# Shared VPS deploy (no Docker)

Live at https://sushant-codeshare.duckdns.org on a shared 1 vCPU / 4 GB Ubuntu 24.04 VPS
behind OpenLiteSpeed. The server address and root access are not in this repo:
use the `codeshare-vps` ssh alias from `~/.ssh/config`. Nothing under `/usr/local/lsws/api/` or
`/usr/local/lsws/Example/` is ours; do not touch them.
For the Docker + Caddy variant on a dedicated VM see `../../docs/DEPLOY.md`.

## What lives where

| Thing | Path |
|---|---|
| Jar | `/opt/codeshare/app.jar`, run by `codeshare.service` as user `codeshare` |
| Config | `/etc/codeshare/env` (see `env.example`; root:codeshare, 640) |
| Listens | 127.0.0.1:8080 (app), 127.0.0.1:9091 (actuator, not proxied) |
| Database | `codeshare` in the host Postgres 16, role `codeshare` |
| Backups | `/var/backups/codeshare/*.sql.gz`, nightly 02:30, 14 kept (`codeshare-backup.cron`) |
| Proxy | OpenLiteSpeed vhost `codeshare` (`vhconf.conf`), mapped on the :80 and :443 listeners |
| Cert | `/etc/letsencrypt/live/sushant-codeshare.duckdns.org/`, certbot webroot, deploy hook restarts OLS |
| Logs | `journalctl -u codeshare`; `/usr/local/lsws/codeshare/logs/` |

DNS: DuckDNS A record `sushant-codeshare` -> the VPS (Sushant's DuckDNS account).

## Update the app

```sh
./mvnw -B -DskipTests clean package
scp target/realtime-codeshare-*.jar codeshare-vps:/opt/codeshare/app.jar
ssh codeshare-vps 'chown codeshare:codeshare /opt/codeshare/app.jar && systemctl restart codeshare && sleep 20 && curl -s 127.0.0.1:9091/actuator/health'
```

About 20 s of downtime; open invite links are lost (session registry is in memory).

## Restore a backup

```sh
zcat /var/backups/codeshare/codeshare-YYYY-MM-DD.sql.gz | sudo -u postgres psql codeshare
```

## Change the hostname

1. `CODESHARE_ALLOWED_ORIGINS` in `/etc/codeshare/env`, then `systemctl restart codeshare`.
2. `vhssl` paths in `/usr/local/lsws/conf/vhosts/codeshare/vhconf.conf`.
3. Edit `H=` in `expose.sh` and run it again: it adds the listener maps, gets the cert and
   restarts OpenLiteSpeed gracefully. Existing maps for the old name can be removed by hand.

## How it was set up (one time)

1. `apt install openjdk-21-jre-headless`; `useradd --system codeshare`.
2. Postgres role + database, `/etc/codeshare/env` with a generated password.
3. `codeshare.service` -> `/etc/systemd/system/`, `systemctl enable --now codeshare`.
4. `codeshare-backup.cron` -> `/etc/cron.d/`.
5. `vhconf.conf` -> `/usr/local/lsws/conf/vhosts/codeshare/`, then `expose.sh` as root.

Uptime monitor: check `https://sushant-codeshare.duckdns.org/` (the actuator port is not proxied).
