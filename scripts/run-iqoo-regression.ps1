[CmdletBinding()]
param(
    [string]$Serial = "192.168.31.210:40223",
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$databasePackage = "com.assetsking.database.test"
$databaseRunner = "$databasePackage/androidx.test.runner.AndroidJUnitRunner"
$databaseClass = "com.assetsking.database.LedgerRepositoryIntegrationTest"
$databaseTests = @(
    "notificationConfirmationIsIdempotent",
    "mergedTransferConfirmationIsIdempotent",
    "overdueRecurringRuleDoesNotCreateAConfirmedTransaction",
    "recurringRuleAdvancesOnlyAfterClaimingARealTransaction",
    "repeatedReimbursementIsCappedAtRemainingEligibleAmount",
    "migrationCreatesANewSnapshotBeforeClearingOldFlows",
    "failedMigrationBackupKeepsExistingFlowsUntouched",
    "migrationPreservesAccountsLoanPlansAndCardInstallments",
    "wrongRestorePinCannotReplaceTheCurrentDatabase"
)

if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "ADB not found: $AdbPath"
}

& $AdbPath connect $Serial | Out-Host
if ((& $AdbPath -s $Serial get-state) -ne "device") {
    throw "Device is not ready: $Serial"
}

function Invoke-InstrumentationTest {
    param(
        [string]$Package,
        [string]$Runner,
        [string]$ClassName,
        [string]$ExpectedSummary
    )

    & $AdbPath -s $Serial shell am force-stop $Package | Out-Null
    $output = (& $AdbPath -s $Serial shell am instrument -w -r -e class $ClassName $Runner 2>&1 | Out-String)
    $output.TrimEnd() | Write-Host
    if ($LASTEXITCODE -ne 0 -or $output -notmatch [regex]::Escape($ExpectedSummary)) {
        throw "Instrumentation failed: $ClassName"
    }
}

# OriginOS may hang when this Room class runs continuously. Each case gets a
# fresh instrumentation process so a runner leak cannot hide a product failure.
foreach ($test in $databaseTests) {
    Invoke-InstrumentationTest `
        -Package $databasePackage `
        -Runner $databaseRunner `
        -ClassName "$databaseClass#$test" `
        -ExpectedSummary "OK (1 test)"
}

Invoke-InstrumentationTest `
    -Package "com.assetsking.usecase.test" `
    -Runner "com.assetsking.usecase.test/androidx.test.runner.AndroidJUnitRunner" `
    -ClassName "com.assetsking.usecase.ProcessPendingIntegrationTest" `
    -ExpectedSummary "OK (3 tests)"

Write-Host "iQOO regression passed: 9 database cases + 3 pending cases."
