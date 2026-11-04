# Stage 4 local providers and production jobs

Stage 4 separates the four production capabilities behind provider interfaces:
`ResearchProvider`, `ScriptProvider`, `TextToSpeechProvider`, and
`VisualAssetProvider`. The API coordinates local jobs; provider implementations
and the Stage 2 renderer run in the Python worker. The default profile and API
configuration use `MOCK` mode. No API credential or network access is needed for
the mock workflow.

## Workflow configuration

Production profiles keep their Stage 4 configuration in the existing
`settings["mediaos.workflow_json"]` property. The dashboard's profile editor
provides a JSON editor. A minimal Shorts configuration is:

```json
{
  "workflow_id": "local-short-video",
  "version": "1",
  "mode": "MOCK",
  "renderer": "shorts-vertical-v1",
  "duration_seconds": 8,
  "providers": {
    "research": "mock",
    "script": "mock",
    "text_to_speech": "mock",
    "visual_asset": "mock"
  },
  "required_capabilities": ["research", "script", "text_to_speech", "visual_asset"],
  "capability_order": ["research", "script", "text_to_speech", "visual_asset"],
  "voice_profile": "synthetic-test",
  "visual_profile": "synthetic-test",
  "audio_format": "wav",
  "subtitle_settings": { "font_size": 48, "margin_vertical": 160 },
  "limits": { "max_concurrent_provider_operations": 1, "requests_per_second": 1, "max_retries": 0, "max_output_tokens": 1200 },
  "prompt_template_id": "default-script",
  "prompt_version": "1"
}
```

The API-level `MEDIAOS_PROVIDER_CONFIG_JSON` can contain `defaults`, `brands`,
`categories`, and `profiles` objects keyed by their existing IDs. They are merged
in that order; profile workflow JSON is applied last. Categories and formats are
user-defined. Unsupported combinations are rejected before a worker starts.
Only `shorts-vertical-v1` currently has a renderer; other formats return
`UNSUPPORTED_RENDERER` instead of being forced into video.

`mode` and selected capability providers are part of the saved profile. The API
catalog lists names and whether real calls are globally enabled; it never returns
credential values. For real use, set `MEDIAOS_REAL_PROVIDERS_ENABLED=true` in the
API process and set `MEDIAOS_OPENAI_COMPAT_API_KEY` in its local environment.
Then configure only the script capability as `openai-compatible` and provide
`base_url` and `model` in the profile JSON. Optional fields include
`endpoint_path` (defaults to `/v1/chat/completions`), `timeout_seconds`,
`max_output_tokens`, `temperature`, and documented input/output token pricing.
The adapter uses the configured endpoint and model; it does not assume a vendor
endpoint. No real calls are made by CI. Local environment variables are the only
secret source in this stage; the `SecretResolver` interface is ready for a future
Secret Manager adapter, which is not implemented here.

An optional `limits.brand_budget_usd` is a cumulative cap per brand based only on
cost already reported or reliably calculated and stored on jobs. The API
serializes jobs for that brand when this limit is set and passes the remaining
allowance to the worker as its job budget. A strict budget without usable pricing
blocks a real provider call before network access. `limits.job_budget_usd` can set
a tighter per-job cap.

## Research, rights and mock assets

The `mock` and `user-supplied` research providers organize only source records
already attached to the content item. They never fetch URLs. They preserve the
original URL/title/publisher/access date, provide no fabricated evidence, and mark
brief-derived claims as needing verification. The optional text adapter is told
to treat source material as untrusted data; its structured response is validated
against the returned research packet.

Mock script output is a draft. Mock TTS creates a deterministic 440 Hz WAV test
tone, explicitly marked as synthetic and not speech. Mock visuals are original
locally generated PNGs marked `synthetic-test` with local testing/rendering rights.
The dashboard labels both assets. Their local-use rights do not authorize external
publication; human rights review remains required.

To select a user-provided visual, set the visual capability to `user-supplied`,
set `MEDIAOS_SOURCE_ASSET_DIR` in the API environment, and store a relative image
path in the content asset URI. Only existing PNG/JPEG files that resolve inside
that configured directory are read. Each visual record must include a known owner,
license type, license evidence, and permitted uses including `render`,
`local-rendering`, or `production`. Missing, unknown, unsupported, oversized, or
out-of-directory assets fail the job; URLs are never fetched. This is a local
allow-list path, not an upload endpoint.

## Cost and execution safeguards

Worker input is limited to 1 MiB and briefs to 12,000 characters. Structured
responses and generated token limits are bounded. Provider concurrency is capped
at four and the per-job request rate at ten per second; defaults are one and one
per second. Retries default to zero and are capped at two additional attempts;
only transient provider errors are retried, using a stable script-job idempotency
key. HTTP timeouts are bounded. A configured job budget blocks a real call when
no usable price estimate is available and stops calls that would exceed the
configured budget. Costs are recorded only from provider usage plus explicit
profile pricing. Mock operations and unpriced providers report `null`, never an
invented zero-dollar cost.

Set `MEDIAOS_MAX_CONCURRENT_PRODUCTION_JOBS` conservatively (default 1),
`MEDIAOS_WORKER_TIMEOUT_SECONDS` (default 900), `MEDIAOS_PYTHON` (Python 3.12),
`MEDIAOS_WORKER_DIR` when local paths differ, and optionally `MEDIAOS_SOURCE_ASSET_DIR`
for licensed local image inputs. `MEDIAOS_DATA_DIR` contains
production job JSON, generated intermediates, Stage 2 output, and Stage 3 artifact
records. Local API and review JSON are development stores: single-process,
unauthenticated, and not tamper-proof audit storage. Do not treat them as durable
production history.

## Run a local mock production job

Start the API and dashboard as described in the README. Create a brand, a
`short-form video` production profile with the sample JSON above, and a content
item with a non-empty brief. In the **Production** workspace choose the item and
profile and start the job. The API persists a job ID and progress; completed
renders are handed to Stage 3's local artifact service and appear in **Review
studio** as pending human review. The job can be cancelled while active. It never
approves, exports as approved, or publishes.

The worker can also be exercised directly with a worker input JSON using:

```powershell
mediaos-worker produce --input .\job-input.json --output-dir .\local-job
```

The API-generated input format is intentionally an internal handoff. Use the
dashboard for normal production so artifact registration is completed as well.

## Troubleshooting

- `UNSUPPORTED_RENDERER`: select a video profile with `renderer` set to
  `shorts-vertical-v1`; non-video renderer modules are future work.
- `real_providers_disabled`: real calls require the explicit API environment flag.
- `MEDIAOS_OPENAI_COMPAT_API_KEY is required`: set the key in the API process
  environment; never add it to profile JSON or browser config.
- `budget_unverifiable`: set documented input/output token prices or remove the
  strict job budget. The system will not guess prices.
- `worker_failed`: inspect the job's structured error and verify Python 3.12,
  worker package installation, FFmpeg and FFprobe availability.
- A process restart marks active jobs failed. Generated intermediates remain in
  the local app-data folder for diagnosis and cleanup; the local store does not
  resume a render automatically.
