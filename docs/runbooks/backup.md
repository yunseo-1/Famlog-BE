# DB 백업·복구 runbook

> 사양서 12장(DB 하루 1회 자동 백업, 7일 보관, 출시 전 복구 리허설 1회), 8.9(복구 시 삭제 대상 다시 적용), 9.4(백업 암호화).
> 스크립트: `infra/scripts/backup-db.sh`, `infra/scripts/restore-db.sh`. 원본은 이 레포, 문서 레포 `docs/runbooks/`에는 사본.

## 1. 무엇을 백업하나

| 대상 | 방법 | 보관 |
|---|---|---|
| PostgreSQL (모든 테이블) | `pg_dump -Fc` → openssl AES-256-CBC(PBKDF2 20만 회) 암호화 → `BACKUP_DIR` | 7일 (`BACKUP_RETENTION_DAYS`) |
| Redis | 백업하지 않는다. 세션 폐기 표시·요청 제한·분석 대기열·스트림뿐이라 잃어도 다시 만들어진다 (아래 5절) | - |
| S3 (사진·동영상·내보내기 ZIP) | 이 스크립트 범위 밖. S3 자체 내구성에 맡기고, 지우는 것은 앱 배치가 한다 (버전 관리는 끔: 삭제한 사진이 남지 않게, 사양서 8.9) | - |

- 암호(`BACKUP_PASSPHRASE`)는 `infra/.env`에만 둔다. **백업과 다른 곳(비밀번호 관리자 등)에도 보관한다. 잃으면 복구할 수 없다.**
- 백업 파일 옆에 `.sha256`이 생긴다. 복구 스크립트가 먼저 확인한다.
- 백업 디렉터리는 700, 파일은 600.
- 운영(S22-1): 암호화된 파일을 백업 전용 S3 버킷에도 올린다 (`BACKUP_S3_URI`, 버킷 수명 규칙으로 30일 뒤 삭제). [deploy.md](deploy.md) 2.2

## 2. 매일 자동 백업

```bash
# 운영: infra/prod/famlog.cron 이 /etc/cron.d/famlog 로 들어간다 (매일 03:00 KST = UTC 18:00)
0 18 * * * root cd /opt/famlog/app && FAMLOG_ENV_FILE=/opt/famlog/.env FAMLOG_COMPOSE_FILE=infra/prod/docker-compose.yml sh infra/scripts/backup-db.sh >> /var/log/famlog-backup.log 2>&1
```

- 성공하면 `백업 완료: famlog-<UTC시각>.dump.enc (... bytes)` 한 줄. 실패하면 0이 아닌 종료 코드와 `백업 실패: ...`.
- 배포할 때마다 `deploy.sh` 가 교체 직전에 한 번 더 백업한다.
- 실패 알림: 성공할 때마다 `BACKUP_METRICS_DIR/backup.prom` 에 시각을 남기고, 26시간 넘게 성공이 없으면 Discord 로 `BackupStale` 알림.
- 확인: 매주 한 번 `ls -l $BACKUP_DIR`로 최근 7개가 있는지 본다.

## 3. 복구 리허설 (출시 전 1회 + 분기마다)

```bash
sh infra/scripts/restore-db.sh ~/famlog-backups/famlog-<시각>.dump.enc famlog_restore_rehearsal
# → "복구 완료" + flyway 버전·사용자·메시지 수. 운영 DB와 테이블별 건수를 비교한 뒤
docker compose -f infra/docker-compose.yml --env-file infra/.env exec -T postgres \
  psql -U <POSTGRES_USER> -d postgres -c 'DROP DATABASE famlog_restore_rehearsal'
```

| 날짜 | 환경 | 결과 |
|---|---|---|
| 2026-11-25 | 로컬 (V17, 37개 테이블) | 백업 134KB, 복구 약 1초, 테이블 37개 건수 모두 일치 |

## 4. 실제 복구 (운영 DB 교체)

1. **알리기**: 점검 공지(관리자 도구 앱 버전 공지)와 사고라면 `incident.md` 절차를 함께 시작한다.
2. **멈추기**: API 서버와 워커를 멈춘다 (쓰기가 들어오지 않게).
3. **고르기**: 사고 직전의 가장 최근 백업을 고른다. 시각은 파일 이름(UTC).
4. **복구**: `sh infra/scripts/restore-db.sh <파일> --into-main` → DB 이름을 입력해 확인.
5. **복구 뒤 할 일** (아래 5절) → API·워커를 다시 띄우고 `/readyz`, 관리자 운영 배너를 확인한다.

## 5. 복구 뒤 할 일 (사양서 8.9: 삭제 대상 다시 적용)

백업 이후에 일어난 일은 사라진다. 특히 **지워야 할 개인정보가 되살아나지 않게** 다시 적용한다.

1. **백업 이후의 삭제 요청**: 서버 로그에서 백업 시각 이후 줄을 찾는다.
   - `계정 삭제 요청: userId=..` → 관리자 도구 `POST /admin/api/users/{userId}/deletion` (`deleteMedia`는 로그 값 그대로)
   - `그룹 삭제 예정: groupId=..` → 그 그룹 그룹장에게 알리거나 DB에서 같은 상태로 되돌린다 (`status='PENDING_DELETION'`, 유예 30일)
   - `계정 파기`·`그룹 파기` 로그의 대상은 위 요청이 다시 적용되면 다음 배치(04:00·04:30 KST)가 다시 파기한다
2. **유예가 끝난 요청**: 복구된 DB에 남아 있는 REQUESTED 삭제 요청은 다음 배치가 처리한다. 당장 처리해야 하면 배치 시각까지 기다리지 말고 공지 후 수동으로 진행한다.
3. **세션**: 백업 이후 로그아웃·기기 삭제가 사라진다. 사고 복구라면 전 세션 폐기를 검토한다 (`incident.md` 3절).
4. **S3와 어긋남**: 백업 이후 지운 사진은 DB 행만 돌아온다 (보기 404). 매시 미디어 정리는 붙지 않은·삭제된 메시지 미디어만 지우므로, 필요하면 해당 메시지를 관리자 숨김 처리한다.
5. **Redis**: 분석 대기열·세션 폐기 표시가 DB와 어긋날 수 있다. 세션을 전부 폐기했다면 Redis 를 비워도 된다 (요청 제한 카운터·트리거만 초기화된다).
6. **기록**: 복구 시각, 사용한 백업, 잃은 구간, 다시 적용한 요청 수를 `incident.md` 기록 양식에 남긴다.
