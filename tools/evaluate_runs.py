#!/usr/bin/env python3
"""
GammaLens offline evaluator (single or batch mode).

Examples:
  python tools/evaluate_runs.py --baseline runs/base/summary.json --candidate runs/c1/summary.json
  python tools/evaluate_runs.py --baseline runs/base/summary.json --candidates_dir runs/candidates
  python tools/evaluate_runs.py --baseline runs/base/summary.json --candidates_dir runs/candidates --labels tools/labeling/labels.csv
"""

import argparse
import csv
import json
import random
from pathlib import Path
from collections import defaultdict
from typing import Dict, List, Tuple


def load_summary(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def detect_data_health(label_map: Dict[str, Dict[str, int]]) -> Dict[str, int]:
    stats = {"runs": 0, "tp_runs": 0, "fp_runs": 0, "fn_runs": 0}
    for run_id, row in label_map.items():
        _ = run_id
        stats["runs"] += 1
        if row.get("tp", 0) > 0:
            stats["tp_runs"] += 1
        if row.get("fp", 0) > 0:
            stats["fp_runs"] += 1
        if row.get("fn_proxy", 0) > 0:
            stats["fn_runs"] += 1
    return stats


def extract_metrics(summary: dict) -> dict:
    runtime = summary.get("runtimeMetrics", {})
    reliability = runtime.get("reliabilitySamples", {})
    recall_proxy = float(summary.get("meanRate60s", 0.0))
    fp_proxy = float(reliability.get("poorRatio", 1.0))
    precision_proxy = 1.0 - max(0.0, min(1.0, fp_proxy))
    f1_proxy = 0.0 if (precision_proxy + recall_proxy) == 0 else 2.0 * precision_proxy * recall_proxy / (precision_proxy + recall_proxy)
    return {
        "totalEvents": summary.get("totalEvents", 0),
        "meanRate60s": recall_proxy,
        "processMsAvg": runtime.get("processMsAvg", 0.0),
        "fps": runtime.get("fps", 0.0),
        "significanceZ": runtime.get("significanceZ", 0.0),
        "significanceMean60s": runtime.get("significanceMean60s", 0.0),
        "classifierProbability": runtime.get("classifierProbability", 0.0),
        "eventFeatureScore": runtime.get("eventFeatureScore", 0.0),
        "poorRatio": fp_proxy,
        "f1Proxy": f1_proxy,
        "cusumScore": runtime.get("cusumScore", 0.0),
        "cusumPass": 1.0 if runtime.get("cusumPass", False) else 0.0,
        "hotPixelMapSize": runtime.get("hotPixelMapSize", 0.0),
        "hotPixelMapHitCount": runtime.get("hotPixelMapHitCount", 0.0),
        "scenarioId": runtime.get("scenarioId", summary.get("scenarioId", "unknown")),
        "experimentId": runtime.get("experimentId", summary.get("experimentId", "default")),
        "variantId": runtime.get("variantId", summary.get("variantId", "default")),
        "releaseState": runtime.get("releaseState", summary.get("releaseState", "baseline")),
        "modelVersion": runtime.get("modelVersion", summary.get("modelVersion", "v5-r0")),
    }


def pct_change(new: float, old: float) -> float:
    if old == 0:
        return 0.0
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
    return {
        "mean": sum(values) / n,
        "low": percentile(means, 0.025),
        "high": percentile(means, 0.975),
    }


def effect_label(ci: Dict[str, float]) -> str:
    if ci["low"] > 0:
        return "significant_improve"
    if ci["high"] < 0:
        return "significant_regress"
    return "no_significant_change"


def score_candidate(base: Dict[str, float], cand: Dict[str, float]) -> Tuple[float, List[str]]:
    risks: List[str] = []
    fp_drop = -pct_change(cand["poorRatio"], base["poorRatio"])
    recall_gain = pct_change(cand["meanRate60s"], base["meanRate60s"])
    perf_growth = pct_change(cand["processMsAvg"], base["processMsAvg"])
    if fp_drop < 10.0:
        risks.append(f"误报下降不足(当前{fp_drop:.2f}%)")
    if recall_gain < 2.0:
        risks.append(f"召回代理提升不足(当前{recall_gain:.2f}%)")
    if perf_growth > 8.0:
        risks.append(f"性能超预算(当前+{perf_growth:.2f}%)")
    if cand["classifierProbability"] < 0.5:
        risks.append("分类器概率均值偏低")
    # Weighted objective: high recall/F1, low poorRatio/perf growth.
    score = (
        2.2 * fp_drop +
        1.6 * recall_gain +
        1.2 * pct_change(cand["f1Proxy"], base["f1Proxy"]) -
        1.0 * max(0.0, perf_growth - 5.0)
    )
    return score, risks


def score_candidate_no_tp(base: Dict[str, float], cand: Dict[str, float]) -> Tuple[float, List[str]]:
    """Fallback scoring when labels have no TP samples."""
    risks: List[str] = []
    fp_drop = -pct_change(cand["poorRatio"], base["poorRatio"])
    perf_growth = pct_change(cand["processMsAvg"], base["processMsAvg"])
    stability_gain = pct_change(cand["cusumPass"], base["cusumPass"]) if base["cusumPass"] > 0 else cand["cusumPass"] * 100.0
    if fp_drop < 12.0:
        risks.append(f"背景误报下降不足(当前{fp_drop:.2f}%)")
    if perf_growth > 8.0:
        risks.append(f"性能超预算(当前+{perf_growth:.2f}%)")
    score = (
        2.6 * fp_drop +
        1.1 * stability_gain -
        1.2 * max(0.0, perf_growth - 4.0)
    )
    return score, risks


def aggregate_by_scenario(rows: List[Tuple[str, Dict[str, float], Dict[str, float]]]) -> Dict[str, Dict[str, float]]:
    grouped: Dict[str, List[Tuple[Dict[str, float], Dict[str, float]]]] = defaultdict(list)
    for _, base, cand in rows:
        sid = str(cand.get("scenarioId", "unknown"))
        grouped[sid].append((base, cand))
    out: Dict[str, Dict[str, float]] = {}
    for sid, pairs in grouped.items():
        n = max(1, len(pairs))
        fp_drop = sum(-pct_change(c["poorRatio"], b["poorRatio"]) for b, c in pairs) / n
        recall_gain = sum(pct_change(c["meanRate60s"], b["meanRate60s"]) for b, c in pairs) / n
        perf_growth = sum(pct_change(c["processMsAvg"], b["processMsAvg"]) for b, c in pairs) / n
        fp_ci = bootstrap_ci([-pct_change(c["poorRatio"], b["poorRatio"]) for b, c in pairs])
        recall_ci = bootstrap_ci([pct_change(c["meanRate60s"], b["meanRate60s"]) for b, c in pairs])
        perf_ci = bootstrap_ci([pct_change(c["processMsAvg"], b["processMsAvg"]) for b, c in pairs])
        out[sid] = {
            "fp_drop": fp_drop,
            "recall_gain": recall_gain,
            "perf_growth": perf_growth,
            "fp_drop_ci95": fp_ci,
            "recall_gain_ci95": recall_ci,
            "perf_growth_ci95": perf_ci,
            "fp_drop_effect": effect_label(fp_ci),
            "recall_gain_effect": effect_label(recall_ci),
        }
    return out


def load_labels(path: Path) -> Dict[str, Dict[str, int]]:
    label_map: Dict[str, Dict[str, int]] = defaultdict(lambda: {"tp": 0, "fp": 0, "fn_proxy": 0})
    with path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            run_id = (row.get("run_id") or "").strip()
            label = (row.get("label") or "").strip()
            if not run_id or label not in {"tp", "fp", "fn_proxy"}:
                continue
            label_map[run_id][label] += 1
    return dict(label_map)


def label_metrics(label_map: Dict[str, Dict[str, int]], run_id: str) -> Dict[str, float]:
    stat = label_map.get(run_id, {"tp": 0, "fp": 0, "fn_proxy": 0})
    tp = float(stat.get("tp", 0))
    fp = float(stat.get("fp", 0))
    fnp = float(stat.get("fn_proxy", 0))
    precision = 0.0 if (tp + fp) == 0 else tp / (tp + fp)
    recall = 0.0 if (tp + fnp) == 0 else tp / (tp + fnp)
    f1 = 0.0 if (precision + recall) == 0 else 2.0 * precision * recall / (precision + recall)
    return {
        "tp": tp,
        "fp": fp,
        "fn_proxy": fnp,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def discover_candidates(candidates_dir: Path) -> List[Path]:
    summary_files = sorted(candidates_dir.glob("**/summary.json"))
    return [p for p in summary_files if p.is_file()]


def compare_pair(base_path: Path, cand_path: Path) -> Tuple[Dict[str, float], Dict[str, float]]:
    baseline = extract_metrics(load_summary(base_path))
    candidate = extract_metrics(load_summary(cand_path))
    print(f"\n=== Compare: {cand_path} ===")
    for key in sorted(candidate.keys()):
        if key in {"scenarioId", "experimentId", "variantId", "releaseState", "modelVersion"}:
            print(f"{key:20s} baseline={baseline[key]} candidate={candidate[key]}")
            continue
        old = float(baseline[key])
        new = float(candidate[key])
        delta = pct_change(new, old)
        print(f"{key:20s} baseline={old:10.4f} candidate={new:10.4f} delta={delta:8.2f}%")
    return baseline, candidate


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True, help="Path to baseline summary.json")
    parser.add_argument("--candidate", help="Path to candidate summary.json")
    parser.add_argument("--candidates_dir", help="Directory containing many run folders/summary.json")
    parser.add_argument("--labels", help="Optional labels csv path")
    parser.add_argument("--public_manifest", help="Optional public/history sample manifest csv")
    parser.add_argument("--report_out", help="Optional json report output path")
    args = parser.parse_args()
    if not args.candidate and not args.candidates_dir:
        raise SystemExit("需要提供 --candidate 或 --candidates_dir")

    baseline_path = Path(args.baseline)
    baseline = extract_metrics(load_summary(baseline_path))

    ranking: List[Tuple[str, float, List[str]]] = []
    compared_rows: List[Tuple[str, Dict[str, float], Dict[str, float]]] = []
    label_map: Dict[str, Dict[str, int]] = {}
    no_tp_mode = False
    if args.labels:
        label_map = load_labels(Path(args.labels))
        health = detect_data_health(label_map)
        print("\n=== Label Data Health ===")
        print(
            f"runs={health['runs']} tp_runs={health['tp_runs']} "
            f"fp_runs={health['fp_runs']} fn_proxy_runs={health['fn_runs']}"
        )
        if health["runs"] < 10:
            print("WARN: 标注run数量偏少（<10），排名稳定性有限。")
        no_tp_mode = health["tp_runs"] == 0
        if no_tp_mode:
            print("INFO: 检测到无TP标注，将启用无TP评分分支（偏重误报与稳定性）。")
    if args.candidate:
        candidate_path = Path(args.candidate)
        _, candidate = compare_pair(baseline_path, candidate_path)
        score, risks = score_candidate_no_tp(baseline, candidate) if no_tp_mode else score_candidate(baseline, candidate)
        ranking.append((candidate_path.parent.name or candidate_path.stem, score, risks))
        compared_rows.append((candidate_path.parent.name or candidate_path.stem, baseline, candidate))
    if args.candidates_dir:
        for summary_path in discover_candidates(Path(args.candidates_dir)):
            _, candidate = compare_pair(baseline_path, summary_path)
            score, risks = score_candidate_no_tp(baseline, candidate) if no_tp_mode else score_candidate(baseline, candidate)
            ranking.append((summary_path.parent.name, score, risks))
            compared_rows.append((summary_path.parent.name, baseline, candidate))

    print("\nAcceptance Targets (V7A):")
    print("- false positive / poorRatio: lower is better, target drop >=12% (background first)")
    if no_tp_mode:
        print("- recall proxy / meanRate60s: 无TP模式仅做趋势参考，不作为硬门槛")
    else:
        print("- recall proxy / meanRate60s: target gain >=2%")
    print("- performance / processMsAvg: target increase <= +8%")
    print("- v7a note: zero-radiation collection, focus on FP and stability")

    if not ranking:
        return
    ranking.sort(key=lambda x: x[1], reverse=True)
    scenario_aggr = aggregate_by_scenario(compared_rows)
    report_payload = {"ranking": [], "scenarioAggregate": scenario_aggr, "labelGate": None}
    if scenario_aggr:
        print("\n=== Scenario Aggregate ===")
        for sid in sorted(scenario_aggr.keys()):
            row = scenario_aggr[sid]
            print(
                f"{sid:20s} fp_drop={row['fp_drop']:7.2f}% "
                f"recall_gain={row['recall_gain']:7.2f}% perf_growth={row['perf_growth']:7.2f}%"
            )
            print(
                f"{'':20s} fp_ci=[{row['fp_drop_ci95']['low']:.2f}, {row['fp_drop_ci95']['high']:.2f}] "
                f"recall_ci=[{row['recall_gain_ci95']['low']:.2f}, {row['recall_gain_ci95']['high']:.2f}] "
                f"effect={row['fp_drop_effect']}/{row['recall_gain_effect']}"
            )
    print("\n=== Recommendation ===")
    best_name, best_score, best_risks = ranking[0]
    report_payload["ranking"] = [
        {"name": name, "score": score, "risks": risks}
        for name, score, risks in ranking
    ]
    print(f"推荐参数组: {best_name} (score={best_score:.2f})")
    if best_risks:
        print("风险提示:")
        for risk in best_risks:
            print(f"- {risk}")
    else:
        print("风险提示: 无明显风险，满足当前门槛。")

    if label_map:
        print("\n=== Label Metrics ===")
        seen = set()
        align_errors: List[float] = []
        agg_tp = 0.0
        agg_fp = 0.0
        agg_fn = 0.0
        for candidate_name, _, _ in compared_rows:
            run_id = candidate_name
            if run_id in seen:
                continue
            seen.add(run_id)
            lm = label_metrics(label_map, run_id)
            agg_tp += lm["tp"]
            agg_fp += lm["fp"]
            agg_fn += lm["fn_proxy"]
            proxy = None
            for name, _, cand in compared_rows:
                if name == run_id:
                    proxy = float(cand.get("f1Proxy", 0.0))
                    break
            if proxy is not None:
                align_errors.append(abs(proxy - lm["f1"]))
            print(
                f"{run_id:20s} tp={lm['tp']:4.0f} fp={lm['fp']:4.0f} fn_proxy={lm['fn_proxy']:4.0f} "
                f"precision={lm['precision']:.3f} recall={lm['recall']:.3f} f1={lm['f1']:.3f}"
            )
        if align_errors:
            mean_abs_err = sum(align_errors) / len(align_errors)
            print(f"proxy_alignment mean_abs_error(f1Proxy,label_f1)={mean_abs_err:.4f}")
        aggregate_label = label_metrics({"all": {"tp": int(agg_tp), "fp": int(agg_fp), "fn_proxy": int(agg_fn)}}, "all")
        report_payload["labelGate"] = aggregate_label
        print(
            "label_gate "
            f"precision={aggregate_label['precision']:.3f} "
            f"recall={aggregate_label['recall']:.3f} "
            f"f1={aggregate_label['f1']:.3f}"
        )

    if args.public_manifest:
        manifest = Path(args.public_manifest)
        if not manifest.exists():
            print(f"\nWARN: public manifest not found: {manifest}")
            return
        print("\n=== Public/History Relative Trend ===")
        improved = 0
        total = 0
        with manifest.open("r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                base_path = Path((row.get("baseline_summary") or "").strip())
                cand_path = Path((row.get("candidate_summary") or "").strip())
                sample_id = (row.get("sample_id") or "unknown").strip()
                if not base_path.exists() or not cand_path.exists():
                    print(f"{sample_id:20s} skip(missing_summary)")
                    continue
                b = extract_metrics(load_summary(base_path))
                c = extract_metrics(load_summary(cand_path))
                fp_drop = -pct_change(c["poorRatio"], b["poorRatio"])
                stable_gain = pct_change(c["cusumPass"], b["cusumPass"]) if b["cusumPass"] > 0 else c["cusumPass"] * 100.0
                verdict = "better" if (fp_drop >= 0 and stable_gain >= 0) else "worse"
                if verdict == "better":
                    improved += 1
                total += 1
                print(
                    f"{sample_id:20s} fp_drop={fp_drop:7.2f}% "
                    f"cusum_pass_gain={stable_gain:7.2f}% trend={verdict}"
                )
        if total > 0:
            print(f"summary improved={improved}/{total} ({(improved/total)*100.0:.1f}%)")
            print("note: 该结果仅用于相对趋势，不代表绝对剂量结论。")

    if args.report_out:
        out = Path(args.report_out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report_payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nreport written: {out}")


if __name__ == "__main__":
    main()

