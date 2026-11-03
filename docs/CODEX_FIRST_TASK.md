# Codex First Task — Scaffold the Media OS Monorepo

Use this file as the first task prompt in Codex after connecting the repository.
This is the historical Stage 0 scaffold task. Current product decisions in `AGENTS.md`, `README.md`, and `PRODUCT_BRIEF.md` supersede its illustrative brand selector wording; never treat example brands as a fixed product taxonomy.

## Prompt to give Codex

You are the lead engineer for Media OS. Read `AGENTS.md`, `README.md`, `docs/PRODUCT_BRIEF.md`, `docs/ARCHITECTURE.md`, and `docs/IMPLEMENTATION_PLAN.md` before editing.

Implement **Stage 0 only** from the implementation plan. The goal is a clean, runnable monorepo scaffold for:
1. `apps/dashboard`: React + TypeScript + Vite.
2. `apps/api`: Java 21 + Spring Boot REST API.
3. `workers/shorts-worker`: Python 3.12+ package with a CLI dry-run/demo job.

Requirements:
- Inspect the existing repository first. Preserve all project docs and instructions.
- Use stable, mutually compatible dependencies. Pin the versions you choose and explain them.
- Create a simple dashboard shell with a Media OS header and empty content queue. Any sample brands are optional data only; do not hardcode a fixed brand list or niche logic.
- Add a Spring Boot health endpoint (for example `GET /api/v1/health`) that returns JSON with app name and status. Add a small API test.
- Add a Python CLI command that accepts a brand ID and content brief, then prints a deterministic demo production plan in JSON. Do not call external LLM/TTS/image APIs.
- Add unit tests for the Python CLI/domain logic.
- Add root-level developer instructions and useful scripts. Include `.env.example` with placeholders only.
- Add GitHub Actions CI to lint/build/test the dashboard, test/build the API, and run Python tests.
- Ensure `git status` contains no credentials, generated videos, build output, node_modules, or real env files.
- Keep all local checks runnable without Google Cloud credentials or paid API keys.
- Document exact setup and run commands for Windows PowerShell and Linux/macOS.
- Keep the architecture modular but avoid premature microservices and abstractions.
- Do not create GCP resources, deploy services, configure billing, create YouTube OAuth credentials, or publish any content.
- Do not implement the complete Shorts generator in this task. Do not add real AI provider integrations yet.
- Do not add a database emulator unless it is genuinely required by the scaffold.
- Before finishing, run the available checks and fix failures.

At completion, report the repository tree, chosen dependency versions, exact commands run, test results, and any manual setup still required. Do not claim cloud deployment or publishing.

## Definition of done
- [ ] Dashboard starts locally.
- [ ] API builds and health test passes.
- [ ] Python worker CLI runs in dry-run mode and its tests pass.
- [ ] CI workflow is present and syntactically valid.
- [ ] No paid services or credentials are required.
- [ ] No GCP billable resources have been created.
- [ ] Existing docs and `AGENTS.md` remain intact.
