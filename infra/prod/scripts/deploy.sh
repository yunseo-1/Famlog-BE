#!/bin/sh
# 운영 배포 (S22-1, docs/runbooks/deploy.md). GitHub Actions 가 승인 뒤 SSM 으로 부른다. 되돌리기도 같은 명령에 이전 SHA.
#   저장소를 그 커밋으로 맞춘 뒤 실행한다 (이 스크립트는 git 을 건드리지 않는다: 실행 중 파일이 바뀌지 않게)
#   cd /opt/famlog/app && git fetch -q origin && git checkout -q --detach <SHA> && sh infra/prod/scripts/deploy.sh <SHA>
# 순서: 설정 확인 → 이미지 받기 → 배포 전 DB 백업 → 교체(up --wait) → /readyz 확인
set -eu
. "$(dirname "$0")/lib.sh"

SHA=${1:-}
echo "$SHA" | grep -Eq '^[0-9a-f]{7,40}$' || fail "사용: deploy.sh <커밋 SHA>"
[ -f "$ENV_FILE" ] || fail "$ENV_FILE 가 없습니다 (init-env.sh)"

for key in FAMLOG_DOMAIN FAMLOG_IMAGE_REPO POSTGRES_PASSWORD REDIS_PASSWORD JWT_PRIVATE_KEY FAMLOG_ADMIN_TOKEN \
    BACKUP_PASSPHRASE S3_BUCKET_MEDIA; do
  [ -n "$(env_value "$key")" ] || fail "$ENV_FILE 에 $key 가 비어 있습니다"
done
SECRETS_DIR=$(env_value FAMLOG_SECRETS_DIR)
SECRETS_DIR=${SECRETS_DIR:-$FAMLOG_HOME/secrets}
for file in firebase-adminsdk.json discord-webhook-url; do
  [ -f "$SECRETS_DIR/$file" ] && [ -s "$SECRETS_DIR/$file" ] || fail "$SECRETS_DIR/$file 가 없습니다"
done

PREV=$(env_value FAMLOG_IMAGE_TAG)
set_env FAMLOG_IMAGE_TAG "$SHA"
SWAPPED=no
on_exit() {
  # 교체 전에 멈췄으면 지금 떠 있는 버전 그대로이므로 태그도 되돌린다
  [ "$SWAPPED" = yes ] || set_env FAMLOG_IMAGE_TAG "$PREV"
  echo "배포 실패: ${SHA}. 되돌리기: deploy.sh ${PREV:-<이전 SHA>} (DB 마이그레이션이 있었으면 docs/runbooks/deploy.md 참고)" >&2
}
trap 'on_exit' EXIT

echo "이미지 받기: $SHA"
compose pull --quiet

# 배포 전 백업 (Flyway 마이그레이션이 돌기 전 상태). DB 가 떠 있을 때만
if [ -n "$(compose ps --status running -q postgres 2>/dev/null)" ]; then
  echo "배포 전 DB 백업"
  sh "$REPO_DIR/infra/scripts/backup-db.sh"
fi

# 인증서가 아직 없으면 임시(자체 서명) 인증서로 Nginx 를 띄운다. 실제 발급은 init-cert.sh
if ! compose run --rm --no-deps --entrypoint sh certbot -c 'test -f /etc/letsencrypt/live/famlog/privkey.pem'; then
  echo "임시 인증서를 만듭니다 (다음: sh infra/prod/scripts/init-cert.sh)"
  sh "$PROD_DIR/scripts/dummy-cert.sh"
fi

echo "교체"
SWAPPED=yes
compose up -d --wait --wait-timeout 300 --remove-orphans
API_PORT=$(env_value API_HOST_PORT)
curl -fsS "http://127.0.0.1:${API_PORT:-8080}/readyz" > /dev/null || fail "/readyz 실패"

trap - EXIT
docker image prune -f > /dev/null
echo "배포 완료: $SHA (이전: ${PREV:-없음})"
