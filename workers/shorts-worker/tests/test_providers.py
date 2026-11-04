import json
import os
import shutil
import tempfile
import io
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

from shorts_worker.orchestrator import _execution, _validate_research, _validate_script, run_production
from shorts_worker.providers.contracts import ProviderError, ProviderRequest
from shorts_worker.providers.mock import (MockResearchProvider, MockScriptProvider,
    MockTextToSpeechProvider, MockVisualAssetProvider, UserSourceResearchProvider,
    UserSuppliedVisualAssetProvider)
from shorts_worker.providers.registry import OpenAICompatibleScriptProvider, default_registry
from shorts_worker.providers.safeguards import BudgetGuard, call_with_bounded_retry


def fixture_request():
    content = {"id": "content-test", "brandId": "brand-test", "contentType": "user-defined short video",
        "language": "en", "brief": "Explore a question without asserting unsupported facts.", "title": "A test brief",
        "sourceRecords": [{"title": "Supplied source", "publisher": "Example", "url": "https://example.test/ref", "accessedAt": "today"}]}
    return ProviderRequest("research", "job-test", {"content": content, "brand": {"name": "Test Brand"},
        "brief": content["brief"], "language": "en"}, {})


class ProviderTests(unittest.TestCase):
    def test_registry_exposes_capability_specific_providers_and_rejects_unsupported(self):
        registry = default_registry()
        self.assertEqual("openai-compatible", registry.resolve("script", "openai-compatible").name)
        self.assertEqual("user-supplied", registry.resolve("research", "user-supplied").name)
        with self.assertRaisesRegex(ProviderError, "not registered"):
            registry.resolve("visual_asset", "openai-compatible")

    def test_mock_research_preserves_user_sources_without_fabrication_or_verification_claim(self):
        packet = MockResearchProvider().research(fixture_request())
        self.assertEqual("https://example.test/ref", packet["sources"][0]["url"])
        self.assertEqual([], packet["evidence"])
        self.assertEqual("requires_verification", packet["claims"][0]["verification_status"])
        self.assertIn("did not fetch", packet["sources"][0]["evidence"])
        self.assertEqual(packet, UserSourceResearchProvider().research(fixture_request()))

    def test_research_validation_rejects_fabricated_urls_and_unsupported_claims(self):
        request = fixture_request()
        packet = MockResearchProvider().research(request)
        fabricated = json.loads(json.dumps(packet))
        fabricated["sources"][0]["url"] = "https://made-up.example/fact"
        with self.assertRaisesRegex(ProviderError, "not supplied by the user"):
            _validate_research(fabricated, request.inputs["content"]["sourceRecords"])
        unsupported = json.loads(json.dumps(packet))
        unsupported["claims"][0]["verification_status"] = "supported"
        with self.assertRaisesRegex(ProviderError, "without evidence"):
            _validate_research(unsupported, request.inputs["content"]["sourceRecords"])

    def test_mock_script_is_structured_and_claims_remain_unverified(self):
        request = fixture_request()
        research = MockResearchProvider().research(request)
        script = MockScriptProvider().generate(ProviderRequest("script", request.job_id,
            {**request.inputs, "research": research}, {"duration_seconds": 7}))
        validated = _validate_script(script, research, "en", 7)
        self.assertGreaterEqual(len(validated["sections"]), 1)
        self.assertEqual("requires_verification", validated["sections"][0]["verification_status"])
        invalid = dict(validated)
        invalid["sections"] = [dict(validated["sections"][0], evidence_refs=["invented-source"])]
        with self.assertRaisesRegex(ProviderError, "not in the research packet"):
            _validate_script(invalid, research, "en", 7)

    def test_mock_audio_is_labeled_synthetic_and_visual_is_original_with_limited_rights(self):
        request = fixture_request()
        with tempfile.TemporaryDirectory() as temporary:
            audio_path = Path(temporary) / "tone.wav"
            audio = MockTextToSpeechProvider().synthesize(ProviderRequest("text_to_speech", "job-test",
                {"narration_text": "This is not speech", "language": "en"}, {"duration_seconds": 1}), str(audio_path))
            self.assertTrue(audio["synthetic"])
            self.assertIn("not speech", audio["rights"]["attribution"])
            with wave.open(str(audio_path), "rb") as sound:
                self.assertEqual(22050, sound.getframerate())
            visual_request = ProviderRequest("visual_asset", "job-test", {"brief": request.inputs["brief"],
                "scene_breakdown": [{"id": "s1", "visual_instruction": "abstract card"}]}, {})
            visuals = MockVisualAssetProvider().create(visual_request, str(Path(temporary) / "visuals"))
            self.assertTrue((Path(temporary) / "visuals" / "visual-1.png").read_bytes().startswith(b"\x89PNG"))
            self.assertEqual("synthetic-test", visuals[0]["rights"]["license_type"])
            self.assertIn("local-rendering", visuals[0]["rights"]["permitted_uses"])

    def test_user_supplied_visual_requires_allowlisted_path_and_complete_rights(self):
        request = ProviderRequest("visual_asset", "job-test", {"user_assets": [{
            "kind": "image", "uri": "original.png", "mimeType": "image/png", "owner": "Me",
            "licenseType": "owned", "licenseEvidence": "Original artwork", "attribution": "By me",
            "permittedUses": ["render", "review"]}]}, {})
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "source"
            root.mkdir()
            generated = MockVisualAssetProvider().create(ProviderRequest("visual_asset", "seed",
                {"brief": "fixture", "scene_breakdown": ["cover"]}, {}), str(root / "generated"))[0]
            shutil.copyfile(generated["path"], root / "original.png")
            with patch.dict(os.environ, {"MEDIAOS_SOURCE_ASSET_DIR": str(root)}):
                visuals = UserSuppliedVisualAssetProvider().create(request, str(Path(temporary) / "output"))
                self.assertEqual("image/png", visuals[0]["mime_type"])
                self.assertEqual(360, visuals[0]["width"])
                bad_path = ProviderRequest("visual_asset", "job-test", {"user_assets": [dict(request.inputs["user_assets"][0], uri="../escape.png")]}, {})
                with self.assertRaisesRegex(ProviderError, "inside MEDIAOS_SOURCE_ASSET_DIR"):
                    UserSuppliedVisualAssetProvider().create(bad_path, str(Path(temporary) / "bad"))
                missing_rights = ProviderRequest("visual_asset", "job-test", {"user_assets": [dict(request.inputs["user_assets"][0], licenseType="unknown")]}, {})
                with self.assertRaisesRegex(ProviderError, "require known owner"):
                    UserSuppliedVisualAssetProvider().create(missing_rights, str(Path(temporary) / "bad-rights"))

    def test_real_provider_requires_explicit_enablement_and_credentials_before_network(self):
        provider = OpenAICompatibleScriptProvider()
        request = ProviderRequest("script", "job-test", {}, {"base_url": "https://unused.invalid", "model": "m"})
        with patch.dict(os.environ, {"MEDIAOS_REAL_PROVIDERS_ENABLED": "false"}, clear=False):
            with self.assertRaisesRegex(ProviderError, "disabled"):
                provider.generate(request)
        with patch.dict(os.environ, {"MEDIAOS_REAL_PROVIDERS_ENABLED": "true"}, clear=False):
            os.environ.pop("MEDIAOS_OPENAI_COMPAT_API_KEY", None)
            with self.assertRaisesRegex(ProviderError, "API_KEY"):
                provider.generate(request)

    def test_openai_compatible_adapter_uses_configured_endpoint_and_returns_usage(self):
        provider = OpenAICompatibleScriptProvider()
        request = ProviderRequest("script", "job-test", {"brief": "A sample"}, {
            "base_url": "https://provider.example/custom", "endpoint_path": "/generate",
            "model": "configured-model", "prompt_template_id": "test-prompt", "prompt_version": "3"})
        body = {"model": "served-model", "usage": {"prompt_tokens": 100, "completion_tokens": 50},
                "choices": [{"message": {"content": json.dumps({"title": "x", "sections": []})}}]}
        with patch.dict(os.environ, {"MEDIAOS_REAL_PROVIDERS_ENABLED": "true", "MEDIAOS_OPENAI_COMPAT_API_KEY": "test-secret"}):
            with patch("shorts_worker.providers.registry.urllib.request.urlopen", return_value=io.BytesIO(json.dumps(body).encode())) as open_url:
                result = provider.generate(request)
        self.assertEqual({"prompt_tokens": 100, "completion_tokens": 50}, result["_provider_usage"])
        self.assertEqual("served-model", result["_provider_model"])
        sent_request = open_url.call_args.args[0]
        self.assertEqual("https://provider.example/custom/generate", sent_request.full_url)
        self.assertEqual("Bearer test-secret", sent_request.get_header("Authorization"))
        execution = _execution("script", provider.name, "configured-model", request, result,
                               {"pricing_usd_per_1m_input_tokens": 2, "pricing_usd_per_1m_output_tokens": 4})
        self.assertEqual(0.0004, execution.cost_usd)
        self.assertNotIn("test-secret", json.dumps(execution.as_dict()))

    def test_provider_budget_retry_and_no_fabricated_cost(self):
        guard = BudgetGuard(0.05)
        with self.assertRaisesRegex(ProviderError, "without configured provider pricing"):
            guard.before_call(None, real_provider=True)
        guard.before_call(None, real_provider=False)
        guard.record(None)
        self.assertEqual(0, guard.spent_usd)
        calls = []
        def transient():
            calls.append(1)
            if len(calls) == 1:
                raise ProviderError("temporarily_unavailable", "retry", retryable=True)
            return "ok"
        self.assertEqual("ok", call_with_bounded_retry(transient, attempts=2))
        with self.assertRaises(ValueError):
            call_with_bounded_retry(transient, attempts=4)

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg/FFprobe are not installed")
    def test_end_to_end_mock_workflow_renders_real_stage2_mp4_with_qa(self):
        request = fixture_request()
        profile = {"id": "profile-test", "name": "Local mock", "contentFormat": request.inputs["content"]["contentType"],
                   "settings": {}}
        payload = {"job_id": "job-end-to-end", "content": {**request.inputs["content"],
                   "productionProfileId": "profile-test"}, "brand": {"id": "brand-test", "name": "Test Brand",
                   "tone": "curious", "contentPillars": ["questions"], "audienceProfile": {"primaryAudience": "general"}},
                   "category": {"id": "category-test", "name": "User category"}, "profile": profile,
                   "workflow": {"workflow_id": "workflow-test", "version": "1", "mode": "MOCK",
                       "renderer": "shorts-vertical-v1", "duration_seconds": 2,
                       "providers": {"research": "mock", "script": "mock", "text_to_speech": "mock", "visual_asset": "mock"}}}
        with tempfile.TemporaryDirectory() as temporary:
            result = run_production(payload, temporary)
            self.assertEqual("READY_FOR_REVIEW", result["status"])
            self.assertEqual("success", result["render"]["qa"]["status"])
            self.assertEqual(1080, result["render"]["qa"]["output_metadata"]["width"])
            self.assertEqual("h264", result["render"]["qa"]["output_metadata"]["video_codec"])
            self.assertEqual("aac", result["render"]["qa"]["output_metadata"]["audio_codec"])
            self.assertIsNone(result["usage"]["reported_cost_usd"])
            self.assertTrue(Path(temporary, "production-manifest.json").is_file())
            self.assertEqual("synthetic-test", result["visuals"][0]["rights"]["license_type"])


if __name__ == "__main__":
    unittest.main()
