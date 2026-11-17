"""미디어 버킷 접근 (S3 API, 로컬은 SeaweedFS)."""

from pathlib import Path

import boto3
from botocore.config import Config

from .config import Settings


class Storage:
    def __init__(self, settings: Settings):
        self.bucket = settings.s3_bucket
        self.client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint,
            region_name=settings.s3_region,
            aws_access_key_id=settings.s3_access_key,
            aws_secret_access_key=settings.s3_secret_key,
            config=Config(
                s3={"addressing_style": "path"} if settings.s3_endpoint else {},
                retries={"max_attempts": 3, "mode": "standard"},
            ),
        )

    def download(self, key: str, path: Path) -> None:
        self.client.download_file(self.bucket, key, str(path))

    def put_bytes(self, key: str, data: bytes, content_type: str) -> None:
        self.client.put_object(Bucket=self.bucket, Key=key, Body=data, ContentType=content_type)

    def put_file(self, key: str, path: Path, content_type: str) -> None:
        self.client.upload_file(str(path), self.bucket, key, ExtraArgs={"ContentType": content_type})

    def delete(self, key: str) -> None:
        self.client.delete_object(Bucket=self.bucket, Key=key)
