# FamLog 인수인계 문서

- 작성일: 2026-11-12
- 인계: 기획·설계 (Claude 채팅) → 구현 (Claude Code)
- 담당자: 김윤서 (기획·백엔드·안드로이드, 1인 개발)

---

## 1. 지금까지 만든 것

| 산출물 | 위치 | 상태 |
|---|---|---|
| 프로젝트 허브 | Notion `FamLog (팸로그) - 가족 라이프 플랫폼` (`40dfc8bd-3e5e-81e7-a350-d047fb58d744`) | 완료 |
| **통합 사양서 v1.0** (개발 기준) | Notion `416fc8bd-3e5e-8128-aab8-dc6d43bbd7f2` | 완료 (7.4 채팅 기반 자동 등록 포함) |
| 기획서 | Notion `40dfc8bd-3e5e-811c-a214-ca77147cf511` | 배경·방향 문서 |
| 기능명세서 | Notion `40dfc8bd-3e5e-8190-b3bb-dbcadd1477ab` | 사양서로 대체됨 (참고용) |
| ERD 초안 | Notion `40dfc8bd-3e5e-81f6-b5e9-e8e2a5e9c7dc` | 컬럼 상세 참고용, 일부 구버전 (아래 4장) |
| 경쟁 서비스 분석 (카나나) | Notion `40dfc8bd-3e5e-8108-8869-dcf1b0f0cbe4` | 완료 |
| Figma 와이어프레임 + 디자인 패턴 | https://www.figma.com/design/1nECE5iK8TBDRSydCasO1j | 02 채팅방 화면만 미완 (아래 3장) |
| Figma 화면 지도 (로컬) | `docs/design/figma-map.md` | 이 패키지 |
| 디자인 토큰 / Compose 테마 초안 | `docs/design/design-tokens.json`, `docs/design/compose-theme/` | 이 패키지 |
| 화면 캡처 | `docs/design/screenshots/` | 2026-11-11~12 시점 스냅숏 |

## 2. 확정된 결정 (Decision Log)

| 날짜 | 결정 | 이유 |
|---|---|---|
| 11/03 | DB는 PostgreSQL 하나 (+PostGIS +pgvector) | 위치 검색·벡터 검색을 DB 하나로 |
| 11/03 | 채팅과 AI 처리는 큐(Redis Streams)로 분리 | AI 장애가 채팅에 영향 없게 |
| 11/11 | 서비스명 **FamLog (팸로그)** | 'Famory'는 Play에 같은 이름·같은 컨셉 앱이 있음 |
| 11/11 | Android 전용, Google Play 출시 목표 | 개인 계정 → 비공개 테스트 12명·14일 필요 |
| 11/11 | **1:1 채팅 없음**, 가족 그룹당 단체방 1개 | 데이터 범위 = 그룹 범위로 단순화 |
| 11/11 | 여러 그룹 소속 시 **앱 바에서 현재 그룹 전환** (채팅 탭만 전체 목록) | 친가·외가 데이터가 섞이지 않게 |
| 11/12 | 월간 스토리 우선 (주간은 추후) | 요구사항 |
| 11/12 | YouTube: v1.1은 저장+공유 시트, v1.2에 API 직접 업로드 | 감사 전 API 업로드 영상은 비공개 고정 |
| 11/12 | **AI 자동 등록은 후보 테이블 분리** (`registration_candidates`) | "AI가 임의로 등록하지 않는다" 요구사항을 구조로 보장 |
| 11/12 | 로그인: 카카오·Google 소셜 로그인 (제안, 오픈 이슈) | 부모님 세대 편의, 비밀번호 미보관 |

## 3. Figma 상태

- 파일 키: `1nECE5iK8TBDRSydCasO1j` / 페이지: `🎨 Design Patterns`(0:1), `📱 Wireframes`(2:2)
- 완료: 화면 01, 03~07, 컴포넌트 12종, 토큰(변수), 디자인 가이드(패턴 A~H)
- **미완: 02 채팅방 화면 재구성** — Figma MCP 호출 한도(Starter)로 중단됨.
  현재 02(`6:114`)는 구버전 메시지 내용이다. 카드 컴포넌트는 최신 구조로 바뀌어 있다.
  만들어야 할 화면은 `figma-map.md` 5장 "02 계열 목표 구조"에 적어 두었다.
  → 앱 구현은 **목표 구조 문서 + 컴포넌트 기준**으로 진행하면 된다. Figma 반영은 한도가 풀리면 따로.
- 04~06 캡처는 앱 바가 "장소/앨범/이야기"로 찍혀 있지만, 실제 Figma는 **"우리 가족 ▾"(그룹 전환)**으로 바뀌어 있다.

## 4. 알려진 불일치 (구현 시 이렇게 처리)

| 위치 | 내용 | 처리 |
|---|---|---|
| ERD `users` | email, password_hash 컬럼 | 소셜 로그인 확정 시 제거, `social_accounts` 사용 (사양서 5.2) |
| ERD `group_members` | family_role, ai_opt_out 없음 | 사양서 5.2대로 추가 |
| ERD `archive_items.status` | SUGGESTED/DISMISSED 상태 | 쓰지 않는다. 후보는 `registration_candidates` |
| ERD `messages.type` | ARCHIVE_SUGGESTION | `CANDIDATE` 카드 타입으로 바꾸고 metadata에 candidateId |
| ERD `album_videos` | 앨범 전용 | 사양서 5.2 `videos`(source_type)로 일반화 |
| ERD `stories.period_type` | WEEKLY 기본처럼 보임 | MONTHLY 기본 |
| `suggestion_feedback` | archive_item_id 기준 | `candidate_id` 기준으로 기록 (승인/수정/무시 모두) |
| 기능명세서 | 구 ID 체계(SUM-01 등) | 사양서 4장 ID만 사용 |

## 5. MVP 범위와 일정 (사양서 12·13장)

포함: 소셜 로그인·만 14세 처리·계정 삭제 / 그룹 생성·초대·전환·가족 역할 / 단체 채팅(사진·동영상·읽음·푸시) /
구성원별·전체 일정, 할 일 / 장소 공유·지역별·구성원별 목록·지도 / ⭐ 채팅 기반 자동 등록(일정·기념일·할 일·장소)

| 주차 | 기간 | 목표 |
|---|---|---|
| W1 | 11/16~11/22 | 개발 환경 (아래 6장) |
| W2~3 | 11/23~12/06 | AUTH, GRP-01~04, SCR-01~03·70 |
| W4~5 | 12/07~12/20 | CHAT-01~05, NOTI-01, Photo Picker EXIF 검증 |
| W6 | 12/21~12/27 | SCH-01~03, TODO-01 |
| W7 | 12/28~01/03 | PLC-01~04 |
| W8~9 | 01/04~01/17 | ⭐ ARC (사양서 7.4 전체), 테스트 대화 데이터셋 |
| W10 | 01/18~01/24 | ACC-01, SET-01, 개인정보처리방침, QA |
| W11~12 | 01/25~02/07 | 비공개 테스트 (12명·14일) |
| W13~14 | 02/08~02/21 | v1.0 출시 |

## 6. 다음 작업: W1 체크리스트

- [ ] 저장소 생성 (`famlog`), 이 패키지 파일 복사, `.mcp.json` 확인
- [x] 사양서 로컬 사본 → `docs/spec/` (통합 사양서 v2.0, 장별 Markdown + 색인 `README.md`)
- [ ] `infra/docker-compose.yml`: postgres(postgis+pgvector), redis, minio
- [ ] `backend/` Spring Boot 3 + Java 21 골격, Flyway V1 (users, social_accounts, family_groups, group_members, group_invites, chat_rooms)
- [ ] 공통: 에러 응답 형식(`code/message/traceId`), `GroupAccessChecker`, JWT 필터 골격
- [ ] `android/` Compose 골격 + `core/designsystem`에 `compose-theme/` 반영, 하단 탭 5개 빈 화면
- [ ] `worker/` FastAPI + Redis Streams consumer 골격 (`stream:messages`)
- [ ] GitHub Actions: backend test, android assembleDebug
- [ ] **기술 검증 스파이크**: Photo Picker로 고른 사진에 GPS EXIF가 남는지 (여행 감지 설계에 영향)

## 7. 오픈 이슈 (결정 필요)

- [ ] LLM 제공자·모델, 월 비용 상한
- [ ] 로그인 방식 확정 (소셜만 vs 이메일 병행)
- [ ] 만 14세 미만 지원 범위 (초기엔 14세 이상만 받는 안 검토)
- [ ] YouTube API 감사 신청 시점, BGM 음원 확보 방법
- [ ] KIPRIS 상표 확인 (FamLog / 팸로그)

## 8. 진행 현황 (Claude Code가 갱신)

| 날짜 | 기능 ID | 내용 | 커밋 |
|---|---|---|---|
| 2026-11-12 | - | 통합 사양서 v2.0 사본(`docs/spec/`), CLAUDE.md 사양서 v2.0 기준으로 갱신, `/feature` 명령 추가 | docs(spec) |
