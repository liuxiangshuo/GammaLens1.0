## 你只要放文件到这两个目录

- `runs/baseline/`：放 baseline 的 `summary.json`（可放多个）
- `runs/candidates/`：放 candidate 的 `summary.json`（按 run 子目录放）

推荐结构：

```text
runs/
  baseline/
    run_base_001/summary.json
    run_base_002/summary.json
  candidates/
    run_cand_001/summary.json
    run_cand_002/summary.json
```

可选标注文件：

- `tools/labeling/labels.csv`（已为你生成模板副本）

## 一键评估命令

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File "tools/run_eval.ps1"
```

输出报告：

- `tools/reports/report_final.json`

## 严格门禁失败条件（重点）

- `scenario_template.json` 缺失关键 targets 字段或格式非法（如百分比字段无法解析）。
- `runs/candidates` 下没有 `summary.json`，或 baseline/candidate 数值字段非法（NaN/Inf/越界）。
- 同一批候选出现重复 `run_id`（同名父目录）导致对齐冲突。
- strict 模式下场景覆盖不足、配对覆盖率不足、或没有任何有效配对样本。
- 传入 `--labels` 但文件不存在：strict 模式会直接失败；非 strict 会在报告 `warnings` 给出警告。

## 新增性能/温升代理指标

- `cpu_duty_growth_pct`：以 `fps * processMsAvg` 作为负载代理，相比 baseline 的增幅。
- `temp_slope_abs_p95`：`runtime_timeseries.csv` 中 `tempSlopeCPerSec` 绝对值的 P95。
- 可在 `tools/scenario_template.json` 中通过：
  - `targets.cpu_duty_budget`
  - `targets.temp_slope_abs_p95_budget`
  配置预算门禁。
