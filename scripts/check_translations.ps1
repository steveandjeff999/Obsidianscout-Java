<#
.SYNOPSIS
    Runs the ObsidianScout Translation Auditor & Sync Utility.
.DESCRIPTION
    Scans i18n JSON files (en.json, es.json, he.json, tr.json) and frontend/backend code
    for missing, untranslated, empty, or mismatched translations.
.EXAMPLE
    .\scripts\check_translations.ps1
    .\scripts\check_translations.ps1 --log translation_report.md
    .\scripts\check_translations.ps1 --export-missing ./scratch/i18n_patches
    .\scripts\check_translations.ps1 --sync
#>

param(
    [string]$Log = "",
    [string]$ExportMissing = "",
    [switch]$Sync,
    [switch]$Clean,
    [switch]$Sort,
    [switch]$Strict,
    [string]$ApplyPatch = "",
    [string]$Lang = ""
)

$scriptPath = Join-Path $PSScriptRoot "check_translations.py"

$pythonCmd = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCmd) {
    $pythonCmd = Get-Command python3 -ErrorAction SilentlyContinue
}

if (-not $pythonCmd) {
    Write-Error "Python 3 is required to run the translation check script. Please install Python or ensure it is in your PATH."
    exit 1
}

$argsList = @()
if ($Log) { $argsList += "--log", $Log }
if ($ExportMissing) { $argsList += "--export-missing", $ExportMissing }
if ($Sync) { $argsList += "--sync" }
if ($Clean) { $argsList += "--clean" }
if ($Sort) { $argsList += "--sort" }
if ($Strict) { $argsList += "--strict" }
if ($ApplyPatch) { $argsList += "--apply-patch", $ApplyPatch }
if ($Lang) { $argsList += "--lang", $Lang }

& $pythonCmd.Source $scriptPath $argsList
exit $LASTEXITCODE
