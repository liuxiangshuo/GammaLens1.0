#!/usr/bin/env python3
"""
Train a lightweight logistic model from run summaries + labels.

Example:
  python tools/train/train_logreg.py --runs_dir runs --labels tools/labeling/labels.csv --out tools/train/model_weights.json
"""

import argparse
import csv
import json
import math
from pathlib import Path
from typing import Dict, List, Tuple


FEATURE_KEYS = [
    "eventFeatureScore",
    "trackStability",
    "peakStability",
    "trajectoryLengthNorm",
    "significanceNorm",
    "suppressionPenalty",
    "sustainedFramesNorm",
    "tempCompAbs",
]


def load_labels(path: Path) -> Dict[str, Dict[str, int]]:
    out: Dict[str, Dict[str, int]] = {}
    with path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            run_id = (row.get("run_id") or "").strip()
            label = (row.get("label") or "").strip()
            if not run_id or not label:
                continue
            bucket = out.setdefault(run_id, {"tp": 0, "fp": 0, "fn_proxy": 0})
            if label in bucket:
                bucket[label] += 1
    return out


def sigmoid(x: float) -> float:
    if x >= 0:
        z = math.exp(-x)
        return 1.0 / (1.0 + z)
    z = math.exp(x)
    return z / (1.0 + z)


def norm(v: float, lo: float, hi: float) -> float:
    if hi <= lo:
        return 0.0
    return max(0.0, min(1.0, (v - lo) / (hi - lo)))


def extract_features(summary: dict) -> Dict[str, float]:
    rt = summary.get("runtimeMetrics", {})
    return {
        "eventFeatureScore": float(rt.get("eventFeatureScore", 0.0)),
        "trackStability": float(rt.get("trackStability", 0.0)),
        "peakStability": float(rt.get("peakStability", 0.0)),
        "trajectoryLengthNorm": norm(float(rt.get("trajectoryLength", 0.0)), 0.0, 400.0),
        "significanceNorm": norm(float(rt.get("significanceMean60s", 0.0)), -1.0, 6.0),
        "suppressionPenalty": float(rt.get("pairPenalty", 0.0)),
        "sustainedFramesNorm": norm(float(rt.get("sustainedFrames", 0.0)), 0.0, 30.0),
        "tempCompAbs": norm(abs(float(rt.get("tempCompensationTerm", 0.0))), 0.0, 2.0),
    }


def load_training_rows(runs_dir: Path, labels: Dict[str, Dict[str, int]]) -> List[Tuple[List[float], float]]:
    rows: List[Tuple[List[float], float]] = []
    for p in runs_dir.glob("**/summary.json"):
        run_id = p.parent.name
        if run_id not in labels:
            continue
        with p.open("r", encoding="utf-8") as f:
            summary = json.load(f)
        feats = extract_features(summary)
        x = [feats[k] for k in FEATURE_KEYS]
        stat = labels[run_id]
        pos = stat.get("tp", 0)
        neg = stat.get("fp", 0) + stat.get("fn_proxy", 0)
        if pos + neg == 0:
            continue
        y = 1.0 if pos >= neg else 0.0
        rows.append((x, y))
    return rows


def train_logreg(rows: List[Tuple[List[float], float]], lr: float, epochs: int, l2: float) -> Tuple[float, List[float]]:
    if not rows:
        return -2.0, [0.0 for _ in FEATURE_KEYS]
    w0 = 0.0
    w = [0.0 for _ in FEATURE_KEYS]
    n = float(len(rows))
    for _ in range(epochs):
        g0 = 0.0
        g = [0.0 for _ in FEATURE_KEYS]
        for x, y in rows:
            z = w0 + sum(wi * xi for wi, xi in zip(w, x))
            p = sigmoid(z)
            d = p - y
            g0 += d
            for i in range(len(w)):
                g[i] += d * x[i]
        w0 -= lr * g0 / n
        for i in range(len(w)):
            reg = l2 * w[i]
            w[i] -= lr * (g[i] / n + reg)
    return w0, w


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs_dir", required=True, help="Root directory containing run_*/summary.json")
    ap.add_argument("--labels", required=True, help="CSV labels file")
    ap.add_argument("--out", required=True, help="Output model weights json")
    ap.add_argument("--lr", type=float, default=0.2)
    ap.add_argument("--epochs", type=int, default=300)
    ap.add_argument("--l2", type=float, default=0.02)
    args = ap.parse_args()

    labels = load_labels(Path(args.labels))
    rows = load_training_rows(Path(args.runs_dir), labels)
    b, w = train_logreg(rows, args.lr, args.epochs, args.l2)
    payload = {
        "modelVersion": "v5-trained",
        "intercept": b,
        "weights": {k: wi for k, wi in zip(FEATURE_KEYS, w)},
        "samples": len(rows),
    }
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"saved: {out_path} samples={len(rows)}")


if __name__ == "__main__":
    main()
