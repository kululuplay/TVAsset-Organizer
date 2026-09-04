#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
cd /opt/kululu-support
test -f /etc/kululu-support/acme/live/support-ip/fullchain.pem
id kululu-support >/dev/null 2>&1 || useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin kululu-support
npm ci --omit=dev --ignore-scripts --no-audit --no-fund --registry=https://registry.npmjs.org
node --test support-desk/*.test.js
node support-desk/deploy/configure.js
install -m 644 support-desk/deploy/kululu-support.service /etc/systemd/system/kululu-support.service
install -m 644 support-desk/deploy/kululu-support-renew.service /etc/systemd/system/kululu-support-renew.service
install -m 644 support-desk/deploy/kululu-support-renew.timer /etc/systemd/system/kululu-support-renew.timer
install -m 644 support-desk/deploy/nginx-support.conf /etc/nginx/sites-available/kululu-support
ln -sfn /etc/nginx/sites-available/kululu-support /etc/nginx/sites-enabled/kululu-support
nginx -t
systemctl daemon-reload
systemctl enable --now kululu-support.service kululu-support-renew.timer
systemctl reload nginx
sleep 2
curl --fail --silent --show-error http://127.0.0.1:5086/healthz
curl --fail --silent --show-error https://212.95.41.130:8443/healthz
systemctl is-active kululu-support kstream-tv kululu-play
systemctl list-timers kululu-support-renew --no-pager
