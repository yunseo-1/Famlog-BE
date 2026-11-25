#!/bin/sh
# 인증서 자리에 1일짜리 자체 서명 인증서를 넣는다. Nginx 가 처음 뜰 수 있게 하는 용도다 (deploy.sh 가 부른다).
# init-cert.sh 가 Let's Encrypt 인증서로 바꾼다
set -eu
. "$(dirname "$0")/lib.sh"

DOMAIN=$(env_value FAMLOG_DOMAIN)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
openssl req -x509 -nodes -newkey rsa:2048 -days 1 -subj "/CN=api.$DOMAIN" \
  -keyout "$TMP/privkey.pem" -out "$TMP/fullchain.pem" 2>/dev/null
compose run --rm --no-deps -v "$TMP:/dummy:ro" --entrypoint sh certbot -c \
  'mkdir -p /etc/letsencrypt/live/famlog && cp /dummy/privkey.pem /dummy/fullchain.pem /etc/letsencrypt/live/famlog/'
