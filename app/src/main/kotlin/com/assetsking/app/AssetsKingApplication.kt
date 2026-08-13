package com.assetsking.app

import android.app.Application
import com.assetsking.database.AssetsKingDatabase
import com.assetsking.database.LedgerRepository
import com.assetsking.usecase.AddAccountUseCase
import com.assetsking.usecase.GetV5MetricsUseCase
import com.assetsking.usecase.ProcessPendingUseCase
import com.assetsking.usecase.RecordTransactionUseCase
import com.assetsking.usecase.RecordTransferUseCase
import com.assetsking.usecase.SeedAccountsUseCase
import com.assetsking.usecase.SpendPatternsUseCase
import com.assetsking.usecase.UpdateCategoryUseCase

class AssetsKingApplication : Application() {
    val database by lazy { AssetsKingDatabase.get(this) }
    val repository by lazy { LedgerRepository(database, getSharedPreferences("app_prefs", MODE_PRIVATE)) }

    val seedAccounts by lazy { SeedAccountsUseCase(repository) }
    val recordTransaction by lazy { RecordTransactionUseCase(repository) }
    val recordTransfer by lazy { RecordTransferUseCase(repository) }
    val addAccount by lazy { AddAccountUseCase(repository) }
    val updateCategory by lazy { UpdateCategoryUseCase(repository) }
    val getV5Metrics by lazy { GetV5MetricsUseCase(repository) }
    val processPending by lazy { ProcessPendingUseCase(repository) }
    val spendPatterns by lazy { SpendPatternsUseCase(repository) }
}
