# HarmonyOS-side build entry point.
#
# Why this exists:
#  - Official Kotlin has no ohosArm64 target; KuiklyBase's custom Kotlin is required.
#  - That Kotlin only supports Gradle 8.x, while the Android build uses Gradle 9.8.2.
#  - Linking the .so needs the OpenHarmony SDK (DEVECO_SDK_HOME).
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less files as ANSI.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Rest
)

$ErrorActionPreference = "Stop"

$autoDetected = $false
if (-not $env:DEVECO_SDK_HOME) {
    $autoDetected = $true
    $candidates = @(
        "D:\Huawei\DevEco Studio\sdk",
        "C:\Program Files\Huawei\DevEco Studio\sdk"
    )
    $found = $candidates |
        Where-Object { Test-Path (Join-Path $_ "default\openharmony\native\sysroot") } |
        Select-Object -First 1
    if (-not $found) {
        throw "OpenHarmony SDK not found. Set DEVECO_SDK_HOME to the DevEco Studio sdk directory."
    }
    $env:DEVECO_SDK_HOME = $found
}

if (-not $env:OHOS_SDK_HOME) {
    # Kotlin/Native reads OHOS_SDK_HOME when linking; without it the compiler
    # falls back to a hard-coded DevEco install path.
    $env:OHOS_SDK_HOME = Join-Path $env:DEVECO_SDK_HOME "default\openharmony"
    $autoDetected = $true
}

Write-Host "DEVECO_SDK_HOME=$env:DEVECO_SDK_HOME"
Write-Host "OHOS_SDK_HOME=$env:OHOS_SDK_HOME"

Push-Location $PSScriptRoot
try {
    if ($autoDetected) {
        # Daemons started before this variable existed keep looking at the default SDK path.
        & (Join-Path $PSScriptRoot "gradlew.bat") --stop 2>&1 | Out-Null
    }

    & (Join-Path $PSScriptRoot "gradlew.bat") @Rest
}
finally {
    Pop-Location
}
exit $LASTEXITCODE
