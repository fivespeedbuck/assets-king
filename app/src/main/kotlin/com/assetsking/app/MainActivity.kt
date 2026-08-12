package com.assetsking.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetsking.app.ui.screen.HomeScreen
import com.assetsking.ui.theme.AssetsKingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AssetsKingTheme {
                val app = application as AssetsKingApplication
                val model: LedgerViewModel = viewModel(
                    factory = LedgerViewModel.factory(app)
                )
                HomeScreen(model = model, repository = app.repository)
            }
        }
    }
}
