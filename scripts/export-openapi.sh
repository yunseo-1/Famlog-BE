#!/bin/sh
# API 계약(docs/api/openapi.yaml)을 문서 레포로 복사한다 (사양서 11.1: 계약은 문서 레포 docs/api/openapi.yaml 기준).
# 복사만 한다. 커밋·push는 문서 레포에서 직접 한다 (마지막에 명령을 안내한다).
#
# 사용: sh scripts/export-openapi.sh <문서 레포(yunseo-1/Famlog) 경로>
#   예: sh scripts/export-openapi.sh ~/IdeaProjects/Famlog
#
# 이 저장소의 docs/api/openapi.yaml 은 CI(OpenApiContractTest)가 코드와 같은지 확인한다.
# API를 바꿨다면 먼저 ./gradlew updateOpenApi 로 다시 만들고 이 저장소에 커밋한 뒤 실행한다.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/docs/api/openapi.yaml"
DOCS_REPO="${1:-}"

[ -n "$DOCS_REPO" ] || { echo "사용: sh scripts/export-openapi.sh <문서 레포 경로>" >&2; exit 1; }
[ -f "$SRC" ] || { echo "$SRC 가 없습니다. 먼저 ./gradlew updateOpenApi" >&2; exit 1; }
[ -d "$DOCS_REPO/.git" ] || { echo "$DOCS_REPO 는 git 저장소가 아닙니다" >&2; exit 1; }
[ -f "$DOCS_REPO/docs/api/README.md" ] || { echo "$DOCS_REPO 에 docs/api/ 가 없습니다. 문서 레포 경로가 맞나요?" >&2; exit 1; }

if [ -n "$(git -C "$ROOT" status --porcelain -- docs/api/openapi.yaml)" ]; then
  echo "경고: 이 저장소의 docs/api/openapi.yaml 이 아직 커밋되지 않았습니다." >&2
fi

DEST="$DOCS_REPO/docs/api/openapi.yaml"
if [ -f "$DEST" ] && cmp -s "$SRC" "$DEST"; then
  echo "문서 레포의 openapi.yaml 이 이미 최신입니다. 할 일이 없습니다."
  exit 0
fi

cp "$SRC" "$DEST"
COMMIT="$(git -C "$ROOT" rev-parse --short HEAD)"
echo "복사했습니다: $DEST (백엔드 커밋 $COMMIT 기준)"
echo
git -C "$DOCS_REPO" --no-pager diff --stat -- docs/api/openapi.yaml || true
echo
echo "문서 레포에서 커밋·push 하세요:"
echo "  cd \"$DOCS_REPO\""
echo "  git add docs/api/openapi.yaml"
echo "  git commit -m \"docs(api): openapi.yaml 갱신 (Famlog-BE $COMMIT)\""
echo "  git push"
