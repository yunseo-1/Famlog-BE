#!/bin/sh
# 새 운영 서버 준비 (S22-1, Ubuntu 24.04 LTS EC2). SSM 세션에서 root 로 한 번 실행한다. 여러 번 실행해도 된다.
#   curl -fsSL https://raw.githubusercontent.com/yunseo-1/Famlog-BE/main/infra/prod/scripts/setup-server.sh | sudo sh
# 하는 일: Docker·Compose·AWS CLI 설치, 스왑 2GB, /opt/famlog 폴더, 저장소 받기, cron·로그 회전 등록, .env 만들기
set -eu
[ "$(id -u)" = 0 ] || { echo "root 로 실행하세요 (sudo)" >&2; exit 1; }
FAMLOG_HOME=/opt/famlog
REPO_URL=https://github.com/yunseo-1/Famlog-BE.git

# Docker (공식 저장소: compose 플러그인 포함)
if ! command -v docker > /dev/null; then
  apt-get update -q
  apt-get install -y -q ca-certificates curl git openssl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
fi

# AWS CLI (백업 서버 밖 사본)
command -v aws > /dev/null || snap install aws-cli --classic

# 스왑 2GB (메모리 4GB 에서 순간 부족으로 컨테이너가 죽지 않게)
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile > /dev/null
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# 폴더와 저장소 (공개 저장소라 인증 없이 받는다. 비밀은 저장소에 없다)
mkdir -p "$FAMLOG_HOME"
chmod 755 "$FAMLOG_HOME"
[ -d "$FAMLOG_HOME/app/.git" ] || git clone -q "$REPO_URL" "$FAMLOG_HOME/app"

# cron (백업·인증서 갱신)과 그 로그 회전
install -m 0644 "$FAMLOG_HOME/app/infra/prod/famlog.cron" /etc/cron.d/famlog
cat > /etc/logrotate.d/famlog <<'EOF'
/var/log/famlog-*.log {
  weekly
  rotate 8
  compress
  missingok
  notifempty
}
EOF

sh "$FAMLOG_HOME/app/infra/prod/scripts/init-env.sh"
echo "서버 준비 완료. 다음: docs/runbooks/deploy.md 의 '비밀 채우기' → GitHub Actions 배포"
