# Media OS

**A modular content production and operations platform for multiple digital media brands.**

Media OS will eventually support multiple brands, content formats, publishing platforms, analytics, and monetization workflows. The first release is deliberately smaller: a human-reviewed Shorts Factory that can produce vertical videos for two independent pilot brands.

## Current decisions

| Area | Decision |
|---|---|
| Infrastructure | Google Cloud Platform |
| Reported trial credit | $300; owner reported approximately 84 days left on 2026-11-03. Verify in Billing Console before deployment. |
| Pilot brands | Two independent brands |
| First content format | YouTube Shorts |
| Publishing | Human approval required before every external publish |
| Dashboard | React + TypeScript + Vite |
| API | Java 21 + Spring Boot |
| Media worker | Python + FFmpeg |
| Metadata | Firestore Native |
| Media storage | Google Cloud Storage |
| Runtime | Cloud Run service + Cloud Run Jobs |
| CI | GitHub Actions |

## Pilot brands (provisional)

1. **Cosmic Curiosity** — space, astronomy, and science explainers.
2. **History Uncovered** — history, ancient civilizations, and evidence-based mysteries.

These are working concepts, not approved channel names or existing accounts. Each brand has independent configuration, content records, assets, and publishing destinations.

## MVP outcome

A user can select a brand, create a content brief, generate a researched script, create narration and visuals through provider adapters, render a vertical MP4, run quality checks, review the result in the dashboard, and approve or reject it. The first reliable milestone is **render + review + export**. Publishing integration follows after that workflow works.

## Repository layout (target)

```text
apps/
  dashboard/        React + TypeScript web app
  api/              Spring Boot REST API
workers/
  shorts-worker/    Python media-production worker
packages/
  contracts/        Shared API schemas and workflow contracts
infra/
  gcp/              Deployment configuration and documentation
docs/               Product, architecture, and implementation docs
```

## Getting started

The initial repository setup is being scaffolded. See:
- [AGENTS.md](AGENTS.md) — instructions for Codex and contributors.
- [Product brief](docs/PRODUCT_BRIEF.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [First Codex task](docs/CODEX_FIRST_TASK.md)

## Local development principles

- The project must run and test without paid model API keys.
- Use deterministic mock providers for tests and first local demos.
- Do not create billable GCP resources as part of ordinary coding tasks.
- Keep real credentials out of Git. Use Secret Manager in deployed environments.
- Never auto-publish: a human must approve the exact rendered asset and metadata first.

## Security note

This repository is currently public. Never commit secrets, real account identifiers, OAuth refresh tokens, service-account keys, private channel details, or real environment files. Consider changing repository visibility to private before adding proprietary code or non-public business data.

## Status

**Stage:** project handoff and initial scaffold.  
**Next:** complete the first Codex task in [docs/CODEX_FIRST_TASK.md](docs/CODEX_FIRST_TASK.md).
