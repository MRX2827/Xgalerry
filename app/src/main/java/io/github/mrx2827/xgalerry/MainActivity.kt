// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mrx2827.xgalerry

import android.app.Activity
import android.content.ClipData
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
import androidx.activity.viewModels
import androidx.activity.result.IntentSenderRequest
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
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : ComponentActivity() {
    private val model: GalleryModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        setContent {
            val library by model.library.collectAsStateWithLifecycle()
            val settings by model.settings.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { model.refresh() }
            val deleteLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true) {
                    model.deletion.fail()
                } else {
                    model.deletion.confirm(result.resultCode == Activity.RESULT_OK)
                }
                model.refresh()
            }
            val deletePrompt by model.deletion.prompt.collectAsStateWithLifecycle()
            val deletionBusy by model.deletion.busy.collectAsStateWithLifecycle()
            val deletionErrors by model.deletion.errors.collectAsStateWithLifecycle()
            var displayedDeletionErrors by androidx.compose.runtime.saveable.rememberSaveable {
                androidx.compose.runtime.mutableIntStateOf(deletionErrors)
            }
            LaunchedEffect(deletePrompt) {
                model.deletion.takePrompt()?.let { pending ->
                    try {
                        deleteLauncher.launch(IntentSenderRequest.Builder(pending).build())
                    } catch (_: RuntimeException) {
                        model.deletion.fail()
                        model.refresh()
                    }
                }
            }
            LaunchedEffect(deletionErrors) {
                if (deletionErrors != displayedDeletionErrors) {
                    displayedDeletionErrors = deletionErrors
                    Toast.makeText(this@MainActivity, R.string.delete_error, Toast.LENGTH_LONG).show()
                }
            }
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
                repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeLibrary() }
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
                        onShare = ::share,
                        onDelete = model.deletion::start,
                        deletionBusy = deletionBusy
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    private fun share(items: List<GalleryItem>) {
        val first = items.firstOrNull() ?: return
        val single = items.size == 1
        val mime = when {
            items.all { it.mime == first.mime } -> first.mime
            items.all { it.video } -> "video/*"
            items.none { it.video } -> "image/*"
            else -> "*/*"
        }
        val intent = Intent(if (single) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            if (single) putExtra(Intent.EXTRA_STREAM, first.uri)
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map(GalleryItem::uri)))
            clipData = ClipData.newUri(contentResolver, first.name, first.uri).also { clip ->
                items.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
        }
    }
}
