"""AI 분석 스트림 메시지 형식 (사양서 6.3, 7.2 구조화 출력 + 검증, 9.7).

필드 이름은 API 와 같다:
- ExtractJob·ExtractPayload: com.famlog.candidate.service.ExtractJobPublisher, candidate.dto.ExtractDtos
- ExtractResult: com.famlog.candidate.service.ExtractResultHandler
스트림 필드는 모두 문자열이다. payload·candidates 는 JSON 문자열이다.
"""

import json
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class _Model(BaseModel):
    model_config = ConfigDict(populate_by_name=True, frozen=True, extra="forbid")


class MemberRef(_Model):
    ref: str = Field(pattern=r"^M\d+$")
    family_role: str | None = Field(alias="familyRole", default=None)
    family_title: str | None = Field(alias="familyTitle", default=None)


class ExtractMessage(_Model):
    id: int = Field(gt=0)
    sender: str = Field(pattern=r"^M\d+$")
    sent_at: str = Field(alias="sentAt")  # +09:00 ISO-8601. 상대 날짜 해석은 API(S17)가 한다
    text: str


class ExtractPayload(_Model):
    timezone: str
    members: list[MemberRef]
    context: list[ExtractMessage]
    messages: list[ExtractMessage] = Field(min_length=1)


class ExtractJob(_Model):
    job_id: int = Field(alias="jobId", gt=0)
    attempt: int = Field(default=1, ge=1)
    payload: ExtractPayload

    @field_validator("payload", mode="before")
    @classmethod
    def _parse_payload(cls, value):
        return json.loads(value) if isinstance(value, str) else value

    def next_attempt_fields(self, raw_payload: str) -> dict[str, str]:
        return {"jobId": str(self.job_id), "attempt": str(self.attempt + 1), "payload": raw_payload}


class Evidence(_Model):
    message_id: int = Field(alias="messageId", gt=0)
    spans: list[Annotated[str, Field(max_length=100)]] = Field(default_factory=list, max_length=5)


_Text = Annotated[str, Field(max_length=100)]
_Texts = Annotated[list[_Text], Field(max_length=10)]


class ScheduleFields(_Model):
    title: _Text | None = None
    date_text: _Text | None = Field(alias="dateText", default=None)
    start_time_text: _Text | None = Field(alias="startTimeText", default=None)
    end_time_text: _Text | None = Field(alias="endTimeText", default=None)
    location_text: _Text | None = Field(alias="locationText", default=None)
    attendee_texts: _Texts = Field(alias="attendeeTexts", default_factory=list)


class TodoFields(_Model):
    title: _Text | None = None
    due_text: _Text | None = Field(alias="dueText", default=None)
    assignee_texts: _Texts = Field(alias="assigneeTexts", default_factory=list)


class AnniversaryFields(_Model):
    title: _Text | None = None
    date_text: _Text | None = Field(alias="dateText", default=None)
    person_text: _Text | None = Field(alias="personText", default=None)


class PlaceFields(_Model):
    name: _Text | None = None
    region_hint: _Text | None = Field(alias="regionHint", default=None)
    opinion: Annotated[str, Field(max_length=200)] | None = None


class _Candidate(_Model):
    """LLM 이 뽑은 원문 표현 (사양서 6.3). 날짜 계산·구성원 매칭·장소 확정·추정 표시는 API 가 한다 (S17·S18)"""

    confidence: float = Field(ge=0, le=1)
    evidence: list[Evidence] = Field(min_length=1, max_length=10)


class ScheduleCandidate(_Candidate):
    type: Literal["SCHEDULE"]
    fields: ScheduleFields


class TodoCandidate(_Candidate):
    type: Literal["TODO"]
    fields: TodoFields


class AnniversaryCandidate(_Candidate):
    type: Literal["ANNIVERSARY"]
    fields: AnniversaryFields


class PlaceCandidate(_Candidate):
    type: Literal["PLACE"]
    fields: PlaceFields


Candidate = Annotated[ScheduleCandidate | TodoCandidate | AnniversaryCandidate | PlaceCandidate,
                      Field(discriminator="type")]


class LlmOutput(_Model):
    candidates: list[Candidate] = Field(max_length=20)


class ExtractResult(BaseModel):
    job_id: int
    status: Literal["DONE", "FAILED"]
    error_code: str | None = None
    candidates: list[Candidate] = Field(default_factory=list)
    model_version: str | None = None
    prompt_version: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    latency_ms: int | None = None

    def to_fields(self) -> dict[str, str]:
        def text(value) -> str:
            return "" if value is None else str(value)

        candidates = [c.model_dump(by_alias=True) for c in self.candidates]
        return {
            "jobId": str(self.job_id),
            "status": self.status,
            "errorCode": text(self.error_code),
            "candidates": json.dumps(candidates, ensure_ascii=False, separators=(",", ":")),
            "modelVersion": text(self.model_version),
            "promptVersion": text(self.prompt_version),
            "inputTokens": text(self.input_tokens),
            "outputTokens": text(self.output_tokens),
            "latencyMs": text(self.latency_ms),
        }
