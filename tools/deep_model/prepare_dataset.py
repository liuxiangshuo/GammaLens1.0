#!/usr/bin/env python3
"""
Create a manifest from raw images.
Supports both:
- classification labels (positive/negative)
- regression targets (radiation_value)
"""

import argparse
import csv
from pathlib import Path


def is_image(path: Path) -> bool:
    return path.suffix.lower() in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw_dir", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    raw_dir = Path(args.raw_dir)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    rows = []
    for p in sorted(raw_dir.rglob("*")):
        if p.is_file() and is_image(p):
            rows.append(
                {
                    "image_path": str(p.as_posix()),
                    "label": "",
                    "radiation_value": "",
                    "unit": "uSv/h",
                    "scenario_id": "dark_static",
                    "notes": "",
                }
            )

    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=["image_path", "label", "radiation_value", "unit", "scenario_id", "notes"],
        )
        writer.writeheader()
        writer.writerows(rows)

    print(f"manifest saved: {out} rows={len(rows)}")


if __name__ == "__main__":
    main()
