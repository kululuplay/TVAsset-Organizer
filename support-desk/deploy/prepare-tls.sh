#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
test -d /opt/kululu-support/support-desk/deploy
install -d -m 755 /var/lib/kululu-support/acme
install -d -m 700 /etc/kululu-support /etc/kululu-support/acme /var/lib/kululu-support/certbot /var/log/kululu-support-certbot
if ! test -x /opt/kululu-support/certbot/bin/certbot; then
  if ! dpkg-query -W -f='${Status}' python3.10-venv 2>/dev/null | grep -q 'install ok installed'; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3.10-venv
  fi
  python3 -m venv /opt/kululu-support/certbot
  /opt/kululu-support/certbot/bin/pip install --disable-pip-version-check 'certbot==5.4.0'
fi
install -m 644 /opt/kululu-support/support-desk/deploy/nginx-acme.conf /etc/nginx/sites-available/kululu-support-acme
ln -sfn /etc/nginx/sites-available/kululu-support-acme /etc/nginx/sites-enabled/kululu-support-acme
nginx -t
systemctl reload nginx
/opt/kululu-support/certbot/bin/certbot certonly --non-interactive --agree-tos --register-unsafely-without-email \
  --config-dir /etc/kululu-support/acme --work-dir /var/lib/kululu-support/certbot --logs-dir /var/log/kululu-support-certbot \
  --webroot --webroot-path /var/lib/kululu-support/acme --ip-address 212.95.41.130 \
  --cert-name support-ip --preferred-profile shortlived --key-type rsa --rsa-key-size 2048 --preferred-chain 'ISRG Root X1'
openssl x509 -in /etc/kululu-support/acme/live/support-ip/cert.pem -noout -dates -ext subjectAltName
