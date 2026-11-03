"""Command line entry point for local dry-run jobs."""

import argparse
import json
import sys

from shorts_worker.plan import create_demo_plan


def main() -> int:
    parser = argparse.ArgumentParser(prog="mediaos-worker", description="Run a deterministic local Shorts demo")
    subparsers = parser.add_subparsers(dest="command", required=True)
    demo = subparsers.add_parser("demo", help="print a mock production plan as JSON")
    demo.add_argument("--brand-id", required=True)
    demo.add_argument("--brief", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(create_demo_plan(args.brand_id, args.brief), indent=2, ensure_ascii=False))
        return 0
    except ValueError as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
