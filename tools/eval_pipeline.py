#!/usr/bin/env python3
"""
GammaLens one-shot evaluation pipeline with hard-gate, CI and diagnostics.
"""

import argparse
import csv
import json
import math
import random
from pathlib import Path
from typing import Dict, List, Optional, Tuple


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def extract(summary: dict) -> Dict[str, float]:
    runtime = summary.get("runtimeMetrics", {})
    reliability = runtime.get("reliabilitySamples", {})
    return {
        "poorRatio": float(reliability.get("poorRatio", 1.0)),
        "meanRate60s": float(summary.get("meanRate60s", 0.0)),
        "processMsAvg": float(runtime.get("processMsAvg", 0.0)),
        "fps": float(runtime.get("fps", 0.0)),
        "scenarioId": str(runtime.get("scenarioId", summary.get("scenarioId", "unknown"))),
        "tempBucket": str(runtime.get("temperatureBucket", "unknown")),
        "modelVersion": str(runtime.get("modelVersion", summary.get("modelVersion", "unknown"))),
        "releaseState": str(runtime.get("releaseState", summary.get("releaseState", "unknown"))),
    }


def collect_invalid_numeric_fields(metrics: Dict[str, float]) -> List[str]:
    invalid: List[str] = []
    for key in ("poorRatio", "meanRate60s", "processMsAvg"):
        value = metrics.get(key)
        if not isinstance(value, (int, float)) or not math.isfinite(float(value)):
            invalid.append(key)
    poor_ratio = metrics.get("poorRatio")
    if isinstance(poor_ratio, (int, float)) and math.isfinite(float(poor_ratio)):
        if not (0.0 <= float(poor_ratio) <= 1.0):
            invalid.append("poorRatio(range)")
    process_ms = metrics.get("processMsAvg")
    if isinstance(process_ms, (int, float)) and math.isfinite(float(process_ms)):
        if float(process_ms) <= 0.0:
            invalid.append("processMsAvg(<=0)")
    mean_rate = metrics.get("meanRate60s")
    if isinstance(mean_rate, (int, float)) and math.isfinite(float(mean_rate)):
        if float(mean_rate) < 0.0:
            invalid.append("meanRate60s(<0)")
    return invalid


def pct_change(new: float, old: float) -> float:
    return (new - old) / old * 100.0


def percentile(values: List[float], q: float) -> float:
    if not values:
        return 0.0
    vs = sorted(values)
    idx = max(0, min(len(vs) - 1, int(round(q * (len(vs) - 1)))))
    return float(vs[idx])


def bootstrap_ci(values: List[float], iters: int = 500, seed: int = 42) -> Dict[str, float]:
    if not values:
        return {"mean": 0.0, "low": 0.0, "high": 0.0}
    if len(values) == 1:
        v = float(values[0])
        return {"mean": v, "low": v, "high": v}
    rng = random.Random(seed)
    n = len(values)
    means: List[float] = []
    for _ in range(max(100, iters)):
        sample = [values[rng.randrange(n)] for _ in range(n)]
        means.append(sum(sample) / n)
    means.sort()
    low = percentile(means, 0.025)
    high = percentile(means, 0.975)
    mean = sum(values) / n
    return {"mean": mean, "low": low, "high": high}


def effect_label(ci: Dict[str, float]) -> str:
    if ci["low"] > 0:
        return "significant_improve"
    if ci["high"] < 0:
        return "significant_regress"
    return "no_significant_change"


def discover_candidates(candidates_dir: Path) -> List[Path]:
    return [p for p in sorted(candidates_dir.glob("**/summary.json")) if p.is_file()]


def discover_baselines(baseline_path: Path) -> List[Dict[str, float]]:
    if baseline_path.is_file():
        return [extract(load_json(baseline_path))]
    if baseline_path.is_dir():
        files = [p for p in sorted(baseline_path.glob("**/summary.json")) if p.is_file()]
        return [extract(load_json(p)) for p in files]
    return []


def choose_baseline(
    cand: Dict[str, float],
    baseline_pool: List[Dict[str, float]],
    allow_fallback: bool,
) -> Optional[Dict[str, float]]:
    if not baseline_pool:
        return None
    # Priority 1: exact scenario+temp bucket.
    for b in baseline_pool:
        if b.get("scenarioId") == cand.get("scenarioId") and b.get("tempBucket") == cand.get("tempBucket"):
            return b
    if allow_fallback:
        # Priority 2: same scenario.
        for b in baseline_pool:
            if b.get("scenarioId") == cand.get("scenarioId"):
                return b
        # Priority 3: fallback only when there is a single baseline.
        if len(baseline_pool) == 1:
            return baseline_pool[0]
    return None


def parse_target_percent(expr: str) -> float:
    cleaned = expr.replace(">=", "").replace("%", "").strip()
    return float(cleaned)


def parse_budget_percent(expr: str) -> float:
    cleaned = expr.replace("<=", "").replace("+", "").replace("%", "").strip()
    return float(cleaned)


def parse_numeric_expr(expr: object, field_name: str) -> float:
    if expr is None:
        raise ValueError(f"{field_name} is missing")
    if isinstance(expr, (int, float)):
        value = float(expr)
    else:
        text = str(expr).strip()
        if not text:
            raise ValueError(f"{field_name} is empty")
        cleaned = (
            text.replace(">=", "")
            .replace("<=", "")
            .replace(">", "")
            .replace("<", "")
            .replace("+", "")
            .replace("%", "")
            .strip()
        )
        if not cleaned:
            raise ValueError(f"{field_name} has invalid format: {expr!r}")
        value = float(cleaned)
    if not math.isfinite(value):
        raise ValueError(f"{field_name} must be finite, got {expr!r}")
    return value


def validate_targets_schema(targets: dict, strict_mode: bool) -> List[str]:
    errors: List[str] = []
    required_fields = ("false_positive_drop", "recall_improvement", "process_ms_budget")
    for field in required_fields:
        if field not in targets:
            errors.append(f"targets.{field} is required")
    for field in required_fields:
        if field in targets:
            try:
                value = parse_numeric_expr(targets.get(field), f"targets.{field}")
                if value < 0.0:
                    errors.append(f"targets.{field} must be >= 0, got {value}")
            except ValueError as exc:
                errors.append(str(exc))
    for field in ("pairing_coverage_min", "label_precision_min", "label_recall_min", "label_f1_min"):
        if field in targets:
            try:
                value = parse_numeric_expr(targets.get(field), f"targets.{field}")
                if not (0.0 <= value <= 1.0):
                    errors.append(f"targets.{field} must be within [0,1], got {value}")
            except ValueError as exc:
                errors.append(str(exc))
    if "scenario_min_samples" in targets:
        try:
            scenario_min_samples = int(targets.get("scenario_min_samples", 1))
            if scenario_min_samples < 1:
                errors.append("targets.scenario_min_samples must be >= 1")
        except Exception:
            errors.append(f"targets.scenario_min_samples must be integer, got {targets.get('scenario_min_samples')!r}")
    if strict_mode and parse_bool(targets.get("allow_baseline_fallback"), default=False):
        errors.append("strict mode does not allow targets.allow_baseline_fallback=true")
    return errors


def parse_ratio(expr: object, default: float) -> float:
    if expr is None:
        return default
    if isinstance(expr, (int, float)):
        return float(expr)
    text = str(expr).strip()
    if not text:
        return default
    if "%" in text:
        cleaned = (
            text.replace(">=", "")
            .replace("<=", "")
            .replace(">", "")
            .replace("<", "")
            .replace("%", "")
            .strip()
        )
        return float(cleaned) / 100.0
    cleaned = (
        text.replace(">=", "")
        .replace("<=", "")
        .replace(">", "")
        .replace("<", "")
        .strip()
    )
    return float(cleaned)


def parse_bool(expr: object, default: bool = False) -> bool:
    if expr is None:
        return default
    if isinstance(expr, bool):
        return expr
    if isinstance(expr, (int, float)):
        return bool(expr)
    text = str(expr).strip().lower()
    if text in {"1", "true", "yes", "y", "on"}:
        return True
    if text in {"0", "false", "no", "n", "off"}:
        return False
    return default


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


def label_metrics(stat: Dict[str, int]) -> Dict[str, float]:
    tp = float(stat.get("tp", 0))
    fp = float(stat.get("fp", 0))
    fnp = float(stat.get("fn_proxy", 0))
    precision = 0.0 if (tp + fp) == 0 else tp / (tp + fp)
    recall = 0.0 if (tp + fnp) == 0 else tp / (tp + fnp)
    f1 = 0.0 if (precision + recall) == 0 else 2.0 * precision * recall / (precision + recall)
    return {"tp": tp, "fp": fp, "fn_proxy": fnp, "precision": precision, "recall": recall, "f1": f1}


def merge_label_count(dst: Dict[str, float], src: Dict[str, float]) -> None:
    dst["tp"] += src.get("tp", 0.0)
    dst["fp"] += src.get("fp", 0.0)
    dst["fn_proxy"] += src.get("fn_proxy", 0.0)


def load_runtime_timeseries(run_dir: Path) -> List[Dict[str, str]]:
    file = run_dir / "runtime_timeseries.csv"
    if not file.exists():
        return []
    with file.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        return [dict(r) for r in reader]


def collect_missing_required_fields(summary: dict) -> List[str]:
    missing: List[str] = []
    runtime = summary.get("runtimeMetrics", {})
    reliability = runtime.get("reliabilitySamples", {})
    if "meanRate60s" not in summary:
        missing.append("meanRate60s")
    if "processMsAvg" not in runtime:
        missing.append("runtimeMetrics.processMsAvg")
    if "scenarioId" not in runtime and "scenarioId" not in summary:
        missing.append("scenarioId")
    if "temperatureBucket" not in runtime:
        missing.append("runtimeMetrics.temperatureBucket")
    if "modelVersion" not in runtime and "modelVersion" not in summary:
        missing.append("modelVersion")
    if "releaseState" not in runtime and "releaseState" not in summary:
        missing.append("releaseState")
    if "poorRatio" not in reliability:
        missing.append("runtimeMetrics.reliabilitySamples.poorRatio")
    return missing


def build_timeseries_diag(rows: List[Dict[str, str]]) -> Dict[str, float]:
    if not rows:
        return {
            "risk_jitter_count": 0.0,
            "warmup_poor_ratio_60s": 0.0,
            "process_ms_p95": 0.0,
            "temp_slope_abs_p95": 0.0,
            "cpu_duty_proxy_avg": 0.0,
            "samples": 0,
        }
    risk_scores: List[float] = []
    process_values: List[float] = []
    cpu_duty_values: List[float] = []
    temp_slope_abs_values: List[float] = []
    timestamps: List[int] = []
    reliability: List[str] = []
    for row in rows:
        try:
            risk_scores.append(float(row.get("riskScore", "0") or 0.0))
            process_val = float(row.get("processMsAvg", "0") or 0.0)
            fps_val = float(row.get("fps", "0") or 0.0)
            temp_slope_val = float(row.get("tempSlopeCPerSec", "0") or 0.0)
            process_values.append(process_val)
            cpu_duty_values.append(process_val * fps_val)
            temp_slope_abs_values.append(abs(temp_slope_val))
            timestamps.append(int(float(row.get("timestampMs", "0") or 0)))
            reliability.append(str(row.get("measurementReliability", "")).strip().upper())
        except ValueError:
            continue
    jitter = 0
    for i in range(1, len(risk_scores)):
        if abs(risk_scores[i] - risk_scores[i - 1]) >= 0.20:
            jitter += 1
    warmup_poor_ratio = 0.0
    if timestamps:
        t0 = min(timestamps)
        warmup_flags = [rel == "POOR" for rel, t in zip(reliability, timestamps) if (t - t0) <= 60_000]
        if warmup_flags:
            warmup_poor_ratio = sum(1 for v in warmup_flags if v) / len(warmup_flags)
    return {
        "risk_jitter_count": float(jitter),
        "warmup_poor_ratio_60s": float(warmup_poor_ratio),
        "process_ms_p95": percentile(process_values, 0.95),
        "temp_slope_abs_p95": percentile(temp_slope_abs_values, 0.95),
        "cpu_duty_proxy_avg": (sum(cpu_duty_values) / len(cpu_duty_values)) if cpu_duty_values else 0.0,
        "samples": len(risk_scores),
    }


def aggregate_metric_group(details: List[dict], key: str) -> Dict[str, Dict[str, float]]:
    grouped: Dict[str, List[dict]] = {}
    for row in details:
        grouped.setdefault(str(row.get(key, "unknown")), []).append(row)
    out: Dict[str, Dict[str, float]] = {}
    for gid, rows in grouped.items():
        fp_vals = [float(r["fp_drop_pct"]) for r in rows]
        rc_vals = [float(r["recall_gain_pct"]) for r in rows]
        pf_vals = [float(r["perf_growth_pct"]) for r in rows]
        cpu_duty_vals = [float(r.get("cpu_duty_growth_pct", 0.0)) for r in rows]
        temp_slope_vals = [float(r.get("temp_slope_abs_p95", 0.0)) for r in rows]
        jitter_vals = [float(r.get("risk_jitter_count", 0.0)) for r in rows]
        warmup_vals = [float(r.get("warmup_poor_ratio_60s", 0.0)) for r in rows]
        p95_vals = [float(r.get("process_ms_p95", 0.0)) for r in rows]
        out[gid] = {
            "samples": len(rows),
            "fp_drop_pct": sum(fp_vals) / len(fp_vals) if fp_vals else 0.0,
            "recall_gain_pct": sum(rc_vals) / len(rc_vals) if rc_vals else 0.0,
            "perf_growth_pct": sum(pf_vals) / len(pf_vals) if pf_vals else 0.0,
            "cpu_duty_growth_pct": sum(cpu_duty_vals) / len(cpu_duty_vals) if cpu_duty_vals else 0.0,
            "temp_slope_abs_p95": sum(temp_slope_vals) / len(temp_slope_vals) if temp_slope_vals else 0.0,
            "risk_jitter_count_avg": sum(jitter_vals) / len(jitter_vals) if jitter_vals else 0.0,
            "warmup_poor_ratio_60s_avg": sum(warmup_vals) / len(warmup_vals) if warmup_vals else 0.0,
            "process_ms_p95_avg": sum(p95_vals) / len(p95_vals) if p95_vals else 0.0,
            "fp_drop_ci95": bootstrap_ci(fp_vals),
            "recall_gain_ci95": bootstrap_ci(rc_vals),
            "perf_growth_ci95": bootstrap_ci(pf_vals),
            "fp_drop_effect": effect_label(bootstrap_ci(fp_vals)),
            "recall_gain_effect": effect_label(bootstrap_ci(rc_vals)),
        }
    return out


def evaluate_gate(
    scenario_aggr: Dict[str, Dict[str, float]],
    targets: dict,
    label_gate: Optional[Dict[str, float]],
    label_gate_by_scenario: Dict[str, Dict[str, float]],
    has_labels: bool,
    pairing_coverage: float,
    config_failures: List[str],
    template_coverage_failures: List[str],
    label_required: bool,
) -> Tuple[bool, List[str]]:
    failures: List[str] = []
    fp_target = parse_target_percent(str(targets.get("false_positive_drop", ">=12%")))
    recall_target = parse_target_percent(str(targets.get("recall_improvement", ">=2%")))
    perf_budget = parse_budget_percent(str(targets.get("process_ms_budget", "<=+8%")))
    cpu_duty_budget = None
    if "cpu_duty_budget" in targets:
        cpu_duty_budget = parse_budget_percent(str(targets.get("cpu_duty_budget")))
    temp_slope_budget = None
    if "temp_slope_abs_p95_budget" in targets:
        temp_slope_budget = parse_numeric_expr(targets.get("temp_slope_abs_p95_budget"), "targets.temp_slope_abs_p95_budget")
    for sid, row in scenario_aggr.items():
        if row["fp_drop_pct"] < fp_target:
            failures.append(f"{sid}: fp_drop {row['fp_drop_pct']:.2f}% < {fp_target:.2f}%")
        if row["recall_gain_pct"] < recall_target:
            failures.append(f"{sid}: recall_gain {row['recall_gain_pct']:.2f}% < {recall_target:.2f}%")
        if row["perf_growth_pct"] > perf_budget:
            failures.append(f"{sid}: perf_growth +{row['perf_growth_pct']:.2f}% > +{perf_budget:.2f}%")
        if cpu_duty_budget is not None and float(row.get("cpu_duty_growth_pct", 0.0)) > cpu_duty_budget:
            failures.append(f"{sid}: cpu_duty_growth +{float(row.get('cpu_duty_growth_pct', 0.0)):.2f}% > +{cpu_duty_budget:.2f}%")
        if temp_slope_budget is not None and float(row.get("temp_slope_abs_p95", 0.0)) > temp_slope_budget:
            failures.append(f"{sid}: temp_slope_abs_p95 {float(row.get('temp_slope_abs_p95', 0.0)):.4f} > {temp_slope_budget:.4f}")
    pairing_min = parse_ratio(targets.get("pairing_coverage_min"), 0.90)
    if pairing_coverage < pairing_min:
        failures.append(f"pairing coverage {pairing_coverage:.3f} < {pairing_min:.3f}")
    failures.extend(config_failures)
    failures.extend(template_coverage_failures)
    if label_required and not has_labels:
        failures.append("labels are required by targets.label_required but labels file is missing")
    if label_gate:
        p_min = float(targets.get("label_precision_min", 0.55))
        r_min = float(targets.get("label_recall_min", 0.45))
        f1_min = float(targets.get("label_f1_min", 0.50))
        min_samples = int(targets.get("label_min_samples", 10))
        min_tp = int(targets.get("label_min_tp", 3))
        min_fp = int(targets.get("label_min_fp", 3))
        min_fn = int(targets.get("label_min_fn_proxy", 3))
        if label_gate["precision"] < p_min:
            failures.append(f"label precision {label_gate['precision']:.3f} < {p_min:.3f}")
        if label_gate["recall"] < r_min:
            failures.append(f"label recall {label_gate['recall']:.3f} < {r_min:.3f}")
        if label_gate["f1"] < f1_min:
            failures.append(f"label f1 {label_gate['f1']:.3f} < {f1_min:.3f}")
        if int(label_gate.get("samples", 0)) < min_samples:
            failures.append(f"label samples {int(label_gate.get('samples', 0))} < {min_samples}")
        if int(label_gate.get("tp", 0)) < min_tp:
            failures.append(f"label tp {int(label_gate.get('tp', 0))} < {min_tp}")
        if int(label_gate.get("fp", 0)) < min_fp:
            failures.append(f"label fp {int(label_gate.get('fp', 0))} < {min_fp}")
        if int(label_gate.get("fn_proxy", 0)) < min_fn:
            failures.append(f"label fn_proxy {int(label_gate.get('fn_proxy', 0))} < {min_fn}")
    elif has_labels:
        failures.append("labels provided but no valid labeled runs matched candidates")

    # Layered label gate: enforce by scenario when labels exist for that scenario.
    p_min = float(targets.get("label_precision_min", 0.55))
    r_min = float(targets.get("label_recall_min", 0.45))
    f1_min = float(targets.get("label_f1_min", 0.50))
    min_samples = int(targets.get("label_min_samples", 10))
    min_tp = int(targets.get("label_min_tp", 3))
    min_fp = int(targets.get("label_min_fp", 3))
    min_fn = int(targets.get("label_min_fn_proxy", 3))
    for sid, gate in label_gate_by_scenario.items():
        samples = int(gate.get("samples", 0))
        if samples <= 0:
            continue
        if samples < min_samples:
            failures.append(f"{sid}: label samples {samples} < {min_samples}")
            continue
        if int(gate.get("tp", 0)) < min_tp:
            failures.append(f"{sid}: label tp {int(gate.get('tp', 0))} < {min_tp}")
        if int(gate.get("fp", 0)) < min_fp:
            failures.append(f"{sid}: label fp {int(gate.get('fp', 0))} < {min_fp}")
        if int(gate.get("fn_proxy", 0)) < min_fn:
            failures.append(f"{sid}: label fn_proxy {int(gate.get('fn_proxy', 0))} < {min_fn}")
        if gate["precision"] < p_min:
            failures.append(f"{sid}: label precision {gate['precision']:.3f} < {p_min:.3f}")
        if gate["recall"] < r_min:
            failures.append(f"{sid}: label recall {gate['recall']:.3f} < {r_min:.3f}")
        if gate["f1"] < f1_min:
            failures.append(f"{sid}: label f1 {gate['f1']:.3f} < {f1_min:.3f}")
    return len(failures) == 0, failures


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True, help="Path to baseline summary.json")
    parser.add_argument("--candidates_dir", required=True, help="Directory with candidate summary.json files")
    parser.add_argument("--scenario_template", required=True, help="Path to scenario_template.json")
    parser.add_argument("--labels", help="Optional labels csv path")
    parser.add_argument("--strict", action="store_true", help="Enable strict checks for required fields and candidate coverage")
    parser.add_argument("--report_out", required=True, help="Output report json path")
    args = parser.parse_args()

    baseline_path = Path(args.baseline)
    candidates_dir = Path(args.candidates_dir)
    scenario_template_path = Path(args.scenario_template)
    report_out = Path(args.report_out)

    if not baseline_path.exists():
        raise SystemExit(f"baseline not found: {baseline_path}")
    if not candidates_dir.exists():
        raise SystemExit(f"candidates_dir not found: {candidates_dir}")
    if not scenario_template_path.exists():
        raise SystemExit(f"scenario_template not found: {scenario_template_path}")

    baseline_pool = discover_baselines(baseline_path)
    if not baseline_pool:
        raise SystemExit(f"no baseline summary found: {baseline_path}")
    invalid_baselines = [
        idx for idx, b in enumerate(baseline_pool)
        if collect_invalid_numeric_fields(b)
    ]
    if invalid_baselines:
        raise SystemExit(f"baseline has invalid numeric fields at indices: {invalid_baselines}")
    scenario_template = load_json(scenario_template_path)
    required_scenarios = [
        str(item.get("id", "")).strip()
        for item in scenario_template.get("scenarios", [])
        if str(item.get("id", "")).strip()
    ]
    targets = scenario_template.get("targets", {})
    strict_mode = args.strict or parse_bool(targets.get("strict_mode"), default=False)
    allow_baseline_fallback = parse_bool(targets.get("allow_baseline_fallback"), default=False)
    label_required = parse_bool(targets.get("label_required"), default=False)
    target_schema_errors = validate_targets_schema(targets, strict_mode=strict_mode)
    if target_schema_errors:
        raise SystemExit("scenario_template targets validation failed: " + "; ".join(target_schema_errors))
    if strict_mode and not required_scenarios:
        raise SystemExit("strict mode requires non-empty scenarios in scenario_template")
    scenario_min_samples = int(targets.get("scenario_min_samples", 1))
    if strict_mode and scenario_min_samples < 1:
        raise SystemExit("strict mode requires scenario_min_samples >= 1")

    candidate_paths = discover_candidates(candidates_dir)
    if not candidate_paths:
        raise SystemExit("no candidate summary.json found")

    label_map: Dict[str, Dict[str, int]] = {}
    has_labels = False
    warnings: List[str] = []
    if args.labels:
        labels_path = Path(args.labels)
        if labels_path.exists():
            label_map = load_labels(labels_path)
            has_labels = True
        else:
            message = f"labels file not found: {labels_path}"
            if strict_mode or parse_bool(targets.get("labels_must_exist"), default=False):
                raise SystemExit(message)
            warnings.append(message)

    run_id_to_paths: Dict[str, List[str]] = {}
    for candidate_path in candidate_paths:
        run_id = candidate_path.parent.name
        run_id_to_paths.setdefault(run_id, []).append(str(candidate_path))
    duplicate_run_ids = {
        run_id: paths for run_id, paths in run_id_to_paths.items() if len(paths) > 1
    }

    details: List[dict] = []
    agg_label = {"tp": 0.0, "fp": 0.0, "fn_proxy": 0.0}
    scenario_label_counts: Dict[str, Dict[str, float]] = {}
    pairing_failures: List[str] = []
    strict_failures: List[str] = []
    if duplicate_run_ids:
        for run_id, paths in sorted(duplicate_run_ids.items()):
            strict_failures.append(
                f"duplicate run_id detected: {run_id}, paths={paths}"
            )
    for candidate_path in candidate_paths:
        run_id = candidate_path.parent.name
        if run_id in duplicate_run_ids:
            continue
        raw_summary = load_json(candidate_path)
        if strict_mode:
            missing = collect_missing_required_fields(raw_summary)
            if missing:
                strict_failures.append(f"{candidate_path.parent.name}: missing required fields: {missing}")
        cand = extract(raw_summary)
        invalid_numeric = collect_invalid_numeric_fields(cand)
        if invalid_numeric:
            strict_failures.append(
                f"{candidate_path.parent.name}: invalid numeric fields: {invalid_numeric}"
            )
            continue
        baseline = choose_baseline(cand, baseline_pool, allow_fallback=allow_baseline_fallback)
        if baseline is None:
            pairing_failures.append(
                f"{candidate_path.parent.name}: no baseline match for scenario={cand['scenarioId']} tempBucket={cand['tempBucket']}"
            )
            continue
        if baseline["meanRate60s"] <= 0.0:
            strict_failures.append(f"{run_id}: baseline meanRate60s must be > 0 for pct_change")
            continue
        if baseline["processMsAvg"] <= 0.0:
            strict_failures.append(f"{run_id}: baseline processMsAvg must be > 0 for pct_change")
            continue
        if baseline["fps"] <= 0.0:
            strict_failures.append(f"{run_id}: baseline fps must be > 0 for cpu duty proxy")
            continue
        if cand["fps"] <= 0.0:
            strict_failures.append(f"{run_id}: candidate fps must be > 0 for cpu duty proxy")
            continue
        if baseline["poorRatio"] <= 0.0:
            strict_failures.append(f"{run_id}: baseline poorRatio must be > 0 for pct_change")
            continue
        fp_drop = -pct_change(cand["poorRatio"], baseline["poorRatio"])
        recall_gain = pct_change(cand["meanRate60s"], baseline["meanRate60s"])
        perf_growth = pct_change(cand["processMsAvg"], baseline["processMsAvg"])
        baseline_cpu_duty = baseline["fps"] * baseline["processMsAvg"]
        candidate_cpu_duty = cand["fps"] * cand["processMsAvg"]
        if baseline_cpu_duty <= 0.0:
            strict_failures.append(f"{run_id}: baseline cpu duty proxy must be > 0")
            continue
        cpu_duty_growth = pct_change(candidate_cpu_duty, baseline_cpu_duty)
        diag = build_timeseries_diag(load_runtime_timeseries(candidate_path.parent))
        row = {
            "run": run_id,
            "scenarioId": cand["scenarioId"],
            "tempBucket": cand["tempBucket"],
            "modelVersion": cand["modelVersion"],
            "releaseState": cand["releaseState"],
            "fp_drop_pct": fp_drop,
            "recall_gain_pct": recall_gain,
            "perf_growth_pct": perf_growth,
            "cpu_duty_growth_pct": cpu_duty_growth,
            "risk_jitter_count": diag["risk_jitter_count"],
            "warmup_poor_ratio_60s": diag["warmup_poor_ratio_60s"],
            "process_ms_p95": diag["process_ms_p95"],
            "temp_slope_abs_p95": diag["temp_slope_abs_p95"],
            "cpu_duty_proxy_avg": diag["cpu_duty_proxy_avg"],
            "timeseries_samples": diag["samples"],
        }
        if run_id in label_map:
            lm = label_metrics(label_map[run_id])
            row["labelMetrics"] = lm
            merge_label_count(agg_label, lm)
            sid = str(cand["scenarioId"])
            bucket = scenario_label_counts.setdefault(sid, {"tp": 0.0, "fp": 0.0, "fn_proxy": 0.0, "samples": 0.0})
            merge_label_count(bucket, lm)
            bucket["samples"] += 1.0
        details.append(row)

    pairing_total = len(candidate_paths)
    pairing_matched = len(details)
    pairing_coverage = (pairing_matched / pairing_total) if pairing_total > 0 else 0.0
    if strict_mode and pairing_matched == 0:
        strict_failures.append("strict mode: no matched candidate details after baseline pairing")

    scenario_aggr = aggregate_metric_group(details, "scenarioId")
    template_coverage_failures: List[str] = []
    for sid in required_scenarios:
        matched = int(scenario_aggr.get(sid, {}).get("samples", 0))
        if matched < scenario_min_samples:
            template_coverage_failures.append(
                f"scenario coverage failed: {sid} samples {matched} < {scenario_min_samples}"
            )

    temp_bucket_aggr = aggregate_metric_group(details, "tempBucket")
    fp_all = [float(x["fp_drop_pct"]) for x in details]
    rc_all = [float(x["recall_gain_pct"]) for x in details]
    pf_all = [float(x["perf_growth_pct"]) for x in details]
    jitter_all = [float(x["risk_jitter_count"]) for x in details]
    warmup_all = [float(x["warmup_poor_ratio_60s"]) for x in details]
    p95_all = [float(x["process_ms_p95"]) for x in details]
    cpu_duty_all = [float(x.get("cpu_duty_growth_pct", 0.0)) for x in details]
    temp_slope_all = [float(x.get("temp_slope_abs_p95", 0.0)) for x in details]
    overall_ci = {
        "fp_drop_ci95": bootstrap_ci(fp_all),
        "recall_gain_ci95": bootstrap_ci(rc_all),
        "perf_growth_ci95": bootstrap_ci(pf_all),
        "fp_drop_effect": effect_label(bootstrap_ci(fp_all)),
        "recall_gain_effect": effect_label(bootstrap_ci(rc_all)),
    }
    diagnostics = {
        "risk_jitter_count_avg": sum(jitter_all) / len(jitter_all) if jitter_all else 0.0,
        "warmup_poor_ratio_60s_avg": sum(warmup_all) / len(warmup_all) if warmup_all else 0.0,
        "process_ms_p95_avg": sum(p95_all) / len(p95_all) if p95_all else 0.0,
        "cpu_duty_growth_pct_avg": sum(cpu_duty_all) / len(cpu_duty_all) if cpu_duty_all else 0.0,
        "temp_slope_abs_p95_avg": sum(temp_slope_all) / len(temp_slope_all) if temp_slope_all else 0.0,
    }

    label_gate = None
    label_gate_by_scenario: Dict[str, Dict[str, float]] = {}
    if agg_label["tp"] + agg_label["fp"] > 0 or agg_label["tp"] + agg_label["fn_proxy"] > 0:
        label_gate = label_metrics(
            {"tp": int(agg_label["tp"]), "fp": int(agg_label["fp"]), "fn_proxy": int(agg_label["fn_proxy"])}
        )
        label_gate["samples"] = sum(1 for d in details if "labelMetrics" in d)
    for sid, cnt in scenario_label_counts.items():
        gate = label_metrics({"tp": int(cnt["tp"]), "fp": int(cnt["fp"]), "fn_proxy": int(cnt["fn_proxy"])})
        gate["samples"] = int(cnt.get("samples", 0.0))
        label_gate_by_scenario[sid] = gate

    # Configuration consistency checks across candidate runs.
    config_failures: List[str] = []
    expected_model = str(targets.get("expected_model_version", "")).strip()
    expected_release = str(targets.get("expected_release_state", "")).strip()
    seen_models = sorted({str(d.get("modelVersion", "")) for d in details if str(d.get("modelVersion", "")).strip()})
    seen_releases = sorted({str(d.get("releaseState", "")) for d in details if str(d.get("releaseState", "")).strip()})
    if len(seen_models) > 1:
        config_failures.append(f"candidate modelVersion inconsistent: {seen_models}")
    if len(seen_releases) > 1:
        config_failures.append(f"candidate releaseState inconsistent: {seen_releases}")
    if expected_model and any(m != expected_model for m in seen_models):
        config_failures.append(f"expected modelVersion={expected_model}, got={seen_models}")
    if expected_release and any(r != expected_release for r in seen_releases):
        config_failures.append(f"expected releaseState={expected_release}, got={seen_releases}")

    passed, failures = evaluate_gate(
        scenario_aggr,
        targets,
        label_gate,
        label_gate_by_scenario,
        has_labels=has_labels,
        pairing_coverage=pairing_coverage,
        config_failures=config_failures,
        template_coverage_failures=template_coverage_failures,
        label_required=label_required,
    )
    failures.extend(strict_failures)
    failures.extend(pairing_failures)
    passed = passed and not pairing_failures and not strict_failures

    report = {
        "baseline": str(baseline_path),
        "candidates_dir": str(candidates_dir),
        "scenario_template": str(scenario_template_path),
        "labels": args.labels or "",
        "targets": targets,
        "scenarioAggregate": scenario_aggr,
        "tempBucketAggregate": temp_bucket_aggr,
        "overall": {
            "fp_drop_pct_avg": sum(fp_all) / len(fp_all) if fp_all else 0.0,
            "recall_gain_pct_avg": sum(rc_all) / len(rc_all) if rc_all else 0.0,
            "perf_growth_pct_avg": sum(pf_all) / len(pf_all) if pf_all else 0.0,
            **overall_ci,
            **diagnostics,
        },
        "labelGate": label_gate,
        "labelGateByScenario": label_gate_by_scenario,
        "configConsistency": {
            "models": seen_models,
            "releaseStates": seen_releases,
            "failures": config_failures,
        },
        "strictMode": strict_mode,
        "strictFailures": strict_failures,
        "warnings": warnings,
        "pairingCoverage": {
            "matched": pairing_matched,
            "total": pairing_total,
            "ratio": pairing_coverage,
        },
        "scenarioTemplateCoverage": {
            "requiredScenarios": required_scenarios,
            "minSamplesPerScenario": scenario_min_samples,
            "failures": template_coverage_failures,
        },
        "pairingFailures": pairing_failures,
        "details": details,
        "gate": {
            "passed": passed,
            "failures": failures,
        },
    }

    report_out.parent.mkdir(parents=True, exist_ok=True)
    report_out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"report written: {report_out}")
    print(f"gate: {'PASS' if passed else 'FAIL'}")
    if failures:
        for failure in failures:
            print(f"- {failure}")


if __name__ == "__main__":
    main()

