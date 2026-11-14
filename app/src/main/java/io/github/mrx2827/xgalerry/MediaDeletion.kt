// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mrx2827.xgalerry

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MediaDeletion(
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
    private val onDeleted: (Set<String>) -> Unit
) {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _prompt = MutableStateFlow<PendingIntent?>(null)
    val prompt = _prompt.asStateFlow()
    private val _errors = MutableStateFlow(0)
    val errors = _errors.asStateFlow()

    private val queued = ArrayDeque<Uri>()
    private var awaitingConfirmation = emptyList<Uri>()

    fun start(items: List<GalleryItem>) {
        if (_busy.value || items.isEmpty()) return
        queued.addAll(items.map(GalleryItem::uri).distinct())
        _busy.value = true
        advance()
    }

    fun takePrompt(): PendingIntent? {
        val current = _prompt.value
        _prompt.value = null
        return current
    }

    fun confirm(approved: Boolean) {
        val batch = awaitingConfirmation
        awaitingConfirmation = emptyList()
        if (batch.isEmpty()) return
        if (!approved) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            onDeleted(batch.mapTo(mutableSetOf(), Uri::toString))
            advance()
        } else {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { resolver.delete(batch.single(), null, null) }
                    onDeleted(setOf(batch.single().toString()))
                    advance()
                } catch (_: RuntimeException) {
                    fail()
                }
            }
        }
    }

    fun fail() {
        finish()
        _errors.value++
    }

    private fun advance() {
        if (queued.isEmpty()) {
            finish()
            return
        }
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    // Keep the request below the MediaStore limit and comfortably within binder size.
                    val batch = List(minOf(queued.size, 500)) { queued.removeFirst() }
                    val request = withContext(Dispatchers.IO) { MediaStore.createDeleteRequest(resolver, batch) }
                    awaitingConfirmation = batch
                    _prompt.value = request
                } else {
                    deleteOnAndroid10()
                }
            } catch (_: RuntimeException) {
                fail()
            }
        }
    }

    private suspend fun deleteOnAndroid10() {
        val deleted = mutableSetOf<String>()
        try {
            while (queued.isNotEmpty()) {
                val uri = queued.removeFirst()
                try {
                    withContext(Dispatchers.IO) { resolver.delete(uri, null, null) }
                    deleted.add(uri.toString())
                } catch (e: RecoverableSecurityException) {
                    awaitingConfirmation = listOf(uri)
                    _prompt.value = e.userAction.actionIntent
                    return
                }
            }
            finish()
        } finally {
            if (deleted.isNotEmpty()) onDeleted(deleted)
        }
    }

    private fun finish() {
        queued.clear()
        awaitingConfirmation = emptyList()
        _prompt.value = null
        _busy.value = false
    }
}
