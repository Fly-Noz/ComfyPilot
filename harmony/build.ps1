# Build the HarmonyOS HAP (debug, unsigned).
#
# Steps:
#   1. locate DevEco Studio + OpenHarmony SDK
#   2. build libcomfypilot_core.so via ../ohos
#   3. copy the .so and its header into this project
#   4. run hvigor assembleHap
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less files as ANSI.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Rest
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot

$devEcoCandidates = @(
    "D:\Huawei\DevEco Studio",
    "C:\Program Files\Huawei\DevEco Studio"
)
$devEco = $devEcoCandidates |
    Where-Object { Test-Path (Join-Path $_ "tools\hvigor\bin\hvigorw.bat") } |
    Select-Object -First 1
if (-not $devEco) {
    throw "DevEco Studio not found. Install it or add it to devEcoCandidates."
}

if (-not $env:DEVECO_SDK_HOME) {
    $env:DEVECO_SDK_HOME = Join-Path $devEco "sdk"
}

$repoRoot = Split-Path -Parent $projectRoot

Write-Host "==> Building shared Kotlin library for ohosArm64"
& (Join-Path $repoRoot "ohos\build.ps1") :shared-ohos:linkDebugSharedOhosArm64
if ($LASTEXITCODE -ne 0) {
    throw "Kotlin shared library build failed."
}

$nativeOut = Join-Path $repoRoot "ohos\shared-ohos\build\bin\ohosArm64\debugShared"
$libDest = Join-Path $projectRoot "entry\libs\arm64-v8a"
$headerDest = Join-Path $projectRoot "entry\src\main\cpp\include"
New-Item -ItemType Directory -Force -Path $libDest, $headerDest | Out-Null
Copy-Item (Join-Path $nativeOut "libcomfypilot_core.so") $libDest -Force
Copy-Item (Join-Path $nativeOut "libcomfypilot_core_api.h") $headerDest -Force
Write-Host "==> Copied native artifacts into entry/libs and cpp/include"

$env:NODE_HOME = Join-Path $devEco "tools\node"

Push-Location $projectRoot
try {
    if ($Rest.Count -eq 0) {
        $Rest = @("assembleHap", "--mode", "module", "-p", "product=default", "-p", "buildMode=debug", "--no-daemon")
    }
    & (Join-Path $devEco "tools\hvigor\bin\hvigorw.bat") @Rest
}
finally {
    Pop-Location
}

exit $LASTEXITCODE
