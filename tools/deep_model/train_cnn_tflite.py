#!/usr/bin/env python3
"""
Train a small CNN and export TFLite.
Requires: tensorflow, pillow, numpy
"""

import argparse
import csv
import json
from pathlib import Path
from typing import List, Tuple, Dict

import numpy as np
from PIL import Image
import tensorflow as tf


def load_manifest_classification(path: Path) -> List[Tuple[Path, int]]:
    rows: List[Tuple[Path, int]] = []
    with path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            label = (row.get("label") or "").strip().lower()
            if label not in {"positive", "negative"}:
                continue
            p = Path((row.get("image_path") or "").strip())
            if not p.exists():
                continue
            y = 1 if label == "positive" else 0
            rows.append((p, y))
    return rows


def load_manifest_regression(path: Path) -> List[Tuple[Path, float]]:
    rows: List[Tuple[Path, float]] = []
    with path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            raw = (row.get("radiation_value") or "").strip()
            if not raw:
                continue
            p = Path((row.get("image_path") or "").strip())
            if not p.exists():
                continue
            try:
                y = float(raw)
            except ValueError:
                continue
            rows.append((p, y))
    return rows


def load_image(path: Path, size: int) -> np.ndarray:
    img = Image.open(path).convert("L").resize((size, size))
    arr = np.asarray(img, dtype=np.float32) / 255.0
    return arr[..., None]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--out_dir", required=True)
    ap.add_argument("--size", type=int, default=64)
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--batch_size", type=int, default=32)
    ap.add_argument("--task", choices=["classification", "regression"], default="regression")
    args = ap.parse_args()

    task = args.task
    rows = load_manifest_regression(Path(args.manifest)) if task == "regression" else load_manifest_classification(Path(args.manifest))
    if len(rows) < 20:
        raise SystemExit("样本太少，至少需要20张已标注图片。")

    x = np.stack([load_image(p, args.size) for p, _ in rows], axis=0).astype(np.float32)
    y_raw = np.asarray([v for _, v in rows], dtype=np.float32)

    meta: Dict[str, float] = {}
    if task == "regression":
        y_min = float(np.min(y_raw))
        y_max = float(np.max(y_raw))
        if y_max <= y_min:
            raise SystemExit("radiation_value 变化范围过小，无法回归训练。")
        y = (y_raw - y_min) / (y_max - y_min)
        meta = {"target_min": y_min, "target_max": y_max}
    else:
        y = y_raw

    # simple split
    idx = np.arange(len(rows))
    np.random.shuffle(idx)
    split = int(len(idx) * 0.8)
    tr, va = idx[:split], idx[split:]
    x_tr, y_tr = x[tr], y[tr]
    x_va, y_va = x[va], y[va]

    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(args.size, args.size, 1)),
            tf.keras.layers.Conv2D(16, 3, activation="relu"),
            tf.keras.layers.MaxPool2D(),
            tf.keras.layers.Conv2D(32, 3, activation="relu"),
            tf.keras.layers.MaxPool2D(),
            tf.keras.layers.Flatten(),
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dense(1, activation="sigmoid"),
        ]
    )
    if task == "regression":
        model.compile(optimizer="adam", loss="mse", metrics=["mae"])
    else:
        model.compile(optimizer="adam", loss="binary_crossentropy", metrics=["accuracy", tf.keras.metrics.AUC(name="auc")])
    hist = model.fit(
        x_tr,
        y_tr,
        validation_data=(x_va, y_va),
        epochs=args.epochs,
        batch_size=args.batch_size,
        verbose=2,
    )

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    keras_path = out_dir / "white_dot_detector.keras"
    model.save(keras_path)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    tflite_path = out_dir / "white_dot_detector.tflite"
    tflite_path.write_bytes(tflite_model)

    metrics = {
        "task": task,
        "samples": len(rows),
        "train_samples": int(len(tr)),
        "val_samples": int(len(va)),
    }
    if task == "regression":
        metrics["last_train_mae_norm"] = float(hist.history["mae"][-1])
        metrics["last_val_mae_norm"] = float(hist.history["val_mae"][-1])
        metrics["target_min"] = meta["target_min"]
        metrics["target_max"] = meta["target_max"]
    else:
        metrics["last_train_acc"] = float(hist.history["accuracy"][-1])
        metrics["last_val_acc"] = float(hist.history["val_accuracy"][-1])
        metrics["last_val_auc"] = float(hist.history["val_auc"][-1])
    (out_dir / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    (out_dir / "white_dot_detector_meta.json").write_text(
        json.dumps(
            {
                "task": task,
                "input_size": args.size,
                "target_min": meta.get("target_min"),
                "target_max": meta.get("target_max"),
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"saved tflite: {tflite_path}")


if __name__ == "__main__":
    main()
