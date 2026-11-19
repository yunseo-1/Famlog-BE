-- V8: 장소·다녀옴/가고 싶어요·별칭 장소, 위치 이용 목적 추가 (사양서 4.4 PLC-01~06, 8.6, 10.2)
-- 장소 좌표는 사람의 위치가 아니라 가게·관광지 정보라 일반 데이터로 저장한다 (8.6)

CREATE TABLE places (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id        BIGINT        NOT NULL REFERENCES family_groups (id),
    kakao_place_id  VARCHAR(30)   NOT NULL,
    name            VARCHAR(100)  NOT NULL,
    category        VARCHAR(20)   NOT NULL,                -- RESTAURANT, CAFE, TRAVEL, LIFE, ETC
    category_detail VARCHAR(100),                          -- 카카오 분류의 마지막 단계 (예: 냉면)
    address         VARCHAR(200)  NOT NULL,                -- 도로명 주소, 없으면 지번 주소
    region1         VARCHAR(20)   NOT NULL,                -- 시·도 (서울, 경기 …)
    region2         VARCHAR(30),                           -- 시·군·구 (송파구, 수원시 영통구). 세종처럼 없으면 NULL
    region3         VARCHAR(30),                           -- 읍·면·동
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    -- 거리 검색용. lat·lng 에서 만든다 (JPA 는 lat·lng 만 쓴다)
    geom            GEOGRAPHY(Point, 4326) GENERATED ALWAYS AS
                        (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED,
    phone           VARCHAR(30),
    place_url       VARCHAR(300),
    rating          SMALLINT,
    one_line_review VARCHAR(100),
    shared_by       BIGINT        NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_places_category CHECK (category IN ('RESTAURANT', 'CAFE', 'TRAVEL', 'LIFE', 'ETC')),
    CONSTRAINT ck_places_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_places_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT ck_places_lng CHECK (lng BETWEEN -180 AND 180),
    -- 같은 그룹에 같은 카카오 장소는 하나
    CONSTRAINT uq_places_group_kakao UNIQUE (group_id, kakao_place_id)
);
CREATE INDEX ix_places_group_region ON places (group_id, region1, region2);
CREATE INDEX ix_places_group_shared_by ON places (group_id, shared_by);
CREATE INDEX ix_places_geom ON places USING GIST (geom);

-- 다녀옴·가고 싶어요 (PLC-05). 구성원마다 장소별로 켜고 끈다
CREATE TABLE place_marks (
    place_id   BIGINT      NOT NULL REFERENCES places (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    kind       VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (place_id, user_id, kind),
    CONSTRAINT ck_place_marks_kind CHECK (kind IN ('VISITED', 'WANT'))
);

-- 별칭 장소 (PLC-06): "할머니 댁"처럼 검색이 안 되는 곳. 그룹 안에서만 보인다
CREATE TABLE place_aliases (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id     BIGINT       NOT NULL REFERENCES family_groups (id),
    alias        VARCHAR(30)  NOT NULL,
    place_id     BIGINT REFERENCES places (id) ON DELETE SET NULL,
    address_text VARCHAR(200),
    created_by   BIGINT       NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_place_aliases_group_alias UNIQUE (group_id, alias)
);

-- 개인위치정보 이용 확인자료 (8.6, V5 에서 만들었다): 장소 검색에 현재 위치를 보낸 경우도 남긴다
ALTER TABLE location_access_logs DROP CONSTRAINT ck_location_access_logs_purpose;
ALTER TABLE location_access_logs ADD CONSTRAINT ck_location_access_logs_purpose
    CHECK (purpose IN ('NEARBY', 'PHOTO_LOCATION', 'PLACE_SEARCH'));

-- 일정 장소 연결 (S12 에서 컬럼만 만들었다). 장소를 지우면 연결만 비운다
ALTER TABLE schedules
    ADD CONSTRAINT fk_schedules_place FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE SET NULL;
