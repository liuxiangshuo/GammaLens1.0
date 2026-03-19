Training and release utilities for GammaLens V5.

Files:
- train_logreg.py: train lightweight logistic model weights from summaries + labels
- promote_variant.py: manage baseline/candidate/promoted release states
- release_manifest_template.json: template for release tracking

Typical flow:
1) Prepare labels in tools/labeling/labels.csv
2) Train:
   python tools/train/train_logreg.py --runs_dir <runs_root> --labels tools/labeling/labels.csv --out tools/train/model_weights.json
3) Evaluate:
   python tools/evaluate_runs.py --baseline <base_summary> --candidates_dir <candidates_root> --labels tools/labeling/labels.csv
4) Promote or rollback:
   python tools/train/promote_variant.py --manifest tools/train/release_manifest.json --set candidate=v5-r1 --reason "offline pass"
