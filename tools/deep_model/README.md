GammaLens image deep model pipeline (white-dot photos).

## 1) Prepare unlabeled images

Place raw images under:
- tools/deep_model/data/raw/

Generate a manifest:
- python tools/deep_model/prepare_dataset.py --raw_dir tools/deep_model/data/raw --out tools/deep_model/data/labels_manifest.csv

## 2) Fill labels or radiation values

Edit `labels_manifest.csv` and fill:
- Prefer regression: fill `radiation_value` (e.g. uSv/h)
- Optional classification: fill `label` as positive / negative

## 3) Train + export TFLite

- Regression (recommended):
  - python tools/deep_model/train_cnn_tflite.py --task regression --manifest tools/deep_model/data/labels_manifest.csv --out_dir tools/deep_model/out
- Classification:
  - python tools/deep_model/train_cnn_tflite.py --task classification --manifest tools/deep_model/data/labels_manifest.csv --out_dir tools/deep_model/out

Outputs:
- tools/deep_model/out/white_dot_detector.tflite
- tools/deep_model/out/metrics.json
- tools/deep_model/out/white_dot_detector_meta.json

## 4) Deploy to app

Copy:
- white_dot_detector.tflite -> app/src/main/assets/models/white_dot_detector.tflite
- white_dot_detector_meta.json -> app/src/main/assets/models/white_dot_detector_meta.json

The app loads this model via `WhiteDotTfliteClassifier`.
