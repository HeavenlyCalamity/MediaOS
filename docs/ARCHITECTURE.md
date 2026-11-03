# Media OS — Architecture

## 1. High-level components

### Dashboard — React, TypeScript, Vite
Responsibilities:
- Dynamic brand, category/subcategory, content pillar, audience profile, production profile, and channel configuration.
- Content brief and script editor.
- Production job status.
- Video preview and metadata editor.
- Source and asset-rights review.
- Explicit approve/reject actions.
- Export controls.
- Later: analytics and publishing calendar.

The frontend must never hold provider API keys, Google credentials, or YouTube refresh tokens.

### Core API — Java 21, Spring Boot
Responsibilities:
- Own the content lifecycle and state transitions.
- Validate dynamic brand/category/channel configuration and content requests.
- Create and track production jobs.
- Provide signed, time-limited media access where necessary.
- Store approval events and publish attempts.
- Invoke media workers through a job-dispatch abstraction.
- Later: integrate OAuth and platform publishing adapters.

Start as a modular monolith. Avoid microservices beyond the separate media worker process.

### Shorts worker — Python 3.12+, FFmpeg
Responsibilities:
- Accept a versioned production request.
- Validate its input.
- Produce or consume script, narration, visuals, and subtitle assets.
- Assemble the final MP4 using FFmpeg.
- Run technical quality checks.
- Save output files and a machine-readable manifest.
- Report success/failure and logs.

The worker must be stateless between jobs. Durable files belong in Cloud Storage, not the container filesystem. Use a temporary working directory and clean it on exit.

### GCP services
- **Cloud Run service:** host the Spring Boot API. Set minimum instances to zero for the MVP.
- **Cloud Run Jobs:** execute CPU-based media-rendering tasks. Start with conservative CPU, memory, task count, and timeout settings; measure before increasing.
- **Firestore Native:** store brands, content metadata, jobs, approval events, publishing attempts, and analytics snapshots.
- **Cloud Storage:** store source assets, intermediate assets, final renders, thumbnails, and manifests. Keep buckets private.
- **Secret Manager:** hold provider secrets and OAuth credentials.
- **Artifact Registry:** store API and worker container images.
- **Cloud Logging/Monitoring:** structured logs, error visibility, and basic operational metrics.
- **Cloud Scheduler:** add later for scheduled generation/publishing workflows, not required for the first local MVP.

Avoid GPUs, always-on VMs, Cloud SQL, and permanent streaming infrastructure in the first MVP. Run FFmpeg on CPU first and measure actual render duration and cost.

## 2. Provider abstraction
Define interfaces before integrating paid providers:
- `ResearchProvider`
- `ScriptProvider`
- `TextToSpeechProvider`
- `VisualAssetProvider`
- `MediaStorage`
- `JobDispatcher`
- `Publisher` (later)

Provide deterministic fake/mock implementations for local development and CI. The app and tests must work without API keys or network access. Provider selection must be configured by brand or environment, not embedded in business logic.

Do not assume that one AI provider can perform all tasks or that a GCP trial credit covers every third-party API.

## 3. Content lifecycle
Suggested state machine:

```text
IDEA
  -> RESEARCHING
  -> RESEARCH_READY
  -> SCRIPT_READY
  -> ASSETS_READY
  -> RENDERING
  -> QA_PENDING
  -> IN_REVIEW
  -> APPROVED
  -> EXPORT_READY

Alternative terminal/exception states:
REJECTED, FAILED, CANCELLED

Later, after a publishing adapter is added:
APPROVED -> PUBLISH_QUEUED -> PUBLISHING -> PUBLISHED
                           \-> PUBLISH_FAILED
```

Rules:
- Validate transitions in the backend, never only in the UI.
- Use optimistic concurrency or a version field to avoid stale approvals.
- An approval binds to a specific rendered asset checksum/version and exact title, description, tags, destination, and visibility.
- Changing the asset or publishing metadata invalidates the prior approval.
- Retries must be idempotent and must not create duplicate jobs or uploads.

## 4. Niche-agnostic data model
Use collections (Stage 1 may use local in-memory repositories; Firestore is a later adapter):
- `brands/{brandId}`
- `categories/{categoryId}` (optional `parentCategoryId` supports hierarchy)
- `productionProfiles/{profileId}`
- `channels/{channelId}`
- `contentItems/{contentId}`
- `productionJobs/{jobId}`
- `assets/{assetId}`
- `approvalEvents/{eventId}`
- `publishAttempts/{attemptId}` (later)
- `analyticsSnapshots/{snapshotId}` (later)

Recommended document fields:

### Brand
`name`, `slug`, `description`, `defaultLanguage`, `tone`, `contentPillars[]`, `audienceProfile`, `enabled`, `createdAt`, `updatedAt`. Brands are user-created and may have many channels and production profiles.

### Category and production profile
Category: `name`, `slug`, `description`, `parentCategoryId`, `createdAt`, `updatedAt`. Production profile: `name`, optional `categoryId`, `contentFormat`, ordered `workflowStages[]`, and configurable `settings`.

### Channel
`brandId`, `platform`, `displayName`, optional platform account reference, connection status, and non-secret configuration. One brand may target many accounts, including multiple accounts on one platform. Stage 1 stores destination metadata only; it does not connect accounts or publish.

### Content item
`brandId`, `categoryId`, `title`, `workingTitle`, `contentType`, `language`, `status`, `brief`, `sourceRecords[]`, `assetIds`, `targetChannelIds[]`, production profile reference, `version`, `createdAt`, `updatedAt`.

### Asset
`contentId`, `brandId`, `kind`, `storageUri`, `mimeType`, `checksum`, `sourceUrl`, `owner`, `licenseType`, `licenseEvidenceRef`, `attributionText`, `permittedUses[]`, `createdAt`. Record rights for music, gameplay footage, and third-party visuals.

### Approval event
`contentId`, `contentVersion`, `assetChecksum`, `metadataChecksum`, `decision`, `reviewerId`, `note`, `createdAt`.

Store timestamps in UTC. Use stable IDs. Do not put large video/audio/image bytes in Firestore; store objects in Cloud Storage and keep references/metadata in documents.

## 5. Initial API surface
Use versioned routes:
- `POST /api/v1/brands`
- `GET /api/v1/brands`
- `GET /api/v1/brands/{brandId}`
- `PATCH /api/v1/brands/{brandId}`
- `POST /api/v1/content-items`
- `GET /api/v1/content-items`
- `GET /api/v1/content-items/{contentId}`
- `POST /api/v1/content-items/{contentId}/generate`
- `POST /api/v1/content-items/{contentId}/approve`
- `POST /api/v1/content-items/{contentId}/reject`
- `GET /api/v1/jobs/{jobId}`
- `GET /api/v1/dashboard/summary`
- `GET /api/v1/content-items/{contentId}/export`

Stage 1 builds CRUD for brands/categories, brand-associated channel and production profile configuration, content create/list/detail/update, and validated state transitions. It does not generate media, connect accounts, publish, or deploy.

## 6. Asset layout in Cloud Storage
Use a private bucket with a predictable structure:
`brands/{brandId}/content/{contentId}/source/`
`brands/{brandId}/content/{contentId}/research/`
`brands/{brandId}/content/{contentId}/audio/`
`brands/{brandId}/content/{contentId}/visuals/`
`brands/{brandId}/content/{contentId}/subtitles/`
`brands/{brandId}/content/{contentId}/renders/{version}/`
`brands/{brandId}/content/{contentId}/exports/`

Keep a manifest with file checksums, source references, licensing/attribution information, and tool/provider versions.

## 7. Security and IAM
- Use separate service accounts for API and worker.
- Grant only required Firestore, Storage, Secret Manager, and Cloud Run permissions.
- Use attached workload identity / Application Default Credentials in GCP.
- For local development, use developer ADC; never commit credentials.
- For GitHub Actions, prefer Workload Identity Federation over long-lived service-account JSON keys.
- Keep buckets private; use short-lived signed URLs only when necessary.
- Add authentication before exposing the API publicly.
- Do not log secrets, tokens, signed URLs, or private user data.

## 8. YouTube integration note
The YouTube Data API upload route should be implemented behind a publisher adapter, with OAuth token storage and explicit approval. Current Google documentation for `videos.insert` says unverified API projects are not restricted to private-only uploads; a Google Issue Tracker response dated 2026-10-13 confirms that the old forced-private restriction was lifted. Re-check current official policy while implementing, and do not bypass OAuth, quota, audit, or platform rules:
- https://developers.google.com/youtube/data/reference/rest/v3/videos/insert
- https://issuetracker.google.com/issues/572863050

## 9. Operational constraints
The owner reports 84 days remaining on the $300 Google Cloud Free Trial as of 2026-11-03. Confirm balance and expiry before deployment. The free trial credit is not a reason to create idle resources. Start with local tests and deploy only a small API plus on-demand CPU jobs after the local pipeline works.
