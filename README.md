# GammaLens

GammaLens 是一个基于 Android 相机与统计信号处理的核辐射检测实验项目（当前默认为 **无模型路径**），重点在以下目标：

- 在“遮光测量”场景中提升稳定性与可重复性
- 通过严格评测门禁（fail-closed）控制回归风险
- 用可观测指标驱动参数迭代，而不是凭主观体感调参

---

## 1. 当前版本状态

- 默认发布状态：`promoted`
- 默认模型版本：`v8-r3-nomodel-prod`
- 配置来源：`app/src/main/java/com/gammalens/app/config/ReleaseVersionConfig.kt`

项目已包含发布前硬化能力（生命周期稳定性、评测门禁、CI、日志导出与回放）。

---

## 2. 功能概览

### 2.1 检测主路径（无模型）

- 多分支检测：`MOG2 / 帧间差分 / 融合`
- 信号门控：`CUSUM`、Poisson 一致性、风险分数迟滞触发
- 稳定性增强：热像素抑制、ROI 加权、多帧堆叠、动态 dead-time
- 可靠性判定：`RELIABLE / LIMITED / POOR`

核心实现：

- `app/src/main/java/com/gammalens/app/camera/FrameProcessor.kt`
- `app/src/main/java/com/gammalens/app/camera/FrameProcessorAlgorithms.kt`
- `app/src/main/java/com/gammalens/app/camera/DetectionConfig.kt`

### 2.2 相机与生命周期

- Camera2 双/单摄管线，支持双摄失败自动回退单摄
- 启停过程具备超时与幂等保护，降低 ANR/卡死风险
- 启动路径与处理线程已做稳定性收口

核心实现：

- `app/src/main/java/com/gammalens/app/MainActivity.kt`
- `app/src/main/java/com/gammalens/app/camera/CameraPipeline.kt`
- `app/src/main/java/com/gammalens/app/camera/FrameSynchronizer.kt`

### 2.3 运行数据导出

单次会话会导出 `params.json / events.csv / summary.json / runtime_timeseries.csv / debug.txt`，用于离线评估与参数回放。

核心实现：

- `app/src/main/java/com/gammalens/app/data/RunSnapshotManager.kt`

---

## 3. 项目结构（关键目录）

- `app/`：Android 应用主体（Kotlin + Camera2 + OpenCV）
- `opencv/`：OpenCV Android 依赖模块
- `tools/`：评测、训练、门禁与辅助脚本
- `.github/workflows/`：CI 工作流
- `README_DELIVERY.md`：交付与发布补充说明

---

## 4. 环境要求

- Android Studio（建议最新稳定版）
- JDK 17
- Android SDK（可编译目标）
- Python 3.11（用于 `tools/` 脚本）
- 可选：`adb`（真机烟测）

---

## 5. 本地构建与验证

在项目根目录执行：

```powershell
.\gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest
```

生成 APK：

- `app/build/outputs/apk/debug/app-debug.apk`

---

## 6. 评测门禁（严格模式）

### 6.1 一键执行

```powershell
powershell -ExecutionPolicy Bypass -File "tools/run_eval.ps1"
```

输出报告：

- `tools/reports/report_final.json`

### 6.2 输入目录约定

```text
runs/
  baseline/
    run_base_xxx/summary.json
  candidates/
    run_cand_xxx/summary.json
```

说明：

- `runs/` 在 `.gitignore` 中默认忽略，仅用于本地/CI评测输入
- 可选标签文件：`tools/labeling/labels.csv`（仅显式传入时启用）

### 6.3 当前门禁指标（来自 `tools/scenario_template.json`）

- `false_positive_drop >= 12%`
- `recall_improvement >= 2%`
- `process_ms_budget <= +8%`
- `cpu_duty_budget <= +12%`（`fps * processMsAvg` 代理）
- `temp_slope_abs_p95_budget <= 0.08`
- `pairing_coverage_min = 1.0`
- `strict_mode = true`

---

## 7. 自动调参与参数管理

参数搜索脚本：

- `tools/train/search_nomodel_params.py`

能力：

- 小维参数搜索（风险融合 + Poisson/dead-time + ROI/stack）
- 输出可回放候选集（`generatedCandidates`）
- 输出搜索空间与样本来源 run_id

运行示例：

```powershell
python tools/train/search_nomodel_params.py --runs_dir runs --out tools/reports/auto_tune_profiles.json
```

---

## 8. CI（GitHub Actions）

工作流文件：

- `.github/workflows/android-ci.yml`

CI 默认执行：

- `assembleDebug`
- `:app:lintDebug`
- `testDebugUnitTest`
- 严格评测门禁（要求 `runs/baseline` 与 `runs/candidates` 输入存在）

---

## 9. 发布建议流程

1. 本地通过构建、lint、单测
2. 准备 baseline/candidate 输入并跑严格门禁
3. 真机烟测（启动/切后台/恢复）
4. 生成 APK 后做完整性校验（见 `README_DELIVERY.md`）
5. 提交并推送

---

## 10. 仓库规范与注意事项

- 已忽略本地运行产物与评测输入：`runs/`、`tools/reports/`
- 已忽略本地日志临时文件（如 `smoke_log.txt` 等）
- 大文件建议使用 Git LFS（仓库中已有 `.gitattributes` 规则）

---

## 11. 已知边界

- 当前默认无深度模型推理路径（`deepModelEnabled=false`）
- 环境光场景结果仅供参考；精准测量建议遮光并保持设备稳定
- 评测可信度依赖输入样本质量与场景覆盖

---

## 12. 相关文档

- 交付说明：`README_DELIVERY.md`
- 评测输入说明：`tools/EVAL_INPUT_GUIDE.md`
- 训练工具说明：`tools/train/README.md`

