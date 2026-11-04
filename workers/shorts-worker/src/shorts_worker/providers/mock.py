"""Deterministic local providers. Audio and visuals are synthetic test assets."""

import hashlib
import json
import math
import os
import shutil
import struct
import wave
import zlib
from pathlib import Path

from .contracts import ProviderError, ProviderRequest


def canonical_hash(value) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


class MockResearchProvider:
    name = "mock"

    def research(self, request: ProviderRequest) -> dict:
        content = request.inputs["content"]
        sources = content.get("sourceRecords") or []
        refs = [{"source_id": f"source-{i + 1}", "title": item.get("title", ""),
                 "publisher": item.get("publisher", ""), "url": item.get("url", ""),
                 "accessed_at": item.get("accessedAt"),
                 "evidence": "User-supplied reference only; this provider did not fetch or verify its contents."}
                for i, item in enumerate(sources)]
        brief = content.get("brief", "").strip()
        claims = ([{"claim_id": "claim-brief-1", "text": brief, "evidence_ids": [],
                    "verification_status": "requires_verification"}] if brief else [])
        warnings = ["Mock research does not fetch or verify source contents.",
                    "Brief-derived claims require human verification before publication."]
        if not refs:
            warnings.append("No user-supplied source records were provided; no citations were invented.")
        return {"packet_id": f"research-{canonical_hash([request.job_id, content.get('id')])[:16]}",
                "summary": "Local mock research organized the brief and user-supplied references. No external research was performed.",
                "sources": refs, "evidence": [], "claims": claims, "unverified_claims": claims,
                "warnings": warnings, "verification_status": "not_verified"}


class UserSourceResearchProvider(MockResearchProvider):
    """Organize only supplied references; never fetches URLs or asserts verification."""
    name = "user-supplied"


class MockScriptProvider:
    name = "mock"

    def generate(self, request: ProviderRequest) -> dict:
        content = request.inputs["content"]
        brief = content.get("brief", "").strip()
        if not brief:
            raise ProviderError("missing_brief", "script generation requires a non-empty content brief")
        language = content.get("language") or "und"
        wording = f"What should viewers understand about: {brief}?"
        narration = (f"This draft explores the brief: {brief}. "
                     "No factual claims were verified by the mock provider. Add and review source-backed evidence before release.")
        return {"title": content.get("title") or brief[:100], "hook": wording,
                "sections": [{"id": "opening", "heading": "Opening question", "narration": wording,
                              "visual_instruction": "Show an original abstract color card related to the topic.",
                              "subtitle": wording, "evidence_refs": ["claim-brief-1"],
                              "verification_status": "requires_verification"},
                             {"id": "context", "heading": "Context", "narration": narration,
                              "visual_instruction": "Use a second original abstract topic card.",
                              "subtitle": "Mock draft: facts remain unverified.", "evidence_refs": ["claim-brief-1"],
                              "verification_status": "requires_verification"}],
                "narration_text": f"{wording} {narration}", "language": language,
                "approximate_duration_seconds": float(request.settings.get("duration_seconds", 8)),
                "warnings": ["Mock-generated script is a draft, not factual verification."]}


class UserSuppliedScriptProvider:
    """Preserve an edited user script exactly; do not have a provider rewrite it."""
    name = "user-supplied"

    def generate(self, request: ProviderRequest) -> dict:
        content = request.inputs.get("content", {})
        narration = content.get("script")
        if not isinstance(narration, str) or not narration.strip():
            raise ProviderError("missing_user_script", "user-supplied script is empty")
        lines = [line.strip() for line in narration.splitlines() if line.strip()]
        return {"title": content.get("title") or "User-supplied script", "hook": lines[0][:240],
                "sections": [{"id": "user-script", "heading": "User-supplied narration", "narration": narration,
                              "visual_instruction": "Use original or properly licensed visuals relevant to the user-supplied script.",
                              "subtitle": narration[:4000], "evidence_refs": [],
                              "verification_status": "requires_verification"}],
                "narration_text": narration, "language": content.get("language") or "und",
                "approximate_duration_seconds": float(request.settings.get("duration_seconds", 8)),
                "warnings": ["User-edited script is preserved exactly and has not been fact-checked."]}


class MockTextToSpeechProvider:
    name = "mock"

    def synthesize(self, request: ProviderRequest, output: str) -> dict:
        narration = request.inputs["narration_text"]
        if not narration.strip():
            raise ProviderError("empty_narration", "TTS requires non-empty narration text")
        duration = max(1.0, min(30.0, float(request.settings.get("duration_seconds", 8))))
        sample_rate = 22050
        path = Path(output)
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as sound:
            sound.setnchannels(1)
            sound.setsampwidth(2)
            sound.setframerate(sample_rate)
            chunk = bytearray()
            for index in range(int(sample_rate * duration)):
                envelope = min(1.0, index / 600.0, (sample_rate * duration - index) / 600.0)
                value = int(1000 * max(0.0, envelope) * math.sin(2 * math.pi * 440 * index / sample_rate))
                chunk.extend(struct.pack("<h", value))
                if len(chunk) >= 65536:
                    sound.writeframesraw(chunk)
                    chunk.clear()
            if chunk:
                sound.writeframesraw(chunk)
        return {"path": str(path), "mime_type": "audio/wav", "duration_seconds": duration,
                "provider": self.name, "model": "synthetic-tone-v1", "voice_id": "synthetic-440hz-test-tone",
                "language": request.inputs.get("language", "und"), "synthetic": True,
                "rights": {"owner": "Media OS local mock provider", "license_type": "synthetic-test",
                           "license_evidence": "Generated locally by deterministic mock TTS; no third-party recording.",
                           "attribution": "Synthetic test tone; not speech or user-ready narration.",
                           "permitted_uses": ["testing", "local-rendering"]}}


def _png(path: Path, width: int, height: int, color: tuple[int, int, int], accent: tuple[int, int, int]):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            band = ((x // max(1, width // 12)) + (y // max(1, height // 16))) % 7 == 0
            pixel = accent if band else color
            raw.extend(pixel)

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xffffffff)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">2I5B", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))
    path.write_bytes(data)


class MockVisualAssetProvider:
    name = "mock"

    def create(self, request: ProviderRequest, output_dir: str) -> list[dict]:
        root = Path(output_dir)
        root.mkdir(parents=True, exist_ok=True)
        seed = hashlib.sha256(request.inputs["brief"].encode("utf-8")).digest()
        visuals = []
        instructions = request.inputs.get("scene_breakdown") or ["Opening", "Context"]
        dimensions = request.inputs.get("required_dimensions", {})
        width = int(dimensions.get("width", 360)) if isinstance(dimensions, dict) else 360
        height = int(dimensions.get("height", 640)) if isinstance(dimensions, dict) else 640
        if not (64 <= width <= 1920 and 64 <= height <= 1920):
            raise ProviderError("invalid_visual_dimensions", "visual dimensions must be between 64 and 1920 pixels")
        for index, instruction in enumerate(instructions[:4]):
            color = tuple(28 + seed[(index * 3 + c) % len(seed)] % 150 for c in range(3))
            accent = tuple(min(255, channel + 65) for channel in color)
            path = root / f"visual-{index + 1}.png"
            _png(path, width, height, color, accent)
            visuals.append({"asset_id": f"mock-visual-{index + 1}", "path": str(path),
                            "mime_type": "image/png", "width": width, "height": height,
                            "source_reference": f"generated://mediaos-mock/{canonical_hash([request.job_id, index])[:16]}",
                            "instruction": instruction,
                            "provider": self.name, "model": "deterministic-original-png-v1",
                            "rights": {"owner": "Media OS local mock provider", "license_type": "synthetic-test",
                                       "license_evidence": "Generated locally by deterministic mock visual provider.",
                                       "attribution": "Synthetic test visual; verify rights and suitability before external use.",
                                       "permitted_uses": ["testing", "local-rendering"]}})
        return visuals


class UserSuppliedVisualAssetProvider:
    """Select local image assets only from an explicit source directory allow-list."""
    name = "user-supplied"

    def create(self, request: ProviderRequest, output_dir: str) -> list[dict]:
        allowed_root = os.getenv("MEDIAOS_SOURCE_ASSET_DIR")
        if not allowed_root:
            raise ProviderError("source_asset_directory_required", "set MEDIAOS_SOURCE_ASSET_DIR to enable user-supplied visual assets")
        root = Path(allowed_root).expanduser().resolve()
        output = Path(output_dir).resolve()
        output.mkdir(parents=True, exist_ok=True)
        candidates = request.inputs.get("user_assets") or []
        visuals = []
        for index, asset in enumerate(candidates):
            if not isinstance(asset, dict) or asset.get("kind", "").lower() not in {"image", "visual"}:
                continue
            rights = {"owner": asset.get("owner"), "license_type": asset.get("licenseType"),
                      "license_evidence": asset.get("licenseEvidence"), "attribution": asset.get("attribution"),
                      "permitted_uses": asset.get("permittedUses")}
            if (any(not isinstance(rights[key], str) or not rights[key].strip()
                    for key in ("owner", "license_type", "license_evidence"))
                    or rights["license_type"].lower() in {"unknown", "unverified"}
                    or not isinstance(rights["permitted_uses"], list)
                    or not rights["permitted_uses"]
                    or any(not isinstance(use, str) or not use.strip() for use in rights["permitted_uses"])):
                raise ProviderError("incomplete_asset_rights", "user-supplied visual assets require known owner, license evidence, and permitted uses")
            uses = {str(value).lower() for value in rights["permitted_uses"]}
            if not (uses & {"render", "local-rendering", "production"}):
                raise ProviderError("asset_render_use_not_permitted", "user-supplied asset rights do not permit local rendering")
            uri = asset.get("uri")
            if not isinstance(uri, str) or not uri.strip():
                raise ProviderError("asset_source_missing", "user-supplied visual asset has no source path")
            source = (root / uri).resolve() if not Path(uri).is_absolute() else Path(uri).resolve()
            if not source.is_relative_to(root) or not source.is_file():
                raise ProviderError("invalid_asset_path", "user-supplied visual path must exist inside MEDIAOS_SOURCE_ASSET_DIR")
            extension = source.suffix.lower()
            mime = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg"}.get(extension)
            if not mime:
                raise ProviderError("unsupported_visual_format", "user-supplied visual assets must be PNG or JPEG images")
            if asset.get("mimeType") and asset["mimeType"] != mime:
                raise ProviderError("visual_mime_mismatch", "user-supplied visual MIME type does not match its file extension")
            if source.stat().st_size > 25 * 1024 * 1024:
                raise ProviderError("visual_asset_too_large", "user-supplied visual assets are limited to 25 MiB")
            raw = source.read_bytes()
            if extension == ".png":
                if not raw.startswith(b"\x89PNG\r\n\x1a\n") or len(raw) < 24:
                    raise ProviderError("invalid_visual_asset", "user-supplied PNG is corrupt")
                width, height = struct.unpack(">II", raw[16:24])
            else:
                width, height = _jpeg_dimensions(raw)
            if not (1 <= width <= 8192 and 1 <= height <= 8192):
                raise ProviderError("invalid_visual_dimensions", "user-supplied image dimensions must not exceed 8192 pixels")
            asset_id = f"supplied-visual-{index + 1}"
            destination = output / f"{asset_id}{extension}"
            shutil.copyfile(source, destination)
            digest = hashlib.sha256(raw).hexdigest()
            visuals.append({"asset_id": asset_id, "path": str(destination), "mime_type": mime,
                "width": width, "height": height, "source_reference": f"user-supplied://{digest}",
                "instruction": asset.get("kind", "user supplied visual"), "provider": self.name,
                "model": "local-original-or-licensed-asset-v1", "rights": rights})
        if not visuals:
            raise ProviderError("no_user_visual_assets", "no user-supplied image assets are attached to this content item")
        return visuals


def _jpeg_dimensions(data: bytes) -> tuple[int, int]:
    if not data.startswith(b"\xff\xd8"):
        raise ProviderError("invalid_visual_asset", "user-supplied JPEG is corrupt")
    index = 2
    while index + 4 <= len(data):
        if data[index] != 0xFF:
            index += 1
            continue
        marker = data[index + 1]
        index += 2
        if marker in {0xD8, 0xD9, 0x01} or 0xD0 <= marker <= 0xD7:
            continue
        if index + 2 > len(data):
            break
        length = int.from_bytes(data[index:index + 2], "big")
        if length < 2 or index + length > len(data):
            break
        if marker in {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}:
            height, width = struct.unpack(">HH", data[index + 3:index + 7])
            return width, height
        index += length
    raise ProviderError("invalid_visual_asset", "could not read JPEG dimensions")
