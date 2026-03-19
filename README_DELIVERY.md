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

默认运行版本由单一配置源维护：
- `app/src/main/java/com/gammalens/app/config/ReleaseVersionConfig.kt`
- `tools/train/release_manifest.json` 中 `runtime_default`

## 回滚方式
如需快速回退到更保守策略，可在代码中调整：
- `ReleaseVersionConfig` 中默认 `releaseState/modelVersion`
- `DetectionConfig` 中阈值（例如 `cusumThresholdH`、`pulseDensityMin`）

也可通过已有发布状态机制切换：
- `baseline/candidate/promoted`

## 已知限制
- 当前为无模型路径，不依赖后端与深度模型。
- 若需进一步提升召回，建议后续结合更多样本进行离线调参。

## CI 与可复现构建
- 已提供 GitHub Actions：`.github/workflows/android-ci.yml`
- 覆盖检查：`assembleDebug`、`:app:lintDebug`、`testDebugUnitTest`，以及 `eval_pipeline` gate（缺输入直接失败）
- Gradle Wrapper 已固定到官方源并配置 `distributionSha256Sum`

## 仓库体积治理
- 仓库已增加 `.gitattributes`，对 `*.a` / `*.so` / `*.aar` / `*.apk` / `*.aab` 及 OpenCV 三方库路径启用 LFS 规则
- 首次克隆前建议执行 `git lfs install`
- 对于大二进制文件，优先使用 LFS 或依赖化分发，避免仓库持续膨胀

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

## 评测门禁失败判定（fail-closed）
- `run_eval.ps1` 默认以 `--strict` 执行，`eval_pipeline.py` 非 0 退出码将直接失败。
- 以下情况会直接判为失败：模板场景覆盖不足、基线配对覆盖不足、关键字段缺失、关键数值非法（NaN/Inf/越界）等。
- 建议在发布前保留 `tools/reports/report_final.json` 作为审计依据。

## APK 完整性校验
- 计算 SHA-256：
  - `Get-FileHash app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256`
- 安装前比对 SHA-256 与发布记录，避免传输链路篡改。
- 可选签名信息检查：
  - `jarsigner -verify -verbose -certs app/build/outputs/apk/debug/app-debug.apk`
