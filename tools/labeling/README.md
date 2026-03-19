GammaLens labeling format (minimal).

Columns:
- run_id: run folder name, e.g. run_20260314_101010
- scenario_id: dark_static / dark_light_motion / ambient_light
- event_id: event key; use session for run-level labels
- label: tp / fp / fn_proxy
- confidence: [0,1]
- notes: optional free text

Usage:
1) Copy labels_template.csv and fill rows for each run.
2) Use with:
   python tools/evaluate_runs.py --baseline ... --candidates_dir ... --labels tools/labeling/labels.csv
3) Use with training:
   python tools/train/train_logreg.py --runs_dir <runs_root> --labels tools/labeling/labels.csv --out tools/train/model_weights.json

Zero-radiation bootstrap (V7A):
1) Collect only background runs first:
   - dark_static
   - dark_light_motion
   - ambient_light
2) If no TP is available, labeling only fp/fn_proxy is acceptable.
3) Evaluate in no-TP mode:
   python tools/evaluate_runs.py --baseline ... --candidates_dir ... --labels tools/labeling/labels.csv
4) In no-TP mode, ranking emphasizes poorRatio drop + stability + performance budget.
