"""Pure deterministic demo planning logic; no external providers are called."""

import hashlib


def create_demo_plan(brand_id: str, brief: str) -> dict[str, object]:
    brand = brand_id.strip()
    content_brief = brief.strip()
    if not brand:
        raise ValueError("brand_id must not be empty")
    if not content_brief:
        raise ValueError("brief must not be empty")
    digest = hashlib.sha256(f"{brand}\n{content_brief}".encode("utf-8")).hexdigest()[:12]
    return {
        "job_id": f"demo-{digest}",
        "mode": "deterministic-demo",
        "brand_id": brand,
        "brief": content_brief,
        "steps": ["validate-brief", "draft-script-mock", "prepare-render-inputs", "await-human-review"],
        "publishing": {"enabled": False, "approval_required": True},
    }
