# 운영 배포 runbook (S22-1)

> 사양서 13장(MVP: VM 1대 + Docker Compose + S3), 9.4(TLS·비밀 관리), 12장(관측·백업), 16.3(운영 준비).
> 파일: `infra/prod/` (Compose·Nginx·스크립트·AWS 정책), `.github/workflows/deploy.yml`.
> 백업·복구는 [backup.md](backup.md), 사고 대응은 [incident.md](incident.md).

## 1. 구성

```
앱 ──HTTPS──▶ Nginx(80·443, Let's Encrypt) ──▶ API(:8080) ──▶ Postgres · Redis (컨테이너, 외부 비공개)
                                               │                └▶ 워커(사진 처리·AI 분석)
                                               └▶ S3 (파일, 인스턴스 역할로 접근)
Prometheus · Alertmanager(→ Discord) · node-exporter: 서버 안에서만 (SSM 터널로 본다)
```

| 항목 | 값 (2026-11-25 결정) |
|---|---|
| 서버 | AWS 서울(ap-northeast-2) EC2 `t3.medium`(2vCPU·4GB) + gp3 30GB, Ubuntu 24.04 LTS, 스왑 2GB |
| 도메인 | `famlog.kr` (앱 API `https://api.famlog.kr`, 계정 삭제 안내·초대 링크 `https://famlog.kr`) |
| 열린 포트 | 80·443 만. SSH(22)는 열지 않는다 → 서버 접속은 SSM Session Manager |
| 비밀 | 서버 `/opt/famlog/.env`(600), `/opt/famlog/secrets/`(700). 운영 키는 서버에서 새로 만든다 |
| 배포 | main 머지 → 테스트 통과 → 이미지(GHCR) → **사용자 승인** → SSM 으로 `deploy.sh` |
| 알림 | 서버 장애: Alertmanager → Discord. 비용: AWS Budgets 80% → 이메일 |
| 백업 | 매일 03:00 KST + 배포 직전. 서버 7일 + 백업 버킷 30일 |

서버 폴더:

```
/opt/famlog/
├ app/          저장소 (배포한 커밋으로 맞춰 둔다. 직접 고치지 않는다)
├ .env          운영 비밀 (infra/prod/.env.example 형식)
├ secrets/      firebase-adminsdk.json, discord-webhook-url
└ backups/      암호화된 DB 백업 (7일)
```

## 2. 처음 한 번: AWS·GitHub 준비

아래 `ACCOUNT_ID`(AWS 계정 번호 12자리), 버킷 이름은 실제 값으로 바꾼다. 콘솔에서 해도 되고 AWS CLI 로 해도 된다.

### 2.1 도메인

1. `famlog.kr` 구매 (가비아·후이즈 등 .kr 등록 대행). 2026-11-25 기준 미등록 확인.
2. 서버를 만든 뒤(2.4) DNS 에 A 레코드 두 개: `famlog.kr`, `api.famlog.kr` → 탄력적 IP.

### 2.2 S3 버킷 두 개 (서울 리전)

| 버킷 | 용도 | 설정 |
|---|---|---|
| `famlog-media-<임의 문자>` | 사진·동영상 | 퍼블릭 액세스 차단(기본값 유지), 기본 암호화(SSE-S3) |
| `famlog-backup-<임의 문자>` | DB 백업 사본 | 위와 같음 + 수명 규칙 `infra/prod/aws/backup-bucket-lifecycle.json`(`db/` 30일 뒤 삭제) |

```bash
aws s3api put-bucket-lifecycle-configuration --bucket famlog-backup-xxxx \
  --lifecycle-configuration file://infra/prod/aws/backup-bucket-lifecycle.json
```

앱은 Presigned URL 로 S3 에 직접 올리고 받는다 (Android 앱이라 CORS 설정은 필요 없다).

### 2.3 서버 역할 (IAM)

1. 역할 `famlog-ec2` (신뢰: EC2) 를 만든다.
2. 관리형 정책 `AmazonSSMManagedInstanceCore` 를 붙인다 (SSM 접속·배포).
3. 인라인 정책: `infra/prod/aws/instance-role-policy.json` 에서 `MEDIA_BUCKET`·`BACKUP_BUCKET` 을 실제 이름으로 바꿔 붙인다.

### 2.4 EC2

| 설정 | 값 |
|---|---|
| AMI | Ubuntu Server 24.04 LTS (x86_64) |
| 유형 | t3.medium |
| 키 페어 | 없음 (SSH 를 쓰지 않는다) |
| 보안 그룹 | 인바운드 80·443 (0.0.0.0/0, ::/0) 만 |
| 스토리지 | gp3 30GB |
| IAM 인스턴스 프로파일 | `famlog-ec2` |
| 태그 | `Name=famlog-prod`, **`famlog-role=prod`** (배포가 이 태그로 서버를 찾는다) |
| 메타데이터 | IMDSv2 필수, **홉 제한 2** (컨테이너 안에서 인스턴스 역할을 쓰기 위해) |

만든 뒤 탄력적 IP 를 붙이고 2.1 의 DNS 를 건다.

### 2.5 GitHub → AWS 배포 역할 (OIDC, 키 저장 없음)

1. IAM > 자격 증명 공급자 > OpenID Connect 추가: URL `https://token.actions.githubusercontent.com`, 대상 `sts.amazonaws.com`.
2. 역할 `famlog-github-deploy`: 신뢰 정책 `infra/prod/aws/deploy-role-trust.json`, 인라인 정책 `infra/prod/aws/deploy-role-policy.json` (둘 다 `ACCOUNT_ID` 바꾸기).
   이 역할은 `famlog-role=prod` 태그가 붙은 서버에 `AWS-RunShellScript` 만 보낼 수 있고, `production` 환경의 워크플로만 쓸 수 있다.
3. GitHub 저장소 > Settings:
   - Environments > `production` 만들기 → Required reviewers 에 본인, Deployment branches 는 `main` 만.
   - Secrets and variables > Actions > Variables: `AWS_DEPLOY_ROLE_ARN` = 위 역할 ARN.

### 2.6 비용 알림

AWS Budgets > 월 예산(예: 7만 원 상당 USD) → 실제 비용 80% 에 이메일 알림. Claude API 는 Console 의 사용 한도와 앱 안 월 상한(7.2)이 따로 막는다.

### 2.7 Discord 웹훅

알림 받을 채널 > 채널 편집 > 연동 > 웹후크 만들기 → URL 복사 (3.3 에서 서버 파일로 넣는다. 저장소·채팅에 붙이지 않는다).

## 3. 처음 한 번: 서버 준비

### 3.1 서버 접속 (SSM)

콘솔: EC2 > 인스턴스 > 연결 > Session Manager. 또는 내 컴퓨터에서 (session-manager-plugin 설치 필요):

```bash
aws ssm start-session --target i-xxxxxxxx --region ap-northeast-2
```

### 3.2 설치

```bash
curl -fsSL https://raw.githubusercontent.com/yunseo-1/Famlog-BE/main/infra/prod/scripts/setup-server.sh | sudo sh
```

Docker·Compose·AWS CLI, 스왑, `/opt/famlog`, cron(백업·인증서 갱신)을 준비하고 `init-env.sh` 로 비밀 파일을 만든다.
DB·Redis 비밀번호, JWT 키(`prod-YYYYMMDD`), 관리자 토큰, 백업 암호는 이때 무작위로 생긴다. 값은 출력하지 않는다.

### 3.3 비밀 채우기

`init-env.sh` 가 "직접 채울 항목" 이름을 알려 준다. **값을 화면에 출력하거나 채팅·저장소에 붙이지 않는다.**

```bash
sudo nano /opt/famlog/.env
```

| 항목 | 값 |
|---|---|
| `ACME_EMAIL` | 인증서 만료 안내 받을 이메일 |
| `S3_BUCKET_MEDIA` | 2.2 의 미디어 버킷 |
| `BACKUP_S3_URI` | `s3://famlog-backup-xxxx/db` |
| `KAKAO_APP_ID`·`GOOGLE_CLIENT_ID`·`KAKAO_REST_API_KEY` | 로컬과 같은 앱 값 (릴리스 키 해시·SHA-1 은 앱 쪽에서 콘솔에 추가) |
| `ANTHROPIC_API_KEY` | **운영용 키를 새로 만든다** (Console 에서 이름 `famlog-prod`, 사용 한도 설정) |

파일 두 개:

```bash
# Discord 웹훅 URL (붙여넣고 Ctrl+D)
sudo sh -c 'umask 077; cat > /opt/famlog/secrets/discord-webhook-url'
```

Firebase 서비스 계정 키는 내 컴퓨터에서 백업 버킷을 잠깐 거쳐 옮기고 바로 지운다:

```bash
# 내 컴퓨터
aws s3 cp ~/.famlog/firebase-adminsdk.json s3://famlog-backup-xxxx/tmp/fcm.json
# 서버
sudo sh -c 'umask 077; aws s3 cp s3://famlog-backup-xxxx/tmp/fcm.json /opt/famlog/secrets/firebase-adminsdk.json' \
  && aws s3 rm s3://famlog-backup-xxxx/tmp/fcm.json
```

다시 `sudo sh /opt/famlog/app/infra/prod/scripts/init-env.sh` 를 돌려 "모든 항목이 채워져 있습니다" 를 확인한다 (있는 값은 바꾸지 않는다).
**`BACKUP_PASSPHRASE` 를 비밀번호 관리자에도 보관한다** (`sudo grep ^BACKUP_PASSPHRASE= /opt/famlog/.env` 를 보는 즉시 옮기고 화면을 지운다).

### 3.4 첫 배포

1. GHCR 이미지 공개: 첫 배포 워크플로가 이미지를 올린 뒤 GitHub > Packages 에서 `famlog-api`·`famlog-worker`·`famlog-postgres` 를 Public 으로 바꾼다 (공개 저장소의 코드로 만든 이미지라 비밀이 없다. 서버가 로그인 없이 받는다). 처음 승인 전에 바꾼다.
2. Actions > deploy > Run workflow (main 의 최신 커밋 SHA 전체) → 승인.
   처음에는 인증서가 없으므로 `deploy.sh` 가 임시(자체 서명) 인증서로 Nginx 를 띄운다.
3. DNS 가 서버를 가리키는지 확인한 뒤 서버에서 인증서를 발급한다:
   ```bash
   cd /opt/famlog/app
   sudo sh infra/prod/scripts/init-cert.sh --staging   # 시험 발급 (한도 없음)
   sudo sh infra/prod/scripts/init-cert.sh              # 실제 발급
   ```
4. 확인 (5장 체크리스트).
5. 앱에 운영 주소 전달: `API_BASE_URL=https://api.famlog.kr` (WebSocket 은 `wss://api.famlog.kr/ws`).

## 4. 평소 배포

1. PR 머지 → `backend-test` 통과 → `deploy` 가 이미지를 올리고 **승인 대기**.
2. Actions 에서 Review deployments → Approve.
3. 서버에서 `deploy.sh` 가: 설정 확인 → 이미지 받기 → **배포 전 DB 백업** → 교체(`up --wait`) → `/readyz` 확인.
4. 실패하면 워크플로가 빨간색이 되고 로그 마지막 줄에 되돌리기 명령이 나온다.
   - 이미지 받기·백업 단계에서 멈췄으면 지금 버전이 그대로 떠 있다.
   - 교체 중 멈췄으면 아래 5 의 되돌리기.

API 가 다시 뜨는 20~40초 동안 앱 요청이 실패할 수 있다 (MVP 는 서버 1대. 앱은 재시도·재연결한다). 사용자가 적은 시간에 승인한다.

## 5. 되돌리기

Actions > deploy > Run workflow 에 **이전 커밋 SHA**. 이전 이미지로 다시 띄운다 (Discord·Actions 로그에 이전 SHA 가 남는다).

DB 마이그레이션(Flyway)은 앞으로만 간다. 우리 마이그레이션은 칼럼·테이블을 더하는 방식이라 보통 이전 버전이 새 스키마에서도 뜬다.
이전 버전이 안 뜨면(칼럼 이름 변경·삭제가 있었던 경우) 배포 전 백업으로 복구한다: [backup.md](backup.md) "실제 복구".

## 6. 점검·운영 명령 (서버, SSM 세션)

```bash
cd /opt/famlog/app
sudo sh infra/prod/scripts/compose.sh ps                    # 상태
sudo sh infra/prod/scripts/compose.sh logs --tail 200 api   # 로그 (본문·토큰은 남지 않는다)
curl -s localhost:8080/readyz                               # 준비 상태
tail -n 20 /var/log/famlog-backup.log                       # 백업 결과
```

관리자 도구·Prometheus·Alertmanager 는 밖에 열지 않는다. 내 컴퓨터에서 SSM 터널로 본다:

```bash
# 관리자 도구 → http://localhost:18080/admin (토큰은 서버 .env 의 FAMLOG_ADMIN_TOKEN)
aws ssm start-session --target i-xxxxxxxx --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["8080"],"localPortNumber":["18080"]}'
# Prometheus → http://localhost:19090 (9093 이면 Alertmanager)
aws ssm start-session --target i-xxxxxxxx --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["9090"],"localPortNumber":["19090"]}'
```

인증서: cron 이 하루 두 번 갱신을 확인한다 (`/var/log/famlog-cert.log`). 만료 30일 전부터 실제로 바뀐다.

## 7. 운영 시작 전 확인 (S22-1b 체크리스트)

- [ ] `https://api.famlog.kr/readyz` 200, `http://` 는 HTTPS 로 넘어간다
- [ ] `https://api.famlog.kr/admin`·`/actuator/health`·`/v3/api-docs` 는 404
- [ ] TLS 1.1 거절, HSTS 헤더 한 번 (`curl -sI`)
- [ ] 외부에서 22·5432·6379·8080·8091·9090 이 닫혀 있다
- [ ] 앱(릴리스 빌드): 로그인 → 그룹 → 채팅(실시간) → 사진 → 푸시
- [ ] 계정 삭제 안내 `https://famlog.kr/account-deletion` 열림 (Play 등록용)
- [ ] Prometheus 대상 `famlog-api`·`node` 가 up, `famlog_worker_alive` 1, 디스크 지표에 `mountpoint="/"` 가 있다
- [ ] 첫 백업 뒤 `famlog_backup_last_success_timestamp_seconds` 가 보인다 (BackupStale 알림의 바탕)
- [ ] Discord 시험 알림 (워커를 3분 멈췄다가 다시 켠다: `compose.sh stop worker` → `start worker`)
- [ ] 백업 cron 결과와 백업 버킷에 사본, 복구 리허설 1회 ([backup.md](backup.md))
- [ ] 관리자 도구 SSM 터널 접속, 기능 플래그로 AI·위치 기능 끄기 확인 (사양서 16.3)
- [ ] AWS Budgets 알림 설정
