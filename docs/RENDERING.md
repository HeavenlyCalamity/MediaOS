# Stage 2 local rendering

The worker in `workers/shorts-worker` renders a versioned local manifest through
FFmpeg. It uses subprocess argument arrays (no shell), then inspects the output
with FFprobe and writes `<output>.qa.json`. No cloud account or paid API is used.

## Install FFmpeg

Both `ffmpeg` and `ffprobe` must be on `PATH`.

Windows PowerShell, using winget (the Gyan essentials build includes libass and
libx264, used for burned captions and H.264 output):

```powershell
winget install "FFmpeg (Essentials Build)"
```

The [FFmpeg download page](https://ffmpeg.org/download.html) links to the
[Gyan Windows builds](https://www.gyan.dev/ffmpeg/builds/).

Alternatively, download a trusted FFmpeg Windows build, extract it, and add its
`bin` directory to `PATH`. Open a new terminal and check `ffmpeg -version` and
`ffprobe -version`.

Linux (Debian/Ubuntu): `sudo apt-get update && sudo apt-get install -y ffmpeg`.
macOS (Homebrew): `brew install ffmpeg`.

Install the worker with Python 3.12:

```powershell
cd workers/shorts-worker
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e .
```

```bash
cd workers/shorts-worker
python3.12 -m venv .venv && . .venv/bin/activate
python -m pip install -e .
```

## Generate and render the synthetic sample

The fixture generator makes two original color-card images, an original 440 Hz
tone, SRT captions, and a manifest. It downloads nothing. Generated audio/video
are ignored by Git.

```powershell
python fixtures/generate_fixtures.py
mediaos-worker validate-manifest --manifest fixtures/sample-manifest.json
mediaos-worker render --manifest fixtures/sample-manifest.json
mediaos-worker inspect --input media-output/sample-render.mp4
```

```bash
python fixtures/generate_fixtures.py
mediaos-worker validate-manifest --manifest fixtures/sample-manifest.json
mediaos-worker render --manifest fixtures/sample-manifest.json
mediaos-worker inspect --input media-output/sample-render.mp4
```

Pass `--output path/to/result.mp4` to override the manifest output. The original
`mediaos-worker demo --brand-id ... --brief ...` command remains available.

## Manifest v1.0

Source and subtitle paths are resolved relative to the manifest; relative source
paths must stay inside its directory. Absolute local paths are also accepted.
Expected output can be relative or absolute. Missing files, unknown schema/profile
values, unsupported formats, missing rights metadata, and incompatible profile
settings fail before rendering.

| Field | Meaning |
|---|---|
| `schema_version` | Currently `1.0`. |
| `job` | `job_id`, `content_id`, and `brand_id`. |
| `content` | User-defined `format` and selected `production_profile_id`. |
| `visuals` | Ordered image/video assets with IDs, local paths, source references, and `rights`. |
| `audio` | Optional/required narration or audio asset; required audio needs a local path and rights. |
| `subtitles` | Required `.srt` path and optional style settings (`font_size`, `margin_vertical`). |
| `render` | Profile, duration, optional fixed values to assert (1080x1920, 30 FPS, H.264/AAC). |
| `expected_output` | Output MP4 location. |

Every asset records `rights.owner`, `rights.license_type`, and non-empty
`rights.permitted_uses`; non-owned licenses also require `license_evidence` and
`attribution`. Keep actual source and rights references in production manifests.
Stage 1 API `AssetRequest` and content `sourceRecords` carry corresponding domain
concepts. The worker manifest adds local paths and ordered segments required by
rendering.

Profile `shorts-vertical-v1` means a 1080x1920 canvas, 30 FPS, H.264 (`libx264`),
AAC, and `yuv420p`. Duration is configurable from one frame through 600 seconds.
Stills are center-cropped; video inputs loop as needed to fill their segment.
Total duration is split evenly across the ordered visual segments. Audio loops or
is trimmed to match the requested duration. Captions burn near the bottom with a
160 px default margin. Manifest subtitle sizes/margins are output-pixel values and
are converted to libass's default 384x288 subtitle script coordinates.

## QA report

The adjacent JSON report records success/failure, FFmpeg/FFprobe versions,
manifest reference, output stream metadata, SHA-256, subtitle settings, asset
provenance, checks, warnings, and errors. Failed rendering or verification exits
non-zero and writes a failed report. Empty partial outputs are removed. Checks
cover FFprobe readability, dimensions, codec, frame rate, required audio, and
duration (150 ms tolerance). A one-second black interval is a review warning;
black detection is heuristic and frozen-frame classification is not implemented.
Visually review output before any later human approval.

Integration verification (a real render when FFmpeg is installed):

```powershell
python -m unittest discover -s tests -v
```

```bash
python -m unittest discover -s tests -v
```
