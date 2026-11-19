-- V11: 프롬프트 캐시 토큰 수 (사양서 7.2 비용 관리). 월 비용 = 토큰 종류별 수 × 단가 (application.yml famlog.ai.pricing)
-- input_tokens 는 캐시를 거치지 않은 입력만이다
ALTER TABLE ai_jobs ADD COLUMN cache_read_tokens INTEGER;   -- 캐시에서 읽은 입력
ALTER TABLE ai_jobs ADD COLUMN cache_write_tokens INTEGER;  -- 캐시에 쓴 입력

-- 이번 달 비용 합계용
CREATE INDEX ix_ai_jobs_created ON ai_jobs (created_at);
