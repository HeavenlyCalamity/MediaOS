"""Create tiny original synthetic media used by the example render manifest."""

import json
import math
import struct
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main():
    for index, color in enumerate(((20, 90, 160), (180, 70, 35)), start=1):
        row = " ".join(str(channel) for _ in range(4) for channel in color)
        image = "P3\n4 4\n255\n" + (row + "\n") * 4
        (ROOT / f"visual-{index}.ppm").write_text(image, encoding="ascii")
    rate, seconds = 22050, 8
    with wave.open(str(ROOT / "narration.wav"), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(rate)
        samples = (int(4000 * math.sin(2 * math.pi * 440 * i / rate)) for i in range(rate * seconds))
        audio.writeframes(b"".join(struct.pack("<h", sample) for sample in samples))
    (ROOT / "captions.srt").write_text(
        "1\n00:00:00,000 --> 00:00:02,400\nA small, original synthetic scene.\n\n"
        "2\n00:00:02,400 --> 00:00:04,800\nTwo colors mark two visual beats.\n\n"
        "3\n00:00:04,800 --> 00:00:08,000\nCaptions are burned in with safe margins.\n", encoding="utf-8")
    manifest = {
        "schema_version": "1.0", "job": {"job_id": "fixture-stage-2", "content_id": "fixture-content", "brand_id": "sample-brand"},
        "content": {"format": "short-form video", "production_profile_id": "shorts-vertical-v1"},
        "visuals": [{"asset_id": f"fixture-visual-{index}", "kind": "image", "path": f"visual-{index}.ppm", "order": index,
                     "source_reference": "generated://mediaos-stage-2-fixture", "rights": {"owner": "Media OS test fixture", "license_type": "owned", "permitted_uses": ["testing", "rendering"]}}
                    for index in (1, 2)],
        "audio": {"asset_id": "fixture-tone", "path": "narration.wav", "required": True,
                  "source_reference": "generated://440hz-synthetic-tone", "rights": {"owner": "Media OS test fixture", "license_type": "owned", "permitted_uses": ["testing", "rendering"]}},
        "subtitles": {"path": "captions.srt", "settings": {"font_size": 48, "margin_vertical": 160}},
        "render": {"profile": "shorts-vertical-v1", "duration_seconds": 8, "audio_required": True},
        "expected_output": "../media-output/sample-render.mp4"}
    (ROOT / "sample-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
