"""평가셋 1단계: 가상 가족 대화(cases.jsonl)로 실제 LLM 을 불러 결과를 out/results.jsonl 에 저장한다 (사양서 7.3).

실제 API 를 부르므로 돈이 든다 (claude-haiku-5-5 로 60건 약 0.01달러). 돌리기 전에 사용자 확인을 받는다.
2단계(날짜 정확도 등 지표)는 서버 정규화를 거쳐야 해서 API 저장소의 ./gradlew evalReplay 가 계산한다.

    docker run --rm -e ANTHROPIC_API_KEY -v "$PWD/worker/evals:/worker/evals" famlog-worker-test python -m evals.run

실제 사용자 대화는 여기에 쓰지 않는다 (가상 데이터만). 키는 출력하지 않는다.
"""

import json
import os
import sys
from dataclasses import replace
from pathlib import Path

from app.ai.llm import AnthropicLlmClient
from app.ai.pipeline import process
from app.ai.schemas import ExtractJob
from app.config import Settings

HERE = Path(__file__).resolve().parent
PRICE = {"input": 0.10, "output": 0.50, "cache_read": 0.01, "cache_write": 0.125}  # 100만 토큰당 달러


def main() -> int:
    settings = replace(Settings.from_env(), llm_provider="anthropic")
    client = AnthropicLlmClient(settings)
    cases = [json.loads(line) for line in (HERE / "cases.jsonl").read_text(encoding="utf-8").splitlines() if line]
    out_dir = HERE / "out"
    out_dir.mkdir(exist_ok=True)
    totals = dict.fromkeys(PRICE, 0)
    failed = 0
    with (out_dir / "results.jsonl").open("w", encoding="utf-8") as out:
        for number, case in enumerate(cases, 1):
            payload = {"timezone": "Asia/Seoul", "members": case["members"], "context": case["context"],
                       "messages": case["messages"]}
            job = ExtractJob.model_validate({"jobId": number, "payload": json.dumps(payload, ensure_ascii=False)})
            try:
                result = process(job, client)
            except Exception as e:  # 한 건 실패로 전체를 멈추지 않는다
                print(f"{case['id']}: 호출 실패 {type(e).__name__}", file=sys.stderr)
                failed += 1
                continue
            for key, field in [("input", "input_tokens"), ("output", "output_tokens"),
                               ("cache_read", "cache_read_tokens"), ("cache_write", "cache_write_tokens")]:
                totals[key] += getattr(result, field) or 0
            out.write(json.dumps({
                "id": case["id"], "status": result.status, "errorCode": result.error_code,
                "model": result.model_version, "prompt": result.prompt_version,
                "candidates": [c.model_dump(by_alias=True, exclude_none=True) for c in result.candidates],
            }, ensure_ascii=False) + "\n")
            print(f"{case['id']}: {result.status} candidates={len(result.candidates)} {result.error_code or ''}")
    cost = sum(totals[k] * PRICE[k] for k in PRICE) / 1_000_000
    print(f"토큰 {totals}  비용 약 ${cost:.4f}  호출 실패 {failed}건")
    print(f"결과: {out_dir / 'results.jsonl'} → API 저장소에서 ./gradlew evalReplay")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    if not os.environ.get("ANTHROPIC_API_KEY"):
        sys.exit("ANTHROPIC_API_KEY 가 필요합니다")
    sys.exit(main())
