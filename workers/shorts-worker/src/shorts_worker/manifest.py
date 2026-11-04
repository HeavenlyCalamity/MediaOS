"""Validation and path resolution for the versioned local render manifest."""

import json
import math
from pathlib import Path

SCHEMA_VERSION = "1.0"
PROFILES = {"shorts-vertical-v1": {"width": 1080, "height": 1920, "fps": 30,
                                    "video_codec": "libx264", "audio_codec": "aac"}}
VISUAL_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm", ".png", ".jpg", ".jpeg", ".bmp", ".ppm"}
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".bmp", ".ppm"}
VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm"}


def _required(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} is required")
    return value.strip()


def _local_file(value, label, base):
    raw = Path(_required(value, label))
    path = (base / raw).resolve() if not raw.is_absolute() else raw.resolve()
    if not raw.is_absolute() and not path.is_relative_to(base.resolve()):
        raise ValueError(f"{label} must stay inside the manifest directory when using a relative path")
    if not path.exists() or not path.is_file():
        raise ValueError(f"{label} does not exist or is not a file: {path}")
    return path


def load_manifest(filename):
    source = Path(filename).expanduser().resolve()
    if not source.is_file():
        raise ValueError(f"manifest does not exist: {source}")
    try:
        data = json.loads(source.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ValueError(f"manifest is not valid UTF-8 JSON: {error}") from error
    if not isinstance(data, dict):
        raise ValueError("manifest root must be an object")
    if data.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"unsupported schema_version: {data.get('schema_version')!r}; supported: {SCHEMA_VERSION}")
    job = data.get("job")
    if not isinstance(job, dict):
        raise ValueError("job must be an object")
    for field in ("job_id", "content_id", "brand_id"):
        _required(job.get(field), f"job.{field}")
    content = data.get("content")
    if not isinstance(content, dict):
        raise ValueError("content must be an object")
    _required(content.get("format"), "content.format")
    _required(content.get("production_profile_id"), "content.production_profile_id")
    visuals = data.get("visuals")
    if not isinstance(visuals, list) or not visuals:
        raise ValueError("visuals must contain at least one ordered asset")
    base = source.parent
    resolved_visuals = []
    orders = []
    for index, asset in enumerate(visuals):
        label = f"visuals[{index}]"
        if not isinstance(asset, dict):
            raise ValueError(f"{label} must be an object")
        _required(asset.get("asset_id"), f"{label}.asset_id")
        kind = _required(asset.get("kind"), f"{label}.kind").lower()
        path = _local_file(asset.get("path"), f"{label}.path", base)
        if path.suffix.lower() not in VISUAL_EXTENSIONS:
            raise ValueError(f"{label}.path has unsupported media format: {path.suffix}")
        if kind == "image" and path.suffix.lower() not in IMAGE_EXTENSIONS:
            raise ValueError(f"{label}.kind=image is incompatible with {path.suffix}")
        if kind == "video" and path.suffix.lower() not in VIDEO_EXTENSIONS:
            raise ValueError(f"{label}.kind=video is incompatible with {path.suffix}")
        _required(asset.get("source_reference"), f"{label}.source_reference")
        _validate_rights(asset.get("rights"), label)
        if kind not in {"image", "video"}:
            raise ValueError(f"{label}.kind must be image or video")
        order = asset.get("order", index)
        if not isinstance(order, int) or isinstance(order, bool) or order < 0 or order in orders:
            raise ValueError(f"{label}.order must be a unique non-negative integer")
        orders.append(order)
        resolved_visuals.append({**asset, "path": str(path), "kind": kind, "order": order})
    audio = data.get("audio")
    if not isinstance(audio, dict):
        raise ValueError("audio must be an object (use required=false and path=null for silent output)")
    audio_required = audio.get("required", True)
    if not isinstance(audio_required, bool):
        raise ValueError("audio.required must be a boolean")
    if audio.get("path"):
        audio_path = _local_file(audio["path"], "audio.path", base)
        if audio_path.suffix.lower() not in {".wav", ".mp3", ".m4a", ".aac", ".ogg", ".flac"}:
            raise ValueError(f"audio.path has unsupported audio format: {audio_path.suffix}")
        _required(audio.get("asset_id"), "audio.asset_id")
        _required(audio.get("source_reference"), "audio.source_reference")
        _validate_rights(audio.get("rights"), "audio")
        audio["path"] = str(audio_path)
    elif audio_required:
        raise ValueError("audio.path is required when audio.required is true")
    subtitles = data.get("subtitles")
    if not isinstance(subtitles, dict):
        raise ValueError("subtitles must be an object")
    subtitle_path = _local_file(subtitles.get("path"), "subtitles.path", base)
    if subtitle_path.suffix.lower() != ".srt":
        raise ValueError("subtitles.path must reference an .srt file")
    subtitles["path"] = str(subtitle_path)
    settings = subtitles.get("settings", {})
    if not isinstance(settings, dict):
        raise ValueError("subtitles.settings must be an object")
    unknown_subtitle_settings = set(settings) - {"font_size", "margin_vertical"}
    if unknown_subtitle_settings:
        raise ValueError(f"unsupported subtitle settings: {', '.join(sorted(unknown_subtitle_settings))}")
    for key in ("font_size", "margin_vertical"):
        if key in settings and (not isinstance(settings[key], int) or isinstance(settings[key], bool) or settings[key] <= 0):
            raise ValueError(f"subtitles.settings.{key} must be a positive integer")
    subtitles["settings"] = settings
    render = data.get("render")
    if not isinstance(render, dict):
        raise ValueError("render must be an object")
    unknown_render_settings = set(render) - {"profile", "duration_seconds", "width", "height", "fps", "video_codec", "audio_codec", "audio_required"}
    if unknown_render_settings:
        raise ValueError(f"unsupported render settings: {', '.join(sorted(unknown_render_settings))}")
    profile = _required(render.get("profile"), "render.profile")
    if profile not in PROFILES:
        raise ValueError(f"unsupported rendering profile: {profile}")
    expected = PROFILES[profile]
    for field, value in expected.items():
        if render.get(field, value) != value:
            raise ValueError(f"render.{field} must be {value} for {profile}")
        render[field] = value
    try:
        raw_duration = render.get("duration_seconds")
        if isinstance(raw_duration, bool):
            raise ValueError("render.duration_seconds must be a number")
        duration = float(raw_duration)
    except (TypeError, ValueError) as error:
        raise ValueError("render.duration_seconds must be a number") from error
    if not math.isfinite(duration) or duration < 1 / expected["fps"] or duration > 600:
        raise ValueError("render.duration_seconds must be at least one frame and no more than 600")
    render["duration_seconds"] = duration
    if render.get("audio_required", audio_required) != audio_required:
        raise ValueError("render.audio_required must match audio.required")
    render["audio_required"] = audio_required
    expected_output = _required(data.get("expected_output"), "expected_output")
    output_path = Path(expected_output).expanduser()
    if not output_path.is_absolute():
        output_path = base / output_path
    if output_path.suffix.lower() != ".mp4":
        raise ValueError("expected_output must use the .mp4 extension")
    data["expected_output"] = str(output_path.resolve())
    data["_manifest_path"] = str(source)
    data["visuals"] = sorted(resolved_visuals, key=lambda item: item["order"])
    return data


def _validate_rights(rights, label):
    if not isinstance(rights, dict):
        raise ValueError(f"{label}.rights must record provenance and usage rights")
    _required(rights.get("owner"), f"{label}.rights.owner")
    license_type = _required(rights.get("license_type"), f"{label}.rights.license_type")
    if license_type.lower() != "owned":
        _required(rights.get("license_evidence"), f"{label}.rights.license_evidence")
        _required(rights.get("attribution"), f"{label}.rights.attribution")
    if (not isinstance(rights.get("permitted_uses"), list) or not rights["permitted_uses"]
            or any(not isinstance(use, str) or not use.strip() for use in rights["permitted_uses"])):
        raise ValueError(f"{label}.rights.permitted_uses must contain at least one usage")
