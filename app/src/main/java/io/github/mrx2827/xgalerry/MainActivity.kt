// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mrx2827.xgalerry

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var refreshLibrary: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        setContent {
            val model: GalleryModel = viewModel()
            val library by model.library.collectAsStateWithLifecycle()
            val settings by model.settings.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { model.refresh() }
            val requestAccess = {
                val permissions = mediaPermissions()
                val asked = getPreferences(MODE_PRIVATE).getBoolean("requested_media", false)
                val permanentlyDenied = asked && !library.access.allowed &&
                    permissions.none(::shouldShowRequestPermissionRationale)
                if (permanentlyDenied || library.access.full || (library.access.allowed && !library.access.selected)) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } else {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("requested_media", true).apply()
                    permissionLauncher.launch(permissions)
                }
            }

            LaunchedEffect(model) {
                refreshLibrary = model::refresh
                model.refresh()
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeLibrary() }
                }
            }

            GalleryTheme(settings.theme, settings.dynamic) { dark ->
                var viewerVisible by remember { mutableStateOf(false) }
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark && !viewerVisible
                        isAppearanceLightNavigationBars = !dark && !viewerVisible
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    GalleryScreen(
                        library = library,
                        settings = settings,
                        dark = dark,
                        model = model,
                        onAccess = requestAccess,
                        onViewerVisible = { viewerVisible = it },
                        onShare = ::share
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLibrary?.invoke()
    }

    private fun share(item: GalleryItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            clipData = android.content.ClipData.newUri(contentResolver, item.name, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
        }
    }
}
