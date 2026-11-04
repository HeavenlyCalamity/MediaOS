# Media OS

**A modular, niche-agnostic content production and operations platform for multiple digital media brands.**

Media OS supports user-defined brands and categories across content types and platforms. Its first production milestone is deliberately smaller: a human-reviewed Shorts workflow. Example brands are optional configurations, not product restrictions.

## Current decisions

| Area | Decision |
|---|---|
| Infrastructure | Google Cloud Platform |
| Reported trial credit | $300; owner reported 84 days remaining on 2026-11-03. Verify in Billing Console before deployment. |
| Brand/category model | Dynamic user-managed brands, categories, and formats |
| First content format | YouTube Shorts |
| Publishing | Human approval required before every external publish |
| Dashboard | React + TypeScript + Vite |
| API | Java 21 + Spring Boot |
| Media worker | Python + FFmpeg |
| Metadata | Firestore Native |
| Media storage | Google Cloud Storage |
| Runtime | Cloud Run service + Cloud Run Jobs |
| CI | GitHub Actions |

## Example brand configurations (optional)

No brands or categories are required by the application. Users can define arbitrary categories and subcategories and create any number of brands. Cosmic Curiosity and History Uncovered are illustrative only; the same system supports gaming, music, education, finance, sports, storytelling, travel, food, technology, nature, and user-defined areas.

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

Stage 2 provides three independently runnable local components. The API currently
uses in-memory storage, so configuration resets when the API stops. No cloud account,
credentials, or paid API keys are needed. See:
- [AGENTS.md](AGENTS.md) — instructions for Codex and contributors.
- [Product brief](docs/PRODUCT_BRIEF.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Codex local setup](docs/CODEX_START_HERE.md)
- [First Codex task](docs/CODEX_FIRST_TASK.md)

### Prerequisites

- Git
- Node.js 22 LTS and npm 10+
- Java 21 JDK
- Apache Maven 3.9+
- Python 3.12+

Dependency versions are pinned in each component (`package-lock.json`, Maven
parent/dependency versions, and `pyproject.toml`).

### Windows PowerShell

```powershell
git switch feature/stage-1-brand-management
Set-Location apps/dashboard
npm ci
npm run dev
```

In a second PowerShell window, from the repository root:

```powershell
Set-Location apps/api
mvn spring-boot:run
```

In a third window:

```powershell
Set-Location workers/shorts-worker
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e .
mediaos-worker demo --brand-id sample-brand --brief "Why does the Moon look larger near the horizon?"
```

### Linux/macOS

```bash
git switch feature/stage-1-brand-management
cd apps/dashboard && npm ci && npm run dev
```

In separate terminals from the repository root:

```bash
cd apps/api && mvn spring-boot:run
cd workers/shorts-worker && python3.12 -m venv .venv && . .venv/bin/activate
python -m pip install -e .
mediaos-worker demo --brand-id sample-brand --brief "Why does the Moon look larger near the horizon?"
```

The API health endpoint is `http://localhost:8080/api/v1/health`; the dashboard
uses Vite on port 5173 and proxies `/api` to the API. The dashboard supports dynamic
brand/category management, channel destination metadata, configurable production
profiles, and content metadata including source and asset rights records. The worker demo remains deterministic and prints a JSON plan. The Stage 2 worker also validates manifests and renders local video with FFmpeg. It does not call AI services. Publishing is not implemented;
future publishing must require an explicit human approval for the exact asset
and metadata version.

See [worker rendering setup and manifest reference](docs/RENDERING.md) for FFmpeg
installation, synthetic fixture generation, render commands, and QA details.

### Local checks

Run the component checks from their respective directories:

```text
Dashboard: npm run lint; npm test -- --run; npm run build
API:       mvn test
Worker:    python -m unittest discover -s tests -v (FFmpeg enables real rendering integration coverage)
```

## Local development principles

- The project must run and test without paid model API keys.
- Use deterministic mock providers for tests and first local demos.
- Do not create billable GCP resources as part of ordinary coding tasks.
- Keep real credentials out of Git. Use Secret Manager in deployed environments.
- Never auto-publish: a human must approve the exact rendered asset and metadata first.

## Security note

This repository is currently public. Never commit secrets, real account identifiers, OAuth refresh tokens, service-account keys, private channel details, or real environment files. Consider changing repository visibility to private before adding proprietary code or non-public business data.

## Status

**Stage:** Stage 2 deterministic local rendering, following Stage 1 brand, category, production profile, channel destination, and content management.
Optional examples are in [config/sample-configurations.json](config/sample-configurations.json); they are not loaded automatically.
