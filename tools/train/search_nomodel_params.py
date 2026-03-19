#!/usr/bin/env python3
"""
Search no-model detection params from existing run snapshots.

Input requirement per run folder:
- summary.json (runtime metrics)
- params.json (detection params)

Output:
- JSON with three profiles: stable / balanced / sensitive
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple


PARAM_KEYS = [
    "cusumDriftK",
    "cusumThresholdH",
    "cusumDecay",
    "pulseDensityMin",
    "minNeighborhoodConsistency",
    "minTrackStabilityConfirm",
]


@dataclass
class RunRow:
    run_id: str
    params: Dict[str, float]
    poor_ratio: float
    process_ms: float
    mean_rate_60s: float
    cusum_pass: float


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def discover_runs(runs_dir: Path) -> List[Tuple[Path, Path]]:
    rows: List[Tuple[Path, Path]] = []
    for summary_path in sorted(runs_dir.glob("**/summary.json")):
        params_path = summary_path.parent / "params.json"
        if params_path.exists():
            rows.append((params_path, summary_path))
    return rows


def build_rows(runs_dir: Path) -> List[RunRow]:
    pairs = discover_runs(runs_dir)
    rows: List[RunRow] = []
    for params_path, summary_path in pairs:
        params_obj = load_json(params_path)
        summary_obj = load_json(summary_path)
        det = params_obj.get("detection", {})
        runtime = summary_obj.get("runtimeMetrics", {})
        reliability = runtime.get("reliabilitySamples", {})
        params: Dict[str, float] = {}
        ok = True
        for k in PARAM_KEYS:
            if k not in det:
                ok = False
                break
            params[k] = float(det.get(k, 0.0))
        if not ok:
            continue
        row = RunRow(
            run_id=summary_path.parent.name,
            params=params,
            poor_ratio=float(reliability.get("poorRatio", 1.0)),
            process_ms=float(runtime.get("processMsAvg", 0.0)),
            mean_rate_60s=float(summary_obj.get("meanRate60s", 0.0)),
            cusum_pass=1.0 if bool(runtime.get("cusumPass", False)) else 0.0,
        )
        rows.append(row)
    return rows


def normalize(v: float, lo: float, hi: float) -> float:
    if hi <= lo:
        return 0.0
    return (v - lo) / (hi - lo)


def rank_profiles(rows: List[RunRow]) -> Dict[str, Dict[str, float]]:
    if not rows:
        return {}
    poor_values = [r.poor_ratio for r in rows]
    perf_values = [r.process_ms for r in rows]
    recall_values = [r.mean_rate_60s for r in rows]
    stable_values = [r.cusum_pass for r in rows]

    poor_lo, poor_hi = min(poor_values), max(poor_values)
    perf_lo, perf_hi = min(perf_values), max(perf_values)
    recall_lo, recall_hi = min(recall_values), max(recall_values)
    stable_lo, stable_hi = min(stable_values), max(stable_values)

    scored = []
    for r in rows:
        poor_norm = normalize(r.poor_ratio, poor_lo, poor_hi)           # lower is better
        perf_norm = normalize(r.process_ms, perf_lo, perf_hi)           # lower is better
        recall_norm = normalize(r.mean_rate_60s, recall_lo, recall_hi)  # higher is better
        stable_norm = normalize(r.cusum_pass, stable_lo, stable_hi)     # higher is better
        score_stable = 2.4 * (1.0 - poor_norm) + 1.6 * (1.0 - perf_norm) + 0.9 * stable_norm + 0.4 * recall_norm
        score_balanced = 2.0 * (1.0 - poor_norm) + 1.0 * (1.0 - perf_norm) + 1.1 * stable_norm + 1.1 * recall_norm
        score_sensitive = 1.4 * (1.0 - poor_norm) + 0.7 * (1.0 - perf_norm) + 0.8 * stable_norm + 1.8 * recall_norm
        scored.append((r, score_stable, score_balanced, score_sensitive))

    stable_best = max(scored, key=lambda x: x[1])[0]
    balanced_best = max(scored, key=lambda x: x[2])[0]
    sensitive_best = max(scored, key=lambda x: x[3])[0]

    return {
        "stable": stable_best.params,
        "balanced": balanced_best.params,
        "sensitive": sensitive_best.params,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs_dir", required=True, help="Root dir containing run folders")
    parser.add_argument("--out", required=True, help="Output JSON path")
    args = parser.parse_args()

    rows = build_rows(Path(args.runs_dir))
    if len(rows) < 3:
        raise SystemExit("有效runs不足（需要至少3个，且含 params.json + summary.json）")
    profiles = rank_profiles(rows)
    payload = {
        "modelVersion": "v7a-r0-nomodel-auto",
        "samples": len(rows),
        "paramKeys": PARAM_KEYS,
        "profiles": profiles,
    }
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"saved: {out_path}")


if __name__ == "__main__":
    main()

