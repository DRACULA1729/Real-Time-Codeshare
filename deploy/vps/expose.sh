#!/usr/bin/env bash
# Expose the codeshare app through OpenLiteSpeed on sushant-codeshare.duckdns.org.
# Touches: httpd_config.conf (append vhost + two map lines, backup taken first),
# two graceful restarts, a certbot deploy hook. Nothing under api/ or Example/.
set -euo pipefail
H=sushant-codeshare.duckdns.org
C=/usr/local/lsws/conf/httpd_config.conf
L=/usr/local/lsws/bin/lswsctrl

cp -n "$C" "$C.before-codeshare" && echo "backup: $C.before-codeshare"

# 1. Register the vhost and map it on the :80 listener.
grep -q "^virtualhost codeshare" "$C" || cat >> "$C" <<EOF

virtualhost codeshare {
  vhRoot                  codeshare/
  configFile              conf/vhosts/codeshare/vhconf.conf
  allowSymbolLink         1
  enableScript            0
  restrained              1
}
EOF
grep -q "map                     codeshare $H" "$C" || \
  sed -i "/^listener Default {/,/^}/ s#^\(  map                     api progixapi.work.gd.*\)#\1\n  map                     codeshare $H#" "$C"

mkdir -p /usr/local/lsws/codeshare/html/.well-known/acme-challenge
chown -R nobody:nogroup /usr/local/lsws/codeshare
"$L" restart >/dev/null; sleep 4
echo probe > /usr/local/lsws/codeshare/html/.well-known/acme-challenge/probe
curl -sf -m 10 "http://$H/.well-known/acme-challenge/probe" >/dev/null && echo "port 80 routes to codeshare vhost: ok"

# 2. Certificate, served from the codeshare vhost's own webroot.
certbot certonly --webroot -w /usr/local/lsws/codeshare/html -d "$H" \
  --non-interactive --agree-tos --keep-until-expiring --register-unsafely-without-email
test -f "/etc/letsencrypt/live/$H/fullchain.pem" && echo "cert: ok"

# 3. Map on the :443 listener now that the cert exists, then reload.
grep -q "map                     codeshare $H" <(sed -n '/^listener Defaultssl {/,/^}/p' "$C") || \
  sed -i "/^listener Defaultssl {/,/^}/ s#^\(  map                     api progix.org.*\)#\1\n  map                     codeshare $H#" "$C"
"$L" restart >/dev/null; sleep 4

# 4. Pick up renewed certs (graceful restart, every 60-90 days).
cat > /etc/letsencrypt/renewal-hooks/deploy/codeshare-lsws.sh <<EOF
#!/bin/sh
case "\$RENEWED_LINEAGE" in */$H) $L restart ;; esac
EOF
chmod +x /etc/letsencrypt/renewal-hooks/deploy/codeshare-lsws.sh

# 5. Verify.
echo "--- diff of httpd_config.conf"; diff "$C.before-codeshare" "$C" || true
echo "--- checks"
curl -s -o /dev/null -w "index: %{http_code}\n" -m 10 "https://$H/"
curl -s -o /dev/null -w "http->https redirect: %{http_code}\n" -m 10 "http://$H/"
curl -s -o /dev/null -w "progixapi.work.gd still: %{http_code}\n" -m 10 "http://progixapi.work.gd/"
curl -sk -o /dev/null -w "progix.org still: %{http_code}\n" -m 10 "https://progix.org/"
"$L" status
