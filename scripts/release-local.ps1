<#
.SYNOPSIS
  Local stand-in for .github/workflows/release.yml when GitHub-hosted runners are unavailable.

.DESCRIPTION
  Mirrors the CI pipeline step for step:
    1. bump-version - gradlew bumpVersion, commit "chore: bump version to vX [skip ci]", tag vX
    2. build-matrix - windows-x86_64 native (built here, needs Visual Studio C++ tools)
                      linux-x86_64 native, linux-arm64 native and fatjar (built in WSL inside
                      GraalVM 25 containers from a clean LF checkout of the tag; arm64 runs
                      under QEMU emulation and is slow)
                      packaged as zip / tar.gz / rpm with the same file names CI produces
    3. Copies every release asset to Downloads\obsidianscout-vX, then pushes the commit + tag.
  Afterwards create the GitHub release for tag vX by hand and upload everything in that folder.

.PARAMETER SkipBump
  Reuse the current version instead of bumping (e.g. to resume after a failed build).
.PARAMETER NoPush
  Do not push the version commit and tag to origin.
.PARAMETER Targets
  Any of windows-x86_64, linux-x86_64, linux-arm64, fatjar (comma separated). Default: all.
.PARAMETER WslDistro
  WSL distro used for the Linux builds. Default: first Fedora distro, else the default distro.
.PARAMETER OutDir
  Output folder. Default: <Downloads>\obsidianscout-v<version>.

.EXAMPLE
  .\release-local.bat
.EXAMPLE
  .\release-local.bat -SkipBump -Targets linux-arm64
#>
param(
    [switch]$SkipBump,
    [switch]$NoPush,
    [string[]]$Targets = @('windows-x86_64', 'linux-x86_64', 'linux-arm64', 'fatjar'),
    [string]$WslDistro,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$env:WSL_UTF8 = '1'

$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Repo
$started = Get-Date

function Step([string]$Message) {
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Exit([string]$What) {
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
}

function Get-CurrentVersion {
    $m = Select-String -Path (Join-Path $Repo 'config\app-config.json') -Pattern '"current_version"\s*:\s*"([^"]+)"' | Select-Object -First 1
    if (-not $m) { return '0.1.0' }
    return $m.Matches[0].Groups[1].Value
}

function Get-DownloadsFolder {
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders'
    $raw = (Get-ItemProperty -Path $key -ErrorAction SilentlyContinue).'{374DE290-123F-4565-9164-39C4925E467B}'
    if ($raw) { return [Environment]::ExpandEnvironmentVariables($raw) }
    return Join-Path $env:USERPROFILE 'Downloads'
}

function Use-GraalVM {
    $dir = Join-Path $env:USERPROFILE '.graalvm\graalvm-jdk-25'
    if (-not (Test-Path "$dir\bin\native-image.cmd")) {
        Step 'GraalVM JDK 25 not found - installing via scripts\install-graal.ps1'
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Repo 'scripts\install-graal.ps1')
        Assert-Exit 'GraalVM install'
    }
    $env:GRAALVM_HOME = $dir
    $env:JAVA_HOME = $dir
    $env:PATH = "$dir\bin;$env:PATH"
}

# native-image on Windows needs cl.exe/link.exe from an x64 VS developer environment.
function Import-MsvcEnvironment {
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) { throw 'Visual Studio with "Desktop development with C++" is required for the Windows native build.' }
    $vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $vs) { throw 'Visual Studio C++ x64 build tools not found (install the "Desktop development with C++" workload).' }
    $vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
    cmd /c "call `"$vcvars`" >nul 2>&1 && set" | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { Set-Item -Path "env:$($Matches[1])" -Value $Matches[2] }
    }
    if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) { throw "Failed to load MSVC environment from $vcvars" }
}

function Get-WslDistro {
    if ($WslDistro) { return $WslDistro }
    $names = @(wsl.exe -l -q | ForEach-Object { ($_ -replace "`0", '').Trim() } | Where-Object { $_ -and $_ -notmatch '^docker-desktop' })
    if (-not $names) { throw 'No WSL distro found. Install one with: wsl --install -d FedoraLinux-42' }
    $fedora = $names | Where-Object { $_ -match 'fedora' } | Select-Object -First 1
    if ($fedora) { return $fedora }
    return $names[0]
}

function ConvertTo-WslPath([string]$Distro, [string]$WindowsPath) {
    $p = (wsl.exe -d $Distro -e wslpath -a $WindowsPath | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $p) { throw "wslpath failed for $WindowsPath" }
    return $p
}

# --- validate arguments ------------------------------------------------------
$allTargets = @('windows-x86_64', 'linux-x86_64', 'linux-arm64', 'fatjar')
$Targets = @($Targets | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
foreach ($t in $Targets) {
    if ($allTargets -notcontains $t) { throw "Unknown target '$t'. Valid targets: $($allTargets -join ', ')" }
}
$linuxTargets = @($Targets | Where-Object { $_ -ne 'windows-x86_64' })

# --- preflight ----------------------------------------------------------------
Step 'Preflight checks'
if (git status --porcelain) { throw 'Working tree has uncommitted changes. Commit or stash them first (the release is built from the tag).' }
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne 'master') { Write-Host "Warning: releasing from branch '$branch' (CI releases from whatever branch was pushed)." -ForegroundColor Yellow }
if ($linuxTargets) {
    $distro = Get-WslDistro
    Write-Host "WSL distro for Linux builds: $distro"
}
Use-GraalVM
Write-Host "GraalVM: $env:GRAALVM_HOME"

# --- 1. bump-version ----------------------------------------------------------
if ($SkipBump) {
    $version = Get-CurrentVersion
    $tag = "v$version"
    Step "Skipping version bump - using $tag"
    git rev-parse -q --verify "refs/tags/$tag" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        git tag $tag
        Assert-Exit "git tag $tag"
        Write-Host "Created tag $tag at HEAD"
    }
} else {
    Step 'Bumping version number'
    & .\gradlew.bat bumpVersion --console=plain
    Assert-Exit 'gradlew bumpVersion'
    $version = Get-CurrentVersion
    $tag = "v$version"
    Write-Host "Bumped version: $tag"

    git add -u
    git diff --staged --quiet
    if ($LASTEXITCODE -ne 0) {
        git commit -m "chore: bump version to $tag [skip ci]"
        Assert-Exit 'git commit'
    } else {
        Write-Host 'No version changes detected.'
    }
    git tag $tag
    Assert-Exit "git tag $tag (does it already exist?)"
}

if (-not $OutDir) { $OutDir = Join-Path (Get-DownloadsFolder) "obsidianscout-$tag" }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
Write-Host "Release assets will be written to: $OutDir"

# --- 2a. Windows x86_64 native ------------------------------------------------
if ($Targets -contains 'windows-x86_64') {
    Step '[windows-x86_64] Native build'
    Import-MsvcEnvironment
    & .\gradlew.bat publishnative -PwithNativeImage -PskipBump --console=plain
    Assert-Exit 'gradlew publishnative (Windows)'

    $bundle = Join-Path $Repo "build\bundle-native\obsidianscout-v$version-x86_64"
    if (-not (Test-Path $bundle)) { throw "Bundle directory not found: $bundle" }
    $archiveName = "obsidianscout-v$version-windows-x86_64.zip"
    Write-Host "Zipping $bundle to $archiveName..."
    Compress-Archive -Path "$bundle\*" -DestinationPath (Join-Path $OutDir $archiveName) -Force
}

# --- 2b. Linux x86_64 / arm64 native + fatjar (WSL) ---------------------------
if ($linuxTargets) {
    Step "[$($linuxTargets -join ', ')] Building in WSL ($distro)"
    $wslRepo = ConvertTo-WslPath $distro $Repo
    $wslOut = ConvertTo-WslPath $distro $OutDir
    $wslScript = "$wslRepo/scripts/release-linux.sh"
    # The Windows checkout has CRLF line endings, so strip them before running.
    $bashCmd = "tr -d '\r' < '$wslScript' > /tmp/obsidianscout-release-linux.sh && bash /tmp/obsidianscout-release-linux.sh '$wslRepo' '$tag' '$version' '$wslOut' '$($linuxTargets -join ',')'"
    wsl.exe -d $distro -u root -e bash -c $bashCmd
    Assert-Exit 'Linux builds'
}

# --- 3. push + summary --------------------------------------------------------
if ($NoPush) {
    Step 'Skipping push (-NoPush). Push later with:'
    Write-Host "  git push origin HEAD; git push origin $tag"
} else {
    Step "Pushing version commit and tag $tag"
    git push origin HEAD
    Assert-Exit 'git push'
    git push origin $tag
    Assert-Exit "git push $tag"
}

$elapsed = (Get-Date) - $started
Step "Release $tag ready ($([int]$elapsed.TotalMinutes) min)"
Get-ChildItem $OutDir -File | Sort-Object Name | ForEach-Object {
    Write-Host ('  {0,-50} {1,8:N1} MB' -f $_.Name, ($_.Length / 1MB))
}
Write-Host ''
Write-Host "Next: create a GitHub release for tag $tag and upload every file in:" -ForegroundColor Green
Write-Host "  $OutDir" -ForegroundColor Green
Write-Host "  https://github.com/steveandjeff999/Obsidianscout-Java/releases/new?tag=$tag" -ForegroundColor Green
