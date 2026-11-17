"""미디어 처리: GPS 제거한 원본, 썸네일 3단계, 촬영 시각 (사양서 4.2 CHAT-05, 8.6, 9.1)."""


class Rejected(Exception):
    """처리할 수 없는 파일 (재시도하지 않는다). reason 은 API의 media_assets.reject_reason 으로 간다."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason
