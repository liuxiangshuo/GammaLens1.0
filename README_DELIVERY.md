# GammaLens 成品交付说明

## 交付内容
- 源码：当前项目目录
- APK：`app/build/outputs/apk/debug/app-debug.apk`

## 安装步骤（Debug APK）
1. 在手机开启“允许安装未知来源应用”。
2. 通过 USB 或聊天工具把 `app-debug.apk` 发送到手机。
3. 点击安装并授予相机权限。

## 使用建议
- 默认模式为“遮光测量”（平衡策略）。
- 测量时请尽量遮住摄像头并保持手机静止。
- 环境巡检模式结果仅供参考，不建议用于精准判断。

## 当前默认版本
- `releaseState=promoted`
- `modelVersion=v8-r3-nomodel-prod`

## 回滚方式
如需快速回退到更保守策略，可在代码中调整：
- `MainActivity` 中 `currentModelVersion`
- `DetectionConfig` 中阈值（例如 `cusumThresholdH`、`pulseDensityMin`）

也可通过已有发布状态机制切换：
- `baseline/candidate/promoted`

## 已知限制
- 当前为无模型路径，不依赖后端与深度模型。
- 若需进一步提升召回，建议后续结合更多样本进行离线调参。

## 无新增数据验证流程
1. 导出最近运行目录（`runs/run_*`）中的 `summary.json`。
2. 选择历史基线 `summary.json` 作为 baseline。
3. 执行：
   - `python tools/evaluate_runs.py --baseline <baseline_summary.json> --candidates_dir <runs_dir> --labels tools/labeling/labels.csv`
   - 或一键评估：`python tools/eval_pipeline.py --baseline <baseline_summary.json> --candidates_dir <runs_dir> --scenario_template tools/scenario_template.json --report_out tools/reports/report.json`
4. 重点关注：
   - `processMsAvg` 增幅是否 <= 8%
   - 误报代理是否下降 >= 12%（无TP场景）
   - 召回代理是否提升 >= 2%（有TP标注场景）
