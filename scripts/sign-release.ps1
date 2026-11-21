<#
.SYNOPSIS
  Signs a published release's checksums.txt so servers can verify updates (optional; unsigned releases still install).

.DESCRIPTION
  One-time setup:
    java -cp build\libs\obsidianscout-server.jar com.obsidianscout.utils.ReleaseSigner keygen $HOME\.obsidianscout-release.key
  Keep that key file offline and backed up. Paste the printed public key into
  ReleaseSignature.PINNED_PUBLIC_KEY (src/main/kotlin/com/obsidianscout/utils/ReleaseSignature.kt) and release once;
  from then on servers on that build check signatures.

  Per release (after the GitHub release exists):
    .\scripts\sign-release.ps1 -Tag v0.7.4.9

  Downloads checksums.txt from the release, signs it with the local key, and uploads checksums.txt.sig. Needs gh.

.PARAMETER Tag
  Release tag, for example v0.7.4.9.
.PARAMETER KeyFile
  Private key from `ReleaseSigner keygen`. Defaults to $HOME\.obsidianscout-release.key.
.PARAMETER Jar
  Server jar that contains ReleaseSigner. Defaults to build\libs\obsidianscout-server.jar (gradlew shadowJar).
#>
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$KeyFile = (Join-Path $HOME ".obsidianscout-release.key"),
    [string]$Jar = (Join-Path $PSScriptRoot "..\build\libs\obsidianscout-server.jar")
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $KeyFile)) { throw "Signing key not found at $KeyFile. Create one with ReleaseSigner keygen (see Get-Help $PSCommandPath)." }
if (-not (Test-Path $Jar)) { throw "Server jar not found at $Jar. Run .\gradlew shadowJar first." }

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("obsidianscout-sign-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
    gh release download $Tag --pattern checksums.txt --dir $work
    if ($LASTEXITCODE -ne 0) { throw "Could not download checksums.txt for $Tag" }
    $checksums = Join-Path $work "checksums.txt"
    java -cp $Jar com.obsidianscout.utils.ReleaseSigner sign $KeyFile $checksums
    if ($LASTEXITCODE -ne 0) { throw "Signing failed" }
    gh release upload $Tag "$checksums.sig" --clobber
    if ($LASTEXITCODE -ne 0) { throw "Upload of checksums.txt.sig failed" }
    Write-Host "Signed release $Tag."
} finally {
    Remove-Item -Recurse -Force $work
}
