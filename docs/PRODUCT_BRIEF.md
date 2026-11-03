# Media OS — Product Brief

## Vision
Build an owned digital-media operating system that helps a small team operate multiple distinct content brands across multiple distribution platforms. The platform should make research, production, review, publishing, analytics, and monetization measurable and repeatable without sacrificing originality or editorial control.

Media OS is not a single YouTube channel, a generic AI video generator, or a mass-upload bot. It is an internal operating platform for original media brands.

## Initial business experiment
Media OS is niche-agnostic. Users can create any number of independent brands, categories, subcategories, content pillars, audience profiles, and publishing destinations. Gaming, music, entertainment, education, science, history, finance, sports, animation, storytelling, travel, food, technology, nature, and user-defined categories are all data configurations, not application logic.

Cosmic Curiosity (space/science) and History Uncovered (history) are optional provisional examples only. They are not required seed records, defaults, or product restrictions. Keep all brand and category definitions data-driven.

## Target user
Initially, one owner/operator who controls brand settings, creates content, reviews rendered videos, approves publishing, and studies analytics. Design the data model so multiple users and roles can be added later, but do not build SaaS tenancy or complex RBAC in the first milestone.

## MVP: Shorts Factory
A user should be able to:
1. Choose a brand.
2. Create a content brief or select a researched topic.
3. Generate a source-backed research packet.
4. Draft and edit a short-form script.
5. Generate narration using a configurable TTS provider.
6. Create or select rights-cleared visuals.
7. Generate synchronized subtitles.
8. Render a vertical MP4.
9. Run automated technical and editorial checks.
10. Preview the result and metadata in a review screen.
11. Approve or reject the exact rendered version.
12. Export the MP4 and metadata. Add platform publishing once the review-and-export loop is reliable.

Brands own configurable content pillars, audience profiles, production profiles, and multiple publishing destinations. Content items reference a brand, category, format/type, language, source records, rights-managed assets, and zero or more platform targets. Production profiles are configurable by category and format so different content types need not share identical workflows.

Asset records capture ownership, license, attribution, and permitted uses. Rights must be explicit for music, gameplay footage, and third-party visuals.

## Initial video profile
- Target canvas: 1080 × 1920 (9:16).
- Target frame rate: 30 fps.
- Container: MP4.
- Video codec: H.264.
- Audio codec: AAC.
- Initial target duration: 35–55 seconds; keep this configurable per brand/platform.
- Subtitles: burned-in captions with a configurable brand style.
- Provide safe margins for platform UI overlays.
- These are starting defaults, not hard limits. Validate actual duration and platform requirements at publishing time.

## Editorial principles
- Every factual video should retain its research sources and distinguish sourced facts from speculation.
- No fabricated quotes, statistics, dates, events, or citations.
- Clearly label uncertainty and disputed historical interpretations.
- Avoid misleading hooks and claims that the video does not substantiate.
- Visuals must be original, public-domain, or licensed for the intended use. Retain provenance and attribution where required.
- Use licensed or original music only. Keep music optional.
- AI generation is a production aid, not a substitute for original editorial judgment.
- The final rendered video and its metadata must be reviewed by a human before external publication.

## Success criteria for the first pilot
The first pilot is an engineering and audience-validation experiment, not a revenue promise. Initial acceptance:
- A repeatable local workflow can produce at least one complete Shorts MP4 for each pilot brand.
- The rendered output passes technical checks and has synchronized captions.
- Research sources and asset rights are recorded.
- The review screen shows the video, script, sources, rights information, and publishing metadata.
- Approval/rejection works and is auditable.
- The approved MP4 and metadata can be exported.
- Ten pilot videos can be produced and reviewed across owner-selected brands before we invest in scale.
- Measure production time, direct cost per video, view duration/retention where available, and subscriber conversion. Do not assume views or income.

## Later roadmap
1. YouTube publishing adapter, only after review/export is reliable.
2. Analytics collection and per-brand reporting.
3. Long-form video workflow.
4. Instagram/Facebook and other platform adapters, subject to their API and account requirements.
5. Scheduled interactive livestreams.
6. Revenue and affiliate attribution tracking.
7. Optional SaaS product, only after the internal system is validated.

## Explicit non-goals for the MVP
- Fully autonomous or unattended publishing.
- 24/7 livestream operation.
- A large network of channels.
- Scraping or re-uploading other creators' work.
- Guaranteed earnings or views.
- SaaS billing, customer onboarding, or multi-tenant isolation.
- Automatically spending credits or creating cloud infrastructure without owner approval.
