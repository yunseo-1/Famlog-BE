#!/bin/sh
# 인증서 갱신 (만료 30일 전부터 실제로 바꾼다). cron 이 하루 두 번 부른다 (infra/prod/famlog.cron)
set -eu
. "$(dirname "$0")/lib.sh"

compose run --rm --no-deps certbot renew --webroot -w /var/www/acme --quiet
compose exec nginx nginx -s reload
