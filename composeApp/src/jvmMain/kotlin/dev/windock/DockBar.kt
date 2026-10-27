package dev.windock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import dev.windock.windows.GlassBackdrop
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windock.mvi.BatteryStatus
import dev.windock.mvi.DockItem
import dev.windock.mvi.DockTheme
import dev.windock.mvi.DockUiState
import dev.windock.mvi.NetworkKind
import dev.windock.mvi.ShellAction
import dev.windock.mvi.SystemStatus
import dev.windock.mvi.UiEvent
import dev.windock.windows.ShellIcons
import dev.windock.windows.TaskbarApps
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import dev.windock.mvi.MediaCommand
import dev.windock.mvi.MediaStatus
import dev.windock.mvi.PlaybackState
import dev.windock.mvi.WeatherStatus
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

internal val Ink = Color(0xFF20242D)
internal val Blue = Color(0xFF1685F8)

/**
 * Height of the dock bar: the floating capsule needs room around it; the edge-to-edge bar hugs its icons,
 * like the native taskbar. Scenes play in the [SceneHeadroom] above the bar ([StageHeight] in all).
 */
internal val BarHeight = 92.dp
internal val EdgeBarHeight = 72.dp
internal val SceneHeadroom = 40.dp
internal val StageHeight = SceneHeadroom + BarHeight
private val TileSize = 52.dp

internal fun DockTheme.isCapsule() = this == DockTheme.FLOATING || this == DockTheme.GLASS
internal fun DockUiState.barHeight() = if (visibleTheme().isCapsule()) BarHeight else EdgeBarHeight

/** How far a hovered tile rises: the capsule has room above its icons, the snug edge-to-edge bar much less. */
private val LocalTileLift = staticCompositionLocalOf { 1f }
private val TileShape = RoundedCornerShape(13.dp)
private val PtBr: Locale = Locale.forLanguageTag("pt-BR")

/**
 * What the pointer is over. Shared between the bar and the label layer, which in the real dock live in
 * different windows; both windows start at the screen's left edge, so window x coordinates line up.
 */
@Stable
internal class DockHover {
    var target by mutableStateOf<HoverTarget?>(null)
    /** The floating capsule's left and right edges (px from the bar's left), where the mascot walks. */
    var capsuleSpan by mutableStateOf<ClosedFloatingPointRange<Float>?>(null)
}

internal data class HoverTarget(val id: String, val label: String, val centerX: Float)

/** White veil over the blurred wallpaper on the glass theme; also used to pick the text colour. */
private const val GlassVeil = .18f

/** What the glass theme shows through: a frosted capture of the screen behind the dock (or of the wallpaper). */
internal val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** While the picker is open the draft theme is shown as a live preview; Apply commits it, Cancel restores it. */
internal fun DockUiState.visibleTheme() = if (settingsOpen) draftTheme else theme

/** The bar with the stage above it, in one window; used by the preview. */
@Composable
internal fun DockStage(state: DockUiState, apps: TaskbarApps, mediaArtwork: ImageBitmap?, hover: DockHover, onEvent: (UiEvent) -> Unit, modifier: Modifier = Modifier) {
    // The preview is an ordinary focusable window, so the search field can always have the keyboard.
    val keyboard = remember { KeyboardGrant() }
    Box(modifier.fillMaxWidth().height(SceneHeadroom + state.barHeight())) {
        DockBar(state, apps, mediaArtwork, hover, keyboard, onEvent, Modifier.align(Alignment.BottomCenter))
        DockOverlay(state, hover)
    }
}

/** Everything drawn over the bar without taking clicks: the theme's scene and the hover label. */
@Composable
internal fun DockOverlay(state: DockUiState, hover: DockHover, modifier: Modifier = Modifier) {
    val barHeight = state.barHeight()
    Box(modifier.fillMaxWidth().height(SceneHeadroom + barHeight)) {
        if (state.decorativeAnimationsEnabled && !state.reducedMotion) {
            when (state.visibleTheme()) {
                DockTheme.HALLOWEEN_GHOST -> HalloweenGhostScene(Modifier.align(Alignment.TopStart))
                DockTheme.HALLOWEEN_ZOMBIES -> ZombieGraveyardScene(Modifier.align(Alignment.TopStart))
                DockTheme.HALLOWEEN_TRICK_OR_TREAT -> TrickOrTreatScene(Modifier.align(Alignment.TopStart))
                DockTheme.AUTUMN -> AutumnScene(Modifier.align(Alignment.TopStart))
                DockTheme.CHRISTMAS -> ChristmasScene(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(barHeight))
                DockTheme.CHRISTMAS_WORKSHOP -> SantaWorkshopScene(Modifier.align(Alignment.TopStart))
                DockTheme.CHRISTMAS_COZY -> CozyChristmasScene(Modifier.align(Alignment.TopStart))
                DockTheme.SYSTEM -> Unit
                else -> MascotScene(state.mascot, hover, capsule = state.visibleTheme().isCapsule())
            }
        }
        HoverLabel(hover, Modifier.align(Alignment.TopStart).fillMaxWidth().height(SceneHeadroom))
    }
}

@Composable
internal fun DockBar(
    state: DockUiState,
    apps: TaskbarApps,
    mediaArtwork: ImageBitmap?,
    hover: DockHover,
    keyboard: KeyboardGrant,
    onEvent: (UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = themePalette(state.visibleTheme(), state.randomPaletteIndex)
    val glass = LocalGlassBackdrop.current
    val isGlass = state.visibleTheme() == DockTheme.GLASS
    // On glass the text colour follows what's actually behind it once the frosted veil is applied: dark text
    // unless the wallpaper is dark enough to stay dark under the veil.
    val glassLight = glass == null || glass.luminance * (1 - GlassVeil) + GlassVeil > .5f
    val ink = when {
        isGlass && glassLight -> BarInk(Ink, Color(0xFF3F4652), Blue, Color.White.copy(alpha = .7f))
        isGlass -> BarInk(Color(0xFFF7F8FB), Color(0xFFD5D9E2), Color(0xFF8CCBFF), Color.White.copy(alpha = .45f))
        else -> barInk(state.visibleTheme(), palette)
    }
    val now = rememberNow()
    fun launch(id: String) = onEvent(UiEvent.LaunchItem(id))
    fun menu(id: String) = onEvent(UiEvent.OpenContextMenu(id))
    val floating = state.visibleTheme() == DockTheme.FLOATING || isGlass

    if (floating) {
        // A capsule centred over the wallpaper. Shadow and glass are drawn without clipping, so icons that
        // grow on hover can rise past the capsule's top edge.
        val capsule = RoundedCornerShape(26.dp)
        // Where the capsule sits in the window, so the glass shows exactly the wallpaper behind it.
        var capsuleOrigin by remember { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(modifier.fillMaxWidth().height(BarHeight)) {
            val available = maxWidth
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).height(BarHeight - 14.dp)
                    .widthIn(max = available - 48.dp)
                    .onGloballyPositioned {
                        capsuleOrigin = it.positionInWindow()
                        val bounds = it.boundsInParent()
                        hover.capsuleSpan = bounds.left..bounds.right
                    }
                    .shadow(18.dp, capsule, clip = false)
                    .then(
                        if (isGlass) {
                            Modifier.drawBehind {
                                val outline = Path().apply { addOutline(capsule.createOutline(size, layoutDirection, this@drawBehind)) }
                                clipPath(outline) {
                                    if (glass != null) {
                                        drawImage(
                                            glass.image,
                                            dstOffset = IntOffset(-capsuleOrigin.x.roundToInt(), -capsuleOrigin.y.roundToInt()),
                                            dstSize = IntSize(glass.image.width * glass.upscale, glass.image.height * glass.upscale),
                                            filterQuality = FilterQuality.Low,
                                        )
                                    }
                                    else drawRect(Brush.verticalGradient(listOf(Color(0xCCE9EEF6), Color(0xB3D5DEEA))))
                                    // A light frosted veil, a sheen on the upper half and a faint shade at the
                                    // bottom give the pane depth, as on macOS.
                                    drawRect(Color.White.copy(alpha = GlassVeil))
                                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.Transparent), endY = size.height * .45f))
                                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .06f)), startY = size.height * .6f))
                                }
                                // The specular edge: a crisp highlight just inside the top rim.
                                val radius = 26.dp.toPx()
                                drawLine(
                                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .75f), Color.White.copy(alpha = .75f), Color.Transparent)),
                                    Offset(radius * .6f, 1.5.dp.toPx()), Offset(size.width - radius * .6f, 1.5.dp.toPx()), 1.dp.toPx(),
                                )
                            }
                        } else {
                            Modifier.background(Brush.verticalGradient(listOf(Color(0x99353B4E), Color(0x8C1A1D28))), capsule)
                        },
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = if (isGlass) .7f else .35f), Color.White.copy(alpha = if (isGlass) .2f else .08f))),
                        capsule,
                    )
                    .onRightClick { menu(ShellAction.BAR) }
                    // No end padding: the show-desktop slice is the capsule's own rounded right end.
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DockContent(state, apps, mediaArtwork, hover, keyboard, onEvent, ink, now,
                    // Leaves room for the widgets on the right (~560 dp) plus the capsule margins.
                    appsModifier = Modifier.widthIn(max = (available - 640.dp).coerceAtLeast(220.dp)))
                ShowDesktopSliver(ink, hover, width = 18.dp, shape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)) {
                    launch(ShellAction.SHOW_DESKTOP)
                }
            }
        }
        return
    }

    Row(
        modifier.fillMaxWidth().height(EdgeBarHeight)
            .background(Brush.horizontalGradient(palette))
            // Flush with the screen edges like the native taskbar; a hairline marks where it meets the windows above.
            .drawBehind { drawLine(ink.border, Offset(0f, .5f), Offset(size.width, .5f), 1.dp.toPx()) }
            // Right-click on empty bar space; tiles consume their own right-clicks first.
            .onRightClick { menu(ShellAction.BAR) }
            // No end padding: the show-desktop sliver sits on the screen's right edge, like Windows 11's.
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The app list takes the free space, pushing the widgets to the right.
        CompositionLocalProvider(LocalTileLift provides .45f) {
            DockContent(state, apps, mediaArtwork, hover, keyboard, onEvent, ink, now, appsModifier = Modifier.weight(1f))
        }
        ShowDesktopSliver(ink, hover) { launch(ShellAction.SHOW_DESKTOP) }
    }
}

/** Everything on the bar except the show-desktop corner; shared by the edge-to-edge bar and the floating capsule. */
@Composable
private fun DockContent(
    state: DockUiState,
    apps: TaskbarApps,
    mediaArtwork: ImageBitmap?,
    hover: DockHover,
    keyboard: KeyboardGrant,
    onEvent: (UiEvent) -> Unit,
    ink: BarInk,
    now: LocalDateTime,
    appsModifier: Modifier,
) {
    fun launch(id: String) = onEvent(UiEvent.LaunchItem(id))
    fun menu(id: String) = onEvent(UiEvent.OpenContextMenu(id))
    run {
        // Start + apps magnify together, so the hovered icon's neighbours grow a little too.
        val rowIds = listOf(ShellAction.START) + state.items.map { it.id }
        val hoveredIndex = rowIds.indexOf(hover.target?.id)
        fun magnify(index: Int) = when (if (hoveredIndex < 0) -1 else abs(index - hoveredIndex)) {
            0 -> 1f
            1 -> .32f
            else -> 0f
        }
        Row(
            appsModifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DockTile(ShellAction.START, "Iniciar", hover, magnify(0), Indicator.NONE, ink, onClick = { launch(ShellAction.START) },
                onRightClick = { menu(ShellAction.START) },
                background = Brush.verticalGradient(listOf(Color(0xFF3C9BFF), Color(0xFF0B6DDF)))) { StartGlyph() }
            SearchTile(keyboard, onEvent)
            state.items.forEachIndexed { index, item ->
                // Keyed by app, so hover and indicator animations stay with their icon when apps open or close.
                key(item.id) {
                    DockTile(item.id, appLabel(item), hover, magnify(index + 1), item.indicator(), ink, onClick = { launch(item.id) },
                        onRightClick = { menu(item.id) }) {
                        AppIcon(apps.icon(item.id), item.label)
                    }
                }
            }
            Spacer(Modifier.width(2.dp))
            MusicCard(state.media, mediaArtwork, hover, onEvent)
        }
        fun single(id: String) = if (hover.target?.id == id) 1f else 0f
        CalendarTile(now, hover, single("dock:calendar")) { launch(ShellAction.NOTIFICATIONS) }
        WeatherCard(state.weather, state.weather?.city ?: state.weatherCity, hover) { launch(ShellAction.WIDGETS) }
        DockTile("dock:settings", "Personalizar dock", hover, single("dock:settings"), Indicator.NONE, ink,
            onClick = { onEvent(UiEvent.ToggleSettings) }) { PaletteGlyph() }
        Box(Modifier.width(1.dp).height(46.dp).background(ink.muted.copy(alpha = .45f)))
        DockTile(ShellAction.DOWNLOADS, "Downloads", hover, single(ShellAction.DOWNLOADS), Indicator.NONE, ink, onClick = { launch(ShellAction.DOWNLOADS) },
            onRightClick = { menu(ShellAction.DOWNLOADS) }) {
            AppIcon(ShellIcons.downloads, "Downloads")
        }
        val binFull = state.system.recycleBinFull
        DockTile(ShellAction.RECYCLE_BIN, if (binFull) "Lixeira · contém itens" else "Lixeira · vazia", hover, single(ShellAction.RECYCLE_BIN),
            Indicator.NONE, ink, onClick = { launch(ShellAction.RECYCLE_BIN) }, onRightClick = { menu(ShellAction.RECYCLE_BIN) }) {
            AppIcon(ShellIcons.recycleBin(binFull), "Lixeira")
        }
        Tray(state.system, now, ink, hover, onEvent)
    }
}

/** Calls [action] on a right-button press that no child has already handled, and marks it handled. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.onRightClick(action: () -> Unit): Modifier = onPointerEvent(PointerEventType.Press) { event ->
    if (event.buttons.isSecondaryPressed && event.changes.none { it.isConsumed }) {
        event.changes.forEach { it.consume() }
        action()
    }
}

/**
 * Lets a normally non-activating dock window take keyboard focus for a moment (the search field), and
 * gives it back. The host window watches [granted]; the search field asks for it and reacts when it's revoked.
 */
@Stable
internal class KeyboardGrant {
    var granted by mutableStateOf(false)
}

/** The current search session in the dock window; null in the preview, where the field keeps its own. */
internal val LocalSearchSession = staticCompositionLocalOf<SearchSession?> { null }

/**
 * Search from the dock: a magnifier that grows into a field on hover. As you type, a panel above it lists
 * matching apps and Settings pages plus file and web searches; ↑/↓ pick, Enter opens, Esc or clicking away closes.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun SearchTile(keyboard: KeyboardGrant, onEvent: (UiEvent) -> Unit) {
    val session = LocalSearchSession.current ?: remember { SearchSession() }
    val index = LocalAppIndex.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val expanded = hovered || focused || keyboard.granted || session.query.isNotEmpty()
    val width by animateDpAsState(if (expanded) 220.dp else TileSize, spring(dampingRatio = .72f, stiffness = 380f), label = "search-width")
    val reveal by animateFloatAsState(if (expanded) 1f else 0f, spring(dampingRatio = .8f, stiffness = 300f), label = "search-reveal")

    fun close() {
        session.query = ""
        focusManager.clearFocus()
        keyboard.granted = false
    }
    session.open = { result ->
        close()
        onEvent(UiEvent.OpenTarget(result.target))
    }
    // Matching runs off the UI thread and only for the latest text, so typing never waits on it.
    LaunchedEffect(index) {
        snapshotFlow { session.query }.collectLatest { query ->
            val found = if (index != null && query.isNotBlank()) withContext(Dispatchers.Default) { searchFor(query, index) } else emptyList()
            session.results = found
            session.selected = 0
        }
    }
    val fieldVisible = reveal > .02f
    // Native activation and field composition are asynchronous. Wait for both, not a fixed timeout.
    LaunchedEffect(keyboard.granted, windowFocused, fieldVisible) {
        if (keyboard.granted && windowFocused && fieldVisible) {
            androidx.compose.runtime.withFrameNanos { }
            focusRequester.requestFocus()
        } else if (!keyboard.granted && (focused || session.query.isNotEmpty())) {
            session.query = ""
            focusManager.clearFocus()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.width(width).height(TileSize)
                .onGloballyPositioned { session.anchorX = it.boundsInWindow().left }
                .shadow((3f + 5f * reveal).dp, TileShape).clip(TileShape)
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF0F2F5))))
                .border(1.dp, if (focused) Blue.copy(alpha = .7f) else Color.White.copy(alpha = .7f), TileShape)
                .hoverable(interaction)
                // Observe the press before BasicTextField consumes it; leave caret placement to the field.
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                    if (event.buttons.isPrimaryPressed) keyboard.granted = true
                }
                .clickable(interaction, indication = null) {
                    keyboard.granted = true
                    if (windowFocused && fieldVisible) focusRequester.requestFocus()
                }
                .padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The lens tilts up and grows a touch as the field opens, like it's starting to look.
            Canvas(Modifier.size(22.dp).graphicsLayer {
                rotationZ = -18f * reveal
                val s = 1f + .08f * reveal
                scaleX = s
                scaleY = s
            }) {
                val u = 1.dp.toPx()
                val lens = Offset(size.width * .42f, size.height * .42f)
                drawCircle(Ink, size.width * .3f, lens, style = Stroke(2.2f * u))
                drawCircle(Blue.copy(alpha = .18f * reveal), size.width * .24f, lens)
                drawLine(Ink, lens + Offset(size.width * .22f, size.height * .22f), Offset(size.width * .92f, size.height * .92f), 2.6f * u, StrokeCap.Round)
            }
            if (fieldVisible) {
                Box(Modifier.weight(1f).padding(start = 10.dp).graphicsLayer {
                    alpha = reveal
                    translationX = (1f - reveal) * -10.dp.toPx()
                }) {
                    BasicTextField(
                        value = session.query,
                        onValueChange = { session.query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 13.sp),
                        cursorBrush = SolidColor(Blue),
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val count = session.results.size
                                when (event.key) {
                                    Key.DirectionDown -> { if (count > 0) session.selected = (session.selected + 1) % count; true }
                                    Key.DirectionUp -> { if (count > 0) session.selected = (session.selected - 1 + count) % count; true }
                                    Key.Enter, Key.NumPadEnter -> {
                                        session.results.getOrNull(session.selected)?.let(session.open)
                                        true
                                    }
                                    Key.Escape -> {
                                        close()
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                    if (session.query.isEmpty()) {
                        Text("Pesquisar", color = Color(0xFF7A828E), fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
        // Same footprint as the indicator under app tiles, so everything stays aligned.
        Spacer(Modifier.height(10.dp))
    }
}

private enum class Indicator { NONE, RUNNING, ACTIVE }

private fun DockItem.indicator() = when {
    isActive -> Indicator.ACTIVE
    isRunning -> Indicator.RUNNING
    else -> Indicator.NONE
}

private fun appLabel(item: DockItem) = if (item.windowCount > 1) "${item.label} · ${item.windowCount} janelas" else item.label

/**
 * Reports hover to [hover] with this element's centre, so the label layer can point at it.
 * Returns the modifier that must be applied to the element.
 */
@Composable
private fun hoverReporting(id: String, label: String, hover: DockHover, interaction: MutableInteractionSource): Modifier {
    val hovered by interaction.collectIsHoveredAsState()
    val center = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(hovered, label) {
        if (hovered) hover.target = HoverTarget(id, label, center.floatValue)
        else if (hover.target?.id == id) hover.target = null
    }
    DisposableEffect(id) { onDispose { if (hover.target?.id == id) hover.target = null } }
    return Modifier.onGloballyPositioned { center.floatValue = it.boundsInWindow().center.x }.hoverable(interaction)
}

@Composable
private fun DockTile(
    id: String,
    label: String,
    hover: DockHover,
    magnify: Float,
    indicator: Indicator,
    ink: BarInk,
    onClick: () -> Unit,
    onRightClick: (() -> Unit)? = null,
    background: Brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFF0F2F5))),
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val highlight by animateFloatAsState(if (hovered) 1f else 0f, tween(150), label = "tile-highlight-$id")
    val lift = LocalTileLift.current
    val grow by animateFloatAsState(magnify * lift, spring(dampingRatio = .62f, stiffness = 420f), label = "tile-grow-$id")
    val press by animateFloatAsState(if (pressed) .9f else 1f, spring(dampingRatio = .5f, stiffness = 900f), label = "tile-press-$id")
    val indicatorWidth by animateDpAsState(
        when (indicator) { Indicator.ACTIVE -> 18.dp; Indicator.RUNNING -> 6.dp; Indicator.NONE -> 0.dp },
        spring(dampingRatio = .7f, stiffness = 500f), label = "tile-indicator-$id",
    )
    Column(
        hoverReporting(id, label, hover, interaction)
            .then(if (onRightClick != null) Modifier.onRightClick(onRightClick) else Modifier)
            .clickable(interaction, indication = null, onClick = onClick)
            .width(TileSize + 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(TileSize)
                .graphicsLayer {
                    // Grow from the bottom edge and lift, so the icon rises out of the bar instead of swelling in place.
                    val scale = (1f + .16f * grow) * press
                    scaleX = scale
                    scaleY = scale
                    translationY = -5.dp.toPx() * grow
                    transformOrigin = TransformOrigin(.5f, 1f)
                    shadowElevation = (3f + 7f * grow).dp.toPx()
                    shape = TileShape
                    clip = true
                }
                .background(background)
                .background(ink.hover.copy(alpha = .12f * highlight))
                .border(1.dp, androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = .7f), ink.hover, highlight), TileShape),
            contentAlignment = Alignment.Center,
            content = content,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.width(indicatorWidth).height(5.dp).clip(CircleShape)
                .background(ink.accent),
        )
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, label: String) {
    if (icon != null) {
        Image(icon, label, Modifier.fillMaxSize().padding(7.dp), filterQuality = FilterQuality.High)
    } else {
        Text(label.take(1).uppercase(), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Floating name tag above the bar that glides between icons instead of popping a tooltip window. */
@Composable
private fun HoverLabel(hover: DockHover, modifier: Modifier) {
    val target = hover.target
    var shown by remember { mutableStateOf<HoverTarget?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val x = remember { Animatable(0f) }
    val alpha by animateFloatAsState(if (target != null) 1f else 0f, tween(if (target != null) 120 else 180), label = "hover-label-alpha")
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        val wasHidden = shown == null || alpha < .05f
        shown = target
        // Appear in place; once visible, slide to the next icon.
        if (wasHidden) x.snapTo(target.centerX) else x.animateTo(target.centerX, spring(dampingRatio = .8f, stiffness = 600f))
    }
    Box(modifier.onGloballyPositioned { origin = it.positionInWindow() }) {
        val label = shown?.label ?: return@Box
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val left = (x.value - origin.x - placeable.width / 2f).roundToInt()
                            .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                        placeable.place(left, constraints.maxHeight - placeable.height - 6.dp.roundToPx())
                    }
                }
                .graphicsLayer {
                    this.alpha = alpha
                    translationY = (1f - alpha) * 4.dp.toPx()
                }
                .shadow(6.dp, RoundedCornerShape(8.dp))
                .background(Color(0xF0222631), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Current time, refreshed exactly on each minute boundary rather than polled. */
@Composable
private fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    return now
}

private fun fullDate(now: LocalDateTime) =
    now.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", PtBr)).replaceFirstChar { it.uppercase() }

@Composable
private fun CalendarTile(now: LocalDateTime, hover: DockHover, magnify: Float, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val grow by animateFloatAsState(magnify, spring(dampingRatio = .62f, stiffness = 420f), label = "calendar-grow")
    Column(
        hoverReporting("dock:calendar", fullDate(now), hover, interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .size(TileSize)
            .graphicsLayer {
                val scale = 1f + .16f * grow
                scaleX = scale
                scaleY = scale
                translationY = -5.dp.toPx() * grow
                transformOrigin = TransformOrigin(.5f, 1f)
                shadowElevation = (3f + 7f * grow).dp.toPx()
                shape = TileShape
                clip = true
            }
            .background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val weekday = now.dayOfWeek.getDisplayName(DateTextStyle.SHORT, PtBr).removeSuffix(".").uppercase()
        Box(Modifier.fillMaxWidth().height(16.dp).background(Color(0xFFF0443A)), contentAlignment = Alignment.Center) {
            Text(weekday, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(now.dayOfMonth.toString(), color = Ink, fontSize = 24.sp, lineHeight = 26.sp, fontWeight = FontWeight.Light)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Tray(status: SystemStatus, now: LocalDateTime, ink: BarInk, hover: DockHover, onEvent: (UiEvent) -> Unit) {
    val quickSettings = { onEvent(UiEvent.LaunchItem(ShellAction.QUICK_SETTINGS)) }
    Row(Modifier.background(ink.traySurface, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(horizontalAlignment = Alignment.Start) {
            status.battery?.let { battery ->
                TrayButton("tray:battery", batteryLabel(battery), hover, ink, quickSettings) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BatteryGlyph(battery, ink.content)
                        Text("${battery.percent}%", color = ink.content, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 12.sp)
                    }
                }
            }
            Row {
                TrayButton("tray:network", networkLabel(status.network), hover, ink, quickSettings) { NetworkGlyph(status.network, ink.content) }
                TrayButton(
                    "tray:volume", volumeLabel(status), hover, ink, quickSettings,
                    Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) onEvent(UiEvent.ChangeVolume(if (dy < 0) 2 else -2))
                    },
                ) { VolumeGlyph(status.volumePercent, status.muted, ink.content) }
            }
        }
        TrayButton("tray:clock", fullDate(now), hover, ink, { onEvent(UiEvent.LaunchItem(ShellAction.NOTIFICATIONS)) }) {
            Column(horizontalAlignment = Alignment.End) {
                Text("%02d:%02d".format(now.hour, now.minute), color = ink.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                Text(now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), color = ink.muted, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }
    }
}

/**
 * Windows 11's "show desktop" corner: a narrow strip marked by a hairline. On the edge-to-edge bar it sits
 * flush with the screen corner, so it can be hit by flinging the pointer there; on the floating themes it's
 * the capsule's right end, and [shape] makes its hover highlight follow the capsule's curve.
 */
@Composable
private fun ShowDesktopSliver(
    ink: BarInk,
    hover: DockHover,
    width: Dp = 12.dp,
    shape: Shape = RectangleShape,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight by animateFloatAsState(if (hovered) 1f else 0f, tween(120), label = "show-desktop-highlight")
    Box(
        hoverReporting("dock:show-desktop", "Mostrar área de trabalho", hover, interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .width(width).fillMaxHeight()
            .clip(shape)
            .background(ink.hover.copy(alpha = .16f * highlight)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight(.5f).background(ink.muted.copy(alpha = .55f + .35f * highlight)))
    }
}

@Composable
private fun TrayButton(
    id: String,
    label: String,
    hover: DockHover,
    ink: BarInk,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight by animateFloatAsState(if (hovered) 1f else 0f, tween(120), label = "tray-highlight-$id")
    Box(
        hoverReporting(id, label, hover, interaction)
            .then(modifier)
            .clip(RoundedCornerShape(7.dp))
            .background(ink.hover.copy(alpha = .14f * highlight))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun batteryLabel(battery: BatteryStatus): String {
    val minutesLeft = battery.minutesLeft
    val state = when {
        battery.pluggedIn && battery.percent >= 100 -> "Carregada"
        battery.charging -> "Carregando"
        battery.pluggedIn -> "Conectada à tomada"
        minutesLeft != null -> "%dh %02dmin restantes".format(minutesLeft / 60, minutesLeft % 60)
        else -> "Na bateria"
    }
    return "Bateria ${battery.percent}% · $state"
}

private fun networkLabel(network: NetworkKind) = when (network) {
    NetworkKind.WIFI -> "Wi-Fi · conectado"
    NetworkKind.ETHERNET -> "Ethernet · conectada"
    NetworkKind.NONE -> "Sem conexão de rede"
}

private fun volumeLabel(status: SystemStatus) = when {
    status.volumePercent == null -> "Volume"
    status.muted -> "Sem som · role para ajustar"
    else -> "Volume ${status.volumePercent}% · role para ajustar"
}

@Composable
private fun BatteryGlyph(battery: BatteryStatus, color: Color) {
    Canvas(Modifier.size(22.dp, 12.dp)) {
        val u = 1.dp.toPx()
        val body = Size(size.width - 3 * u, size.height)
        drawRoundRect(color, Offset.Zero, body, CornerRadius(3 * u), style = Stroke(1.3f * u))
        drawRoundRect(color, Offset(body.width + .8f * u, size.height * .3f), Size(1.8f * u, size.height * .4f), CornerRadius(1 * u))
        val low = !battery.pluggedIn && battery.percent <= 15
        val inner = Size((body.width - 4 * u) * battery.percent / 100f, body.height - 4 * u)
        drawRoundRect(if (low) Color(0xFFFF5A4E) else color, Offset(2 * u, 2 * u), inner, CornerRadius(1.5f * u))
        if (battery.pluggedIn) {
            // Lightning bolt, outlined in the fill's negative so it reads on top of a full battery.
            val bolt = Path().apply {
                moveTo(body.width * .56f, 1.2f * u); lineTo(body.width * .36f, body.height * .55f); lineTo(body.width * .52f, body.height * .55f)
                lineTo(body.width * .44f, body.height - 1.2f * u); lineTo(body.width * .66f, body.height * .42f); lineTo(body.width * .5f, body.height * .42f); close()
            }
            drawPath(bolt, if (color.luminance() > .5f) Color(0xFF1B1E26) else Color.White, style = Stroke(1.6f * u, join = StrokeJoin.Round))
            drawPath(bolt, Color(0xFFFFC93C))
        }
    }
}

@Composable
private fun NetworkGlyph(network: NetworkKind, color: Color) {
    Canvas(Modifier.size(18.dp, 15.dp)) {
        val u = 1.dp.toPx()
        val stroke = Stroke(1.5f * u, cap = StrokeCap.Round)
        if (network == NetworkKind.ETHERNET) {
            drawRoundRect(color, Offset(1.5f * u, 1 * u), Size(size.width - 3 * u, size.height - 5 * u), CornerRadius(1.5f * u), style = stroke)
            drawLine(color, Offset(size.width / 2, size.height - 4 * u), Offset(size.width / 2, size.height - 1 * u), 1.5f * u)
            drawLine(color, Offset(size.width * .3f, size.height - .8f * u), Offset(size.width * .7f, size.height - .8f * u), 1.5f * u, StrokeCap.Round)
            return@Canvas
        }
        val tint = if (network == NetworkKind.NONE) color.copy(alpha = .35f) else color
        val base = Offset(size.width / 2, size.height - 1.5f * u)
        drawCircle(tint, 1.4f * u, base)
        for (ring in 1..3) {
            val r = ring * 4.2f * u
            drawArc(tint, 225f, 90f, false, Offset(base.x - r, base.y - r), Size(r * 2, r * 2), style = stroke)
        }
        if (network == NetworkKind.NONE) {
            drawLine(color, Offset(size.width - 6 * u, size.height - 6 * u), Offset(size.width - 1 * u, size.height - 1 * u), 1.5f * u, StrokeCap.Round)
            drawLine(color, Offset(size.width - 1 * u, size.height - 6 * u), Offset(size.width - 6 * u, size.height - 1 * u), 1.5f * u, StrokeCap.Round)
        }
    }
}

@Composable
private fun VolumeGlyph(percent: Int?, muted: Boolean, color: Color) {
    Canvas(Modifier.size(20.dp, 15.dp)) {
        val u = 1.dp.toPx()
        val cy = size.height / 2
        val speaker = Path().apply {
            moveTo(1 * u, cy - 2.5f * u); lineTo(4 * u, cy - 2.5f * u); lineTo(8 * u, cy - 6 * u)
            lineTo(8 * u, cy + 6 * u); lineTo(4 * u, cy + 2.5f * u); lineTo(1 * u, cy + 2.5f * u); close()
        }
        drawPath(speaker, color, style = Stroke(1.4f * u, join = StrokeJoin.Round))
        if (muted || percent == 0) {
            drawLine(color, Offset(11 * u, cy - 3 * u), Offset(17 * u, cy + 3 * u), 1.5f * u, StrokeCap.Round)
            drawLine(color, Offset(17 * u, cy - 3 * u), Offset(11 * u, cy + 3 * u), 1.5f * u, StrokeCap.Round)
            return@Canvas
        }
        val waves = when (percent ?: 100) { in 1..33 -> 1; in 34..66 -> 2; else -> 3 }
        for (wave in 1..waves) {
            val r = (2.5f + wave * 3f) * u
            drawArc(color, -50f, 100f, false, Offset(7 * u - r, cy - r), Size(r * 2, r * 2), style = Stroke(1.4f * u, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun StartGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val gap = size.width * .08f
        val cell = (size.width - gap) / 2f
        for (row in 0..1) for (col in 0..1) {
            drawRoundRect(Color.White.copy(alpha = if (row == 0 && col == 0) 1f else .94f), Offset(col * (cell + gap), row * (cell + gap)),
                Size(cell, cell), CornerRadius(1.5f.dp.toPx()))
        }
    }
}

/** A painter's palette with dabs of paint: the dock's "change the look" button. */
@Composable
private fun PaletteGlyph() {
    Canvas(Modifier.size(32.dp)) {
        val w = size.width
        val h = size.height
        val u = 1.dp.toPx()
        // Palette board with the thumb hole and the notch cut out of it.
        val board = Path().apply { addOval(Rect(w * .04f, h * .1f, w * .96f, h * .9f)) }
        val thumb = Path().apply { addOval(Rect(Offset(w * .3f, h * .66f), w * .1f)) }
        val notch = Path().apply { addOval(Rect(Offset(w * .98f, h * .7f), w * .2f)) }
        val palette = Path.combine(PathOperation.Difference, Path.combine(PathOperation.Difference, board, thumb), notch)
        drawPath(palette, Brush.verticalGradient(listOf(Color(0xFFF8E9CC), Color(0xFFE9C996))))
        drawPath(palette, Color(0xFFB4884E), style = Stroke(1.2f * u))
        // Paint dabs, each with a small highlight so they read as wet paint.
        listOf(
            Offset(.26f, .38f) to Color(0xFFFF5A4E),
            Offset(.48f, .27f) to Color(0xFFFFC93C),
            Offset(.7f, .34f) to Color(0xFF3DDC84),
            Offset(.6f, .6f) to Color(0xFF4DA8FF),
        ).forEach { (at, color) ->
            val c = Offset(w * at.x, h * at.y)
            drawCircle(color, w * .1f, c)
            drawCircle(Color.White.copy(alpha = .55f), w * .03f, c + Offset(-w * .03f, -h * .03f))
        }
    }
}

/** Cover-art-led player card for the system media session, with working transport buttons. */
@Composable
private fun MusicCard(media: MediaStatus?, artwork: ImageBitmap?, hover: DockHover, onEvent: (UiEvent) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val label = media?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" — ") + " · " + it.app }
        ?: "Nada tocando · abra um player de música ou vídeo"
    // While playing, the bar advances locally from the last snapshot instead of waiting for the host.
    val nowMs by produceState(System.currentTimeMillis(), media) {
        while (media?.state == PlaybackState.PLAYING) {
            value = System.currentTimeMillis()
            delay(500)
        }
        value = System.currentTimeMillis()
    }
    val progress = media?.takeIf { it.durationMs > 0 }?.let {
        val elapsed = if (it.state == PlaybackState.PLAYING) nowMs - it.updatedAtMs else 0
        ((it.positionMs + elapsed).toFloat() / it.durationMs).coerceIn(0f, 1f)
    }
    Row(
        hoverReporting("dock:media", label, hover, interaction)
            .width(232.dp).height(TileSize).shadow(3.dp, TileShape).clip(TileShape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF3A2440), Color(0xFF5A2A3A))))
            .border(1.dp, Color.White.copy(alpha = .25f), TileShape).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFFFFA052), Color(0xFF5D345D)))),
            contentAlignment = Alignment.Center) {
            if (artwork != null) {
                Image(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            } else {
                Text("♪", color = Color.White.copy(alpha = .85f), fontSize = 20.sp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(media?.title ?: "Nada tocando", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
            Text(media?.artist?.ifBlank { media.app } ?: "Abra um player", color = Color(0xFFD7CFDB), fontSize = 9.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, lineHeight = 10.sp)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f))) {
                if (progress != null) Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Color.White))
            }
        }
        val enabled = media != null
        MediaButton(MediaGlyph.PREVIOUS, enabled && media.canPrevious) { onEvent(UiEvent.ControlMedia(MediaCommand.PREVIOUS)) }
        MediaButton(if (media?.state == PlaybackState.PLAYING) MediaGlyph.PAUSE else MediaGlyph.PLAY, enabled) {
            onEvent(UiEvent.ControlMedia(MediaCommand.TOGGLE_PLAY_PAUSE))
        }
        MediaButton(MediaGlyph.NEXT, enabled && media.canNext) { onEvent(UiEvent.ControlMedia(MediaCommand.NEXT)) }
    }
}

private enum class MediaGlyph { PREVIOUS, PLAY, PAUSE, NEXT }

@Composable
private fun MediaButton(glyph: MediaGlyph, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) .85f else 1f, spring(dampingRatio = .5f, stiffness = 900f), label = "media-press")
    Box(
        Modifier.size(24.dp).graphicsLayer { scaleX = press; scaleY = press }.clip(CircleShape)
            .background(Color.White.copy(alpha = if (hovered && enabled) .18f else 0f))
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val color = Color.White.copy(alpha = if (enabled) 1f else .35f)
            val w = size.width
            val h = size.height
            fun triangle(left: Float, right: Float, pointRight: Boolean) = Path().apply {
                if (pointRight) {
                    moveTo(left, 0f); lineTo(right, h / 2); lineTo(left, h)
                } else {
                    moveTo(right, 0f); lineTo(left, h / 2); lineTo(right, h)
                }
                close()
            }
            when (glyph) {
                MediaGlyph.PLAY -> drawPath(triangle(w * .15f, w * .95f, true), color)
                MediaGlyph.PAUSE -> {
                    drawRoundRect(color, Offset(w * .15f, 0f), Size(w * .26f, h), CornerRadius(1.dp.toPx()))
                    drawRoundRect(color, Offset(w * .59f, 0f), Size(w * .26f, h), CornerRadius(1.dp.toPx()))
                }
                MediaGlyph.NEXT -> {
                    drawPath(triangle(0f, w * .78f, true), color)
                    drawRect(color, Offset(w * .8f, 0f), Size(w * .16f, h))
                }
                MediaGlyph.PREVIOUS -> {
                    drawPath(triangle(w * .22f, w, false), color)
                    drawRect(color, Offset(w * .04f, 0f), Size(w * .16f, h))
                }
            }
        }
    }
}

private enum class Sky { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

/** WMO weather codes as used by Open-Meteo. */
private fun skyFor(code: Int) = when (code) {
    0, 1 -> Sky.CLEAR
    2 -> Sky.PARTLY_CLOUDY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..57 -> Sky.DRIZZLE
    in 61..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUDY
}

private fun skyDescription(code: Int) = when (code) {
    0 -> "Céu limpo"
    1 -> "Predominantemente limpo"
    2 -> "Parcialmente nublado"
    3 -> "Nublado"
    45, 48 -> "Neblina"
    in 51..57 -> "Garoa"
    in 61..65 -> "Chuva"
    66, 67 -> "Chuva congelante"
    in 71..77 -> "Neve"
    in 80..82 -> "Pancadas de chuva"
    85, 86 -> "Pancadas de neve"
    in 95..99 -> "Tempestade"
    else -> "Tempo instável"
}

@Composable
private fun WeatherCard(weather: WeatherStatus?, city: String, hover: DockHover, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val label = weather?.let {
        val time = Instant.ofEpochMilli(it.updatedAtMs).atZone(ZoneId.systemDefault()).toLocalTime()
        val source = if (it.fromDevice) " (sua localização)" else ""
        "${skyDescription(it.code)} · $city$source · atualizado às %02d:%02d".format(time.hour, time.minute)
    } ?: "Buscando o clima de $city…"
    Row(
        hoverReporting("dock:weather", label, hover, interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .width(112.dp).height(TileSize).shadow(3.dp, TileShape).clip(TileShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2E3038), Color(0xFF1B1C22))))
            .border(1.dp, Color.White.copy(alpha = .22f), TileShape).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkyGlyph(weather?.let { skyFor(it.code) } ?: Sky.CLOUDY, weather?.isDay ?: true, dim = weather == null)
        Column(verticalArrangement = Arrangement.Center) {
            Text(weather?.let { "${it.temperatureC}°" } ?: "--°", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
            Text(city, color = Color(0xFFBFC1C8), fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SkyGlyph(sky: Sky, isDay: Boolean, dim: Boolean) {
    Canvas(Modifier.size(26.dp).graphicsLayer { alpha = if (dim) .4f else 1f }) {
        val u = 1.dp.toPx()
        val sun = Color(0xFFFFC94D)
        val cloud = Color(0xFFE3E7EF)
        val darkCloud = Color(0xFFAEB5C2)
        fun drawSun(center: Offset, r: Float) {
            drawCircle(sun, r, center)
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                drawLine(sun, center + dir * (r + 2 * u), center + dir * (r + 4.5f * u), 1.6f * u, StrokeCap.Round)
            }
        }
        fun drawMoon(center: Offset, r: Float) {
            val disc = Path().apply { addOval(Rect(center, r)) }
            val bite = Path().apply { addOval(Rect(center + Offset(r * .55f, -r * .35f), r * .85f)) }
            drawPath(Path.combine(PathOperation.Difference, disc, bite), Color(0xFFF1E3B0))
        }
        fun drawCloud(origin: Offset, scale: Float, color: Color) {
            drawCircle(color, 4.5f * u * scale, origin + Offset(6 * u * scale, -2 * u * scale))
            drawCircle(color, 3.5f * u * scale, origin + Offset(1.5f * u * scale, 0f))
            drawCircle(color, 3.2f * u * scale, origin + Offset(11 * u * scale, .5f * u * scale))
            drawRoundRect(color, origin + Offset(-1 * u * scale, 0f), Size(15 * u * scale, 3.8f * u * scale), CornerRadius(2 * u * scale))
        }
        val c = Offset(size.width / 2, size.height / 2)
        when (sky) {
            Sky.CLEAR -> if (isDay) drawSun(c, 5.5f * u) else drawMoon(c, 7 * u)
            Sky.PARTLY_CLOUDY -> {
                if (isDay) drawSun(c + Offset(-3 * u, -3 * u), 4.5f * u) else drawMoon(c + Offset(-3 * u, -4 * u), 5.5f * u)
                drawCloud(Offset(4 * u, 17 * u), 1f, cloud)
            }
            Sky.CLOUDY -> {
                drawCloud(Offset(7 * u, 11 * u), .8f, darkCloud)
                drawCloud(Offset(3 * u, 17 * u), 1f, cloud)
            }
            Sky.FOG -> for (k in 0..3) {
                drawLine(cloud, Offset((3 + k % 2 * 3) * u, (7 + k * 4) * u), Offset((21 + k % 2 * 2) * u, (7 + k * 4) * u), 1.8f * u, StrokeCap.Round)
            }
            Sky.DRIZZLE, Sky.RAIN, Sky.STORM -> {
                drawCloud(Offset(3 * u, 11 * u), 1f, if (sky == Sky.STORM) darkCloud else cloud)
                if (sky == Sky.STORM) {
                    val bolt = Path().apply {
                        moveTo(14 * u, 15 * u); lineTo(10 * u, 21 * u); lineTo(13 * u, 21 * u)
                        lineTo(11 * u, 26 * u); lineTo(17 * u, 19 * u); lineTo(14 * u, 19 * u); close()
                    }
                    drawPath(bolt, sun)
                } else {
                    val drops = if (sky == Sky.RAIN) 3 else 2
                    for (k in 0 until drops) {
                        val x = (7 + k * 5) * u
                        drawLine(Color(0xFF7CC4FF), Offset(x, 18 * u), Offset(x - 2 * u, (if (sky == Sky.RAIN) 24 else 22) * u), 1.6f * u, StrokeCap.Round)
                    }
                }
            }
            Sky.SNOW -> {
                drawCloud(Offset(3 * u, 11 * u), 1f, cloud)
                for (k in 0 until 3) drawCircle(Color.White, 1.4f * u, Offset((7 + k * 5) * u, (20 + k % 2 * 3) * u))
            }
        }
    }
}
