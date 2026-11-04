"""Local FFmpeg render adapter and FFprobe based technical QA."""

import hashlib
import json
import re
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from shorts_worker.manifest import load_manifest


def _tool(name):
    path = shutil.which(name)
    if not path:
        raise RuntimeError(f"{name} was not found on PATH. Install FFmpeg (includes FFprobe); see docs/RENDERING.md.")
    return path


def _run(args):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", check=False)
    if result.returncode:
        detail = result.stderr[-4000:].strip()
        raise RuntimeError(f"command failed with exit code {result.returncode}: {detail}")
    return result


def inspect_media(filename):
    path = Path(filename).expanduser().resolve()
    if not path.is_file():
        raise ValueError(f"input media does not exist: {path}")
    data = json.loads(_run([_tool("ffprobe"), "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path)]).stdout)
    return {"input": str(path), "metadata": data}


def _probe(filename):
    return json.loads(_run([_tool("ffprobe"), "-v", "error", "-show_format", "-show_streams", "-of", "json", str(filename)]).stdout)


def _filter_path(path):
    # libass filter syntax uses ':' as a separator even on Windows.
    return str(path).replace("\\", "/").replace(":", r"\:").replace("'", r"\'")


def _subtitle_force_style(settings):
    # SRT is converted to ASS at a 384x288 script resolution by libass. Manifest
    # style values are output pixels, so translate them to that native scale.
    scale = 288 / 1920
    font_size = max(1, round(int(settings.get("font_size", 48)) * scale))
    margin_vertical = max(1, round(int(settings.get("margin_vertical", 160)) * scale))
    return (f"FontName=Arial,FontSize={font_size},Alignment=2,MarginV={margin_vertical},"
            f"Outline={2 * scale:.2f},Shadow={0.5 * scale:.2f}")


def _version(binary):
    result = subprocess.run([binary, "-version"], capture_output=True, text=True, check=False)
    return result.stdout.splitlines()[0] if result.stdout else "unknown"


def _checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def render_manifest(manifest_path, output_override=None):
    data = load_manifest(manifest_path)
    output = Path(output_override).expanduser().resolve() if output_override else Path(data["expected_output"]).resolve()
    report_path = output.with_suffix(output.suffix + ".qa.json")
    warnings, errors = [], []
    ffmpeg, ffprobe = None, None
    render_started = False
    try:
        if output.suffix.lower() != ".mp4":
            raise ValueError("output must use the .mp4 extension")
        ffmpeg, ffprobe = _tool("ffmpeg"), _tool("ffprobe")
        source_paths = {Path(item["path"]).resolve() for item in data["visuals"]}
        source_paths.add(Path(data["subtitles"]["path"]).resolve())
        if data["audio"].get("path"):
            source_paths.add(Path(data["audio"]["path"]).resolve())
        if output in source_paths or output == Path(data["_manifest_path"]).resolve():
            raise ValueError("output path must not overwrite the manifest or any input asset")
        output.parent.mkdir(parents=True, exist_ok=True)
        render = data["render"]
        duration = render["duration_seconds"]
        segment_duration = duration / len(data["visuals"])
        args = [ffmpeg, "-hide_banner", "-y"]
        for asset in data["visuals"]:
            args.extend(["-loop", "1", "-framerate", str(render["fps"]), "-t", f"{segment_duration:.6f}", "-i", asset["path"]] if asset["kind"] == "image"
                         else ["-stream_loop", "-1", "-t", f"{segment_duration:.6f}", "-i", asset["path"]])
        if data["audio"].get("path"):
            args.extend(["-stream_loop", "-1", "-i", data["audio"]["path"]])
        filters = []
        labels = []
        for index, _asset in enumerate(data["visuals"]):
            label = f"v{index}"
            filters.append(f"[{index}:v]trim=duration={segment_duration:.6f},setpts=PTS-STARTPTS,scale={render['width']}:{render['height']}:force_original_aspect_ratio=increase,crop={render['width']}:{render['height']},fps={render['fps']},setsar=1,format=yuv420p[{label}]")
            labels.append(f"[{label}]")
        style = _subtitle_force_style(data["subtitles"]["settings"])
        filters.append("".join(labels) + f"concat=n={len(labels)}:v=1:a=0,trim=duration={duration:.6f},setpts=PTS-STARTPTS,subtitles='{_filter_path(data['subtitles']['path'])}':force_style='{style}'[vout]")
        args.extend(["-filter_complex", ";".join(filters), "-map", "[vout]"])
        if data["audio"].get("path"):
            args.extend(["-map", f"{len(data['visuals'])}:a:0", "-af", "apad"])
        args.extend(["-t", f"{duration:.6f}", "-r", str(render["fps"]), "-c:v", render["video_codec"], "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p"])
        if data["audio"].get("path"):
            args.extend(["-c:a", render["audio_codec"], "-b:a", "192k"])
        args.extend(["-movflags", "+faststart", str(output)])
        render_started = True
        _run(args)
        if not output.is_file() or output.stat().st_size == 0:
            raise RuntimeError("FFmpeg did not produce a non-empty output file")
        probe = json.loads(_run([ffprobe, "-v", "error", "-show_format", "-show_streams", "-of", "json", str(output)]).stdout)
        streams = probe.get("streams", [])
        video = next((stream for stream in streams if stream.get("codec_type") == "video"), None)
        audio = next((stream for stream in streams if stream.get("codec_type") == "audio"), None)
        checks = {
            "video_stream": video is not None,
            "dimensions": bool(video and video.get("width") == 1080 and video.get("height") == 1920),
            "video_codec": bool(video and video.get("codec_name") == "h264"),
            "frame_rate": bool(video and video.get("avg_frame_rate") == "30/1"),
            "pixel_format": bool(video and video.get("pix_fmt") == "yuv420p"),
            "audio_stream_when_required": (audio is not None) if data["audio"].get("required", True) else True,
            "audio_codec_when_present": (audio is None or audio.get("codec_name") == "aac"),
            "duration": abs(float(probe.get("format", {}).get("duration", 0)) - duration) <= 0.15,
            "readable_by_ffprobe": True,
        }
        errors.extend(name for name, passed in checks.items() if not passed)
        # Black frame detection is a warning only; some legitimate edits intentionally contain black.
        black = subprocess.run([ffmpeg, "-hide_banner", "-i", str(output), "-vf", "blackdetect=d=1.0:pix_th=0.10", "-an", "-f", "null", "NUL" if output.drive else "/dev/null"],
                               capture_output=True, text=True, check=False)
        if re.search(r"black_duration:[1-9]", black.stderr):
            warnings.append("black interval of at least one second detected; review visually")
        status = "success" if not errors else "failed"
        checksum = _checksum(output)
        if status == "failed":
            output.unlink(missing_ok=True)
        report = _report(data, output, status, probe, checksum, checks, warnings, errors, ffmpeg, ffprobe)
    except (RuntimeError, OSError, ValueError) as error:
        errors.append(str(error))
        # FFmpeg can leave a truncated MP4 when interrupted or given bad inputs.
        if render_started:
            output.unlink(missing_ok=True)
        report = _report(data, output, "failed", None, _checksum(output) if output.is_file() else None, {}, warnings, errors, ffmpeg, ffprobe)
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if report["status"] != "success":
        raise RuntimeError(f"render failed; QA report: {report_path}; {errors[-1]}")
    return report


def _report(data, output, status, probe, checksum, checks, warnings, errors, ffmpeg, ffprobe):
    streams = probe.get("streams", []) if probe else []
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    return {"status": status, "created_at": datetime.now(timezone.utc).isoformat(), "manifest": data["_manifest_path"],
            "job_id": data["job"]["job_id"], "content_id": data["job"]["content_id"], "brand_id": data["job"]["brand_id"],
            "output": str(output), "output_metadata": {"duration_seconds": (probe or {}).get("format", {}).get("duration"),
                "size_bytes": output.stat().st_size if output.is_file() else None,
                "width": video.get("width") if video else None, "height": video.get("height") if video else None,
                "video_codec": video.get("codec_name") if video else None, "frame_rate": video.get("avg_frame_rate") if video else None,
                "pixel_format": video.get("pix_fmt") if video else None, "audio_codec": audio.get("codec_name") if audio else None,
                "audio_present": audio is not None}, "sha256": checksum,
            "tool_versions": {"ffmpeg": _version(ffmpeg) if ffmpeg else None, "ffprobe": _version(ffprobe) if ffprobe else None},
            "subtitle": {"path": data["subtitles"]["path"], "settings": data["subtitles"]["settings"], "burned_in": True},
            "asset_provenance": [{"asset_id": a["asset_id"], "source_reference": a["source_reference"], "rights": a["rights"]} for a in data["visuals"] + ([data["audio"]] if data["audio"].get("path") else [])],
            "checks": checks, "warnings": warnings, "errors": errors}
