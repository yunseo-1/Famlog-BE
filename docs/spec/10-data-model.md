# 10. 데이터 구조

DB: PostgreSQL 16 + PostGIS + pgvector. 스키마 변경은 Flyway 마이그레이션으로만 한다. 시간은 `timestamptz`(UTC 저장), 날짜만 있는 값은 `date`. 모든 테이블에 `created_at`, `updated_at`.

## 10.1 엔티티 목록

| 영역 | 테이블 | 단계 |
| --- | --- | --- |
| 계정·보안 | users, social_accounts, user_sessions, device_tokens, consents, account_deletion_requests, data_export_requests, audit_logs | ✅ |
| 그룹 | family_groups, group_members, group_invites, group_settings | ✅ |
| 채팅 | chat_rooms, messages, message_reactions, message_reads(또는 group_members.last_read_seq) | ✅ |
| 미디어 | media_assets | ✅ (분석 컬럼 v1.1) |
| 일정·할 일·장소 | schedules, schedule_participants, todos, places, place_marks, place_aliases, item_sources(원문 연결) | ✅ |
| 자동 등록 | registration_candidates, candidate_evidence, suggestion_feedback, ai_jobs | ✅ |
| 운영 | reports, holidays, app_versions, feature_flags, location_access_logs | ✅ |
| 차별화 | polls, poll_options, poll_votes, checkins | 🟦 |
| 앨범·영상 | albums, album_assets, videos, bgm_tracks, youtube_uploads, oauth_tokens | 🟦 |
| 스토리·공유 | stories, story_sources, share_links | 🟦 |
| AI 비서 | assistant_threads, assistant_messages, recommendations, message_chunks(임베딩) | 🟦 |

## 10.2 주요 테이블 컬럼

| 테이블 | 주요 컬럼·제약 |
| --- | --- |
| users | id, nickname, profile_image_key, birth_ym(char 6), age_verified_at, status(ACTIVE/PENDING_DELETION/DELETED), last_group_id, ui_mode(DEFAULT/SENIOR), deleted_at |
| social_accounts | user_id, provider(KAKAO/GOOGLE/APPLE), provider_user_id — UNIQUE(provider, provider_user_id) |
| user_sessions | id, user_id, device_id, device_name, platform(ANDROID/IOS), refresh_token_hash, last_used_at, expires_at, revoked_at |
| device_tokens | user_id, session_id, platform, fcm_token UNIQUE, app_version |
| consents | user_id, type(TERMS/PRIVACY/AI_ANALYSIS/OVERSEAS_TRANSFER/LOCATION), doc_version, agreed_at, withdrawn_at |
| group_members | group_id, user_id, role(OWNER/ADMIN/MEMBER), family_role, family_title, calendar_color, ai_opt_out, last_read_seq, notif_muted, joined_at, left_at — UNIQUE(group_id, user_id) |
| group_invites | group_id, code UNIQUE, created_by, expires_at, revoked_at, use_count |
| messages | id, room_id, seq(BIGINT, 방별 증가), sender_id NULL(시스템), client_message_id, type(TEXT/IMAGE/VIDEO/VOICE/PLACE/SYSTEM/CANDIDATE/POLL), body, payload(JSONB), reply_to_id, deleted_at — UNIQUE(room_id, seq), UNIQUE(sender_id, client_message_id) |
| media_assets | id, group_id, uploader_id, message_id, media_type, mime, size, storage_key, variants(JSONB 썸네일), width, height, duration_sec, taken_at, location(geography NULL), scan_status, phash, blur_score |
| schedules | id, group_id, title, starts_at, ends_at, all_day, rrule, calendar_type(SOLAR/LUNAR), lunar_month_day, place_id, memo, created_by, version |
| schedule_participants | schedule_id, user_id — PK(schedule_id, user_id), INDEX(user_id) |
| todos | id, group_id, kind(TASK/SHOPPING), title, assignee_id, due_date, done_at, done_by, created_by, version |
| places | id, group_id, kakao_place_id, name, category, category_detail, address, region1, region2, region3, geom(geography Point), rating, one_line_review, shared_by — INDEX(group_id, region1, region2), INDEX(group_id, shared_by), GIST(geom) |
| place_aliases | id, group_id, alias("할머니 댁"), place_id NULL, address_text NULL |
| registration_candidates | id, group_id, room_id, type, status(PENDING/APPROVED/DISMISSED/EXPIRED/UNDONE), fields(JSONB), inferred_fields, missing_fields, confidence, dedup_key, target_ref, decided_by, decided_at, dismiss_reason, created_entity_type, created_entity_id, model_version, prompt_version, expires_at, version — INDEX(group_id, dedup_key) |
| candidate_evidence | candidate_id, message_id, spans(JSONB) — PK(candidate_id, message_id) |
| item_sources | entity_type, entity_id, message_id — 등록 항목의 원문 연결 |
| reports | id, reporter_id, group_id, target_type(MESSAGE/MEDIA/AI_OUTPUT/USER), target_id, reason, detail, status(OPEN/REVIEWED/ACTIONED/REJECTED), handled_at |
| location_access_logs | id, user_id, purpose(NEARBY/PHOTO_LOCATION), accessed_at (좌표 저장 안 함) |
| audit_logs | id, actor_id, action, target_type, target_id, group_id, meta(JSONB), ip, created_at |
| holidays / app_versions / feature_flags | 공휴일(date, name, is_substitute) / 플랫폼별 min_supported_version, latest_version / key, enabled, rules(JSONB) |
| polls (v1.1) | id, group_id, message_id, title, closes_at, status, confirmed_option_id + poll_options(id, poll_id, date, time) + poll_votes(option_id, user_id) |
