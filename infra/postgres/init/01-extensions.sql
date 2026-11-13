-- 확장만 만든다. 테이블은 Flyway 마이그레이션(src/main/resources/db/migration)에서만 만든다.
-- 이 스크립트는 데이터 볼륨이 비어 있을 때(최초 기동) 한 번만 실행된다.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
