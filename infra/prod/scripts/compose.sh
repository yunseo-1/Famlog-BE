#!/bin/sh
# 운영 compose 를 운영 .env 로 부른다 (점검·로그 확인용). 예: sudo sh infra/prod/scripts/compose.sh ps
#   sudo sh infra/prod/scripts/compose.sh logs --tail 100 api
set -eu
. "$(dirname "$0")/lib.sh"
compose "$@"
