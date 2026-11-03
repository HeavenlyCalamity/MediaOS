# Media OS — Implementation Plan

## Stage 0 — Repository and local scaffold
Goal: create a clean, runnable monorepo without requiring GCP or paid APIs.

Deliver:
- React + TypeScript + Vite dashboard.
- Java 21 + Spring Boot API with health endpoint.
- Python shorts-worker package with CLI entry point.
- Shared API contract examples/OpenAPI skeleton.
- Root developer documentation and GitHub Actions checks.
- Local deterministic/mock mode.

Acceptance:
- Dashboard installs and starts locally.
- API builds and returns health status.
- Python worker installs and runs a demo/dry-run job.
- Automated tests pass in all three applications.
- No external API key or cloud resource is required.

## Stage 1 — Brand and content management
Goal: create the first usable dashboard and backend domain.

Deliver:
- Brand configuration for multiple independent brands.
- Create/list/update content items.
- Content state machine with validated transitions.
- Job and asset metadata models.
- Dashboard content list, detail view, brand selector, and basic forms.

Acceptance:
- Seed two provisional pilot brands.
- Create a content item for either brand.
- Reject invalid state transitions.
- Unit and API tests cover brand and content flows.

## Stage 2 — Deterministic Shorts rendering
Goal: prove the media pipeline before integrating paid AI.

Deliver:
- Versioned input manifest.
- Test fixtures for narration and visuals.
- FFmpeg vertical render template (1080x1920, 30fps, H.264/AAC).
- Subtitle burn-in and safe margins.
- Technical QA: duration, dimensions, codec, audio stream, black/frozen frames where practical, subtitle file presence, file checksum.
- Render output and QA report.

Acceptance:
- A sample vertical MP4 is generated locally using free/licensed test assets.
- Output is playable and passes automated checks.
- A failed render returns a clear error and does not mark the content ready.

## Stage 3 — Review and export
Goal: implement the human approval gate.

Deliver:
- Preview player.
- Script, title, description, research sources, and asset-rights panel.
- Approve/reject flow.
- Approval bound to exact render checksum and metadata version.
- Export MP4 + JSON metadata + source/rights manifest.

Acceptance:
- Changing video or publishing metadata after approval invalidates approval.
- Rejected content cannot be exported as approved.
- Approval/rejection is logged and visible.

## Stage 4 — Research and generation providers
Goal: add configurable providers without coupling core workflow to one vendor.

Deliver:
- Research, script, TTS, and visual provider interfaces.
- One chosen provider per capability at a time.
- Mock providers remain available for tests.
- Store model/provider/version/cost metadata and original prompts.
- Rate limits, timeouts, bounded retries, and graceful errors.

Acceptance:
- Full workflow can run in mock mode.
- A real provider can be enabled by config and Secret Manager values.
- No real provider token is required for CI.

## Stage 5 — Google Cloud deployment
Goal: deploy only after local workflow works.

Deliver:
- Firestore Native database.
- Private Cloud Storage bucket.
- Artifact Registry.
- Cloud Run API with minimum instances zero.
- Cloud Run Job for CPU rendering.
- Secret Manager integration.
- Separate least-privilege service accounts.
- Cloud Logging and budget alerts.
- GitHub Actions deployment using Workload Identity Federation, if configured.

Acceptance:
- API responds to authenticated health checks.
- A test job is invoked and produces a render stored in Cloud Storage.
- No public bucket is created.
- Resource limits, timeout, retries, and cleanup are documented.
- Billing and trial expiry have been checked immediately before deployment.

## Stage 6 — YouTube publisher
Goal: connect the first publishing platform, retaining manual approval.

Deliver:
- OAuth connection flow.
- Channel connection and account status.
- Resumable upload.
- Draft/private/public visibility selection where permitted.
- Scheduled publish support only after verifying API/account eligibility.
- Publish attempt records, retries, idempotency, and error reporting.

Acceptance:
- User explicitly approves a particular asset and metadata version.
- Upload does not occur without that approval.
- Upload state is recoverable after timeout.
- API quota and current YouTube policies are respected.

## Stage 7 — Pilot and learning
Goal: test the business, not just the software.

Deliver:
- Five reviewed Shorts for each pilot brand.
- Manual or approved publishing.
- Production time and cost tracking.
- View duration/retention and subscriber conversion tracking where available.
- A review of content originality, rights, factual accuracy, audience response, and production economics.

Acceptance:
- Ten pilot videos produced and reviewed.
- Compare performance across the two brands.
- Decide whether to iterate, change one brand, or expand.

## Later (not MVP)
- Long-form video production.
- Scheduled live broadcasts and interactive formats.
- Instagram/Facebook and other publisher adapters, subject to API access/eligibility.
- Podcast and website publishing.
- Monetization and affiliate attribution.
- Multi-tenant SaaS and subscription billing.
- Multi-brand operational scaling.

## Operating rules
- Do not deploy billable cloud services until the user says to deploy.
- Confirm current trial balance and expiry in Billing Console before deployment.
- Do not use GPU instances in the first MVP.
- Do not promise views, revenue, or monetization approval.
- Do not publish without explicit per-content human approval.
