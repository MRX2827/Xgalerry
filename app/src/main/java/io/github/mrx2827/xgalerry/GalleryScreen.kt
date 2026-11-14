// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mrx2827.xgalerry

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Section(val label: Int, val icon: ImageVector) {
    PHOTOS(R.string.photos, Icons.Rounded.Collections),
    ALBUMS(R.string.albums, Icons.Rounded.Folder),
    FAVORITES(R.string.favorites, Icons.Rounded.FavoriteBorder)
}

private enum class MediaFilter(val label: Int, val icon: ImageVector) {
    ALL(R.string.all, Icons.Rounded.Dashboard),
    PHOTOS(R.string.photos, Icons.Rounded.Photo),
    VIDEOS(R.string.videos, Icons.Rounded.Videocam)
}

private class TilePositions {
    val tiles = mutableMapOf<String, Rect>()
    var viewport = Rect.Zero
    fun visible(key: String): Rect? = tiles[key]?.takeIf { it.overlaps(viewport) }
}

private data class GalleryPage(val section: Section, val filter: MediaFilter, val albumKey: String?)

internal data class ViewerSession(
    val items: List<GalleryItem>, val start: Int, val source: Rect?,
    val findTile: (String) -> Rect?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryScreen(
    library: LibraryState,
    settings: GallerySettings,
    dark: Boolean,
    model: GalleryModel,
    onAccess: () -> Unit,
    canManageMedia: Boolean,
    onManageMedia: () -> Unit,
    claimMediaManagementOffer: () -> Boolean,
    onViewerVisible: (Boolean) -> Unit,
    onShare: (List<GalleryItem>) -> Unit,
    onDelete: (List<GalleryItem>) -> Unit,
    deletionBusy: Boolean
) {
    var section by rememberSaveable { mutableStateOf(Section.PHOTOS) }
    var filter by rememberSaveable { mutableStateOf(MediaFilter.ALL) }
    var albumKey by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var viewerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<ViewerSession?>(null) }
    var selection by rememberSaveable(
        stateSaver = Saver<Set<String>, ArrayList<String>>(
            save = { ArrayList(it) }, restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }
    var deleteKeys by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var permissionOfferKeys by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val availableKeys = remember(library.items) { library.items.mapTo(hashSetOf(), GalleryItem::key) }
    val album = library.albums.firstOrNull { it.key == albumKey }
    val inAlbum = section == Section.ALBUMS && album != null
    val page = GalleryPage(
        section, if (section == Section.PHOTOS) filter else MediaFilter.ALL,
        album?.key.takeIf { inAlbum }
    )
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

    LaunchedEffect(library.access.allowed) {
        if (!library.access.allowed) {
            viewerKey = null
            session = null
            selection = emptySet()
            deleteKeys = arrayListOf()
            permissionOfferKeys = arrayListOf()
        }
    }
    LaunchedEffect(availableKeys, library.loading, library.failed) {
        if (!library.loading && !library.failed) {
            selection = selection.intersect(availableKeys)
            val currentKey = viewerKey
            if (session == null && currentKey != null && currentKey !in availableKeys) {
                viewerKey = null
            }
        }
    }
    SideEffect { onViewerVisible(session != null) }

    fun closeSearch() {
        selection = emptySet()
        query = ""
        searchOpen = false
    }

    fun toggleSelection(item: GalleryItem) {
        if (deletionBusy) return
        if (selection.isEmpty()) keyboard?.hide()
        selection = if (item.key in selection) selection - item.key else selection + item.key
    }

    fun requestDeletion(items: List<GalleryItem>) {
        val current = items.filter { it.key in availableKeys }
        if (current.isEmpty() || deletionBusy) return
        val canReadAll = current.all {
            if (it.video) library.access.videos else library.access.photos
        }
        when {
            Build.VERSION.SDK_INT == 29 || (canManageMedia && canReadAll) ->
                deleteKeys = ArrayList(current.map(GalleryItem::key))
            Build.VERSION.SDK_INT >= 31 && canReadAll && claimMediaManagementOffer() -> {
                permissionOfferKeys = ArrayList(current.map(GalleryItem::key))
            }
            else -> onDelete(current)
        }
    }

    BackHandler(enabled = session == null && (selection.isNotEmpty() || searchOpen || inAlbum)) {
        when {
            selection.isNotEmpty() -> selection = emptySet()
            searchOpen -> closeSearch()
            else -> albumKey = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().then(
                if (session != null) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier
            )
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
                if (searchOpen) {
                    SearchBar(query, { selection = emptySet(); query = it }, ::closeSearch)
                } else {
                    AnimatedContent(
                        targetState = album.takeIf { inAlbum },
                        contentKey = { it?.key },
                        transitionSpec = { fadeIn(tween(175, delayMillis = 55)) togetherWith fadeOut(tween(120)) },
                        label = "album header"
                    ) { headerAlbum ->
                        GalleryHeader(
                            album = headerAlbum,
                            onBack = { selection = emptySet(); albumKey = null },
                            onSearch = { selection = emptySet(); searchOpen = true },
                            onSettings = { selection = emptySet(); settingsOpen = true }
                        )
                    }
                }
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    label = "gallery page",
                    transitionSpec = {
                        val direction = when {
                            targetState.section != initialState.section ->
                                if (targetState.section.ordinal > initialState.section.ordinal) 1 else -1
                            targetState.albumKey != initialState.albumKey ->
                                if (targetState.albumKey != null) 1 else -1
                            else -> if (targetState.filter.ordinal > initialState.filter.ordinal) 1 else -1
                        }
                        (slideInHorizontally(
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                        ) { it * direction / 5 } + fadeIn(tween(190, delayMillis = 70)))
                            .togetherWith(
                                slideOutHorizontally(tween(205, easing = FastOutSlowInEasing)) { -it * direction / 8 } +
                                    fadeOut(tween(145))
                            )
                    }
                ) { shownPage ->
                    val visibleAlbum = shownPage.albumKey?.let { key -> library.albums.firstOrNull { it.key == key } }
                    val screenKey = if (searchOpen) "search:$shownPage" else shownPage.toString()
                    val active = shownPage == page
                    stateHolder.SaveableStateProvider(screenKey) {
                        GalleryPageContent(
                            page = shownPage, album = visibleAlbum, library = library, settings = settings,
                            searchOpen = searchOpen, query = query, active = active, viewerKey = viewerKey,
                            thumbnails = model.thumbnails,
                            selection = if (active) selection else emptySet(),
                            deletionBusy = deletionBusy,
                            onToggleSelection = { if (active && session == null) toggleSelection(it) },
                            onSelectAll = { selection = it },
                            onShare = onShare,
                            onFavorites = model::toggleFavorites,
                            onDelete = ::requestDeletion,
                            onFilter = {
                                if (active) {
                                    selection = emptySet()
                                    filter = it
                                }
                            },
                            onAccess = onAccess, onRefresh = model::refresh,
                            onAlbum = { if (active) albumKey = it.key },
                            onRestore = { files, positions ->
                                if (active && session == null) {
                                    val index = files.indexOfFirst { it.key == viewerKey }
                                    if (index >= 0) session = ViewerSession(files, index, null, positions::visible)
                                }
                            },
                            onOpen = { item, files, positions ->
                                if (active && session == null) {
                                    val index = files.indexOfFirst { it.key == item.key }
                                    if (index >= 0) {
                                        viewerKey = item.key
                                        session = ViewerSession(files, index, positions.visible(item.key), positions::visible)
                                    }
                                }
                            }
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = !searchOpen && !inAlbum,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(180)) + slideInVertically(tween(210)) { it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(190)) { it / 2 }
            ) {
                GalleryNav(section, Modifier.navigationBarsPadding().padding(bottom = 9.dp)) {
                    if (section != it) {
                        selection = emptySet()
                        section = it
                        albumKey = null
                    }
                }
            }
        }
        if (settingsOpen) {
            SettingsSheet(settings, dark, model, onAccess, canManageMedia, onManageMedia) { settingsOpen = false }
        }
        session?.let { active ->
            MediaViewer(
                session = active,
                thumbnails = model.thumbnails,
                favorites = settings.favorites,
                findTile = active.findTile,
                availableKeys = availableKeys.takeUnless { library.loading || library.failed },
                onCurrent = { viewerKey = it.key },
                onFavorite = model::toggleFavorite,
                onShare = { onShare(listOf(it)) },
                onDelete = { requestDeletion(listOf(it)) },
                deletionBusy = deletionBusy,
                onClosed = {
                    viewerKey = null
                    session = null
                }
            )
        }
    }
    if (deleteKeys.isNotEmpty()) {
        val count = deleteKeys.size
        val dismiss = { deleteKeys = arrayListOf() }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(pluralStringResource(R.plurals.delete_confirmation, count, count)) },
            dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton({
                    val keys = deleteKeys.toHashSet()
                    dismiss()
                    onDelete(library.items.filter { it.key in keys })
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            }
        )
    }
    if (permissionOfferKeys.isNotEmpty()) {
        val dismiss = { permissionOfferKeys = arrayListOf() }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.media_management)) },
            text = { Text(stringResource(R.string.media_management_explanation)) },
            dismissButton = {
                TextButton({
                    val keys = permissionOfferKeys.toHashSet()
                    dismiss()
                    onDelete(library.items.filter { it.key in keys })
                }) { Text(stringResource(R.string.use_android_confirmation)) }
            },
            confirmButton = {
                TextButton({
                    dismiss()
                    onManageMedia()
                }) { Text(stringResource(R.string.open_system_settings)) }
            }
        )
    }
}

@Composable
private fun GalleryPageContent(
    page: GalleryPage,
    album: Album?,
    library: LibraryState,
    settings: GallerySettings,
    searchOpen: Boolean,
    query: String,
    active: Boolean,
    viewerKey: String?,
    thumbnails: Thumbnails,
    selection: Set<String>,
    deletionBusy: Boolean,
    onToggleSelection: (GalleryItem) -> Unit,
    onSelectAll: (Set<String>) -> Unit,
    onShare: (List<GalleryItem>) -> Unit,
    onFavorites: (List<GalleryItem>) -> Unit,
    onDelete: (List<GalleryItem>) -> Unit,
    onFilter: (MediaFilter) -> Unit,
    onAccess: () -> Unit,
    onRefresh: () -> Unit,
    onAlbum: (Album) -> Unit,
    onRestore: (List<GalleryItem>, TilePositions) -> Unit,
    onOpen: (GalleryItem, List<GalleryItem>, TilePositions) -> Unit
) {
    val positions = remember { TilePositions() }
    val showAlbums = page.section == Section.ALBUMS && album == null && !searchOpen
    val result by produceState<Pair<List<GalleryItem>, List<Day>>?>(
        null, library.items, album, page, settings.favorites, searchOpen, query
    ) {
        value = withContext(Dispatchers.Default) {
            val base = if (searchOpen) library.items else album?.items ?: library.items
            val needle = query.trim()
            val filtered = when {
                showAlbums -> emptyList()
                searchOpen && needle.isNotEmpty() -> base.filter {
                    it.name.contains(needle, true) || it.albumName.contains(needle, true)
                }
                searchOpen -> base
                page.section == Section.FAVORITES -> base.filter { it.key in settings.favorites }
                page.filter == MediaFilter.PHOTOS -> base.filterNot { it.video }
                page.filter == MediaFilter.VIDEOS -> base.filter { it.video }
                else -> base
            }
            filtered to groupDays(filtered)
        }
    }
    val files = result?.first.orEmpty()
    val selected = remember(files, selection) {
        if (selection.isEmpty()) emptyList() else files.filter { it.key in selection }
    }
    LaunchedEffect(active, files, viewerKey) {
        if (active && viewerKey != null && files.isNotEmpty()) onRestore(files, positions)
    }

    Column(
        Modifier.fillMaxSize().then(
            if (active) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
        )
    ) {
        if (!searchOpen && album == null) {
            Text(
                stringResource(page.section.label),
                Modifier.padding(start = 21.dp, end = 20.dp, top = 9.dp, bottom = 11.dp),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (!searchOpen && page.section == Section.PHOTOS && library.access.allowed) {
            FilterBar(page.filter, onFilter)
        }
        if (library.access.allowed && !library.access.full) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(enabled = active, role = Role.Button, onClick = onAccess)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.limited_access), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Rounded.ChevronRight, stringResource(R.string.manage_access))
            }
        }
        var previousCount by remember { mutableIntStateOf(0) }
        SideEffect { if (selected.isNotEmpty()) previousCount = selected.size }
        AnimatedVisibility(
            visible = selected.isNotEmpty(),
            enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(150, 40)),
            exit = shrinkVertically(tween(210), shrinkTowards = Alignment.Top) + fadeOut(tween(120))
        ) {
            val allSelected = selected.size == files.size
            val allFavorite = selected.all { it.key in settings.favorites }
            SelectionBar(
                count = selected.size.takeIf { it > 0 } ?: previousCount,
                allSelected = allSelected,
                allFavorite = allFavorite,
                enabled = active && selected.isNotEmpty() && !deletionBusy,
                onClear = { onSelectAll(emptySet()) },
                onSelectAll = { onSelectAll(if (allSelected) emptySet() else files.mapTo(hashSetOf(), GalleryItem::key)) },
                onShare = { onShare(selected) },
                onFavorites = { onFavorites(selected) },
                onDelete = { onDelete(selected) }
            )
        }
        val contentModifier = Modifier.weight(1f).fillMaxWidth()
        when {
            !library.access.allowed -> EmptyGallery(
                Icons.Rounded.Collections, R.string.allow_access_title, R.string.allow_access_body,
                contentModifier, R.string.allow_access, onAccess
            )
            library.failed -> EmptyGallery(
                Icons.Rounded.ErrorOutline, R.string.load_error, null,
                contentModifier, R.string.retry, onRefresh
            )
            library.loading -> Box(contentModifier, contentAlignment = Alignment.Center) {
                val label = stringResource(R.string.loading)
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
            showAlbums && library.albums.isEmpty() ->
                EmptyGallery(Icons.Rounded.Folder, R.string.empty_albums, null, contentModifier)
            showAlbums -> AlbumGrid(library.albums, thumbnails, contentModifier, onAlbum)
            result == null -> Spacer(contentModifier)
            else -> {
                val gridState = rememberLazyGridState()
                if (searchOpen) LaunchedEffect(query) { gridState.scrollToItem(0) }
                Box(contentModifier) {
                    MediaGrid(
                        result?.second.orEmpty(), gridState, thumbnails, positions, viewerKey,
                        selection, active && !deletionBusy, Modifier.fillMaxSize(),
                        onItem = { if (selection.isEmpty()) onOpen(it, files, positions) else onToggleSelection(it) },
                        onLongPress = onToggleSelection
                    )
                    EmptyMediaOverlay(files.isEmpty(), searchOpen, page)
                }
            }
        }
    }
}

@Composable
private fun EmptyMediaOverlay(visible: Boolean, searchOpen: Boolean, page: GalleryPage) {
    AnimatedVisibility(visible, enter = fadeIn(tween(160, 220)), exit = fadeOut(tween(100))) {
        val modifier = Modifier.fillMaxSize()
        when {
            searchOpen -> EmptyGallery(Icons.Rounded.Search, R.string.no_results, R.string.no_results_body, modifier)
            page.section == Section.FAVORITES -> EmptyGallery(
                Icons.Rounded.FavoriteBorder, R.string.empty_favorites, R.string.empty_favorites_body, modifier
            )
            page.filter != MediaFilter.ALL -> EmptyGallery(page.filter.icon, R.string.empty_filter, null, modifier)
            else -> EmptyGallery(Icons.Rounded.Collections, R.string.empty_library, null, modifier)
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    allFavorite: Boolean,
    enabled: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onFavorites: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClear, enabled = enabled) {
                Icon(Icons.Rounded.Close, stringResource(R.string.clear_selection))
            }
            Text(pluralStringResource(R.plurals.selection_count, count, count), Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            IconButton(onSelectAll, enabled = enabled) {
                Icon(if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                    stringResource(if (allSelected) R.string.clear_selection else R.string.select_all))
            }
            IconButton(onFavorites, enabled = enabled) {
                Icon(if (allFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(if (allFavorite) R.string.remove_favorite else R.string.add_favorite))
            }
            IconButton(onShare, enabled = enabled) {
                Icon(Icons.Rounded.Share, stringResource(R.string.share))
            }
            FilledTonalIconButton(
                onDelete, enabled = enabled,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = colors.errorContainer, contentColor = colors.onErrorContainer
                )
            ) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun GalleryHeader(album: Album?, onBack: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (album != null) {
            FilledTonalIconButton(onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                Text(album.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.media_count, album.items.size, album.items.size),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        } else {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(13.dp)).background(colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.xgalerry_icon), null, Modifier.fillMaxSize())
            }
            Text(
                stringResource(R.string.app_name), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface
            )
        }
        FilledTonalIconButton(onSearch) {
            Icon(Icons.Rounded.Search, stringResource(R.string.search))
        }
        FilledTonalIconButton(onSettings) {
            Icon(Icons.Rounded.Settings, stringResource(R.string.settings))
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        TextField(
            value = query, onValueChange = onQuery, modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true, shape = CircleShape,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = if (query.isEmpty()) null else ({
                IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear)) }
            }),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun FilterBar(filter: MediaFilter, onFilter: (MediaFilter) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 17.dp, end = 17.dp, bottom = 8.dp)
            .clip(CircleShape).background(colors.surfaceContainerLow).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        MediaFilter.entries.forEach { option ->
            val active = filter == option
            val radius by animateDpAsState(if (active) 23.dp else 14.dp, spring(), label = "filter shape")
            Row(
                Modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(radius))
                    .background(if (active) colors.primaryContainer else Color.Transparent)
                    .semantics { selected = active }
                    .clickable(role = Role.Tab) { onFilter(option) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant
                Icon(option.icon, null, Modifier.size(17.dp), tint = color)
                Text(stringResource(option.label), color = color, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun GalleryNav(section: Section, modifier: Modifier, onSection: (Section) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 14.dp),
        shape = RoundedCornerShape(32.dp), color = colors.surfaceContainerHigh, shadowElevation = 7.dp
    ) {
        Row(Modifier.padding(horizontal = 5.dp, vertical = 5.dp)) {
            Section.entries.forEach { item ->
                val active = section == item
                val indicatorWidth by animateDpAsState(if (active) 66.dp else 40.dp, spring(), label = "tab width")
                val indicatorColor by animateColorAsState(
                    if (active) colors.primaryContainer else Color.Transparent, tween(220), label = "tab color"
                )
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(27.dp))
                        .semantics { selected = active }
                        .clickable(role = Role.Tab) { onSection(item) }
                        .padding(top = 4.dp, bottom = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Box(
                        Modifier.width(indicatorWidth).height(30.dp).clip(CircleShape)
                            .background(indicatorColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (item == Section.FAVORITES && active) Icons.Rounded.Favorite else item.icon,
                            null, Modifier.size(21.dp), tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant
                        )
                    }
                    Text(
                        stringResource(item.label), style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, color = colors.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(
    days: List<Day>,
    state: LazyGridState,
    thumbnails: Thumbnails,
    positions: TilePositions,
    hiddenKey: String?,
    selection: Set<String>,
    enabled: Boolean,
    modifier: Modifier,
    onItem: (GalleryItem) -> Unit,
    onLongPress: (GalleryItem) -> Unit
) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val today = remember(days) { LocalDate.now() }
    val columns = (configuration.screenWidthDp / 116).coerceIn(3, 8)
    val size = thumbnailSize(with(LocalDensity.current) { (configuration.screenWidthDp.dp / columns).toPx() })
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val titles = remember(days, locale) { days.associate { it.date to it.date?.formatted(locale) } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns), state = state,
        modifier = modifier.clipToBounds().onGloballyPositioned { coords ->
            positions.viewport = Rect(coords.positionInRoot(), Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 82.dp + navBottom)
    ) {
        days.forEach { day ->
            item(key = "day-${day.date}", span = { GridItemSpan(maxLineSpan) }, contentType = "date") {
                val title = when (day.date) {
                    today -> stringResource(R.string.today)
                    today.minusDays(1) -> stringResource(R.string.yesterday)
                    null -> stringResource(R.string.unknown_date)
                    else -> titles[day.date].orEmpty()
                }
                Row(
                    Modifier.animateItem(fadeInSpec = null, fadeOutSpec = tween(170))
                        .fillMaxWidth().padding(start = 7.dp, end = 7.dp, top = 13.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(pluralStringResource(R.plurals.media_count, day.items.size, day.items.size),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(day.items, key = { it.key }, contentType = { "media" }) { item ->
                DisposableEffect(item.key) { onDispose { positions.tiles.remove(item.key) } }
                val accessibleName = item.name.ifBlank { stringResource(if (item.video) R.string.videos else R.string.photos) }
                val checked = item.key in selection
                val selecting = selection.isNotEmpty()
                val shape = RoundedCornerShape(14.dp)
                val colors = MaterialTheme.colorScheme
                val selectionScale = animateFloatAsState(
                    if (checked) 0.91f else 1f,
                    spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
                    label = "selection scale"
                )
                val badgeColor by animateColorAsState(
                    if (checked) colors.primary else Color.Black.copy(alpha = 0.55f),
                    tween(155), label = "selection badge color"
                )
                Box(
                    Modifier.animateItem(
                        fadeInSpec = null,
                        placementSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                        fadeOutSpec = tween(235, easing = FastOutSlowInEasing)
                    ).aspectRatio(1f).onGloballyPositioned { coords ->
                        positions.tiles[item.key] = Rect(coords.positionInRoot(), Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
                    }.graphicsLayer { alpha = if (hiddenKey == item.key) 0f else 1f }
                        .clip(shape)
                        .background(colors.surfaceContainerHigh)
                        .semantics {
                            contentDescription = accessibleName
                            if (selecting) selected = checked
                        }
                        .combinedClickable(
                            enabled = enabled,
                            role = if (selecting) Role.Checkbox else Role.Button,
                            onLongClickLabel = stringResource(R.string.select_item),
                            onLongClick = { onLongPress(item) },
                            onClick = { onItem(item) }
                        )
                ) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = selectionScale.value
                            scaleY = selectionScale.value
                        }.clip(shape)
                    ) {
                        MediaThumbnail(item, thumbnails, size, Modifier.fillMaxSize())
                        if (item.video) VideoBadge(item.duration, Modifier.align(Alignment.BottomEnd).padding(5.dp))
                        AnimatedVisibility(checked, Modifier.matchParentSize(), enter = fadeIn(tween(150)), exit = fadeOut(tween(135))) {
                            Box(Modifier.fillMaxSize().background(colors.primary.copy(alpha = 0.20f))
                                .border(3.dp, colors.primary, shape))
                        }
                    }
                    AnimatedVisibility(
                        selecting, Modifier.align(Alignment.TopEnd).padding(7.dp),
                        enter = springBadgeEnter(), exit = scaleOut(tween(135), targetScale = 0.75f) + fadeOut(tween(120))
                    ) {
                        Box(
                            Modifier.size(25.dp).clip(CircleShape).background(badgeColor)
                                .border(1.dp, if (checked) colors.primary else Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                checked, enter = springBadgeEnter(), exit = scaleOut(tween(120), targetScale = 0.65f) + fadeOut(tween(110))
                            ) { Icon(Icons.Rounded.Check, null, Modifier.size(17.dp), tint = colors.onPrimary) }
                        }
                    }
                }
            }
        }
    }
}

private fun springBadgeEnter() = scaleIn(
    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    initialScale = 0.65f
) + fadeIn(tween(150))

@Composable
private fun AlbumGrid(albums: List<Album>, thumbnails: Thumbnails, modifier: Modifier, onAlbum: (Album) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        GridCells.Adaptive(156.dp), modifier,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 86.dp + navBottom),
        horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        items(albums, key = { it.key }) { album ->
            Column(
                Modifier.clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainer)
                    .clickable(role = Role.Button) { onAlbum(album) }
            ) {
                MediaThumbnail(album.items.first(), thumbnails, 640, Modifier.fillMaxWidth().aspectRatio(1.18f))
                Text(album.title, Modifier.padding(start = 13.dp, end = 13.dp, top = 10.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.media_count, album.items.size, album.items.size),
                    Modifier.padding(start = 13.dp, end = 13.dp, top = 2.dp, bottom = 12.dp),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun rememberThumbnail(item: GalleryItem, thumbnails: Thumbnails, size: Int): Bitmap? {
    var bitmap by remember(item.key, item.modified, size) { mutableStateOf(thumbnails.cached(item, size)) }
    LaunchedEffect(item.key, item.modified, size) {
        if (bitmap == null) bitmap = thumbnails.load(item, size)
    }
    return bitmap
}

@Composable
private fun MediaThumbnail(item: GalleryItem, thumbnails: Thumbnails, size: Int, modifier: Modifier) {
    val bitmap = rememberThumbnail(item, thumbnails, size)
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image != null) {
        Image(image, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(if (item.video) Icons.Rounded.Videocam else Icons.Rounded.Image, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun VideoBadge(duration: Long, modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(Color(0xD9191D19)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(12.dp), tint = Color.White)
        Text(durationText(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyGallery(
    icon: ImageVector, title: Int, body: Int?, modifier: Modifier, action: Int? = null, onAction: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.padding(start = 24.dp, end = 24.dp, bottom = 70.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(28.dp)).background(colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, Modifier.size(34.dp), tint = colors.onPrimaryContainer) }
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (body != null) Text(stringResource(body), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (action != null) Button(onAction, Modifier.padding(top = 3.dp)) { Text(stringResource(action)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: GallerySettings, dark: Boolean, model: GalleryModel, onAccess: () -> Unit,
    canManageMedia: Boolean, onManageMedia: () -> Unit, close: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var explainManagement by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = colors.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold)
                FilledTonalIconButton(close) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
            }
            Text(stringResource(R.string.appearance), color = colors.primary, style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.theme == mode,
                        onClick = { model.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        icon = {}
                    ) { Text(stringResource(mode.title), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                SettingsRow(
                    Icons.Rounded.Palette, R.string.dynamic_color,
                    Modifier.toggleable(settings.dynamic, role = Role.Switch, onValueChange = model::setDynamic)
                ) {
                    Switch(checked = settings.dynamic, onCheckedChange = null)
                }
            }
            SettingsRow(Icons.Rounded.PhotoLibrary, R.string.media_access, Modifier.clickable(role = Role.Button, onClick = onAccess)) {
                Icon(Icons.Rounded.ChevronRight, null, tint = colors.onSurfaceVariant)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                SettingsRow(Icons.Rounded.DeleteOutline, R.string.media_management,
                    Modifier.clickable(role = Role.Button) { explainManagement = true }) {
                    if (canManageMedia) Icon(Icons.Rounded.Check, stringResource(R.string.media_management_enabled), tint = colors.primary)
                    else Icon(Icons.Rounded.ChevronRight, null, tint = colors.onSurfaceVariant)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 3.dp, end = 3.dp, top = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.developer), color = if (dark) Color(0xFFFF8A84) else Color(0xFFAD202B),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (explainManagement) {
        AlertDialog(
            onDismissRequest = { explainManagement = false },
            title = { Text(stringResource(R.string.media_management)) },
            text = { Text(stringResource(R.string.media_management_explanation)) },
            dismissButton = { TextButton({ explainManagement = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton({
                    explainManagement = false
                    onManageMedia()
                }) { Text(stringResource(R.string.open_system_settings)) }
            }
        )
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: Int, interaction: Modifier, end: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 55.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).then(interaction)
            .padding(horizontal = 13.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        end()
    }
}
