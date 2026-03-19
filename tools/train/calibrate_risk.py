#!/usr/bin/env python3
"""
Build risk calibration points from labeled runs.

Input:
  - labels csv: run_id,label (tp/fp/fn_proxy)
  - runs_dir: directory containing run_xxx/summary.json

Output:
  - risk_calibration.json with piecewise points [{x,y}, ...]

Example:
  python tools/train/calibrate_risk.py \
    --labels tools/labeling/labels.csv \
    --runs_dir runs/candidates \
    --out tools/train/risk_calibration.json
"""

import argparse
import csv
import json
from pathlib import Path
from typing import Dict, List, Tuple


def load_labels(path: Path) -> Dict[str, Dict[str, int]]:
    out: Dict[str, Dict[str, int]] = {}
    with path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            run_id = (row.get("run_id") or "").strip()
            label = (row.get("label") or "").strip()
            if not run_id or label not in {"tp", "fp", "fn_proxy"}:
                continue
            stat = out.setdefault(run_id, {"tp": 0, "fp": 0, "fn_proxy": 0})
            stat[label] += 1
    return out


def discover_summaries(runs_dir: Path) -> Dict[str, Path]:
    summaries: Dict[str, Path] = {}
    for p in sorted(runs_dir.glob("**/summary.json")):
        summaries[p.parent.name] = p
    return summaries


def load_risk(path: Path) -> float:
    with path.open("r", encoding="utf-8") as f:
        summary = json.load(f)
    runtime = summary.get("runtimeMetrics", {})
    return float(runtime.get("riskScore", 0.0))


def monotonicize(points: List[Tuple[float, float]]) -> List[Tuple[float, float]]:
    if not points:
        return [(0.0, 0.0), (1.0, 1.0)]
    points = sorted(points, key=lambda p: p[0])
    out = [points[0]]
    last_y = points[0][1]
    for x, y in points[1:]:
        y_adj = max(last_y, y)
        out.append((x, y_adj))
        last_y = y_adj
    if out[0][0] > 0.0:
        out.insert(0, (0.0, out[0][1]))
    if out[-1][0] < 1.0:
        out.append((1.0, out[-1][1]))
    return out


def is_identity_like(points: List[Tuple[float, float]], tolerance: float = 0.03) -> bool:
    if not points:
        return True
    max_dev = max(abs(y - x) for x, y in points)
    return max_dev <= tolerance


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--labels", required=True)
    parser.add_argument("--runs_dir", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--bins", type=int, default=8)
    args = parser.parse_args()

    labels = load_labels(Path(args.labels))
    summaries = discover_summaries(Path(args.runs_dir))

    samples: List[Tuple[float, int]] = []
    for run_id, stat in labels.items():
        summary_path = summaries.get(run_id)
        if not summary_path or not summary_path.exists():
            continue
        tp = stat.get("tp", 0)
        fp = stat.get("fp", 0)
        if tp + fp <= 0:
            continue
        target = 1 if tp >= fp else 0
        risk = load_risk(summary_path)
        samples.append((risk, target))

    if len(samples) < 6:
        raise SystemExit("Not enough samples for calibration (need >=6 with tp/fp labels).")

    bins = max(3, args.bins)
    bin_rows: List[List[int]] = [[] for _ in range(bins)]
    for risk, target in samples:
        idx = min(bins - 1, max(0, int(risk * bins)))
        bin_rows[idx].append(target)

    points: List[Tuple[float, float]] = []
    for i in range(bins):
        center = (i + 0.5) / bins
        rows = bin_rows[i]
        if not rows:
            continue
        prob = sum(rows) / len(rows)
        points.append((center, prob))

    calibrated = monotonicize(points)
    identity_like = is_identity_like(calibrated)
    out = {
        "method": "bin_monotonic",
        "bins": bins,
        "sample_count": len(samples),
        "identity_like": identity_like,
        "points": [{"x": round(x, 4), "y": round(y, 4)} for x, y in calibrated],
    }

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"risk calibration saved: {out_path}")
    if identity_like:
        print("WARN: calibration curve is close to identity; check label diversity or increase bins.")


if __name__ == "__main__":
    main()

