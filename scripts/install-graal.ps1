# Automated GraalVM JDK 21 Installer for Windows
$ErrorActionPreference = "Stop"

Write-Host "=========================================================================" -ForegroundColor Cyan
Write-Host " [ObsidianScout] Installing GraalVM JDK 21 (Native Image Support) " -ForegroundColor Cyan
Write-Host "=========================================================================" -ForegroundColor Cyan

$TargetDir = Join-Path $env:USERPROFILE ".graalvm\graalvm-jdk-21"
$ZipPath = Join-Path $env:TEMP "graalvm-jdk-21_windows-x64_bin.zip"
$Url = "https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_windows-x64_bin.zip"

if (Test-Path "$TargetDir\bin\native-image.cmd") {
    Write-Host "[GraalVM] GraalVM JDK 21 already installed at $TargetDir" -ForegroundColor Green
} else {
    Write-Host "[1/3] Downloading GraalVM JDK 21 from $Url..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri $Url -OutFile $ZipPath

    Write-Host "[2/3] Extracting GraalVM JDK 21 to $TargetDir..." -ForegroundColor Yellow
    $TempExtract = Join-Path $env:TEMP "graalvm_extract"
    if (Test-Path $TempExtract) { Remove-Item -Recurse -Force $TempExtract }
    Expand-Archive -Path $ZipPath -DestinationPath $TempExtract -Force

    $ExtractedSubdir = Get-ChildItem -Path $TempExtract | Select-Object -First 1
    if (-not (Test-Path "$env:USERPROFILE\.graalvm")) {
        New-Item -ItemType Directory -Path "$env:USERPROFILE\.graalvm" | Out-Null
    }
    if (Test-Path $TargetDir) { Remove-Item -Recurse -Force $TargetDir }
    Move-Item -Path $ExtractedSubdir.FullName -Destination $TargetDir -Force

    Remove-Item -Force $ZipPath
    Remove-Item -Recurse -Force $TempExtract
    Write-Host "[GraalVM] Extracted successfully!" -ForegroundColor Green
}

Write-Host "[3/3] Setting GRAALVM_HOME and PATH environment variables..." -ForegroundColor Yellow
[Environment]::SetEnvironmentVariable("GRAALVM_HOME", $TargetDir, "User")
$env:GRAALVM_HOME = $TargetDir

$UserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($UserPath -notlike "*$TargetDir\bin*") {
    [Environment]::SetEnvironmentVariable("PATH", "$TargetDir\bin;$UserPath", "User")
}
$env:PATH = "$TargetDir\bin;$env:PATH"

Write-Host "=========================================================================" -ForegroundColor Green
Write-Host " GRAALVM INSTALLATION COMPLETE!" -ForegroundColor Green
Write-Host " GRAALVM_HOME: $TargetDir" -ForegroundColor Green
Write-Host " Native Image CLI: $TargetDir\bin\native-image.cmd" -ForegroundColor Green
Write-Host "=========================================================================" -ForegroundColor Green

& "$TargetDir\bin\native-image.cmd" --version
