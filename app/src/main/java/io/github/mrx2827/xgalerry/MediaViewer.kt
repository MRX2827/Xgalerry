// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mrx2827.xgalerry

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

internal val ViewerAccent = Color(0xFFD8F2AC)
internal val ViewerControl = Color(0xE82A302B)
private val ViewerBackground = Color(0xFF0C100D)

@Composable
internal fun MediaViewer(
    session: ViewerSession,
    thumbnails: Thumbnails,
    favorites: Set<String>,
    findTile: (String) -> Rect?,
    availableKeys: Set<String>?,
    onCurrent: (GalleryItem) -> Unit,
    onFavorite: (GalleryItem) -> Unit,
    onShare: (GalleryItem) -> Unit,
    onDelete: (GalleryItem) -> Unit,
    deletionBusy: Boolean,
    onClosed: () -> Unit
) {
    val pager = rememberPagerState(initialPage = session.start) { session.items.size }
    val current = session.items[pager.currentPage]
    var source by remember { mutableStateOf(session.source) }
    var closing by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(true) }
    var details by rememberSaveable { mutableStateOf(false) }
    var zoomOwner by remember { mutableStateOf<String?>(null) }
    var verticalDrag by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var returnJob by remember { mutableStateOf<Job?>(null) }
    val opening = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val closeCallback by rememberUpdatedState(onClosed)
    val configuration = LocalConfiguration.current
    val thumbSize = thumbnailSize(with(density) {
        (configuration.screenWidthDp.dp / (configuration.screenWidthDp / 116).coerceIn(3, 8)).toPx()
    })
    fun aspectFor(item: GalleryItem): Float {
        val bitmap = thumbnails.cached(item, thumbSize)
        return if (bitmap != null) bitmap.width.toFloat() / bitmap.height else item.aspect
    }
    var motionAspect by remember { mutableFloatStateOf(aspectFor(session.items[session.start])) }

    fun close() {
        if (closing) return
        returnJob?.cancel()
        source = findTile(current.key)
        motionAspect = aspectFor(current)
        closing = true
    }

    BackHandler { if (details) details = false else close() }

    LaunchedEffect(current.key, availableKeys) {
        if (availableKeys != null && current.key !in availableKeys && !closing) {
            returnJob?.cancel()
            details = false
            source = null
            removed = true
            closing = true
        }
    }
    LaunchedEffect(closing) {
        if (closing) {
            opening.animateTo(0f, if (removed) tween(235) else viewerSpring)
            closeCallback()
        } else {
            opening.animateTo(1f, viewerSpring)
        }
    }
    LaunchedEffect(current.key) {
        onCurrent(current)
    }
    val opened by remember { derivedStateOf { opening.value >= 0.94f && !closing } }
    val showChrome by remember { derivedStateOf { opening.value >= 0.82f && !closing } }
    var retainVideo by remember { mutableStateOf(false) }
    LaunchedEffect(opened) { if (opened) retainVideo = true }
    val zoomed = zoomOwner == current.key

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val minVelocity = with(density) { DismissVelocityDp.dp.toPx() }
        val chromeVisible = showChrome && controls && !verticalDrag
        val dragFraction = { (drag / height).coerceIn(0f, 1f) }

        Canvas(Modifier.fillMaxSize()) {
            val opacity = opening.value * (1f - dragFraction() * 0.84f)
            drawRect(ViewerBackground, alpha = opacity.coerceIn(0f, 1f))
        }

        Box(
            Modifier.fillMaxSize().drawWithContent {
                val progress = opening.value
                val origin = source
                if (origin == null || progress >= 0.999f) {
                    drawContent()
                } else {
                    val destination = draggedViewport(size, drag)
                    val frame = lerp(origin, destination, progress)
                    val rounding = CornerRadius(14.dp.toPx() * cornerRetention(progress))
                    val path = Path().apply { addRoundRect(RoundRect(frame, rounding)) }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }
            }
        ) {
            HorizontalPager(
                state = pager,
                key = { session.items[it].key },
                beyondViewportPageCount = 1,
                userScrollEnabled = opened && !zoomed && !verticalDrag,
                modifier = Modifier.fillMaxSize().draggable(
                    state = rememberDraggableState { dy ->
                        drag = (drag + dy).coerceIn(0f, height)
                    },
                    orientation = Orientation.Vertical,
                    enabled = opened && !zoomed && !pager.isScrollInProgress,
                    onDragStarted = {
                        returnJob?.cancel()
                        verticalDrag = true
                    },
                    onDragStopped = { velocity ->
                        verticalDrag = false
                        if (drag > height * DismissFraction || (drag > 0f && velocity > minVelocity)) {
                            close()
                        } else {
                            returnJob = scope.launch {
                                animate(drag, 0f, animationSpec = viewerSpring) { value, _ -> drag = value }
                            }
                        }
                    }
                ).graphicsLayer {
                    val progress = opening.value
                    val origin = source
                    val destination = draggedViewport(size, drag)
                    val endScale = destination.width / size.width
                    if (origin == null) {
                        alpha = progress
                        val scale = lerp(if (removed) 0.85f else 0.91f, endScale, progress)
                        scaleX = scale
                        scaleY = scale
                        translationX = 0f
                        val exitOffset = if (removed) 0.035f else 0.18f
                        translationY = drag * progress + (1f - progress) * size.height * exitOffset
                    } else {
                        alpha = 1f
                        val fittedWidth = min(size.width, size.height * motionAspect).coerceAtLeast(1f)
                        val fittedHeight = min(size.height, size.width / motionAspect).coerceAtLeast(1f)
                        val startScale = max(origin.width / fittedWidth, origin.height / fittedHeight)
                        val scale = lerp(startScale, endScale, progress)
                        scaleX = scale
                        scaleY = scale
                        val center = lerp(origin.center, destination.center, progress)
                        translationX = center.x - size.width / 2f
                        translationY = center.y - size.height / 2f
                    }
                }
            ) { page ->
                val item = session.items[page]
                val toggleControls = { controls = !controls }
                if (item.video) {
                    VideoPage(
                        item = item,
                        thumbnails = thumbnails,
                        thumbSize = thumbSize,
                        active = page == pager.settledPage && (opened || closing && retainVideo),
                        closing = closing,
                        controls = chromeVisible && page == pager.currentPage && !pager.isScrollInProgress,
                        onTap = toggleControls
                    )
                } else {
                    PhotoPage(
                        item = item, thumbnails = thumbnails, thumbSize = thumbSize,
                        active = page == pager.currentPage, onTap = toggleControls,
                        expansion = { opening.value },
                        onZoom = { zoomedIn ->
                            if (zoomedIn) zoomOwner = item.key else if (zoomOwner == item.key) zoomOwner = null
                        }
                    )
                }
            }
        }

        AnimatedVisibility(chromeVisible, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(current, pager.currentPage + 1, session.items.size, ::close) { details = true }
        }
        AnimatedVisibility(chromeVisible, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            ViewerActions(
                current.key in favorites, { onFavorite(current) }, { onShare(current) },
                { onDelete(current) }, deletionBusy
            )
        }
    }

    if (details) DetailsDialog(current) { details = false }
}

private fun draggedViewport(size: Size, drag: Float): Rect {
    val shrink = (drag / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f) * 0.22f
    val inset = Offset(size.width * shrink / 2f, size.height * shrink / 2f)
    return Rect(inset, Size(size.width * (1f - shrink), size.height * (1f - shrink)))
        .translate(Offset(0f, drag))
}

@Composable
private fun ViewerTopBar(item: GalleryItem, index: Int, count: Int, onClose: () -> Unit, onDetails: () -> Unit) {
    val gradient = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xDF0C100D), Color.Transparent))
    Row(
        Modifier.fillMaxWidth().background(gradient).statusBarsPadding().displayCutoutPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        ViewerButton(Icons.Rounded.Close, R.string.close, onClose)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                item.name, color = Color.White, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (count > 1) Text(
                stringResource(R.string.media_position, index, count),
                color = Color(0xFFD0D8CF), style = MaterialTheme.typography.labelSmall
            )
        }
        ViewerButton(Icons.Rounded.Info, R.string.details, onDetails)
    }
}

@Composable
private fun ViewerActions(
    favorite: Boolean, onFavorite: () -> Unit, onShare: () -> Unit,
    onDelete: () -> Unit, deletionBusy: Boolean
) {
    Row(
        Modifier.navigationBarsPadding().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        ViewerButton(
            if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (favorite) R.string.remove_favorite else R.string.add_favorite,
            onFavorite, selected = favorite
        )
        ViewerButton(Icons.Rounded.Share, R.string.share, onShare)
        ViewerButton(Icons.Rounded.Delete, R.string.delete, onDelete, enabled = !deletionBusy)
    }
}

@Composable
internal fun ViewerButton(
    icon: ImageVector, label: Int, onClick: () -> Unit, modifier: Modifier = Modifier,
    selected: Boolean = false, enabled: Boolean = true
) {
    FilledIconButton(
        onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp), shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (selected) ViewerAccent else ViewerControl,
            contentColor = if (selected) Color(0xFF23330F) else Color.White
        )
    ) {
        Icon(icon, stringResource(label))
    }
}

@Composable
private fun PhotoPage(
    item: GalleryItem,
    thumbnails: Thumbnails,
    thumbSize: Int,
    active: Boolean,
    onTap: () -> Unit,
    expansion: () -> Float,
    onZoom: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val preview = rememberThumbnail(item, thumbnails, thumbSize)
    val placeholder = remember(preview) { preview?.let { BitmapPainter(it.asImageBitmap()) } }
    val request = remember(item.key, item.modified) {
        ImageRequest.Builder(context).data(item.uri)
            .memoryCacheKey("${item.key}:${item.modified}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .crossfade(140)
            .build()
    }
    var failed by remember(item.key) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var aspect by remember(item.key) { mutableFloatStateOf(item.aspect) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val currentTap by rememberUpdatedState(onTap)

    fun bounded(pan: Offset, zoom: Float): Offset {
        val width = viewport.width.toFloat()
        val height = viewport.height.toFloat()
        val fittedWidth = min(width, height * aspect)
        val fittedHeight = min(height, width / aspect)
        val x = ((fittedWidth * zoom - width) / 2).coerceAtLeast(0f)
        val y = ((fittedHeight * zoom - height) / 2).coerceAtLeast(0f)
        return Offset(pan.x.coerceIn(-x, x), pan.y.coerceIn(-y, y))
    }

    fun zoomAt(tap: Offset) {
        zoomJob?.cancel()
        val start = scale
        val target = if (start > 1.05f) 1f else 2.6f
        val origin = offset
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val focus = tap - center
        zoomJob = scope.launch {
            animate(start, target, animationSpec = viewerSpring) { value, _ ->
                scale = value
                val ratio = value / start
                offset = bounded(origin * ratio + focus * (1f - ratio), value)
            }
        }
    }

    val transform = rememberTransformableState { centroid, zoom, pan, _ ->
        zoomJob?.cancel()
        val old = scale
        scale = (scale * zoom).coerceIn(1f, 5f)
        val ratio = scale / old
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val focus = if (centroid == Offset.Unspecified) Offset.Zero else centroid - center
        offset = bounded(offset * ratio + focus * (1f - ratio) + pan, scale)
    }
    LaunchedEffect(active) {
        if (!active) {
            zoomJob?.cancel()
            scale = 1f
            offset = Offset.Zero
        }
    }
    val zoomed = scale > 1.015f
    LaunchedEffect(active, zoomed) { onZoom(active && zoomed) }

    val toggle = stringResource(R.string.show_controls)
    val zoom = stringResource(if (zoomed) R.string.reset_zoom else R.string.zoom_photo)
    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
            .pointerInput(item.key) {
                detectTapGestures(onTap = { currentTap() }, onDoubleTap = ::zoomAt)
            }
            .transformable(transform, canPan = { scale > 1.015f }, lockRotationOnZoomPan = true, enabled = active)
            .semantics {
                onClick(label = toggle) { currentTap(); true }
                customActions = listOf(CustomAccessibilityAction(zoom) {
                    zoomAt(Offset(viewport.width / 2f, viewport.height / 2f))
                    true
                })
            },
        contentAlignment = Alignment.Center
    ) {
        val transformModifier = Modifier.fillMaxSize().graphicsLayer {
            val progress = expansion()
            val displayedScale = lerp(1f, scale, progress)
            scaleX = displayedScale
            scaleY = displayedScale
            translationX = offset.x * progress
            translationY = offset.y * progress
        }
        AsyncImage(
            model = request, contentDescription = item.name, placeholder = placeholder,
            modifier = transformModifier, contentScale = ContentScale.Fit,
            onSuccess = { state ->
                val intrinsic = state.painter.intrinsicSize
                if (intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.height > 0) {
                    aspect = intrinsic.width / intrinsic.height
                }
            },
            onError = { failed = true }
        )
        if (failed) Text(stringResource(R.string.photo_unavailable), color = Color.White)
    }
}

@Composable
private fun DetailsDialog(item: GalleryItem, close: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val details = buildList {
        item.localDate()?.let { add(it.formatted(locale)) }
        item.albumName.takeIf { it.isNotBlank() }?.let(::add)
        if (item.width > 0 && item.height > 0) add(stringResource(R.string.dimensions, item.width, item.height))
        if (item.bytes > 0) add(Formatter.formatFileSize(context, item.bytes))
        if (item.duration > 0) add(durationText(item.duration))
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.details)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, fontWeight = FontWeight.SemiBold)
                details.forEach { Text(it) }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.close)) } },
        shape = RoundedCornerShape(28.dp)
    )
}
