import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from shorts_worker.manifest import load_manifest
from shorts_worker.render import _subtitle_force_style, render_manifest


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.visual = self.root / "visual.ppm"
        self.visual.write_text("P3\n1 1\n255\n1 2 3\n", encoding="ascii")
        self.audio = self.root / "tone.wav"
        self.audio.write_bytes(b"synthetic test placeholder")
        self.captions = self.root / "captions.srt"
        self.captions.write_text("1\n00:00:00,000 --> 00:00:01,000\nCaption\n", encoding="utf-8")
        self.manifest = {"schema_version": "1.0", "job": {"job_id": "job-1", "content_id": "content-1", "brand_id": "brand-1"},
            "content": {"format": "short-form video", "production_profile_id": "shorts-profile"},
            "visuals": [{"asset_id": "v1", "kind": "image", "path": "visual.ppm", "order": 0,
                         "source_reference": "generated://test", "rights": {"owner": "test", "license_type": "owned", "permitted_uses": ["test"]}}],
            "audio": {"asset_id": "a1", "path": "tone.wav", "required": True, "source_reference": "generated://tone",
                      "rights": {"owner": "test", "license_type": "owned", "permitted_uses": ["test"]}},
            "subtitles": {"path": "captions.srt", "settings": {"font_size": 48}},
            "render": {"profile": "shorts-vertical-v1", "duration_seconds": 1}, "expected_output": "out.mp4"}
        self.path = self.root / "manifest.json"
        self.write()

    def tearDown(self):
        self.temp.cleanup()

    def write(self):
        self.path.write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_valid_manifest_resolves_paths_and_profile_defaults(self):
        result = load_manifest(self.path)
        self.assertEqual(str(self.visual.resolve()), result["visuals"][0]["path"])
        self.assertEqual(1080, result["render"]["width"])
        self.assertEqual(30, result["render"]["fps"])

    def test_subtitle_settings_are_scaled_to_safe_output_pixels(self):
        self.assertIn("FontSize=7", _subtitle_force_style({"font_size": 48}))
        self.assertIn("MarginV=24", _subtitle_force_style({"margin_vertical": 160}))

    def test_invalid_schema_profile_and_missing_assets_fail(self):
        self.manifest["schema_version"] = "9"
        self.write()
        with self.assertRaisesRegex(ValueError, "unsupported schema_version"):
            load_manifest(self.path)
        self.manifest["schema_version"] = "1.0"
        self.manifest["render"]["profile"] = "unknown"
        self.write()
        with self.assertRaisesRegex(ValueError, "unsupported rendering profile"):
            load_manifest(self.path)
        self.manifest["render"]["profile"] = "shorts-vertical-v1"
        self.manifest["visuals"][0]["path"] = "missing.ppm"
        self.write()
        with self.assertRaisesRegex(ValueError, "does not exist"):
            load_manifest(self.path)

    def test_invalid_path_extension_duration_and_incompatible_profile_fail(self):
        self.manifest["visuals"][0]["path"] = "visual.exe"
        (self.root / "visual.exe").write_text("x")
        self.write()
        with self.assertRaisesRegex(ValueError, "unsupported media format"):
            load_manifest(self.path)
        self.manifest["visuals"][0]["path"] = "visual.ppm"
        self.manifest["render"]["fps"] = 24
        self.write()
        with self.assertRaisesRegex(ValueError, "must be 30"):
            load_manifest(self.path)
        self.manifest["render"]["fps"] = 30
        self.manifest["render"]["duration_seconds"] = 0.001
        self.write()
        with self.assertRaisesRegex(ValueError, "at least one frame"):
            load_manifest(self.path)

    def test_relative_path_traversal_is_explicit_and_must_exist(self):
        outside = self.root.parent / "outside-fixture.ppm"
        outside.write_text("fixture", encoding="utf-8")
        self.manifest["audio"]["path"] = "../missing.wav"
        self.write()
        with self.assertRaisesRegex(ValueError, "must stay inside"):
            load_manifest(self.path)
        self.manifest["audio"]["path"] = "tone.wav"
        self.manifest["visuals"][0]["path"] = "../outside-fixture.ppm"
        self.write()
        with self.assertRaisesRegex(ValueError, "must stay inside"):
            load_manifest(self.path)
        outside.unlink(missing_ok=True)

    def test_missing_ffmpeg_generates_failed_report_and_clear_error(self):
        with patch("shorts_worker.render.shutil.which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "not found on PATH"):
                render_manifest(self.path)
        report = json.loads((self.root / "out.mp4.qa.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", report["status"])

    def test_missing_ffprobe_generates_failed_report(self):
        ffmpeg_path = shutil.which("ffmpeg")
        def resolve(name):
            return ffmpeg_path if name == "ffmpeg" else None

        with patch("shorts_worker.render.shutil.which", side_effect=resolve):
            with self.assertRaisesRegex(RuntimeError, "ffprobe was not found"):
                render_manifest(self.path)
        report = json.loads((self.root / "out.mp4.qa.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", report["status"])

    def test_failed_ffmpeg_run_removes_partial_output_and_writes_failure_report(self):
        def fail_after_partial_output(args):
            Path(args[-1]).write_bytes(b"partial mp4")
            raise RuntimeError("synthetic ffmpeg failure")

        with patch("shorts_worker.render._tool", side_effect=lambda name: name), \
                patch("shorts_worker.render._run", side_effect=fail_after_partial_output):
            with self.assertRaisesRegex(RuntimeError, "synthetic ffmpeg failure"):
                render_manifest(self.path)
        self.assertFalse((self.root / "out.mp4").exists())
        report = json.loads((self.root / "out.mp4.qa.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", report["status"])


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg/FFprobe are not installed")
class RenderIntegrationTests(unittest.TestCase):
    def test_real_fixture_render_and_qa(self):
        fixture_root = Path(__file__).parents[1] / "fixtures"
        generator = fixture_root / "generate_fixtures.py"
        import subprocess
        import sys
        subprocess.run([sys.executable, str(generator)], check=True)
        manifest = fixture_root / "sample-manifest.json"
        report = render_manifest(manifest)
        self.assertEqual("success", report["status"])
        self.assertEqual(1080, report["output_metadata"]["width"])
        self.assertEqual("h264", report["output_metadata"]["video_codec"])
        self.assertEqual("aac", report["output_metadata"]["audio_codec"])
        self.assertEqual(64, len(report["sha256"]))


if __name__ == "__main__":
    unittest.main()
