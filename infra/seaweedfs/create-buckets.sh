#!/bin/sh
# 버킷이 없으면 만든다 (여러 번 실행해도 안전). seaweedfs-init 일회성 컨테이너가 실행한다.
set -eu

MASTER="${SEAWEEDFS_MASTER:-seaweedfs:9333}"

for bucket in ${S3_BUCKETS}; do
  if echo "s3.bucket.list" | weed shell -master="$MASTER" 2>/dev/null | grep -qw "$bucket"; then
    echo "bucket exists: $bucket"
  else
    echo "s3.bucket.create -name $bucket" | weed shell -master="$MASTER"
    echo "bucket created: $bucket"
  fi
done
