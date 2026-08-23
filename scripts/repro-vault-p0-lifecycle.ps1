param(
    [string]$Serial = "192.168.31.80:40819",
    [int]$Cycles = 1,
    [int]$MaxSwipeAttempts = 3,
    [switch]$LeaveInBackground
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\chenyanggggg\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$package = "com.assetsking.app.recovery"
$activity = "$package/com.assetsking.app.MainActivity"
$service = "$package/com.assetsking.app.notification.AssetsNotificationListenerService"

function Invoke-Adb([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & $adb -s $Serial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($exitCode -ne 0) {
        throw "ADB failed: $($Arguments -join ' ')`n$output"
    }
    return ($output -join "`n")
}

function Get-TaskIds {
    $recents = Invoke-Adb @("shell", "dumpsys", "activity", "recents")
    return [regex]::Matches($recents, "Task\{[^#]*#(?<id>\d+)[^\r\n]*A=\d+:$([regex]::Escape($package))") |
        ForEach-Object { [int]$_.Groups["id"].Value } |
        Sort-Object -Unique
}

function Test-VaultRuntime {
    $notifications = Invoke-Adb @("shell", "dumpsys", "notification", "--noredact")
    $services = Invoke-Adb @("shell", "dumpsys", "activity", "services", $package)
    $listeners = Invoke-Adb @("shell", "dumpsys", "notification", "listeners")
    $liveMarker = "Live notification listeners ("
    $liveStart = $listeners.LastIndexOf($liveMarker, [StringComparison]::Ordinal)
    $snoozedStart = if ($liveStart -ge 0) {
        $listeners.IndexOf("Snoozed notification listeners", $liveStart, [StringComparison]::Ordinal)
    } else {
        -1
    }
    $liveBlock = if ($liveStart -ge 0 -and $snoozedStart -gt $liveStart) {
        $listeners.Substring($liveStart, $snoozedStart - $liveStart)
    } else {
        ""
    }
    [pscustomobject]@{
        NotificationPresent = $notifications.Contains("pkg=$package") -and
            $notifications.Contains("FOREGROUND_SERVICE") -and
            ($notifications.Contains("正在监听银行短信和支付通知") -or
                $notifications.Contains("· 监听中"))
        ListenerServicePresent = $services.Contains($service) -and
            $services.Contains("isForeground=true")
        LiveListenerPresent = $liveBlock.Contains("ComponentInfo{$service}")
    }
}

function Wait-VaultRuntime([int]$TimeoutSeconds = 10) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $state = Test-VaultRuntime
        if ($state.NotificationPresent -and $state.ListenerServicePresent -and $state.LiveListenerPresent) {
            return $state
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    return $state
}

$taskRemovalFailures = @()
$relaunchFailures = @()
for ($cycle = 1; $cycle -le $Cycles; $cycle++) {
    Invoke-Adb @("shell", "am", "start", "-n", $activity) | Out-Null
    Start-Sleep -Milliseconds 900
    $taskIds = @(Get-TaskIds)
    if ($taskIds.Count -eq 0) {
        throw "Cycle ${cycle}: recovery task not found"
    }
    $taskId = ($taskIds | Measure-Object -Maximum).Maximum

    # Exact user gesture: open Recents and swipe the current Assets King task away.
    # Vivo occasionally ignores the first gesture. A cycle is valid only after the
    # task really disappears; otherwise gesture noise is mistaken for app survival.
    $taskRemoved = $false
    $swipeAttempts = 0
    while (-not $taskRemoved -and $swipeAttempts -lt $MaxSwipeAttempts) {
        $swipeAttempts++
        Invoke-Adb @("shell", "input", "keyevent", "187") | Out-Null
        Start-Sleep -Milliseconds 700
        Invoke-Adb @("shell", "input", "swipe", "720", "1700", "720", "250", "450") | Out-Null
        Start-Sleep -Seconds 2
        $remainingTaskIds = @(Get-TaskIds)
        $taskRemoved = $taskId -notin $remainingTaskIds
    }
    $afterRemoval = Test-VaultRuntime
    Invoke-Adb @("shell", "am", "start", "-n", $activity) | Out-Null
    $afterRelaunch = Wait-VaultRuntime
    $taskRemovalPassed = $taskRemoved -and
        $afterRemoval.NotificationPresent -and
        $afterRemoval.ListenerServicePresent -and
        $afterRemoval.LiveListenerPresent
    $relaunchPassed = $afterRelaunch.NotificationPresent -and
        $afterRelaunch.ListenerServicePresent -and
        $afterRelaunch.LiveListenerPresent
    Write-Host "cycle=$cycle task=$taskId removed=$taskRemoved swipe_attempts=$swipeAttempts after_remove_notification=$($afterRemoval.NotificationPresent) after_remove_service=$($afterRemoval.ListenerServicePresent) after_remove_live_listener=$($afterRemoval.LiveListenerPresent) after_relaunch_notification=$($afterRelaunch.NotificationPresent) after_relaunch_service=$($afterRelaunch.ListenerServicePresent) after_relaunch_live_listener=$($afterRelaunch.LiveListenerPresent)"
    if (-not $taskRemovalPassed) { $taskRemovalFailures += $cycle }
    if (-not $relaunchPassed) { $relaunchFailures += $cycle }
}

if ($LeaveInBackground) {
    Invoke-Adb @("shell", "input", "keyevent", "HOME") | Out-Null
}

if ($relaunchFailures.Count -eq 0) {
    Write-Host "VAULT_RELAUNCH_GREEN: reopen restored notification, foreground service and live listener in $Cycles/$Cycles cycles"
} else {
    Write-Host "VAULT_RELAUNCH_RED: reopen failed cycles $($relaunchFailures -join ',')"
}

if ($taskRemovalFailures.Count -eq 0) {
    Write-Host "VAULT_TASK_REMOVAL_GREEN: all three runtime signals survived $Cycles/$Cycles task removals"
} else {
    Write-Host "VAULT_TASK_REMOVAL_RED: failed cycles $($taskRemovalFailures -join ',')"
}

if ($taskRemovalFailures.Count -gt 0 -or $relaunchFailures.Count -gt 0) {
    Write-Error "VAULT_P0_RED: task-removal failures=$($taskRemovalFailures -join ','); relaunch failures=$($relaunchFailures -join ',')"
    exit 1
}

Write-Host "VAULT_P0_GREEN: task removal and relaunch both passed $Cycles/$Cycles cycles"
