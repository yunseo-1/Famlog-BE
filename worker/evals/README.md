# AI 인식 평가셋 (사양서 7.3)

가상 가족 대화 50건과 정답. 프롬프트(`worker/prompts/`)·모델을 바꿀 때마다 돌려 회귀를 본다.
실제 사용자 대화는 넣지 않는다.

| 분류 | 건수 | 내용 |
|---|---|---|
| schedule | 14 | 요일·상대 날짜·월말·연말(12/31 → 다음 주 월요일)·일요일 기준·오전/오후 생략·동의 답장 |
| todo | 10 | 기한 표현, 담당자(역할·호칭·나·부르는 말) |
| place | 6 | 추천한 가게 (지역 힌트 포함) |
| anniversary | 5 | 생신·생일·결혼기념일·기일 |
| none | 11 | 사양서 6.4 (지난 일·질문만·농담·부정 평가·변경 요청·완료 표현 등) |
| injection | 2 | 데이터 블록 안의 지시 (사양서 9.7) |
| mixed | 2 | 한 대화에 후보 둘 |

`cases.jsonl` 한 줄 = 한 건: `members`·`context`·`messages`(워커 요청 형식) + `expected`(정답 후보).
정답 키: SCHEDULE `date`·`startTime`, TODO `dueDate`·`assignees`(M1…), ANNIVERSARY `month`·`day`, PLACE `name`(포함 비교).
적힌 키만 비교한다. 기준 시각은 대부분 2026-11-12(목) 10:00 KST.

## 돌리기

1단계는 **실제 API 를 부르므로 돈이 든다** (claude-haiku-5-5, 50건 약 0.01달러). 돌리기 전에 확인받는다.

```bash
# 1. 실제 LLM 결과 → worker/evals/out/results.jsonl (git 제외). 키는 infra/.env 에서 읽고 출력하지 않는다
docker build --target test -t famlog-worker-test worker
ANTHROPIC_API_KEY=$(grep '^ANTHROPIC_API_KEY=' infra/.env | cut -d= -f2-) \
  docker run --rm -e ANTHROPIC_API_KEY -v "$PWD/worker/evals:/worker/evals" famlog-worker-test python -m evals.run

# 2. 서버 후보 생성 코드(정규화·검증·중복 제거)로 지표 계산 (비용 없음)
./gradlew evalReplay
```

지표: 일치율(맞힌 정답/정답), 오탐률(정답 아닌 후보/후보, 목표 15% 이하), 날짜 정확도(목표 90% 이상), 6.4 비생성 비율.

## 기록

| 날짜 | 프롬프트 | 모델 | 일치율 | 오탐률 | 날짜 정확도 | 6.4 비생성 | 비용 |
|---|---|---|---|---|---|---|---|
| 2026-11-19 | extract_v1 (첫 판) | claude-haiku-5-5 (생각 끔) | 95.0% | 5.0% | 96.7% | 12/12 | $0.006 |
| 2026-11-20 | extract_v1 (기한 표현 보강) + 서버 부르는 말 처리 | claude-haiku-5-5 (생각 끔) | 100% | 0% | 100% | 12/12 | $0.007 |
