# AGENTS.md — Media OS

## Project objective
Build Media OS: a modular, multi-brand, multi-platform content operations system. The first MVP is a human-reviewed YouTube Shorts production workflow for two pilot brands. The long-term roadmap may add long-form video, livestreams, other publishing platforms, analytics, monetization tracking, and a SaaS offering.

## Read first
Before changing code, read:
1. `README.md`
2. `docs/PRODUCT_BRIEF.md`
3. `docs/ARCHITECTURE.md`
4. `docs/IMPLEMENTATION_PLAN.md`
5. `docs/CODEX_FIRST_TASK.md`

If a user task conflicts with these documents, follow the latest explicit user instruction and update the documentation when the decision changes.

## Current product decisions
- Cloud provider: Google Cloud Platform.
- Trial status reported by the owner on 2026-11-03: $300 Free Trial credit, approximately 84 days remaining. Treat this as user-reported; never assume the live balance or expiration without checking Billing Console.
- Initial launch: two separate pilot brands, not one mixed-niche channel.
- First production format: vertical short-form video.
- Publishing mode: human approval required before every external publish action.
- Initial output: render, preview, approve, and export; do not make unattended publishing the first milestone.
- First social publishing target: YouTube Shorts. Keep platform integrations behind adapters so other platforms can be added later.
- Initial example brands are provisional: Cosmic Curiosity (space/science) and History Uncovered (history). Do not create channels, accounts, or handles automatically.

## Architecture guardrails
- Dashboard: React + TypeScript + Vite.
- Core API: Java 21 + Spring Boot.
- Media worker: Python 3.12+ with FFmpeg.
- Metadata store: Firestore Native for the GCP MVP.
- Media/object storage: Google Cloud Storage.
- Runtime: Cloud Run service for API; Cloud Run Jobs for CPU-based media processing.
- Secrets: Google Secret Manager in GCP; local environment variables only for development.
- Container registry: Artifact Registry.
- CI: GitHub Actions.
- Use interfaces/adapters for LLM, research/search, TTS, visual generation, storage, and publishing providers. Use deterministic mock providers in tests and local development.
- Do not add GPUs, always-on VMs, Cloud SQL, or 24/7 stream infrastructure to the Shorts MVP.
- Do not create or deploy billable GCP resources unless the user explicitly asks for deployment. Coding and local tests are allowed.
- Keep media worker execution stateless: read inputs, process, store outputs, report status; do not rely on local disk as durable storage.

## Workflow and code quality
- Inspect the current repository before editing. Preserve working code and avoid replacing files wholesale without need.
- Implement in small vertical slices. Prefer a working end-to-end path over premature abstractions.
- Add tests for business logic, validation, state transitions, failure handling, and provider adapters.
- Keep tests runnable without paid API keys, Google credentials, YouTube credentials, or network access.
- Add clear local setup instructions for Windows and Linux/macOS where practical.
- Pin compatible dependency versions and document chosen versions.
- Validate all external input and return clear errors.
- Use structured logs; never log secrets, OAuth tokens, signed URLs, or sensitive personal data.
- Use idempotency keys or equivalent protection for background jobs and publish attempts.
- Add retries only for transient errors. Avoid duplicate content generation and duplicate uploads on retries.
- Keep state transitions explicit and validated server-side.
- Record source references and asset licensing/provenance for generated content.

## Safety, rights, and publishing
- Never commit secrets, credentials, access tokens, private keys, service-account JSON, or real .env files.
- Never bypass platform API policies, OAuth consent, upload restrictions, or audits.
- Never publish externally without an explicit human approval record for that content version.
- Approval must apply to a specific immutable rendered asset/version and its title/description/visibility metadata. If any of these change, require a new approval.
- Do not use copyrighted footage, music, images, or scripts without documented usage rights. Prefer original generation, public-domain material, or properly licensed assets.
- For factual content, retain research sources and flag claims that cannot be verified. Do not claim that an LLM-only check is fact verification.
- Provide disclosure handling for synthetic/altered media where required by a platform.
- Do not build spam, engagement manipulation, fake views, or repetitive mass-generated content workflows.

## Current scope exclusions
Do not implement these in the first MVP unless the user later asks:
- Multi-platform auto-publishing beyond the first YouTube integration.
- Long-form documentary generation.
- Continuous or 24/7 livestreams.
- SaaS billing or multi-tenant customer accounts.
- Automated monetization, affiliate transactions, or sponsorship outreach.
- Large-scale channel farms or unattended bulk publishing.

## Expected Codex completion report
At the end of each task, report:
1. Files created/changed.
2. Commands run and test results.
3. Any assumptions or unresolved issues.
4. Manual steps the owner must complete.
5. Whether the change introduces any cloud cost or external API requirement.
Do not claim deployment or external publishing occurred unless it actually did.