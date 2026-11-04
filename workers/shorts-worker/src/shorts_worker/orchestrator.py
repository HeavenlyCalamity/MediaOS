"""Capability workflow for local production drafts; delegates MP4 rendering to Stage 2."""

import hashlib
import json
import math
import os
from pathlib import Path

from shorts_worker.manifest import load_manifest
from shorts_worker.providers.contracts import ProviderError, ProviderExecution, ProviderRequest, utc_now
from shorts_worker.providers.mock import canonical_hash
from shorts_worker.providers.registry import default_registry
from shorts_worker.providers.safeguards import BudgetGuard, ProviderGate, call_with_bounded_retry
from shorts_worker.render import render_manifest


MAX_INPUT_BYTES = 1024 * 1024
MAX_BRIEF_CHARS = 12000
SUPPORTED_RENDERER = "shorts-vertical-v1"
PROMPTS = {"default-script": "1"}


def _write_progress(progress_path: Path, stage: str, status: str = "RUNNING", usage: dict | None = None):
    progress_data = {"stage": stage, "status": status}
    if usage is not None:
        progress_data["usage"] = usage
    progress_path.write_text(json.dumps(progress_data), encoding="utf-8")


def _json_hash(value) -> str:
    return canonical_hash(value)


def _usage_summary(executions: list[ProviderExecution]) -> dict:
    costs = [item.cost_usd for item in executions if item.cost_usd is not None]
    return {"reported_cost_usd": round(sum(costs), 8) if costs else None,
            "currency": "USD" if costs else None}


def _validate_script(script: dict, research: dict, expected_language: str, duration: float) -> dict:
    required = ("title", "hook", "sections", "narration_text", "language", "warnings")
    if not isinstance(script, dict) or any(key not in script for key in required):
        raise ProviderError("invalid_structured_output", "script provider omitted required structured fields")
    for field in ("title", "hook", "narration_text", "language"):
        if not isinstance(script[field], str) or not script[field].strip():
            raise ProviderError("invalid_structured_output", f"script field {field} must be non-empty text")
    sections = script["sections"]
    if not isinstance(sections, list) or not sections or len(sections) > 20:
        raise ProviderError("invalid_structured_output", "script sections must contain between 1 and 20 entries")
    claim_map = {item["claim_id"]: item for item in research.get("claims", []) if isinstance(item, dict) and item.get("claim_id")}
    evidence_ids = {item["evidence_id"] for item in research.get("evidence", []) if isinstance(item, dict) and item.get("evidence_id")}
    for index, section in enumerate(sections):
        if not isinstance(section, dict) or any(not isinstance(section.get(key), str) or not section[key].strip()
                for key in ("id", "heading", "narration", "visual_instruction", "subtitle")):
            raise ProviderError("invalid_structured_output", f"script section {index + 1} is incomplete")
        refs = section.get("evidence_refs")
        status = section.get("verification_status")
        if not isinstance(refs, list) or any(not isinstance(ref, str) for ref in refs):
            raise ProviderError("invalid_structured_output", f"script section {index + 1} evidence_refs must be strings")
        allowed_refs = evidence_ids | set(claim_map)
        if any(ref not in allowed_refs for ref in refs):
            raise ProviderError("invalid_evidence_reference", f"script section {index + 1} cites evidence not in the research packet")
        if not refs and status != "requires_verification":
            raise ProviderError("unsupported_claim", f"script section {index + 1} has no evidence and must require verification")
        if status not in {"supported_by_evidence", "requires_verification", "editorial_inference"}:
            raise ProviderError("invalid_structured_output", f"script section {index + 1} has invalid verification_status")
        if status == "supported_by_evidence":
            if not refs or any(ref in claim_map and claim_map[ref].get("verification_status") != "supported" for ref in refs):
                raise ProviderError("unsupported_claim", f"script section {index + 1} claims verification without supporting evidence")
    try:
        approximate = float(script.get("approximate_duration_seconds", duration))
    except (TypeError, ValueError):
        raise ProviderError("invalid_structured_output", "script duration must be numeric") from None
    if not math.isfinite(approximate) or approximate <= 0 or approximate > 600:
        raise ProviderError("invalid_structured_output", "script duration is outside supported limits")
    script["approximate_duration_seconds"] = approximate
    script["language"] = expected_language
    return script


def _validate_research(packet: dict, source_records: list) -> dict:
    required = ("packet_id", "summary", "sources", "evidence", "claims", "unverified_claims", "warnings")
    if not isinstance(packet, dict) or any(key not in packet for key in required):
        raise ProviderError("invalid_research_packet", "research provider omitted required packet fields")
    if (not isinstance(packet["packet_id"], str) or not packet["packet_id"].strip()
            or not isinstance(packet["summary"], str)
            or any(not isinstance(packet[key], list) for key in ("sources", "evidence", "claims", "unverified_claims", "warnings"))):
        raise ProviderError("invalid_research_packet", "research packet fields have invalid types")
    provided_urls = {record.get("url", "") for record in source_records if isinstance(record, dict)}
    source_ids = set()
    for source in packet["sources"]:
        if not isinstance(source, dict) or not isinstance(source.get("source_id"), str):
            raise ProviderError("invalid_research_packet", "research sources require source IDs")
        if source["source_id"] in source_ids:
            raise ProviderError("invalid_research_packet", "research packet contains duplicate source IDs")
        source_ids.add(source["source_id"])
        url = source.get("url", "")
        if not isinstance(url, str) or (url and url not in provided_urls):
            raise ProviderError("fabricated_source_url", "research provider returned a URL not supplied by the user")
    evidence_ids = set()
    for evidence in packet["evidence"]:
        if (not isinstance(evidence, dict) or not isinstance(evidence.get("evidence_id"), str)
                or evidence.get("source_id") not in source_ids):
            raise ProviderError("invalid_research_packet", "each evidence record must map to an existing source ID")
        evidence_ids.add(evidence["evidence_id"])
    claim_ids = set()
    for claim in packet["claims"]:
        if (not isinstance(claim, dict) or not isinstance(claim.get("claim_id"), str)
                or not isinstance(claim.get("text"), str) or not claim["text"].strip()
                or not isinstance(claim.get("evidence_ids"), list)):
            raise ProviderError("invalid_research_packet", "research claims require IDs, text, and evidence mappings")
        claim_ids.add(claim["claim_id"])
        if any(evidence_id not in evidence_ids for evidence_id in claim["evidence_ids"]):
            raise ProviderError("invalid_research_packet", "research claim references evidence outside this packet")
        status = claim.get("verification_status")
        if status not in {"supported", "requires_verification", "editorial_inference"}:
            raise ProviderError("invalid_research_packet", "research claim has an invalid verification status")
        if status == "supported" and not claim["evidence_ids"]:
            raise ProviderError("unsupported_claim", "research cannot mark a claim supported without evidence")
    for claim in packet["unverified_claims"]:
        if not isinstance(claim, dict) or claim.get("claim_id") not in claim_ids:
            raise ProviderError("invalid_research_packet", "unverified claims must reference claims in the packet")
    if any(not isinstance(warning, str) for warning in packet["warnings"]):
        raise ProviderError("invalid_research_packet", "research warnings must be text")
    packet.setdefault("verification_status", "not_verified")
    return packet


def _execution(capability: str, provider_name: str, model: str, request: ProviderRequest,
               output: dict, settings: dict, warnings=()) -> ProviderExecution:
    usage = output.pop("_provider_usage", {}) if isinstance(output, dict) else {}
    model = output.pop("_provider_model", model) if isinstance(output, dict) else model
    cost = None
    if isinstance(usage, dict) and ("prompt_tokens" in usage or "input_tokens" in usage):
        input_tokens = usage.get("prompt_tokens", usage.get("input_tokens", 0))
        output_tokens = usage.get("completion_tokens", usage.get("output_tokens", 0))
        in_price = settings.get("pricing_usd_per_1m_input_tokens")
        out_price = settings.get("pricing_usd_per_1m_output_tokens")
        if in_price is not None and out_price is not None:
            cost = (int(input_tokens) * float(in_price) + int(output_tokens) * float(out_price)) / 1_000_000
    return ProviderExecution(capability, provider_name, str(model), None,
                             settings.get("prompt_template_id"), settings.get("prompt_version"),
                             _json_hash(request.inputs), _json_hash(output), utc_now(), usage, cost, tuple(warnings))


def _settings_dict(value):
    if not isinstance(value, dict):
        raise ProviderError("invalid_workflow_config", "workflow configuration must be a JSON object")
    return value


def run_production(payload: dict, work_dir: str, progress_file: str | None = None) -> dict:
    """Run providers and Stage 2 locally. ``payload`` contains API-owned domain snapshots."""
    if not isinstance(payload, dict):
        raise ProviderError("invalid_request", "production request must be an object")
    if len(json.dumps(payload, ensure_ascii=False).encode("utf-8")) > MAX_INPUT_BYTES:
        raise ProviderError("input_too_large", "production input exceeds the 1 MiB limit")
    content = payload.get("content", {})
    brand = payload.get("brand", {})
    profile = payload.get("profile", {})
    brief = content.get("brief", "")
    if not isinstance(brief, str) or not brief.strip() or len(brief) > MAX_BRIEF_CHARS:
        raise ProviderError("invalid_brief", f"brief must contain 1 to {MAX_BRIEF_CHARS} characters")
    workflow = _settings_dict(payload.get("workflow", {}))
    renderer = workflow.get("renderer")
    if renderer != SUPPORTED_RENDERER:
        return {"status": "UNSUPPORTED_RENDERER", "job_id": payload.get("job_id"),
                "content_id": content.get("id"), "workflow": workflow.get("workflow_id"),
                "errors": ["No renderer is configured for this production profile. Configure " + SUPPORTED_RENDERER + " for supported vertical video workflows."],
                "warnings": [], "provider_executions": [], "usage": {"cost_usd": None}}
    mode = str(workflow.get("mode", "MOCK")).upper()
    if mode not in {"MOCK", "REAL"}:
        raise ProviderError("invalid_workflow_config", "workflow mode must be MOCK or REAL")
    selected = workflow.get("providers", {})
    defaults = {"research": "mock", "script": "mock", "text_to_speech": "mock", "visual_asset": "mock"}
    if not isinstance(selected, dict):
        raise ProviderError("invalid_workflow_config", "providers selection must be an object")
    providers = {**defaults, **selected}
    if set(providers) - set(defaults):
        raise ProviderError("invalid_workflow_config", "workflow contains an unknown provider capability")
    required_capabilities = workflow.get("required_capabilities", list(defaults))
    capability_order = workflow.get("capability_order", list(defaults))
    if (not isinstance(required_capabilities, list) or set(required_capabilities) != set(defaults)
            or not isinstance(capability_order, list) or len(capability_order) != len(defaults)
            or set(capability_order) != set(defaults)):
        raise ProviderError("invalid_workflow_config", "the vertical video renderer requires each of the four capabilities exactly once")
    if capability_order.index("research") > capability_order.index("script") or any(
            capability_order.index("script") > capability_order.index(capability)
            for capability in ("text_to_speech", "visual_asset")):
        raise ProviderError("invalid_workflow_config", "research must precede script, and script must precede narration and visuals")
    if mode == "MOCK" and any(name != "mock" for name in providers.values() if name != "user-supplied"):
        raise ProviderError("unsupported_provider_mode", "non-mock provider selection requires workflow mode REAL")
    if mode == "REAL" and os.getenv("MEDIAOS_REAL_PROVIDERS_ENABLED", "false").lower() != "true" and any(n not in {"mock", "user-supplied"} for n in providers.values()):
        raise ProviderError("real_providers_disabled", "real provider calls are disabled; no provider was called")
    prompt_id = workflow.get("prompt_template_id", "default-script")
    custom_templates = workflow.get("prompt_templates", {})
    if not isinstance(custom_templates, dict):
        raise ProviderError("invalid_workflow_config", "prompt_templates must be an object")
    custom_prompt = custom_templates.get(prompt_id)
    if prompt_id not in PROMPTS and not isinstance(custom_prompt, dict):
        raise ProviderError("unsupported_prompt", "selected prompt template is not registered")
    supported_prompt_version = str(custom_prompt.get("version")) if custom_prompt else PROMPTS[prompt_id]
    if custom_prompt and (supported_prompt_version in {"", "None"} or not isinstance(custom_prompt.get("template"), str) or not custom_prompt["template"].strip()):
        raise ProviderError("invalid_workflow_config", "custom prompt template must contain non-empty template text")
    if str(workflow.get("prompt_version", supported_prompt_version)) != supported_prompt_version:
        raise ProviderError("unsupported_prompt", "selected prompt template version is not registered")
    duration = float(workflow.get("duration_seconds", 8))
    if not math.isfinite(duration) or duration < 1 / 30 or duration > 600:
        raise ProviderError("invalid_workflow_config", "duration_seconds must be between one frame and 600 seconds")
    output_root = Path(work_dir).resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    progress = Path(progress_file) if progress_file else output_root / "progress.json"
    registry = default_registry()
    limits = workflow.get("limits", {})
    if not isinstance(limits, dict):
        raise ProviderError("invalid_workflow_config", "limits must be an object")
    max_concurrent = int(limits.get("max_concurrent_provider_operations", 1))
    max_rps = float(limits.get("requests_per_second", 1))
    if max_concurrent < 1 or not math.isfinite(max_rps) or max_rps <= 0:
        raise ProviderError("invalid_workflow_config", "provider concurrency and request rate must be positive finite numbers")
    if max_concurrent > 4 or max_rps > 10:
        raise ProviderError("invalid_workflow_config", "provider concurrency is capped at 4 and request rate at 10 per second")
    retries = int(limits.get("max_retries", 0))
    if retries < 0 or retries > 2:
        raise ProviderError("invalid_workflow_config", "max_retries must be between zero and two")
    gate = ProviderGate(max_concurrent, max_rps)
    budget_limit = limits.get("job_budget_usd")
    if budget_limit is not None:
        try:
            budget_limit = float(budget_limit)
        except (TypeError, ValueError):
            raise ProviderError("invalid_workflow_config", "job_budget_usd must be numeric") from None
        if not math.isfinite(budget_limit) or budget_limit < 0:
            raise ProviderError("invalid_workflow_config", "job_budget_usd must be a non-negative finite number")
    budget = BudgetGuard(budget_limit)
    executions = []
    warnings = []

    base_inputs = {"content": content, "brand": brand, "category": payload.get("category"), "profile": profile,
                   "brief": brief, "audience": brand.get("audienceProfile", {}), "language": content.get("language", "und"),
                   "sources": content.get("sourceRecords", [])}
    request = ProviderRequest("research", payload["job_id"], base_inputs, workflow)
    _write_progress(progress, "RESEARCHING")
    research_provider = registry.resolve("research", providers["research"])
    with gate:
        research = research_provider.research(request)
    research = _validate_research(research, content.get("sourceRecords", []))
    research_hash = _json_hash(research)
    research_model = "source-records-v1" if providers["research"] == "user-supplied" else "mock-research-v1"
    executions.append(ProviderExecution("research", providers["research"], research_model, None,
        None, None, _json_hash(base_inputs), research_hash, utc_now(), {"source_references": len(research["sources"])},
        None, tuple(research.get("warnings", []))))
    warnings.extend(research.get("warnings", []))

    _write_progress(progress, "SCRIPTING")
    script_settings = {**workflow, "prompt_template_id": prompt_id, "prompt_version": str(workflow.get("prompt_version", "1"))}
    if custom_prompt:
        script_settings["prompt_template_text"] = custom_prompt["template"]
    script_request = ProviderRequest("script", payload["job_id"], {**base_inputs, "research": research}, script_settings)
    script_provider_name = "user-supplied" if isinstance(content.get("script"), str) and content["script"].strip() else providers["script"]
    script_provider = registry.resolve("script", script_provider_name)
    max_tokens = int(limits.get("max_output_tokens", workflow.get("max_output_tokens", 1200)))
    if max_tokens < 1 or max_tokens > 8192:
        raise ProviderError("invalid_workflow_config", "max_output_tokens must be between 1 and 8192")
    pricing_in = script_settings.get("pricing_usd_per_1m_input_tokens")
    pricing_out = script_settings.get("pricing_usd_per_1m_output_tokens")
    estimated_cost = None
    if pricing_in is not None and pricing_out is not None:
        estimate_tokens = max(1, math.ceil(len(json.dumps(script_request.inputs, ensure_ascii=False)) / 4))
        estimated_cost = estimate_tokens * float(pricing_in) / 1_000_000 + max_tokens * float(pricing_out) / 1_000_000
    budget.before_call(estimated_cost, real_provider=script_provider_name == "openai-compatible")
    with gate:
        script = call_with_bounded_retry(lambda: script_provider.generate(script_request),
                                         retries + 1)
    script_model = {"mock": "mock-script-v1", "user-supplied": "user-script-v1",
                    "openai-compatible": "configured-model"}.get(script_provider_name, script_provider_name)
    executions.append(_execution("script", script_provider_name, script_model, script_request, script, script_settings,
                                 script.get("warnings", []) if isinstance(script, dict) else []))
    _write_progress(progress, "SCRIPTING", usage=_usage_summary(executions))
    budget.record(executions[-1].cost_usd)
    script = _validate_script(script, research, content.get("language", "und"), duration)
    warnings.extend(script.get("warnings", []))

    audio_path = output_root / "narration.wav"
    audio = None
    visuals = None
    def generate_audio():
        _write_progress(progress, "GENERATING_AUDIO")
        tts_request = ProviderRequest("text_to_speech", payload["job_id"], {"narration_text": script["narration_text"],
            "language": content.get("language", "und"), "voice_profile": workflow.get("voice_profile", "synthetic-test"),
            "voice_id": workflow.get("voice_id", "synthetic-440hz-test-tone"),
            "speaking_rate": workflow.get("speaking_rate", 1.0), "pitch": workflow.get("pitch", 0.0),
            "output_format": workflow.get("audio_format", "wav"),
            "brand_narration_profile": workflow.get("brand_narration_profile", {}), "brand": brand},
            {**workflow, "duration_seconds": duration})
        tts_provider = registry.resolve("text_to_speech", providers["text_to_speech"])
        with gate:
            result = tts_provider.synthesize(tts_request, str(audio_path))
        result["sha256"] = hashlib.sha256(audio_path.read_bytes()).hexdigest()
        result.pop("path", None)
        executions.append(_execution("text_to_speech", providers["text_to_speech"], result.get("model", "synthetic-tone-v1"),
                                     tts_request, {k: v for k, v in result.items() if k != "sha256"}, workflow))
        _write_progress(progress, "GENERATING_AUDIO", usage=_usage_summary(executions))
        result["path"] = str(audio_path)
        if result.get("synthetic"):
            warnings.append("Narration asset is a synthetic test tone, not speech and not user-ready narration.")
        return result
    def generate_visuals():
        _write_progress(progress, "GENERATING_VISUALS")
        scenes = [{"id": section["id"], "visual_instruction": section["visual_instruction"],
                   "subtitle": section["subtitle"], "evidence_refs": section["evidence_refs"],
                   "verification_status": section["verification_status"]} for section in script["sections"]]
        visual_request = ProviderRequest("visual_asset", payload["job_id"], {"brief": brief,
            "scene_breakdown": scenes, "visual_profile": workflow.get("visual_profile", "synthetic-test"),
            "aspect_ratio": workflow.get("aspect_ratio", "9:16"),
            "required_dimensions": workflow.get("visual_dimensions", {"width": 360, "height": 640}),
            "style_configuration": workflow.get("visual_style", {}),
            "asset_provenance_settings": workflow.get("asset_provenance_settings", {}),
            "user_assets": content.get("assets", []), "category": payload.get("category"), "brand": brand}, workflow)
        visual_provider = registry.resolve("visual_asset", providers["visual_asset"])
        with gate:
            result = visual_provider.create(visual_request, str(output_root / "visuals"))
        for asset in result:
            raw = Path(asset["path"]).read_bytes()
            asset["sha256"] = hashlib.sha256(raw).hexdigest()
        visual_model = result[0].get("model", "deterministic-original-png-v1") if result else "unknown"
        executions.append(_execution("visual_asset", providers["visual_asset"], visual_model,
                                     visual_request, [{k: v for k, v in asset.items() if k != "path"} for asset in result], workflow))
        _write_progress(progress, "GENERATING_VISUALS", usage=_usage_summary(executions))
        if providers["visual_asset"] == "mock":
            warnings.append("Mock visual rights permit local testing/rendering only; review licensing before external use.")
        return result
    for capability in capability_order[2:]:
        if capability == "text_to_speech":
            audio = generate_audio()
        else:
            visuals = generate_visuals()

    _write_progress(progress, "RENDERING")
    subtitle_path = output_root / "captions.srt"
    duration_ms = max(1, round(duration * 1000))
    end_time = f"00:{duration_ms // 60000:02d}:{(duration_ms // 1000) % 60:02d},{duration_ms % 1000:03d}"
    captions = " ".join(section["subtitle"] for section in script["sections"])
    lines = ["1", f"00:00:00,000 --> {end_time}", captions, ""]
    subtitle_path.write_text("\n".join(lines), encoding="utf-8")
    rights = lambda item: item["rights"]
    manifest = {"schema_version": "1.0", "job": {"job_id": payload["job_id"], "content_id": content["id"], "brand_id": content["brandId"]},
        "content": {"format": content["contentType"], "production_profile_id": content.get("productionProfileId") or profile["id"]},
        "visuals": [{"asset_id": asset["asset_id"], "kind": "image", "path": asset["path"], "order": index,
                     "source_reference": asset["source_reference"], "rights": rights(asset)} for index, asset in enumerate(visuals)],
        "audio": {"asset_id": "mock-narration", "path": str(audio_path), "required": True,
                  "source_reference": f"generated://{audio.get('provider')}/{audio.get('sha256')}", "rights": audio["rights"]},
        "subtitles": {"path": str(subtitle_path), "settings": workflow.get("subtitle_settings", {"font_size": 48, "margin_vertical": 160})},
        "render": {"profile": SUPPORTED_RENDERER, "duration_seconds": duration, "audio_required": True},
        "expected_output": str(output_root / "render.mp4")}
    manifest_path = output_root / "production-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    load_manifest(str(manifest_path))
    _write_progress(progress, "QA_PENDING")
    qa = render_manifest(str(manifest_path))
    qa_path = Path(str(output_root / "render.mp4") + ".qa.json")
    if qa.get("status") != "success" or any(not value for value in qa.get("checks", {}).values()):
        raise ProviderError("render_qa_failed", "Stage 2 render did not pass all QA checks")
    _write_progress(progress, "READY_FOR_REVIEW", "SUCCEEDED")
    public_visuals = [{k: v for k, v in asset.items() if k != "path"} for asset in visuals]
    audio_public = {k: v for k, v in audio.items() if k != "path"}
    execution_dicts = [item.as_dict() for item in executions]
    usage_summary = _usage_summary(executions)
    return {"status": "READY_FOR_REVIEW", "job_id": payload["job_id"], "content_id": content["id"],
            "workflow_id": workflow.get("workflow_id", profile.get("id")), "workflow_version": workflow.get("version", "1"),
            "mode": mode, "stage": "READY_FOR_REVIEW", "research_packet": research, "script": script,
            "narration": audio_public, "visuals": public_visuals, "provider_executions": execution_dicts,
            "usage": {**usage_summary, "estimated_cost_usd": None},
            "warnings": list(dict.fromkeys(warnings)), "errors": [],
            "render": {"manifest_path": str(manifest_path), "qa_path": str(qa_path),
                       "video_path": str(output_root / "render.mp4"), "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
                       "qa": {"status": qa["status"], "output_metadata": qa["output_metadata"],
                              "sha256": qa["sha256"], "checks": qa["checks"], "warnings": qa["warnings"]}}}
