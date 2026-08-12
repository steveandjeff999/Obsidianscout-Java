@echo off
setlocal enabledelayedexpansion
echo =========================================================================
echo  [ObsidianScout] Installing GraalVM JDK 21 for Windows
echo =========================================================================
powershell -ExecutionPolicy Bypass -File "%~dp0install-graal.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] GraalVM installation failed.
    pause
    exit /b %ERRORLEVEL%
)
echo GraalVM installation completed successfully!
