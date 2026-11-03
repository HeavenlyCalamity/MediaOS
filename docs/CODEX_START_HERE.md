# Start Media OS in Codex

## Recommended path for local development

Codex can work on a local repository through the Codex desktop app, CLI, or supported IDE workflows. Codex Cloud is a separate feature and depends on plan eligibility and rollout. Use local Codex if Cloud is unavailable on your account.

### Windows PowerShell

1. Confirm Git and Node.js are installed:

```powershell
git --version
node --version
npm --version
```

2. Install/update Codex CLI:

```powershell
npm install -g @openai/codex@latest
codex --version
```

3. Clone the repository and open it:

```powershell
git clone https://github.com/HeavenlyCalamity/MediaOS.git
Set-Location MediaOS
codex
```

4. Sign in to Codex with the ChatGPT account when prompted.

5. Give Codex the task in the next section.

### IntelliJ IDEA

Open the cloned `MediaOS` folder in IntelliJ IDEA. Open its integrated terminal at the repository root and run `codex`. Codex CLI can edit the working tree while IntelliJ is used for code inspection and running Java tasks.

### VS Code

Alternatively, open the repository folder in VS Code and install the official Codex extension if available in your setup. Follow the extension's sign-in flow.

## Current continuation prompt

```text
Read `AGENTS.md`, `README.md`, `docs/PRODUCT_BRIEF.md`, `docs/ARCHITECTURE.md`,
and `docs/IMPLEMENTATION_PLAN.md`. Implement only the active requested stage.
Media OS is niche-agnostic: brands, categories, subcategories, formats, content
pillars, audience profiles, production profiles, and destinations are user data.
Do not add generation, external publishing, or GCP deployment unless requested.
Require explicit human approval before any future external publishing. Preserve
existing docs, run available checks, and report files, results, and limitations.
```

## Git workflow

Start on a feature branch. Review the diff and test results before merging. Do not push secrets, credentials, real `.env` files, generated media, or cloud service-account keys.

## Notes

- The repository is currently public. Never add secrets or private account/business data.
- Keep paid provider keys out of the repository. Use local environment variables for development and Secret Manager in deployed GCP environments.
- The owner reported a $300 Google Cloud Free Trial with 84 days remaining as of 2026-11-03. Confirm current balance and exact expiry in Billing Console before creating billable resources.
