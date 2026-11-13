#!/bin/sh
# 로컬 개발용 JWT 서명 키(ES256, P-256)를 만들어 infra/.env 에 넣는다.
# 이미 키가 있으면 바꾸지 않는다 (바꾸면 발급된 토큰이 모두 무효가 된다). 다시 만들려면 --force.
# 사용: sh infra/scripts/gen-jwt-key.sh [--force]
set -eu

ENV_FILE="$(cd "$(dirname "$0")/.." && pwd)/.env"
[ -f "$ENV_FILE" ] || { echo "infra/.env 가 없습니다. 먼저 cp infra/.env.example infra/.env" >&2; exit 1; }

if grep -q '^JWT_PRIVATE_KEY=..*' "$ENV_FILE" && [ "${1:-}" != "--force" ]; then
  echo "이미 JWT 키가 있습니다 ($ENV_FILE). 다시 만들려면 --force"
  exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
openssl ecparam -name prime256v1 -genkey -noout -out "$TMP/ec.pem" 2>/dev/null
PRIVATE=$(openssl pkcs8 -topk8 -nocrypt -in "$TMP/ec.pem" -outform DER | base64 | tr -d '\n')
PUBLIC=$(openssl ec -in "$TMP/ec.pem" -pubout -outform DER 2>/dev/null | base64 | tr -d '\n')
KID="local-$(date +%Y%m%d)"

# 기존 JWT_* 줄을 지우고 새로 붙인다
grep -v '^JWT_ACTIVE_KID=\|^JWT_PRIVATE_KEY=\|^JWT_PUBLIC_KEY=' "$ENV_FILE" > "$TMP/env" || true
{
  cat "$TMP/env"
  echo "JWT_ACTIVE_KID=$KID"
  echo "JWT_PRIVATE_KEY=$PRIVATE"
  echo "JWT_PUBLIC_KEY=$PUBLIC"
} > "$ENV_FILE"
echo "JWT 키를 만들었습니다: kid=$KID ($ENV_FILE)"
