"""Command line entry point for local worker jobs."""

import argparse
import json
import sys

from shorts_worker.plan import create_demo_plan
from shorts_worker.render import inspect_media, render_manifest
from shorts_worker.manifest import load_manifest


def main() -> int:
    parser = argparse.ArgumentParser(prog="mediaos-worker", description="Run a deterministic local Shorts demo")
    subparsers = parser.add_subparsers(dest="command", required=True)
    demo = subparsers.add_parser("demo", help="print a mock production plan as JSON")
    demo.add_argument("--brand-id", required=True)
    demo.add_argument("--brief", required=True)
    validate = subparsers.add_parser("validate-manifest", help="validate a versioned production manifest")
    validate.add_argument("--manifest", required=True)
    render = subparsers.add_parser("render", help="render and QA a production manifest")
    render.add_argument("--manifest", required=True)
    render.add_argument("--output")
    inspect = subparsers.add_parser("inspect", help="inspect a media file with FFprobe")
    inspect.add_argument("--input", required=True)
    args = parser.parse_args()
    try:
        if args.command == "demo":
            result = create_demo_plan(args.brand_id, args.brief)
        elif args.command == "validate-manifest":
            manifest = load_manifest(args.manifest)
            result = {"valid": True, "schema_version": manifest["schema_version"], "job_id": manifest["job"]["job_id"]}
        elif args.command == "render":
            result = render_manifest(args.manifest, args.output)
        else:
            result = inspect_media(args.input)
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0
    except (ValueError, OSError, RuntimeError) as error:
        print(json.dumps({"error": str(error), "status": "failed"}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
