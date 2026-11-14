// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mrx2827.xgalerry

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal data class GalleryItem(
    val uri: Uri,
    val name: String,
    val mime: String,
    val albumKey: String,
    val albumName: String,
    val taken: Long,
    val modified: Long,
    val duration: Long,
    val width: Int,
    val height: Int,
    val bytes: Long
) {
    val key: String = uri.toString()
    val video: Boolean = mime.startsWith("video/")
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
}

internal data class Album(val key: String, val title: String, val items: List<GalleryItem>)
internal data class Day(val date: LocalDate?, val items: List<GalleryItem>)

internal data class MediaAccess(val photos: Boolean, val videos: Boolean, val selected: Boolean) {
    val allowed get() = photos || videos || selected
    val full get() = photos && videos
}

internal fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun mediaAccess(context: Context): MediaAccess {
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    if (Build.VERSION.SDK_INT < 33) {
        val granted = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        return MediaAccess(granted, granted, false)
    }
    return MediaAccess(
        photos = granted(Manifest.permission.READ_MEDIA_IMAGES),
        videos = granted(Manifest.permission.READ_MEDIA_VIDEO),
        selected = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    )
}

internal data class LibraryState(
    val access: MediaAccess,
    val items: List<GalleryItem> = emptyList(),
    val albums: List<Album> = emptyList(),
    val loading: Boolean = access.allowed,
    val failed: Boolean = false
)

internal data class GallerySettings(
    val theme: ThemeMode,
    val dynamic: Boolean,
    val favorites: Set<String>
)

internal class GalleryModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("gallery", Context.MODE_PRIVATE)
    private val resolver = application.contentResolver
    private var loadJob: Job? = null
    private val _library = MutableStateFlow(LibraryState(mediaAccess(application)))
    val library = _library.asStateFlow()
    private val _settings = MutableStateFlow(
        GallerySettings(
            theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.SYSTEM,
            dynamic = prefs.getBoolean("dynamic", true),
            favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
        )
    )
    val settings = _settings.asStateFlow()
    val thumbnails = Thumbnails(application)
    val deletion = MediaDeletion(resolver, viewModelScope, ::removeDeleted)

    @OptIn(FlowPreview::class)
    suspend fun observeLibrary() {
        callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            resolver.registerContentObserver(Uri.parse("content://media"), true, observer)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.debounce(250).collect { refresh() }
    }

    fun refresh() {
        loadJob?.cancel()
        val access = mediaAccess(getApplication())
        if (!access.allowed) {
            _library.value = LibraryState(access)
            return
        }
        if (access != _library.value.access) _library.value = LibraryState(access)
        loadJob = viewModelScope.launch {
            try {
                _library.value = withContext(Dispatchers.IO) {
                    val items = buildList {
                        if (access.photos || access.selected) addAll(readMedia(false))
                        if (access.videos || access.selected) addAll(readMedia(true))
                    }.sortedWith(compareByDescending<GalleryItem> { it.taken }.thenByDescending { it.key })
                    val albums = items.groupBy(GalleryItem::albumKey).map { (key, files) ->
                        Album(key, files.first().albumName, files)
                    }
                    LibraryState(access, items, albums, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                val current = mediaAccess(getApplication())
                _library.value = LibraryState(current, loading = false, failed = current.allowed)
            }
        }
    }

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        _settings.value = _settings.value.copy(theme = mode)
    }

    fun setDynamic(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic", enabled).apply()
        _settings.value = _settings.value.copy(dynamic = enabled)
    }

    fun toggleFavorite(item: GalleryItem) {
        toggleFavorites(listOf(item))
    }

    fun toggleFavorites(items: List<GalleryItem>) {
        val keys = items.mapTo(mutableSetOf(), GalleryItem::key)
        val favorites = _settings.value.favorites
        setFavorites(if (favorites.containsAll(keys)) favorites - keys else favorites + keys)
    }

    private fun setFavorites(favorites: Set<String>) {
        if (favorites == _settings.value.favorites) return
        prefs.edit().putStringSet("favorites", favorites).apply()
        _settings.value = _settings.value.copy(favorites = favorites)
    }

    private fun removeDeleted(keys: Set<String>) {
        val current = _library.value
        _library.value = current.copy(
            items = current.items.filterNot { it.key in keys },
            albums = current.albums.mapNotNull { album ->
                val remaining = album.items.filterNot { it.key in keys }
                if (remaining.isEmpty()) null else album.copy(items = remaining)
            }
        )
        setFavorites(_settings.value.favorites - keys)
        refresh()
    }

    private suspend fun readMedia(video: Boolean): List<GalleryItem> = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        val uri = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = buildList {
            addAll(listOf(
                "_id", "_display_name", "mime_type", "bucket_id", "bucket_display_name",
                "datetaken", "date_added", "date_modified", "width", "height", "_size", "volume_name"
            ))
            if (video) add("duration")
        }.toTypedArray()
        val files = buildList {
            resolver.query(uri, projection, null, null, null, signal)?.use { cursor ->
                val fallbackAlbum = getApplication<Application>().getString(R.string.unknown_album)
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    val id = cursor.getLong(0)
                    val volume = cursor.getString(11) ?: MediaStore.VOLUME_EXTERNAL
                    val collection = if (video) MediaStore.Video.Media.getContentUri(volume)
                    else MediaStore.Images.Media.getContentUri(volume)
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2) ?: if (video) "video/*" else "image/*"
                    val date = cursor.getLong(5).takeIf { it > 0 } ?: (cursor.getLong(6) * 1000)
                    add(GalleryItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        mime = mime,
                        albumKey = "$volume:${cursor.getString(3).orEmpty()}",
                        albumName = cursor.getString(4) ?: fallbackAlbum,
                        taken = date,
                        modified = cursor.getLong(7),
                        duration = if (video) cursor.getLong(12) else 0,
                        width = cursor.getInt(8),
                        height = cursor.getInt(9),
                        bytes = cursor.getLong(10)
                    ))
                }
            }
        }
        continuation.resume(files)
    }
}

internal class Thumbnails(context: Context) {
    private val resolver = context.contentResolver
    private val workers = Dispatchers.IO.limitedParallelism(4)
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun key(item: GalleryItem, size: Int) = "${item.key}:${item.modified}:$size"

    fun cached(item: GalleryItem, size: Int): Bitmap? = cache.get(key(item, size))

    suspend fun load(item: GalleryItem, size: Int): Bitmap? = withContext(workers) {
        cached(item, size)?.let { return@withContext it }
        val bitmap = suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            val result = try {
                resolver.loadThumbnail(item.uri, Size(size, size), signal)
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            continuation.resume(result)
        }
        currentCoroutineContext().ensureActive()
        bitmap?.also { cache.put(key(item, size), it) }
    }
}

internal fun GalleryItem.localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    taken.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

internal fun groupDays(items: List<GalleryItem>): List<Day> {
    val zone = ZoneId.systemDefault()
    return items.groupBy { it.localDate(zone) }.map { (date, files) -> Day(date, files) }
}

internal fun LocalDate.formatted(locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(this)

internal fun durationText(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}

internal fun thumbnailSize(width: Float) = if (width <= 320) 320 else 640
