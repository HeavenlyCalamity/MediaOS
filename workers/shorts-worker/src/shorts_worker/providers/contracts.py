"""Provider contracts shared by generation adapters and the workflow runner."""

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Protocol, TypedDict


CAPABILITIES = ("research", "script", "text_to_speech", "visual_asset")


class ResearchPacket(TypedDict, total=False):
    packet_id: str
    summary: str
    sources: list[dict[str, Any]]
    evidence: list[dict[str, Any]]
    claims: list[dict[str, Any]]
    unverified_claims: list[dict[str, Any]]
    warnings: list[str]
    verification_status: str


class ScriptDraft(TypedDict, total=False):
    title: str
    hook: str
    sections: list[dict[str, Any]]
    narration_text: str
    language: str
    approximate_duration_seconds: float
    warnings: list[str]


class AudioAsset(TypedDict, total=False):
    path: str
    mime_type: str
    duration_seconds: float
    provider: str
    model: str
    voice_id: str
    sha256: str
    rights: dict[str, Any]


class VisualAsset(TypedDict, total=False):
    asset_id: str
    path: str
    mime_type: str
    width: int
    height: int
    source_reference: str
    provider: str
    model: str
    sha256: str
    rights: dict[str, Any]


class ProviderError(RuntimeError):
    def __init__(self, code: str, message: str, *, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.retryable = retryable


@dataclass(frozen=True)
class ProviderRequest:
    capability: str
    job_id: str
    inputs: dict[str, Any]
    settings: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ProviderExecution:
    capability: str
    provider: str
    model: str
    model_version: str | None
    prompt_template_id: str | None
    prompt_version: str | None
    input_sha256: str
    output_sha256: str
    executed_at: str
    usage: dict[str, Any]
    cost_usd: float | None
    warnings: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, Any]:
        return {
            "capability": self.capability, "provider": self.provider, "model": self.model,
            "modelVersion": self.model_version, "promptTemplateId": self.prompt_template_id,
            "promptVersion": self.prompt_version, "inputSha256": self.input_sha256,
            "outputSha256": self.output_sha256, "executedAt": self.executed_at,
            "usage": self.usage, "costUsd": self.cost_usd, "warnings": list(self.warnings),
        }


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class ResearchProvider(Protocol):
    name: str
    def research(self, request: ProviderRequest) -> ResearchPacket: ...


class ScriptProvider(Protocol):
    name: str
    def generate(self, request: ProviderRequest) -> ScriptDraft: ...


class TextToSpeechProvider(Protocol):
    name: str
    def synthesize(self, request: ProviderRequest, output: str) -> AudioAsset: ...


class VisualAssetProvider(Protocol):
    name: str
    def create(self, request: ProviderRequest, output_dir: str) -> list[VisualAsset]: ...
