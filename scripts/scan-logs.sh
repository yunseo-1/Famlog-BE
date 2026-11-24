#!/bin/sh
# SEC-T9 (사양서 9.8, 9.9): 테스트 로그에 토큰·개인정보 패턴이 없는지 검사한다.
# 사용: sh scripts/scan-logs.sh <로그 파일 또는 디렉터리>...
#   서버: sh scripts/scan-logs.sh build/test-results/test   (JUnit XML 의 system-out·system-err)
#   워커: docker run --rm famlog-worker-test 2>&1 | tee worker-test.log && sh scripts/scan-logs.sh worker-test.log
# 찾으면 파일·줄 번호와 패턴 이름만 출력하고(값은 출력하지 않는다) 1로 끝난다.
set -eu

if [ "$#" -eq 0 ]; then
  echo "사용: sh scripts/scan-logs.sh <로그 파일 또는 디렉터리>..." >&2
  exit 2
fi

# 이름|정규식 (grep -E). 카나리는 LogCanaryTest.CANARY 와 같아야 한다
PATTERNS='카나리|kanari7f3a9c
JWT|eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.
Bearer 토큰|Bearer[[:space:]]+[A-Za-z0-9._~+/-]{20,}
FCM 토큰|fcm-[0-9a-f]{8}-[0-9a-f]{4}
소셜 토큰|"providerToken"[[:space:]]*:[[:space:]]*"[^"]+
Refresh 토큰|"refreshToken"[[:space:]]*:[[:space:]]*"[^"]+
출생연도월|"birthYm"[[:space:]]*:[[:space:]]*"[0-9]+
Anthropic 키|sk-ant-[A-Za-z0-9_-]{8,}
좌표|(lat|latitude)[=:"[:space:]]+3[3-8]\.[0-9]{4,}'

found=0
report=$(mktemp)
for target in "$@"; do
  if [ -d "$target" ]; then
    files=$(find "$target" -type f \( -name '*.xml' -o -name '*.log' -o -name '*.txt' \))
  else
    files="$target"
  fi
  for file in $files; do
    # JUnit XML 은 로그 부분(system-out·system-err)만 본다 (테스트 이름·실패 메시지 제외). 줄 번호는 원래 파일 기준
    case "$file" in
      *.xml) lines=$(awk '/<system-(out|err)>/{f=1} f{print NR":"$0} /<\/system-(out|err)>/{f=0}' "$file") ;;
      *) lines=$(awk '{print NR":"$0}' "$file") ;;
    esac
    echo "$PATTERNS" | while IFS='|' read -r name regex; do
      printf '%s\n' "$lines" | grep -E -- "$regex" | cut -d: -f1 | while read -r line; do
        echo "발견: $file:$line ($name)"
      done
    done
  done
done > "$report" 2>&1 || true

if [ -s "$report" ]; then
  cat "$report"
  found=1
fi
rm -f "$report"
if [ "$found" -eq 1 ]; then
  echo "로그에 토큰·개인정보 패턴이 있습니다 (위 위치 확인)." >&2
  exit 1
fi
echo "로그 검사 통과: 토큰·개인정보 패턴 없음"
