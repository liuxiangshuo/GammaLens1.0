# 成品版验收记录

## 构建验收（已完成）
- `:app:compileDebugKotlin`：通过
- `:app:assembleDebug`：通过
- `:app:assembleRelease`：通过（产物为 unsigned）

## APK 产物
- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release-unsigned.apk`

## 三场景验证记录（可直接按此执行）
- 场景1：`dark_static`（遮光静止）  
  目标：误报代理下降、测量条件保持 RELIABLE
- 场景2：`dark_light_motion`（遮光轻动）  
  目标：误报可控、可靠性可降为 LIMITED 但不失稳
- 场景3：`ambient_light`（环境光）  
  目标：结果提示“仅参考”，误报代理低于基线

## 评估命令
- `python tools/evaluate_runs.py --baseline <baseline_summary.json> --candidates_dir <runs_dir> --labels tools/labeling/labels.csv`
- `python tools/eval_pipeline.py --baseline <baseline_summary.json> --candidates_dir <runs_dir> --scenario_template tools/scenario_template.json --report_out tools/reports/report.json`

## 当前成品默认版本
- `releaseState=promoted`
- `modelVersion=v8-r3-nomodel-prod`
- `variantId=balanced`

## 回滚说明
- 快速回滚：切回 `modelVersion=v7a-r0-nomodel` + 保守阈值
- 分级回滚：先关闭 `featureLiteEnabled`，保留 `cusumEnabled` 与 `hotPixelMapEnabled`

## 无新增数据复验建议
- 以 `v8-r0-nomodel-prod` 的历史 run 作为 baseline。
- 以 `v8-r3-nomodel-prod` 的运行目录作为 candidate。
- 至少对比三个场景：`dark_static`、`dark_light_motion`、`ambient_light`。
- 验收门槛：`processMsAvg` 增幅 <= 8%，误报代理下降 >= 12%（无TP场景），召回代理提升 >= 2%（有TP场景）。
