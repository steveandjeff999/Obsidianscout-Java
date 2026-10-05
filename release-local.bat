@echo off
rem Builds every release asset locally (same as .github/workflows/release.yml) and
rem copies them to Downloads\obsidianscout-vX. See scripts\release-local.ps1 for options.
where pwsh >nul 2>&1
if %ERRORLEVEL%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release-local.ps1" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release-local.ps1" %*
)
exit /b %ERRORLEVEL%
