param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,
    [switch]$ExpectDebuggable
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$localProperties = Get-Content -LiteralPath (Join-Path $repoRoot 'local.properties') -Encoding utf8
$sdkEntry = $localProperties | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
if (-not $sdkEntry) { throw 'local.properties 缺少 sdk.dir' }
$sdkDir = $sdkEntry.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
$apkAnalyzer = Join-Path $sdkDir 'cmdline-tools\latest\bin\apkanalyzer.bat'
if (-not (Test-Path -LiteralPath $apkAnalyzer)) { throw "找不到 apkanalyzer: $apkAnalyzer" }

$applicationId = (& $apkAnalyzer manifest application-id $apkPath).Trim()
$versionName = (& $apkAnalyzer manifest version-name $apkPath).Trim()
$debuggable = (& $apkAnalyzer manifest debuggable $apkPath).Trim()

if ($applicationId -ne 'com.assetsking.app.recovery') {
    throw "包名门禁失败：实际为 $applicationId"
}
if (-not $versionName.EndsWith('-recovery')) {
    throw "版本名门禁失败：实际为 $versionName"
}
$expectedDebuggable = if ($ExpectDebuggable) { 'true' } else { 'false' }
if ($debuggable -ne $expectedDebuggable) {
    throw "debuggable 门禁失败：期望 $expectedDebuggable，实际 $debuggable"
}

Write-Output "PASS applicationId=$applicationId versionName=$versionName debuggable=$debuggable"
