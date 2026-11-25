#!/bin/sh
# Let's Encrypt 인증서를 처음 발급한다 (S22-1). 첫 배포(deploy.sh)로 Nginx 가 임시 인증서로 떠 있고,
# DNS 의 <도메인>·api.<도메인> 이 이 서버를 가리킬 때 한 번 실행한다. 갱신은 renew-cert.sh (cron)
# 사용: sudo sh infra/prod/scripts/init-cert.sh [--staging]   (--staging: 발급 한도 없는 시험 인증서)
set -eu
. "$(dirname "$0")/lib.sh"

DOMAIN=$(env_value FAMLOG_DOMAIN)
EMAIL=$(env_value ACME_EMAIL)
[ -n "$DOMAIN" ] && [ -n "$EMAIL" ] || fail "$ENV_FILE 에 FAMLOG_DOMAIN·ACME_EMAIL 을 채우세요"
STAGING=""
[ "${1:-}" = "--staging" ] && STAGING="--staging"

# 임시 인증서를 지우고 발급한다. Nginx 는 이미 읽어 둔 임시 인증서로 계속 응답한다
compose run --rm --no-deps --entrypoint sh certbot -c \
  'rm -rf /etc/letsencrypt/live/famlog /etc/letsencrypt/archive/famlog /etc/letsencrypt/renewal/famlog.conf'
compose run --rm --no-deps certbot certonly --webroot -w /var/www/acme --cert-name famlog \
  -d "$DOMAIN" -d "api.$DOMAIN" --email "$EMAIL" --agree-tos --no-eff-email --non-interactive $STAGING
compose exec nginx nginx -s reload
echo "인증서 발급 완료: $DOMAIN, api.$DOMAIN"
