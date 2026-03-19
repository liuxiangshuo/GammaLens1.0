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
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple


PARAM_SPECS: Dict[str, Dict[str, float | str]] = {
    "riskScoreTriggerHigh": {"default": 0.68, "min": 0.55, "max": 0.85, "type": "float"},
    "riskScoreReleaseLow": {"default": 0.52, "min": 0.40, "max": 0.70, "type": "float"},
    "riskWeightPoisson": {"default": 0.35, "min": 0.20, "max": 0.50, "type": "float"},
    "riskWeightCusum": {"default": 0.25, "min": 0.10, "max": 0.40, "type": "float"},
    "riskWeightStability": {"default": 0.20, "min": 0.10, "max": 0.35, "type": "float"},
    "riskWeightQuality": {"default": 0.20, "min": 0.10, "max": 0.35, "type": "float"},
    "poissonConfidenceMin": {"default": 0.45, "min": 0.35, "max": 0.70, "type": "float"},
    "deadTimeDropTarget": {"default": 0.16, "min": 0.08, "max": 0.30, "type": "float"},
    "deadTimeDropFeedbackGain": {"default": 0.35, "min": 0.10, "max": 0.60, "type": "float"},
    "frameStackDepth": {"default": 5.0, "min": 3.0, "max": 8.0, "type": "int"},
    "frameStackThreshold": {"default": 0.40, "min": 0.25, "max": 0.60, "type": "float"},
    "pulseDensityMin": {"default": 0.08, "min": 0.04, "max": 0.20, "type": "float"},
}
PARAM_KEYS = list(PARAM_SPECS.keys())


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
        for k in PARAM_KEYS:
            raw = det.get(k, PARAM_SPECS[k]["default"])
            try:
                value = float(raw)
            except Exception:
                value = float(PARAM_SPECS[k]["default"])
            if not math.isfinite(value):
                value = float(PARAM_SPECS[k]["default"])
            params[k] = value
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


def clamp_value(key: str, value: float) -> float:
    spec = PARAM_SPECS[key]
    lo = float(spec["min"])
    hi = float(spec["max"])
    v = max(lo, min(hi, value))
    if spec["type"] == "int":
        return float(int(round(v)))
    return float(v)


def clamp_params(params: Dict[str, float]) -> Dict[str, float]:
    return {k: clamp_value(k, float(params.get(k, PARAM_SPECS[k]["default"]))) for k in PARAM_KEYS}


def generate_local_candidates(base_params: Dict[str, float]) -> List[Dict[str, float]]:
    base = clamp_params(base_params)
    candidates: List[Dict[str, float]] = [base]
    for key in PARAM_KEYS:
        spec = PARAM_SPECS[key]
        lo = float(spec["min"])
        hi = float(spec["max"])
        span = hi - lo
        if span <= 0:
            continue
        delta = span * 0.08
        for direction in (-1.0, 1.0):
            c = dict(base)
            c[key] = clamp_value(key, c[key] + direction * delta)
            candidates.append(c)
    dedup: List[Dict[str, float]] = []
    seen = set()
    for c in candidates:
        key = tuple((k, c[k]) for k in PARAM_KEYS)
        if key in seen:
            continue
        seen.add(key)
        dedup.append(c)
    return dedup


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
        "stable": clamp_params(stable_best.params),
        "balanced": clamp_params(balanced_best.params),
        "sensitive": clamp_params(sensitive_best.params),
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
    candidate_sets = {
        "stable": generate_local_candidates(profiles["stable"])[:10],
        "balanced": generate_local_candidates(profiles["balanced"])[:10],
        "sensitive": generate_local_candidates(profiles["sensitive"])[:10],
    }
    payload = {
        "modelVersion": "v8-r3-nomodel-auto",
        "samples": len(rows),
        "paramKeys": PARAM_KEYS,
        "searchSpace": {
            key: {"default": spec["default"], "min": spec["min"], "max": spec["max"], "type": spec["type"]}
            for key, spec in PARAM_SPECS.items()
        },
        "profiles": profiles,
        "generatedCandidates": candidate_sets,
        "sourceRunIds": sorted({r.run_id for r in rows}),
    }
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"saved: {out_path}")


if __name__ == "__main__":
    main()

