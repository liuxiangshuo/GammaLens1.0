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
