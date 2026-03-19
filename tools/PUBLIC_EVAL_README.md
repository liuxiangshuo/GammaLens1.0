Public/History relative evaluation (V7A)

Purpose:
- Compare baseline vs candidate on public/history samples when radiation live collection is unavailable.
- Only relative trend is reported; no absolute dose conclusion.

Steps:
1) Fill `tools/public_samples_manifest.csv`:
   - baseline_summary: summary.json from baseline run
   - candidate_summary: summary.json from candidate run
2) Run evaluator:
   - python tools/evaluate_runs.py --baseline <baseline_summary.json> --candidates_dir <candidate_runs_dir> --public_manifest tools/public_samples_manifest.csv
3) Read section:
   - `Public/History Relative Trend`
   - `summary improved=x/y`

Notes:
- Missing summary files are skipped.
- Recommendation score still follows no-TP / regular branch based on labels availability.
