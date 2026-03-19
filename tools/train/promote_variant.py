#!/usr/bin/env python3
"""
Promote/rollback model release states.

Example:
  python tools/train/promote_variant.py --manifest tools/train/release_manifest.json --set promoted=v5-r2
  python tools/train/promote_variant.py --manifest tools/train/release_manifest.json --rollback_to v5-r0
"""

import argparse
import json
from pathlib import Path


def load_manifest(path: Path) -> dict:
    if not path.exists():
        return {
            "current": {
                "baseline": "v8-r0-nomodel-prod",
                "candidate": "v8-r3-nomodel-prod",
                "promoted": "v8-r3-nomodel-prod",
            },
            "history": [],
        }
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def save_manifest(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default="tools/train/release_manifest.json")
    ap.add_argument("--set", help="state=modelVersion, e.g. candidate=v5-r2")
    ap.add_argument("--rollback_to", help="Rollback promoted to model version")
    ap.add_argument("--reason", default="manual")
    args = ap.parse_args()

    path = Path(args.manifest)
    data = load_manifest(path)
    current = data.setdefault("current", {})
    history = data.setdefault("history", [])

    if args.set:
        state, model = args.set.split("=", 1)
        if state not in {"baseline", "candidate", "promoted"}:
            raise SystemExit("state must be baseline/candidate/promoted")
        current[state] = model
        history.append({"modelVersion": model, "releaseState": state, "note": args.reason})
    if args.rollback_to:
        model = args.rollback_to
        current["promoted"] = model
        history.append({"modelVersion": model, "releaseState": "promoted", "note": f"rollback:{args.reason}"})

    save_manifest(path, data)
    print(f"saved manifest: {path}")


if __name__ == "__main__":
    main()
