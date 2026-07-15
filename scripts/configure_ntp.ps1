# Requires Administrator privileges
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Error "Error: Please run this PowerShell script as an Administrator."
    Exit
}

Write-Host "=== Starting Automatic Windows NTP Configuration ==="

# 1. Stop the Windows Time service and completely re-register it to clear configuration corruption
Write-Host "Re-registering Windows Time Service (w32time)..."
Stop-Service -Name "w32time" -Force -ErrorAction SilentlyContinue
w32tm /unregister
Start-Sleep -Seconds 1
w32tm /register
Start-Sleep -Seconds 1

# 2. Configure Windows Time Service to start automatically
Set-Service -Name "w32time" -StartupType Automatic

# 3. Configure the manual peer list first (before updating settings)
Write-Host "Updating NTP Manual Peer List to Google Smeared Servers..."
w32tm /config /manualpeerlist:"time1.google.com,0x8 time2.google.com,0x8 time3.google.com,0x8 time4.google.com,0x8" /syncfromflags:manual /reliable:YES /update

# 4. Disable VM Time Integration provider in Registry if present to prevent host overrides
$vmTimeProviderPath = "HKLM:\SYSTEM\CurrentControlSet\Services\W32Time\TimeProviders\VMICTimeProvider"
if (Test-Path $vmTimeProviderPath) {
    Write-Host "Disabling VM Time Provider (VMICTimeProvider) to prevent host overrides..."
    Set-ItemProperty -Path $vmTimeProviderPath -Name "Enabled" -Value 0
}

# 5. Disable Phase Correction thresholds (forces w32time to correct any offset immediately instead of ignoring it)
Write-Host "Setting phase correction limits to infinite..."
Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\W32Time\Config" -Name "MaxPosPhaseCorrection" -Value 0xFFFFFFFF
Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\W32Time\Config" -Name "MaxNegPhaseCorrection" -Value 0xFFFFFFFF

# 6. Restart the service to apply registry changes
Write-Host "Starting Windows Time Service..."
Start-Service -Name "w32time"

# 7. Force an immediate update and clock resynchronization
Write-Host "Forcing w32time configuration update and immediate clock resynchronization..."
w32tm /config /update
w32tm /resync /force

Write-Host "=== Windows NTP Setup Complete ==="
