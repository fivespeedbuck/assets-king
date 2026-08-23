[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$databasePackage = "com.assetsking.database.test"
$databaseRunner = "$databasePackage/androidx.test.runner.AndroidJUnitRunner"
$databaseClass = "com.assetsking.database.LedgerRepositoryIntegrationTest"
$migrationClass = "com.assetsking.database.CardInstallmentMigrationTest"
$pendingPackage = "com.assetsking.usecase.test"
$pendingRunner = "$pendingPackage/androidx.test.runner.AndroidJUnitRunner"
$pendingClass = "com.assetsking.usecase.ProcessPendingIntegrationTest"
$databaseTests = @(
    "notificationConfirmationIsIdempotent",
    "loanPaymentNotificationIsAtomicIdempotentAndReversible",
    "duplicateRawNotificationDoesNotAdvanceLastReceivedAt",
    "mergedTransferConfirmationIsIdempotent",
    "singleLegWithdrawalCreatesTransferAndFeeExactlyOnce",
    "overdueRecurringRuleDoesNotCreateAConfirmedTransaction",
    "recurringRuleAdvancesOnlyAfterClaimingARealTransaction",
    "recurringRuleAdvancesWhenIncomingChargeWasAlreadyAutoLinked",
    "ambiguousRecurringRulesDoNotAutoClaimAnIncomingExpense",
    "editingTransactionPersistsPaymentChannel",
    "editingExpenseCanRemoveOutstandingMarkWithoutErasingPaidAudit",
    "repeatedReimbursementIsCappedAtRemainingEligibleAmount",
    "oneArrivalCanSettleOldExpensesAndDeletionRestoresTheirPendingState",
    "migrationCreatesANewSnapshotBeforeClearingOldFlows",
    "failedMigrationBackupKeepsExistingFlowsUntouched",
    "migrationPreservesAccountsLoanPlansAndCardInstallments",
    "wrongRestorePinCannotReplaceTheCurrentDatabase",
    "archiveZeroBalanceAccountKeepsAccountAndTransactionHistory",
    "archiveRejectsAccountWithRemainingBalance",
    "postPurchaseInstallmentKeepsOriginalExpenseAndCardDebtWhileCreatingAuditTrail",
    "statementInstallmentUsesMultipleOriginalPurchasesAndCannotExceedCurrentStatement",
    "allocatedCardExpenseCannotBeEditedOrDeletedOutsideInstallmentWorkflow",
    "paymentOnlyInstallmentStoresUnknownForecastChargeWithoutPostingInterestOrExpense",
    "realCardPaymentAutoMatchesUniqueScheduleWithoutTurningForecastChargeIntoExpense",
    "unmatchedCardPaymentRemainsARealTransferWithoutChangingInstallmentProgress",
    "repeatingAutoMatchForTheSameTransferIsIdempotent",
    "ambiguousCardPaymentStaysPendingWithoutAdvancingEitherPlan",
    "confirmingAmbiguousCardPaymentAdvancesOnePlanAndRejectsTheOtherCandidate",
    "pendingCardPaymentMustBeResolvedBeforeTermsChangeOrCancellation",
    "deletingMatchedTransferRestoresInstallmentProgressAndKeepsAuditHistory",
    "cancellingInstallmentReleasesCapacityWithoutDeletingAllocationOrAudit",
    "adjustingInstallmentAppendsRevisionAndPreservesCancelledForecastRows"
)
$migrationTests = @(
    "version22LegacyPreviewMigratesWithoutGuessingExpenseLinksOrChangingAmounts",
    "version23PlansGainNullableStatementCycleWithoutChangingExistingPlans"
)
$pendingTests = @(
    "learnedMerchantOnlyPrefillsAndNeverAutoPosts",
    "reportedBankBalanceDoesNotChangeAccountBeforeConfirmation",
    "ignoredEvidenceRemainsAPermanentTombstoneAfterEightDays",
    "guangfaStatementUpdatesBillStateOnceAndNeverCreatesATransaction"
)

if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "ADB not found: $AdbPath"
}

$deviceState = (& $AdbPath -s $Serial get-state 2>$null | Out-String).Trim()
if ($deviceState -ne "device") {
    & $AdbPath connect $Serial | Out-Host
    $deviceState = (& $AdbPath -s $Serial get-state 2>$null | Out-String).Trim()
}
if ($deviceState -ne "device") {
    throw "Device is not ready: $Serial"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$testApks = @(
    "$repoRoot\.build-output\core-database\outputs\apk\androidTest\debug\core-database-debug-androidTest.apk",
    "$repoRoot\core-usecase\build\outputs\apk\androidTest\debug\core-usecase-debug-androidTest.apk"
)
foreach ($testApk in $testApks) {
    if (-not (Test-Path -LiteralPath $testApk)) {
        throw "Test APK not found: $testApk"
    }
    $installOutput = (& $AdbPath -s $Serial install -r -t $testApk 2>&1 | Out-String)
    $installOutput.TrimEnd() | Write-Host
    if ($LASTEXITCODE -ne 0 -or $installOutput -notmatch "Success") {
        throw "Test APK install failed: $testApk"
    }
}

function Invoke-InstrumentationTest {
    param(
        [string]$Package,
        [string]$Runner,
        [string]$ClassName,
        [string]$ExpectedSummary
    )

    & $AdbPath -s $Serial shell am force-stop $Package | Out-Null
    $remainingPid = $null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $remainingPid = (& $AdbPath -s $Serial shell pidof $Package 2>$null | Out-String).Trim()
        if ([string]::IsNullOrWhiteSpace($remainingPid)) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not [string]::IsNullOrWhiteSpace($remainingPid)) {
        throw "Previous instrumentation process did not stop: $Package ($remainingPid)"
    }
    # OriginOS reports force-stop before its process bookkeeping is fully settled.
    # A short quiet window prevents a subsequent Room case from inheriting the old runner.
    Start-Sleep -Milliseconds 500
    $output = (& $AdbPath -s $Serial shell am instrument -w -r -e class $ClassName $Runner 2>&1 | Out-String)
    $output.TrimEnd() | Write-Host
    if ($LASTEXITCODE -ne 0 -or $output -notmatch [regex]::Escape($ExpectedSummary)) {
        throw "Instrumentation failed: $ClassName"
    }
}

# OriginOS may hang when these classes run continuously. Each case gets a fresh
# instrumentation process so a runner leak cannot hide a product failure.
foreach ($test in $databaseTests) {
    Invoke-InstrumentationTest `
        -Package $databasePackage `
        -Runner $databaseRunner `
        -ClassName "$databaseClass#$test" `
        -ExpectedSummary "OK (1 test)"
}

foreach ($test in $migrationTests) {
    Invoke-InstrumentationTest `
        -Package $databasePackage `
        -Runner $databaseRunner `
        -ClassName "$migrationClass#$test" `
        -ExpectedSummary "OK (1 test)"
}

foreach ($test in $pendingTests) {
    Invoke-InstrumentationTest `
        -Package $pendingPackage `
        -Runner $pendingRunner `
        -ClassName "$pendingClass#$test" `
        -ExpectedSummary "OK (1 test)"
}

Write-Host "iQOO regression passed: $($databaseTests.Count) ledger cases + $($migrationTests.Count) migration case(s) + $($pendingTests.Count) pending cases."
