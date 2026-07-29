package com.assetsking.app

import android.app.Application
import com.assetsking.database.AssetsKingDatabase
import com.assetsking.database.LedgerRepository

class AssetsKingApplication : Application() {
    val database by lazy { AssetsKingDatabase.get(this) }
    val repository by lazy { LedgerRepository(database) }
}
