param(
    [string]$Serial = "192.168.31.80:40819",
    [string]$ArtifactDir = "D:\assets-king-codex-recovery\artifacts\loan-final-qa-20260823",
    [datetime]$ExpectedInstalledAfter = [datetime]"2026-08-23 04:28:00",
    [switch]$ColdLaunch
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\chenyanggggg\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$package = "com.assetsking.app.recovery"
$activity = "$package/com.assetsking.app.MainActivity"
$script:step = 0

New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null

function Invoke-Adb([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & $adb -s $Serial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($exitCode -ne 0) {
        throw "ADB failed: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return ($output -join "`n")
}

function Get-Ui {
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/assetsking-loan-qa.xml") | Out-Null
    $raw = Invoke-Adb @("shell", "cat", "/sdcard/assetsking-loan-qa.xml")
    return [xml]$raw
}

function Get-Nodes([xml]$Ui, [string]$TextPattern) {
    return @($Ui.SelectNodes("//node")) | Where-Object {
        $_.text -like $TextPattern -or $_.'content-desc' -like $TextPattern
    }
}

function Assert-Text([xml]$Ui, [string]$Pattern) {
    if ((Get-Nodes $Ui $Pattern).Count -eq 0) {
        throw "Missing UI text: $Pattern"
    }
}

function Assert-NoText([xml]$Ui, [string]$Pattern) {
    if ((Get-Nodes $Ui $Pattern).Count -gt 0) {
        throw "Unexpected UI text: $Pattern"
    }
}

function Get-Center($Node) {
    $match = [regex]::Match($Node.bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    if (-not $match.Success) { throw "Invalid node bounds: $($Node.bounds)" }
    return [pscustomobject]@{
        X = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
        Y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    }
}

function Tap-First([xml]$Ui, [string]$Pattern) {
    $node = (Get-Nodes $Ui $Pattern | Select-Object -First 1)
    if ($null -eq $node) { throw "Cannot tap missing UI node: $Pattern" }
    $center = Get-Center $node
    Invoke-Adb @("shell", "input", "tap", ([int]$center.X).ToString(), ([int]$center.Y).ToString()) | Out-Null
    Start-Sleep -Milliseconds 700
}

function Tap-Last([xml]$Ui, [string]$Pattern) {
    $node = (Get-Nodes $Ui $Pattern | Select-Object -Last 1)
    if ($null -eq $node) { throw "Cannot tap missing UI node: $Pattern" }
    $center = Get-Center $node
    Invoke-Adb @("shell", "input", "tap", ([int]$center.X).ToString(), ([int]$center.Y).ToString()) | Out-Null
    Start-Sleep -Milliseconds 700
}

function Reset-ScrollToTop {
    # Navigation preserves each tab's LazyColumn position. Pull content down several times so
    # assertions always start from the loan page header instead of an earlier manual QA offset.
    1..3 | ForEach-Object {
        Invoke-Adb @("shell", "input", "swipe", "720", "900", "720", "2400", "260") | Out-Null
        Start-Sleep -Milliseconds 180
    }
}

function Wait-ForText([string]$Pattern, [int]$TimeoutMillis = 5000) {
    $deadline = [datetime]::UtcNow.AddMilliseconds($TimeoutMillis)
    do {
        $candidate = Get-Ui
        if ((Get-Nodes $candidate $Pattern).Count -gt 0) { return $candidate }
        Start-Sleep -Milliseconds 250
    } while ([datetime]::UtcNow -lt $deadline)
    throw "Missing UI text after ${TimeoutMillis}ms: $Pattern"
}

function Scroll-UntilText([string]$Pattern, [int]$MaxSwipes = 6) {
    for ($attempt = 0; $attempt -le $MaxSwipes; $attempt += 1) {
        $candidate = Get-Ui
        if ((Get-Nodes $candidate $Pattern).Count -gt 0) { return $candidate }
        if ($attempt -lt $MaxSwipes) {
            Invoke-Adb @("shell", "input", "swipe", "720", "2400", "720", "1050", "320") | Out-Null
            Start-Sleep -Milliseconds 420
        }
    }
    throw "Missing UI text after $MaxSwipes scrolls: $Pattern"
}

function Tap-ExpandablePlanWithNextDue([int]$MaxSwipes = 6) {
    for ($attempt = 0; $attempt -le $MaxSwipes; $attempt += 1) {
        $candidate = Get-Ui
        $dueNodes = Get-Nodes $candidate "下次还款 *"
        $expandNodes = Get-Nodes $candidate "展开贷款详情"
        if ($dueNodes.Count -gt 0 -and $expandNodes.Count -gt 0) {
            $dueCenter = Get-Center ($dueNodes | Select-Object -First 1)
            $target = $expandNodes |
                Sort-Object { [math]::Abs((Get-Center $_).Y - $dueCenter.Y) } |
                Select-Object -First 1
            $targetCenter = Get-Center $target
            Invoke-Adb @("shell", "input", "tap", ([int]$targetCenter.X).ToString(), ([int]$targetCenter.Y).ToString()) | Out-Null
            Start-Sleep -Milliseconds 700
            return
        }
        if ($attempt -lt $MaxSwipes) {
            Invoke-Adb @("shell", "input", "swipe", "720", "2400", "720", "1050", "320") | Out-Null
            Start-Sleep -Milliseconds 420
        }
    }
    throw "No expandable loan plan with a next repayment date found"
}

function Dismiss-OpenSheets {
    for ($attempt = 0; $attempt -lt 4; $attempt += 1) {
        $candidate = Get-Ui
        $closeNode = Get-Nodes $candidate "关闭工作表" | Select-Object -First 1
        if ($null -eq $closeNode) { return $candidate }
        $center = Get-Center $closeNode
        Invoke-Adb @("shell", "input", "tap", ([int]$center.X).ToString(), ([int]$center.Y).ToString()) | Out-Null
        Start-Sleep -Milliseconds 500
    }
    throw "Could not dismiss the existing top sheet"
}

function Capture-Step([string]$Name) {
    $script:step += 1
    $remote = "/sdcard/loan-qa-$($script:step).png"
    $local = Join-Path $ArtifactDir ("{0:D2}-{1}.png" -f $script:step, $Name)
    Invoke-Adb @("shell", "screencap", "-p", $remote) | Out-Null
    Invoke-Adb @("pull", $remote, $local) | Out-Null
}

$packageDump = Invoke-Adb @("shell", "dumpsys", "package", $package)
$lastUpdateMatch = [regex]::Match($packageDump, "lastUpdateTime=(?<time>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")
if (-not $lastUpdateMatch.Success) { throw "Cannot read installed package update time" }
$lastUpdate = [datetime]::ParseExact($lastUpdateMatch.Groups["time"].Value, "yyyy-MM-dd HH:mm:ss", $null)
if ($lastUpdate -lt $ExpectedInstalledAfter) {
    throw "Final candidate is not installed. lastUpdateTime=$lastUpdate"
}

if ($ColdLaunch) {
    Invoke-Adb @("shell", "am", "force-stop", $package) | Out-Null
    Invoke-Adb @("shell", "am", "start", "-n", $activity) | Out-Null
    Start-Sleep -Seconds 2
} else {
    $activities = Invoke-Adb @("shell", "dumpsys", "activity", "activities")
    if ($activities -notmatch "topResumedActivity=.*$([regex]::Escape($package))") {
        throw "Recovery app is not foreground; open it manually and exit privacy before loan QA"
    }
}
$ui = Get-Ui
if ((Get-Nodes $ui "*金库异常*").Count -gt 0) {
    throw "App is in privacy mode; exit privacy with biometrics before loan QA"
}
$ui = Dismiss-OpenSheets

# 首页可能同时出现贷款摘要；底部导航在 UI 树中位于最后，明确点最后一个“贷款”。
Tap-Last $ui "贷款"
Reset-ScrollToTop
$ui = Wait-ForText "本月还款"
Assert-Text $ui "*查看还款日历*"
Assert-Text $ui "本月还款"
# 信息架构硬门禁：只能持续向下依次找到这三段；若贷款计划仍夹在信用账款和
# 信用分期之间，第二步就会失败，避免只检查“文字都存在”却漏掉顺序回退。
$ui = Scroll-UntilText "信用账户账款"
$ui = Scroll-UntilText "信用分期"
$ui = Scroll-UntilText "贷款计划"
Capture-Step "loan-lower-cards"

Tap-ExpandablePlanWithNextDue
$ui = Get-Ui
foreach ($required in @("还款进度", "年利率", "已还本金", "剩余本金", "近期计划")) {
    Assert-Text $ui $required
}
$hasCompleteTotal = (Get-Nodes $ui "预计总还款*").Count -gt 0
$hasPartialTotal = (Get-Nodes $ui "当前*期计划合计*").Count -gt 0
if (-not $hasCompleteTotal -and -not $hasPartialTotal) {
    throw "Loan summary must identify either a complete expected repayment or a partial schedule total"
}
if ($hasPartialTotal) {
    $hasCoverageWarning = (Get-Nodes $ui "*未排入计划*").Count -gt 0 -or
        (Get-Nodes $ui "*计划本金比当前剩余本金多*").Count -gt 0
    if (-not $hasCoverageWarning) {
        throw "Partial loan schedule total is missing its principal-coverage warning"
    }
}
Capture-Step "loan-plan-expanded-summary"

$ui = Scroll-UntilText "查看完整计划"
Capture-Step "loan-plan-nearby-schedule"
Tap-First $ui "查看完整计划"
$ui = Get-Ui
Assert-Text $ui "完整还款计划*"
Assert-Text $ui "*第*期*"
Capture-Step "full-schedule"
Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
Start-Sleep -Milliseconds 500

$ui = Scroll-UntilText "提前还款测算"
Assert-Text $ui "提前还款测算"
$ui = Scroll-UntilText "结清"
Assert-Text $ui "记录还款"
Assert-Text $ui "提前还款"
Assert-Text $ui "结清"
$prepay = Get-Nodes $ui "提前还款" | Select-Object -Last 1
$settle = Get-Nodes $ui "结清" | Select-Object -Last 1
$prepayCenter = Get-Center $prepay
$settleCenter = Get-Center $settle
if ([math]::Abs($prepayCenter.Y - $settleCenter.Y) -gt 4) {
    throw "Prepay and settle are not horizontally aligned: $($prepay.bounds) vs $($settle.bounds)"
}
Capture-Step "loan-plan-expanded-actions"

Reset-ScrollToTop
$ui = Scroll-UntilText "贷款计划"
Tap-First $ui "*添加*"
$ui = Get-Ui
Assert-Text $ui "新增贷款计划"
Assert-NoText $ui "宁波银行"
Assert-NoText $ui "广发信用卡"
Assert-NoText $ui "花呗"
$loanAccountStateWasEmpty = (Get-Nodes $ui "贷款计划只能关联贷款账户").Count -gt 0
if ($loanAccountStateWasEmpty) {
    Assert-Text $ui "新建贷款账户"
    Capture-Step "loan-plan-empty-account-state"
    Tap-First $ui "新建贷款账户"
} else {
    Assert-Text $ui "关联贷款账户"
    Assert-Text $ui "贷款本金"
    Assert-Text $ui "首期还款日（YYYY-MM-DD）"
    Capture-Step "loan-plan-form"
    Tap-First $ui "*新建*"
}
$ui = Get-Ui
Assert-Text $ui "新建贷款账户"
Assert-Text $ui "贷款账户"
Assert-NoText $ui "资产（储蓄/借记卡）"
Assert-NoText $ui "信用卡"
Capture-Step "new-loan-account"
Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
Start-Sleep -Milliseconds 400
$ui = Get-Ui
Assert-Text $ui "新增贷款计划"
if ($loanAccountStateWasEmpty) {
    Assert-Text $ui "贷款计划只能关联贷款账户"
} else {
    Assert-Text $ui "关联贷款账户"
    Assert-Text $ui "贷款本金"
}
Capture-Step "loan-plan-context-restored"
Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
Start-Sleep -Milliseconds 400

# 贷款计划现在位于信用分期之后；关闭表单后先回到顶端，再按新顺序寻找分期入口。
Reset-ScrollToTop
$ui = Scroll-UntilText "信用分期"
$ui = Scroll-UntilText "单笔消费分期"
Tap-First $ui "单笔消费分期"
$ui = Get-Ui
Assert-Text $ui "单笔消费分期"
Assert-Text $ui "全部信用账户"
Assert-Text $ui "搜索商户或金额"
Assert-Text $ui "*笔未显示*"
Assert-NoText $ui "分期名称"
Capture-Step "installment-picker"

Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
Start-Sleep -Milliseconds 500
# 关闭选择器时仍停留在页面中部，先回到顶端再验证信用账款说明，
# 避免只会向下滚动的 Scroll-UntilText 误报缺失。
Reset-ScrollToTop
$ui = Scroll-UntilText "信用账户账款"
Assert-Text $ui "普通消费不是分期*"
$ui = Scroll-UntilText "账单分期"
$statementButton = Get-Nodes $ui "账单分期" |
    Where-Object { $_.enabled -eq "true" -and $_.clickable -eq "true" } |
    Select-Object -First 1
if ($null -ne $statementButton) {
    $center = Get-Center $statementButton
    Invoke-Adb @("shell", "input", "tap", ([int]$center.X).ToString(), ([int]$center.Y).ToString()) | Out-Null
    Start-Sleep -Milliseconds 700
    $ui = Get-Ui
    Assert-Text $ui "账单分期"
    Assert-Text $ui "选择本期账单消费"
    Assert-Text $ui "*未出账消费不要勾选*"
    Capture-Step "statement-installment-picker"
} else {
    Assert-Text $ui "账单分期需先设置出账日和还款日，并且本期存在应还账单及已出账消费。"
    Capture-Step "statement-installment-disabled-valid-empty-state"
    Write-Host "STATEMENT_INSTALLMENT_UI_SKIP: no eligible current statement on live user data; disabled-state contract passed, creation is covered by isolated instrumentation"
}

Write-Host "LOAN_UI_GREEN: credit payables, lower cards, summary, schedule, horizontal actions, loan-only account flow, single-purchase picker and statement-installment live-state contract passed"
