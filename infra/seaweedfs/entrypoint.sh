#!/bin/sh
# SeaweedFS S3 자격 증명을 .env 값으로 만든 뒤 기본 엔트리포인트로 서버를 띄운다.
# identities가 하나라도 있으면 익명 접근은 거부된다 (비공개 버킷, 사양서 9.1).
set -eu

: "${S3_ACCESS_KEY:?S3_ACCESS_KEY 가 필요합니다}"
: "${S3_SECRET_KEY:?S3_SECRET_KEY 가 필요합니다}"

mkdir -p /etc/seaweedfs
cat > /etc/seaweedfs/s3.json <<EOF
{
  "identities": [
    {
      "name": "famlog-api",
      "credentials": [{ "accessKey": "${S3_ACCESS_KEY}", "secretKey": "${S3_SECRET_KEY}" }],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    }
  ]
}
EOF
chmod 644 /etc/seaweedfs/s3.json

exec /entrypoint.sh server -dir=/data -s3 -s3.port=8333 -s3.config=/etc/seaweedfs/s3.json "$@"
