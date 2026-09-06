#!/usr/bin/env bash
# Bootstrap Let's Encrypt certificates for the domains served by deploy/nginx/nginx.conf.
# Run once from the deploy/ directory on the EC2 host, after `docker compose up -d backend`.
set -euo pipefail
cd "$(dirname "$0")"

DOMAINS=(lab-calendar.cloud www.lab-calendar.cloud)
RSA_KEY_SIZE=4096

if [ -z "${CERTBOT_EMAIL:-}" ]; then
  echo "CERTBOT_EMAIL env var is required (used for Let's Encrypt expiry notices)"
  exit 1
fi

domain_args=""
for domain in "${DOMAINS[@]}"; do
  domain_args="$domain_args -d $domain"
done

echo "### Creating dummy certificate for ${DOMAINS[0]} ..."
docker compose run --rm --entrypoint /bin/sh certbot -c "\
  mkdir -p /etc/letsencrypt/live/${DOMAINS[0]} && \
  openssl req -x509 -nodes -newkey rsa:$RSA_KEY_SIZE -days 1 \
    -keyout /etc/letsencrypt/live/${DOMAINS[0]}/privkey.pem \
    -out /etc/letsencrypt/live/${DOMAINS[0]}/fullchain.pem \
    -subj '/CN=localhost'"

echo "### Starting nginx ..."
docker compose up -d nginx

echo "### Deleting dummy certificate ..."
docker compose run --rm --entrypoint /bin/sh certbot -c "\
  rm -rf /etc/letsencrypt/live/${DOMAINS[0]} && \
  rm -rf /etc/letsencrypt/archive/${DOMAINS[0]} && \
  rm -rf /etc/letsencrypt/renewal/${DOMAINS[0]}.conf"

echo "### Requesting real certificate ..."
docker compose run --rm certbot certonly --webroot -w /var/www/certbot \
    $domain_args \
    --email "$CERTBOT_EMAIL" \
    --rsa-key-size $RSA_KEY_SIZE \
    --agree-tos \
    --non-interactive

echo "### Reloading nginx ..."
docker compose exec nginx nginx -s reload

echo "### Done. Certificates issued for: ${DOMAINS[*]}"
