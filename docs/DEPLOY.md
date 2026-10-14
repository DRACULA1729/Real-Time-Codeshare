# Deploying to Oracle Cloud (Always Free ARM)

One VM runs everything: Caddy (TLS) → app → Postgres, via `docker compose`.
Cost target: ₹0. The Always Free allowance is 4 OCPU / 24 GB of Ampere A1 per
month; we use 2 OCPU / 12 GB and lock the account so nothing else can be created.

## 1. Oracle account (once)

1. Sign up at cloud.oracle.com. **Home region: Mumbai or Hyderabad.** It can't be
   changed later, and Always Free A1 only lives in the home region.
2. Console → Billing → **Upgrade to Pay As You Go**. Free-trial accounts get A1
   "out of capacity" errors almost permanently; PAYG accounts get placed. Billing
   is per provisioned hour, so a traffic spike on the same VM costs nothing.
3. **Quota policy** (Governance → Quotas → Create): everything zero except the
   free A1 shape and its disk. This is the guardrail; budgets only alert.

   ```
   zero compute-core quotas in tenancy
   zero compute-memory quotas in tenancy
   set compute-core quota standard-a1-core-count to 2 in tenancy
   set compute-memory quota standard-a1-memory-count to 12 in tenancy
   zero block-storage quotas in tenancy
   set block-storage quota total-storage-gb to 100 in tenancy
   zero database quotas in tenancy
   zero load-balancer quotas in tenancy
   ```

   Within one policy a later statement wins, so the `set` lines carve holes in the
   `zero` lines. Ordering matters. If the A1 quota name in your region also has a
   `-regional-count` variant listed under Limits, add the same `set` for it.
4. **Budget** (Billing → Budgets): amount ₹1, alert at 1% and at 100%, email.
   Second budget at ₹100 as a backstop.

## 2. VM

1. Compute → Instances → Create. Shape **VM.Standard.A1.Flex, 2 OCPU, 12 GB**.
   Image **Ubuntu 24.04 (aarch64)**. Boot volume 50 GB. Upload your SSH key.
   "Out of host capacity" means retry later (or a different AD); PAYG usually
   places within a few tries.
2. Networking → the VCN's public subnet → **Security List** → add ingress rules:
   `0.0.0.0/0` TCP 80 and TCP 443 (22 is already there).
3. Networking → IP Management → **Reserve a public IP** and assign it to the VNIC,
   so a rebuild keeps the address.
4. SSH in (`ubuntu@<ip>`). Ubuntu images on OCI ship an iptables REJECT rule that
   the security list does not override:

   ```sh
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
   sudo netfilter-persistent save
   ```

5. Docker:

   ```sh
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker ubuntu && newgrp docker
   sudo systemctl enable docker
   ```

## 3. DNS

Cloudflare (free): add the domain, create an **A record → the reserved IP, proxied
(orange cloud)**. SSL/TLS mode **Full (strict)**. Network → WebSockets on (default).
Caddy still gets its own Let's Encrypt cert; the HTTP-01 challenge passes through
Cloudflare on port 80. Cloudflare's 100 s idle timeout on proxied WebSockets is fine:
the app pings every few seconds.

## 4. App

```sh
sudo mkdir -p /opt/codeshare && sudo chown ubuntu /opt/codeshare
git clone https://github.com/DRACULA1729/Real-Time-Codeshare.git /opt/codeshare
cd /opt/codeshare/deploy
cp env.example .env && nano .env     # PUBLIC_HOST, DB_PASSWORD (openssl rand -hex 24)
docker compose up -d --build         # first build ~10 min on 2 OCPU
docker compose logs -f app           # wait for "Started RealtimeCodeshareApplication"
```

Then open `https://<PUBLIC_HOST>`. Health: `https://<PUBLIC_HOST>/actuator/health`.

Update:

```sh
cd /opt/codeshare && git pull && cd deploy
docker compose up -d --build && docker image prune -f
```

The stack has `restart: unless-stopped`, so a VM reboot brings it back.
Documents live in Postgres and survive redeploys. Session registries are in memory
(Phase B), so a restart invalidates open invite links.

## 5. Backups and monitoring

- Backup: `crontab -e` → `30 2 * * * /opt/codeshare/deploy/backup.sh >> /var/log/codeshare-backup.log 2>&1`.
  For off-site copies install rclone and configure a remote named `r2` (Cloudflare
  R2, 10 GB free); the script picks it up automatically.
  Restore: `zcat backups/<file> | docker compose exec -T db psql -U codeshare codeshare`.
- Uptime: UptimeRobot (free) on `https://<PUBLIC_HOST>/actuator/health`, 5-minute
  interval. This also keeps the VM off Oracle's idle-reclaim list only if real
  traffic does; the check itself is negligible. Reclaim needs CPU, memory **and**
  network all under 20% for 7 days, and 12 GB with the JVM resident already puts
  memory above that.
- Unattended OS patches: `sudo apt install unattended-upgrades` (default config is fine).

## 6. GitHub push (optional)

Create an OAuth app with callback `https://<PUBLIC_HOST>/api/github/callback`, put the
client id/secret and that exact URI into the env file, `docker compose up -d`.
