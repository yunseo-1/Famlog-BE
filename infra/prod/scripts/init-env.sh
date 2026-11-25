#!/bin/sh
# 운영 비밀 파일(/opt/famlog/.env)을 만든다 (S22-1, 사양서 9.4: 운영 키는 서버에서 새로 만든다).
# 비어 있는 DB·Redis 비밀번호, 관리자 토큰, 백업 암호, JWT 키만 무작위로 채우고, 이미 있는 값은 바꾸지 않는다.
# 값은 출력하지 않고, 아직 직접 채워야 할 항목의 "이름"만 알려 준다.
# 사용: sudo sh infra/prod/scripts/init-env.sh
set -eu
. "$(dirname "$0")/lib.sh"

umask 077
mkdir -p "$(dirname "$ENV_FILE")" "$FAMLOG_HOME/secrets" "$FAMLOG_HOME/backups" "$FAMLOG_HOME/metrics"
chmod 755 "$FAMLOG_HOME/metrics"
chmod 700 "$FAMLOG_HOME/secrets" "$FAMLOG_HOME/backups"
if [ ! -f "$ENV_FILE" ]; then
  cp "$PROD_DIR/.env.example" "$ENV_FILE"
  echo "만들었습니다: $ENV_FILE"
fi
chmod 600 "$ENV_FILE"

for key in POSTGRES_PASSWORD REDIS_PASSWORD FAMLOG_ADMIN_TOKEN BACKUP_PASSPHRASE; do
  if [ -z "$(env_value "$key")" ]; then
    set_env "$key" "$(openssl rand -hex 32)"
    echo "생성: $key"
  fi
done
if [ -z "$(env_value JWT_PRIVATE_KEY)" ]; then
  JWT_KID_PREFIX=prod sh "$REPO_DIR/infra/scripts/gen-jwt-key.sh"
fi

missing=""
for key in FAMLOG_DOMAIN ACME_EMAIL S3_BUCKET_MEDIA KAKAO_APP_ID GOOGLE_CLIENT_ID KAKAO_REST_API_KEY ANTHROPIC_API_KEY \
    BACKUP_S3_URI; do
  [ -n "$(env_value "$key")" ] || missing="$missing $key"
done
for file in firebase-adminsdk.json discord-webhook-url; do
  [ -s "$FAMLOG_HOME/secrets/$file" ] || missing="$missing secrets/$file"
done
if [ -n "$missing" ]; then
  echo "직접 채울 항목:$missing"
  echo "  .env: sudo nano $ENV_FILE / 파일: $FAMLOG_HOME/secrets/ (권한 600, docs/runbooks/deploy.md)"
else
  echo "모든 항목이 채워져 있습니다"
fi
echo "BACKUP_PASSPHRASE 는 비밀번호 관리자에도 보관하세요 (잃으면 백업을 풀 수 없습니다)"
