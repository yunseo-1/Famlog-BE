# 11. API

## 11.1 공통 규칙

- Base URL `/api/v1`, JSON, 시각은 ISO-8601 오프셋 포함(`2026-11-14T11:00:00+09:00`), 날짜만은 `2026-11-14`.
- 인증 `Authorization: Bearer {accessToken}`. 앱 버전 헤더 `X-App-Version`, 플랫폼 `X-Platform`.
- 목록은 커서 페이징 `?cursor=&size=` → `{ "items": [], "nextCursor": "..." }`.
- 생성 요청 중 재시도 가능한 것은 `Idempotency-Key` 헤더 지원(미디어 완료, 후보 승인).
- **API 계약:** springdoc으로 OpenAPI를 생성하고 `famlog/docs/api/openapi.yaml`로 내보내 커밋한다. 앱은 이 파일을 기준으로 구현한다.
- 최소 지원 버전보다 낮은 앱은 `426 UPGRADE_REQUIRED` → 앱이 업데이트 화면(SCR-00)으로 이동.

```
{ "code": "GROUP_NOT_MEMBER", "message": "이 가족 그룹의 구성원이 아니에요.", "traceId": "3f2a..." }
```

## 11.2 에러 코드

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 입력 검증 실패 (fieldErrors 포함) |
| 401 | AUTH_TOKEN_EXPIRED / AUTH_INVALID | 토큰 만료(재발급 시도) / 무효 |
| 403 | GROUP_NOT_MEMBER / GROUP_ROLE_REQUIRED | 그룹 구성원 아님 / 권한 부족 |
| 404 | NOT_FOUND | 없거나 접근 권한 없는 리소스 |
| 409 | CANDIDATE_ALREADY_DECIDED / VERSION_CONFLICT | 다른 사람이 먼저 처리 / 동시 수정 |
| 410 | INVITE_EXPIRED | 만료된 초대 코드 |
| 422 | CANDIDATE_MISSING_FIELDS | 필수 항목 누락 |
| 426 | UPGRADE_REQUIRED | 앱 업데이트 필요 |
| 429 | RATE_LIMITED | 요청 제한 (Retry-After) |

## 11.3 엔드포인트

| 기능 | Method | URI |
| --- | --- | --- |
| AUTH-01 | POST | /auth/{provider}/login, /auth/refresh, /auth/logout |
| AUTH-02 | POST | /users/me/age-verification |
| AUTH-03 | GET / DELETE | /users/me/sessions, /users/me/sessions/{sessionId} |
| ACC-01 | DELETE / POST | /users/me (삭제 요청), /users/me/restore (유예 중 복구) · 웹 /account-deletion |
| ACC-02 | POST / GET | /users/me/data-exports, /users/me/data-exports/{id} |
| 동의 | GET / PUT | /users/me/consents, /users/me/consents/{type} |
| GRP-01·05 | POST / GET / DELETE | /groups, /groups/me, /groups/{groupId}, /groups/{groupId}/leave |
| GRP-02 | POST | /groups/{groupId}/invites, /groups/join |
| GRP-03 | PUT | /users/me/current-group |
| GRP-04·05 | PATCH / DELETE | /groups/{groupId}/members/me, /groups/{groupId}/members/{userId} |
| SET-01·02 | GET / PATCH | /groups/{groupId}/settings, /groups/{groupId}/members/me/preferences |
| CHAT-03 | GET | /groups/{groupId}/messages?afterSeq= / ?beforeSeq=&size= |
| CHAT-04 | PUT | /groups/{groupId}/read (lastReadSeq) |
| CHAT-05 | POST | /media/upload-urls, /media/{mediaId}/complete |
| CHAT-06 | POST / DELETE | /messages/{id}/reactions, /messages/{id} |
| MOD-01 | POST | /reports |
| NOTI-01 | PUT / DELETE | /devices/token |
| SCH-01·02 | GET / POST / PATCH / DELETE | /groups/{groupId}/schedules?from=&to=&memberIds=&roles=, /schedules/{id} |
| SCH-04 | GET | /holidays?year= |
| TODO-01 | GET / POST / PATCH / DELETE | /groups/{groupId}/todos?kind=&assigneeId=&done=, /todos/{id} |
| PLC-01\~03 | GET / POST / PATCH / DELETE | /groups/{groupId}/places?region1=&region2=&sharedBy=&category=, /places/{id} |
| PLC-02 | GET | /groups/{groupId}/places/regions (지역별 개수) |
| PLC-04 | GET | /groups/{groupId}/places/nearby?lat=&lng=&radiusKm= (좌표 비저장) |
| PLC-01 | GET | /places/search?query=&x=&y= (카카오 로컬 프록시, 키 비노출) |
| PLC-06 | GET / POST / DELETE | /groups/{groupId}/place-aliases |
| ARC-05 | GET | /groups/{groupId}/candidates?status=PENDING&type=, /candidates/{id} |
| ARC-05 | POST | /candidates/{id}/approve, /candidates/{id}/dismiss, /candidates/{id}/undo |
| 운영 | GET | /app/config (최소 버전, 기능 플래그, 공지) |
| 🟦 v1.1\~ | - | /groups/{groupId}/media?month=, /albums, /videos, /stories, /polls, /share-links, /assistant/messages |

## 11.4 WebSocket (STOMP)

| 방향 | Destination | 내용 |
| --- | --- | --- |
| 앱 → 서버 | /pub/groups/{groupId}/messages | 메시지 전송 (clientMessageId 포함) |
| 앱 → 서버 | /pub/groups/{groupId}/read | 읽음 (lastReadSeq) |
| 서버 → 앱 | /sub/groups/{groupId} | message.created, message.deleted, read.updated, candidate.created, candidate.resolved |
| 서버 → 앱 | /user/queue/events | 개인 이벤트 (세션 폐기, 내보내기 완료, 영상 완료) |

서버가 여러 대일 때를 위해 STOMP 메시지는 Redis Pub/Sub(또는 외부 브로커 릴레이)로 서버 간에 중계한다. v1.0은 1대로 시작하되 이 구조로 만든다.
