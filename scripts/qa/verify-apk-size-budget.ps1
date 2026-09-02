#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$BaselineManifestPath
)

$ErrorActionPreference = "Stop"
$resolvedManifest = (Resolve-Path -LiteralPath $BaselineManifestPath).Path
$manifest = Get-Content -LiteralPath $resolvedManifest -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1) {
    throw "不支持的 APK 体积基线 schemaVersion：$($manifest.schemaVersion)"
}
if ($manifest.variant -ne "releaseSmoke") {
    throw "APK 体积基线必须来自 releaseSmoke，实际为：$($manifest.variant)"
}
$BaselineBytes = [long]$manifest.baselineBytes
if ($BaselineBytes -le 0) {
    throw "基线清单中的 baselineBytes 必须为正数。"
}
if ($manifest.artifactSha256 -notmatch '^[0-9A-Fa-f]{64}$') {
    throw "基线清单缺少有效的 artifactSha256。"
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$actualBytes = (Get-Item -LiteralPath $resolvedApk).Length
$twentyMiB = 20L * 1024L * 1024L
$twentyFivePercent = [long][Math]::Ceiling($BaselineBytes * 0.25)
$allowedGrowth = [Math]::Min($twentyMiB, $twentyFivePercent)
$maximumBytes = $BaselineBytes + $allowedGrowth

Write-Output "APK=$resolvedApk"
Write-Output "BASELINE_MANIFEST=$resolvedManifest"
Write-Output "BASELINE_BYTES=$BaselineBytes"
Write-Output "ACTUAL_BYTES=$actualBytes"
Write-Output "MAXIMUM_BYTES=$maximumBytes"

if ($actualBytes -gt $maximumBytes) {
    throw "APK 体积超过预算：$actualBytes > $maximumBytes（B + min(20 MiB, B × 25%)）。"
}

Write-Output "APK size budget passed."
