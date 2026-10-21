# Build, install and launch the HarmonyOS HAP on a connected device.
#
# Requires signingConfigs in build-profile.json5 (DevEco Studio: Project Structure
# -> Signing Configs -> "Automatically generate signature"). Without it hvigor only
# produces entry-default-unsigned.hap and the device rejects the install.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less files as ANSI.
param(
    [string] $Serial = "33Z0224613077036",
    [switch] $SkipBuild,
    [switch] $SkipInstall,
    [int] $LogSeconds = 6
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$repoRoot = Split-Path -Parent $projectRoot

$devEcoCandidates = @(
    "D:\Huawei\DevEco Studio",
    "C:\Program Files\Huawei\DevEco Studio"
)
$devEco = $devEcoCandidates |
    Where-Object { Test-Path (Join-Path $_ "sdk\default\openharmony\toolchains\hdc.exe") } |
    Select-Object -First 1
if (-not $devEco) {
    throw "DevEco Studio not found. Install it or add it to devEcoCandidates."
}
$hdc = Join-Path $devEco "sdk\default\openharmony\toolchains\hdc.exe"
$appJson = Get-Content (Join-Path $projectRoot "AppScope\app.json5") -Raw
if ($appJson -notmatch '"bundleName"\s*:\s*"([^"]+)"') {
    throw "Cannot read bundleName from AppScope/app.json5."
}
$bundleName = $Matches[1]
$abilityName = "EntryAbility"
Write-Host "==> Bundle: $bundleName"

if (-not $SkipBuild) {
    & (Join-Path $projectRoot "build.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "HAP build failed."
    }
}

$outputDir = Join-Path $projectRoot "entry\build\default\outputs\default"
$hap = Get-ChildItem $outputDir -Filter "*-signed.hap" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $hap) {
    $unsigned = Get-ChildItem $outputDir -Filter "*.hap" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($unsigned) {
        throw "Only an unsigned HAP was produced ($($unsigned.Name)). Enable automatic signing in DevEco Studio (Project Structure -> Signing Configs) and rebuild."
    }
    throw "No HAP found under $outputDir. Run the build first."
}
Write-Host "==> HAP: $($hap.FullName)"

& $hdc kill | Out-Null
& $hdc start | Out-Null
$targets = & $hdc list targets
if ($targets -notmatch [regex]::Escape($Serial)) {
    throw "Device $Serial not found. hdc list targets returned: $targets"
}

if (-not $SkipInstall) {
    Write-Host "==> Installing on $Serial"
    & $hdc -t $Serial install -r $hap.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "Install failed (exit $LASTEXITCODE)."
    }
}

Write-Host "==> Launching $bundleName/$abilityName"
& $hdc -t $Serial shell hilog -r | Out-Null
& $hdc -t $Serial shell aa start -a $abilityName -b $bundleName
if ($LASTEXITCODE -ne 0) {
    throw "Ability start failed (exit $LASTEXITCODE)."
}

Start-Sleep -Seconds $LogSeconds

Write-Host "==> hilog (lines mentioning $bundleName)"
& $hdc -t $Serial shell hilog -x |
    Select-String -Pattern ([regex]::Escape($bundleName) + "|ComfyPilot|cp_version|cp_self_test") |
    Select-Object -Last 80

$artifacts = Join-Path $repoRoot "artifacts"
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$shot = Join-Path $artifacts "ohos-selftest.jpeg"
try {
    & $hdc -t $Serial shell snapshot_display -f /data/local/tmp/comfypilot.jpeg | Out-Null
    & $hdc -t $Serial file recv /data/local/tmp/comfypilot.jpeg $shot | Out-Null
    if (Test-Path $shot) {
        Write-Host "==> Screenshot: $shot"
    }
}
catch {
    Write-Host "==> Screenshot skipped: $($_.Exception.Message)"
}

Write-Host "==> Done"
