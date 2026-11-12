# FamLog 통합 사양서 v2.0 — 색인

> 이 폴더는 FamLog 개발 기준 문서(Single Source of Truth)다.
> **원본은 `famlog/docs/spec/`**이고, `famlog-backend/`와 `famlog-mobile/`의 `docs/spec/`은 `famlog/scripts/sync-spec.sh`로 복사한 사본이다.
> 사본에서는 내용을 고치지 않는다. 사양 변경은 원본(그리고 Notion 통합 사양서)을 먼저 고친 뒤 동기화한다.

## 장 목록

| 파일 | 제목 |
| --- | --- |
| [00-document-info.md](00-document-info.md) | 문서 정보와 변경 이력 |
| [01-service-overview.md](01-service-overview.md) | 1. 서비스 개요 |
| [02-users-permissions.md](02-users-permissions.md) | 2. 사용자 및 권한 |
| [03-screens.md](03-screens.md) | 3. 화면 구성 |
| [04-functional-requirements.md](04-functional-requirements.md) | 4. 기능 요구사항 |
| [05-differentiation.md](05-differentiation.md) | 5. 추천 차별화 기능 |
| [06-chat-auto-registration.md](06-chat-auto-registration.md) | 6. ⭐ 채팅 기반 자동 등록 상세 (ARC) |
| [07-ai-common.md](07-ai-common.md) | 7. AI 기능 공통 사양 |
| [08-privacy.md](08-privacy.md) | 8. 개인정보 보호 사양 |
| [09-security.md](09-security.md) | 9. 보안 사양 |
| [10-data-model.md](10-data-model.md) | 10. 데이터 구조 |
| [11-api.md](11-api.md) | 11. API |
| [12-non-functional.md](12-non-functional.md) | 12. 비기능 요구사항 |
| [13-architecture.md](13-architecture.md) | 13. 기술 아키텍처 |
| [14-gap-review.md](14-gap-review.md) | 14. 개발 누락·보완 검토 결과 |
| [15-testing.md](15-testing.md) | 15. 테스트 전략 |
| [16-release-checklist.md](16-release-checklist.md) | 16. 출시 준비 체크리스트 |
| [17-scope-schedule.md](17-scope-schedule.md) | 17. 릴리스 범위와 일정 |
| [18-acceptance-criteria.md](18-acceptance-criteria.md) | 18. 인수 기준 |
| [19-open-issues.md](19-open-issues.md) | 19. 오픈 이슈 (결정 필요) |
| [20-vibe-coding.md](20-vibe-coding.md) | 20. 바이브 코딩 가이드: 요청 순서와 프롬프트 |

## 기능 ID → 읽을 곳

기능 하나를 작업할 때는 **기능 요구사항 → 데이터 → API → 인수 기준** 순서로 같은 ID를 따라 읽고, 보안(9장) 해당 절을 함께 확인한다.

| 기능 ID 접두어 | 기능 요구사항 | 함께 볼 곳 |
| --- | --- | --- |
| AUTH, ACC, SET, SEC | 04 §4.1 | 08 개인정보, 09 §9.2 인증·세션, 10 users·user_sessions·consents |
| GRP | 04 §4.1 | 02 권한 매트릭스, 09 §9.3 인가, 10 group_members·group_invites |
| CHAT, MOD, NOTI | 04 §4.2 | 08 §8.6 사진 위치, 09 §9.1 위협 모델, 11 §11.4 WebSocket |
| SCH, TODO | 04 §4.3 | 10 schedules·todos·holidays |
| PLC | 04 §4.4 | 08 §8.6 위치정보, 10 places·place_aliases |
| ARC (자동 등록) | 04 §4.5 | **06 전체**, 07 AI 공통, 09 §9.7 프롬프트 인젝션 |
| ALB, VID, STORY | 04 §4.6~4.7 (v1.1) | 07, 08 §8.7 |
| AST | 04 §4.8 (v1.2) | 07, 09 §9.7 |
| DIF | 05 차별화 기능 | 해당 기능의 4장 ID |
| SCR-xx (화면) | 03 화면 목록 | `famlog/docs/design/figma-map.md` |

## 레포별로 자주 볼 장

| 레포 | 장 |
| --- | --- |
| famlog-backend | 04, 06, 07, 08, 09, 10, 11, 12, 13, 15, 18 |
| famlog-mobile | 03, 04, 06(§6.2 ③~⑤ 카드 UI), 08(§8.3 동의, §8.6 위치), 09(§9.5 앱 보안), 11, 12, 18 |
| 공통 | 17 일정, 19 오픈 이슈, 20 바이브 코딩 순서·프롬프트 |

## 규칙 요약 (자세한 내용은 각 장)

- AI 결과는 `registration_candidates`(PENDING)에만 저장하고, 실제 일정·할 일·장소는 승인 API에서만 만든다 (06).
- 모든 그룹 데이터 접근은 `GroupAccessChecker`를 거친다. 다른 그룹 리소스는 404 (09 §9.3).
- 메시지 본문·토큰·좌표·출생연도월을 로그에 남기지 않는다 (09 §9.8).
- 사양과 다르게 구현해야 하거나 사양이 애매하면 구현 전에 질문한다.
