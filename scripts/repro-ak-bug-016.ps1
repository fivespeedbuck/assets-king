param(
    [string]$Serial = "192.168.31.210:42249",
    [string]$Adb = "C:\Users\chenyanggggg\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    [int]$Iterations = 3,
    [int]$StatusBarBottom = 108
)

$ErrorActionPreference = "Stop"
$package = "com.assetsking.app.recovery"
$activity = "$package/com.assetsking.app.MainActivity"
$remoteDump = "/sdcard/assets-king-ak-bug-016.xml"

function Invoke-Adb([string[]]$Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')`n$output"
    }
    return $output
}

function Get-UiXml {
    Invoke-Adb @("shell", "uiautomator", "dump", $remoteDump) | Out-Null
    [xml](Invoke-Adb @("shell", "cat", $remoteDump))
}

function Find-ClickableBounds([xml]$Xml, [string]$Text) {
    $node = $Xml.SelectSingleNode("//node[@text='$Text' or @content-desc='$Text']")
    if ($null -eq $node) { return $null }
    while ($null -ne $node -and $node.clickable -ne "true") {
        $node = $node.ParentNode
    }
    if ($null -eq $node) { return $null }
    return $node.bounds
}

function Tap-Bounds([string]$Bounds) {
    if ($Bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "unexpected bounds: $Bounds"
    }
    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
    Invoke-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
}

function Find-HomeTarget {
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $bounds = Find-ClickableBounds (Get-UiXml) "本月待扣"
        if ($null -ne $bounds) { return $bounds }
        Invoke-Adb @("shell", "input", "swipe", "540", "1900", "540", "700", "350") | Out-Null
        Start-Sleep -Milliseconds 500
    }
    throw "AK-BUG-016 RED: 首页未找到本月待扣入口"
}

Invoke-Adb @("shell", "am", "force-stop", $package) | Out-Null
Invoke-Adb @("shell", "am", "start", "-W", "-n", $activity) | Out-Null
Start-Sleep -Seconds 2

for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
    Tap-Bounds (Find-HomeTarget)
    Start-Sleep -Seconds 2

    $detail = Get-UiXml
    if ($null -eq $detail.SelectSingleNode("//node[@text='周期账单']")) {
        $anr = Invoke-Adb @("shell", "dumpsys", "activity", "lastanr")
        throw "AK-BUG-016 RED: 第 $iteration 次点击后未进入周期账单页`n$anr"
    }

    $backBounds = Find-ClickableBounds $detail "返回"
    if ($null -eq $backBounds) {
        throw "AK-BUG-016 RED: 第 $iteration 次进入后缺少页面返回入口"
    }
    if ($backBounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "AK-BUG-016 RED: 返回入口坐标异常 $backBounds"
    }
    if ([int]$Matches[2] -lt $StatusBarBottom) {
        throw "AK-BUG-016 RED: 第 $iteration 次返回入口侵入系统状态栏 $backBounds"
    }

    if ($iteration % 2 -eq 0) {
        Invoke-Adb @("shell", "input", "keyevent", "BACK") | Out-Null
    } else {
        Tap-Bounds $backBounds
    }
    Start-Sleep -Seconds 2
    $homeXml = Get-UiXml
    if ($null -ne $homeXml.SelectSingleNode("//node[@text='周期账单']")) {
        throw "AK-BUG-016 RED: 第 $iteration 次进入后无法返回首页"
    }

    Write-Output "AK-BUG-016 iteration $iteration PASS"
}

Write-Output "AK-BUG-016 GREEN: $Iterations 次打开/返回均响应"
