package com.leatherbound.listening

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.LeatherboundRoot
import com.leatherbound.listening.ui.theme.LeatherboundTheme
import com.leatherbound.listening.ui.theme.currentThemeMode
import com.leatherbound.listening.ui.theme.LocalWideLayout

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val size = calculateWindowSizeClass(this)
            LeatherboundTheme(currentThemeMode(settings)) {
                CompositionLocalProvider(LocalWideLayout provides (size.widthSizeClass != WindowWidthSizeClass.Compact)) {
                    LeatherboundRoot(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.player.connect()
        // Every time the app comes to the front; skipped if a sync ran in the last few minutes.
        container.scope.launch { container.repository.syncAll() }
        vm.checkForUpdate(manual = false)
    }

    override fun onStop() {
        super.onStop()
        container.player.disconnect()
    }
}
