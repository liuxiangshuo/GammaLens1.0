param(
    [string]$Baseline = "runs/baseline",
    [string]$Candidates = "runs/candidates",
    [string]$Labels = "tools/labeling/labels.csv",
    [string]$ScenarioTemplate = "tools/scenario_template.json",
    [string]$ReportOut = "tools/reports/report_final.json"
)

$ErrorActionPreference = "Stop"

if (!(Test-Path $ScenarioTemplate)) {
    throw "scenario_template not found: $ScenarioTemplate"
}
if (!(Test-Path $Candidates)) {
    throw "candidates dir not found: $Candidates"
}
if (!(Test-Path $Baseline)) {
    throw "baseline path not found: $Baseline"
}

$candidateCount = (Get-ChildItem -Path $Candidates -Recurse -Filter "summary.json" | Measure-Object).Count
if ($candidateCount -le 0) {
    throw "no summary.json under candidates: $Candidates"
}

$pipelineArgs = @(
    "tools/eval_pipeline.py",
    "--baseline", $Baseline,
    "--candidates_dir", $Candidates,
    "--scenario_template", $ScenarioTemplate,
    "--strict",
    "--report_out", $ReportOut
)

if (Test-Path $Labels) {
    $pipelineArgs += @("--labels", $Labels)
}

Write-Host "Running eval pipeline..."
Write-Host "python $($pipelineArgs -join ' ')"
python @pipelineArgs
if ($LASTEXITCODE -ne 0) {
    throw "eval_pipeline failed with exit code $LASTEXITCODE"
}

Write-Host ""
Write-Host "Done. Report: $ReportOut"
