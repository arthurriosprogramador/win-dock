package dev.windock

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.windock.windows.DeviceLocation
import dev.windock.windows.WinRtBridge
import dev.windock.windows.Kernel32Ex
import com.sun.jna.platform.win32.BaseTSD.SIZE_T
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import dev.windock.windows.WindowEvents
import dev.windock.windows.WindowThumbnails
import dev.windock.windows.GlassBackdrop
import dev.windock.windows.Wallpaper
import dev.windock.windows.LiveGlass
import dev.windock.windows.AppIndex
import dev.windock.windows.shellOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.runtime.key
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import dev.windock.mvi.DockStore
import dev.windock.mvi.DockTheme
import dev.windock.mvi.DockUiState
import dev.windock.mvi.NavEvent
import dev.windock.mvi.ShellAction
import dev.windock.mvi.UiEvent
import dev.windock.windows.DockAppBar
import dev.windock.windows.NativeTaskbar
import dev.windock.windows.TaskbarApps
import dev.windock.windows.SystemMonitor
import dev.windock.windows.Win32
import dev.windock.windows.isFullscreenAppInFront
import dev.windock.updates.LocalUpdateCheck
import dev.windock.updates.UpdateDialog
import dev.windock.updates.UpdateNavEvent
import dev.windock.updates.UpdateSettingsRow
import dev.windock.updates.UpdateStore
import dev.windock.updates.UpdateUiEvent
import dev.windock.windows.keepOnTop
import dev.windock.windows.makeClickThrough
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import kotlin.system.exitProcess
import java.awt.GraphicsEnvironment
import kotlin.math.abs
import kotlin.math.roundToInt

/** The app icon for title bars and Alt+Tab (generated by tools/make_icon.py). */
private val AppIcon: BitmapPainter? by lazy {
    runCatching {
        BitmapPainter(Image.makeFromEncoded(object {}.javaClass.getResourceAsStream("/app-icon.png")!!.readBytes()).toComposeImageBitmap())
    }.getOrNull()
}

fun main() {
    // A second copy (say, the logon task plus a manual launch) would stack two docks; the first one wins.
    if (!DockSettings.acquireSingleInstance()) return
    // Older builds didn't take the lock, so one may still be drawing its bar underneath; it has to go.
    closeStaleDocks()
    val initial = DockSettings.initialState()
    if (Win32.isWindows) {
        // A previous session that died while the dock was active left the taskbar hidden; undo that first.
        if (!initial.dockActive) NativeTaskbar.restore()
        // Whatever ends the JVM, give the screen edge back to the native taskbar.
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { NativeTaskbar.restore() } })
    }
    // Background-thread failures are logged and survived; UI failures are logged and end the app cleanly,
    // which restores the taskbar through the shutdown hook instead of leaving a broken bar on screen.
    Thread.setDefaultUncaughtExceptionHandler { thread, error -> CrashLog.exception("thread ${thread.name}", error) }
    startUiWatchdog()
    application {
        @OptIn(ExperimentalComposeUiApi::class)
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory {
                WindowExceptionHandler { error ->
                    CrashLog.exception("interface", error)
                    exitProcess(1)
                }
            },
        ) {
            DockApp(initial)
        }
    }
}

@Composable
private fun ApplicationScope.DockApp(initial: DockUiState) {
    val store = remember { DockStore(initial) }
    val state by store.state.collectAsState()
    val updateScope = rememberCoroutineScope()
    val updater = remember { UpdateStore(updateScope) }
    val updateState by updater.state.collectAsState()
    LaunchedEffect(updater) { updater.poll() }
    LaunchedEffect(updater) {
        updater.navigation.collect { event ->
            if (event == UpdateNavEvent.ExitForInstaller) {
                withContext(Dispatchers.IO) {
                    runCatching { DockSettings.save(store.state.value) }
                        .onFailure { CrashLog.exception("save before update", it) }
                }
                // Normal disposal/shutdown releases the app bar and restores the Windows taskbar.
                exitApplication()
            }
        }
    }
    val apps = remember { TaskbarApps() }
    // Start menu apps for the search; listed once at start-up and refreshed now and then for new installs.
    val appIndex = remember { AppIndex() }
    LaunchedEffect(appIndex) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                appIndex.reload()
                delay(10 * 60_000L)
            }
        }
    }
    val hover = remember { DockHover() }
    val monitor = remember { SystemMonitor(store::updateSystemStatus) }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.stop() }
    }
    val bridge = remember { WinRtBridge(store::updateMedia).apply { webAppName = apps::webAppName } }
    // The bridge is a separate process: told when the taskbar is hidden, it gives it back if the dock dies.
    DisposableEffect(bridge) {
        NativeTaskbar.listener = bridge::reportTaskbar
        onDispose { NativeTaskbar.listener = null }
    }
    DisposableEffect(bridge) {
        bridge.start()
        onDispose { bridge.stop() }
    }
    // Cover art lives in the bridge; the version in state tells us when to pick up a new one.
    val artwork = remember(state.media?.artworkVersion, state.media == null) { bridge.artwork.takeIf { state.media != null } }
    val weather = remember { WeatherService() }
    LaunchedEffect(state.weatherCity, state.weatherAutoLocate) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                // Prefer the municipality Windows reports; fall back to the city from the settings.
                val location = if (state.weatherAutoLocate) bridge.requestLocation() else null
                val current = runCatching {
                    (location as? DeviceLocation.Found)?.let { weather.forPosition(it.latitude, it.longitude) }
                        ?: weather.forCity(state.weatherCity)
                }.getOrNull()
                // Settings may have changed while we were on the network; don't publish a stale answer.
                ensureActive()
                store.updateWeather(current, locationDenied = location == DeviceLocation.Denied)
                // Conditions change slowly; retry sooner when offline or the place wasn't found.
                delay(if (current != null) 20 * 60_000L else 5 * 60_000L)
            }
        }
    }

    var glass by remember { mutableStateOf<GlassBackdrop?>(null) }
    // Set by the dock while its glass capsule is on screen (not slid away by intellihide).
    var glassVisible by remember { mutableStateOf(false) }
    val wantsGlass = state.visibleTheme() == DockTheme.GLASS
    LaunchedEffect(wantsGlass) {
        if (!wantsGlass) {
            glass = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            val scale = screen.defaultTransform.scaleX
            val width = (screen.bounds.width * scale).roundToInt()
            val height = (screen.bounds.height * scale).roundToInt()
            val strip = (BarHeight.value * scale).roundToInt()
            val blur = (30 * scale).toFloat()
            var signature = ""
            var checkedAt = 0L
            LiveGlass.invalidate()
            while (isActive) {
                if (LiveGlass.available) {
                    // What's really behind the dock, a few times a second while it's on screen; a capture is only
                    // published when the screen changed, so a still desktop costs no redraws.
                    if (glassVisible) LiveGlass.capture(width, height, strip, blur)?.let { glass = it }
                    delay(if (glassVisible) 300 else 1000)
                    continue
                }
                // Fallback: the blurred wallpaper, rebuilt when the wallpaper changes.
                val now = System.currentTimeMillis()
                if (now - checkedAt > 60_000) {
                    checkedAt = now
                    val current = runCatching { Wallpaper.signature() }.getOrDefault("")
                    if (current != signature) {
                        signature = current
                        glass = Wallpaper.backdrop(width, height, strip, blur)
                    }
                }
                delay(1000)
            }
        }
    }

    // Start-up (class loading, icon extraction, the first frames) leaves ~120 MB of pages that are never
    // touched again. Trimming hands them back to Windows; the pages still in use fault straight back in.
    LaunchedEffect(Unit) {
        if (!Win32.isWindows) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            delay(30_000)
            while (isActive) {
                runCatching {
                    Kernel32Ex.INSTANCE.SetProcessWorkingSetSize(Kernel32.INSTANCE.GetCurrentProcess(), SIZE_T(-1L), SIZE_T(-1L))
                }
                delay(10 * 60_000L)
            }
        }
    }
    // The window list is rebuilt when Windows reports a change rather than on a timer.
    val windowChanges = remember { Channel<Unit>(Channel.CONFLATED) }
    val windowEvents = remember { WindowEvents { windowChanges.trySend(Unit) } }
    DisposableEffect(windowEvents) {
        windowEvents.start()
        onDispose { windowEvents.stop() }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                runCatching { store.updateItems(apps.refresh()) }
                // A safety net catches what raises no event, like a shortcut pinned on the native taskbar.
                withTimeoutOrNull(10_000) { windowChanges.receive() }
                // One window opening fires a burst of events; settle briefly and handle them as one.
                delay(120)
                windowChanges.tryReceive()
            }
        }
    }
    var menu by remember { mutableStateOf<OpenMenu?>(null) }
    LaunchedEffect(Unit) {
        store.navigation.collect { event ->
            when (event) {
                is NavEvent.LaunchItem -> withContext(Dispatchers.IO) {
                    runCatching {
                        if (event.itemId.startsWith("shell:")) apps.runShellAction(event.itemId) else apps.activate(event.itemId)
                    }
                }
                is NavEvent.ChangeVolume -> monitor.changeVolume(event.deltaPercent)
                is NavEvent.ControlMedia -> withContext(Dispatchers.IO) { bridge.send(event.command) }
                is NavEvent.ContextMenu -> {
                    val pointer = com.sun.jna.platform.win32.WinDef.POINT().also { User32.INSTANCE.GetCursorPos(it) }
                    val built = withContext(Dispatchers.IO) {
                        runCatching { buildContextMenu(event.itemId, store.state.value, apps, store::dispatch) }.getOrNull()
                    }
                    menu = built?.let { OpenMenu(it, pointer.x, pointer.y) }
                }
                is NavEvent.OpenTarget -> withContext(Dispatchers.IO) { runCatching { shellOpen(event.target) } }
                // Back to the plain Windows taskbar: remember the choice, stop starting with Windows, and quit.
                // Opening WinDock again from the Start menu brings back the theme picker.
                NavEvent.ReturnToSystem -> {
                    withContext(Dispatchers.IO) {
                        runCatching { DockSettings.save(store.state.value) }
                        runCatching { Autostart.apply(false) }
                        runCatching { NativeTaskbar.restore() }
                    }
                    exitApplication()
                }
                else -> Unit
            }
        }
    }
    // Pins changed from the dock's menus: hand them to the app list and rebuild it right away.
    LaunchedEffect(state.pinnedApps, state.hiddenPins) {
        apps.dockPins = state.pinnedApps
        apps.hiddenPins = state.hiddenPins
        windowChanges.trySend(Unit)
    }
    LaunchedEffect(state.theme, state.randomPaletteIndex, state.decorativeAnimationsEnabled, state.reducedMotion,
        state.startWithWindows, state.dockActive, state.weatherCity, state.weatherAutoLocate, state.pinnedApps, state.hiddenPins, state.mascot) {
        withContext(Dispatchers.IO) { DockSettings.save(state) }
    }
    LaunchedEffect(state.startWithWindows, state.theme) {
        withContext(Dispatchers.IO) { runCatching { Autostart.apply(state.startWithWindows && state.theme != DockTheme.SYSTEM) } }
    }

    CompositionLocalProvider(LocalGlassBackdrop provides glass, LocalAppIndex provides appIndex,
        LocalUpdateCheck provides { updater.dispatch(UpdateUiEvent.Check) }) {
        if (state.dockActive) {
            DockWindow(state, apps, artwork, hover, menu != null, onGlassVisible = { glassVisible = it }, store::dispatch)
            if (state.settingsOpen) SettingsWindow(state, store::dispatch, updateState.visible)
        } else {
            PreviewWindow(state, apps, artwork, hover, store::dispatch)
        }
        menu?.let { open -> key(open) { MenuWindow(open, aboveDock = state.dockActive, barHeight = state.barHeight()) { if (menu === open) menu = null } } }
        UpdateDialog(updateState, updater::dispatch)
    }
}

@Composable
private fun ApplicationScope.PreviewWindow(state: DockUiState, apps: TaskbarApps, artwork: ImageBitmap?, hover: DockHover, onEvent: (UiEvent) -> Unit) {
    val initialSize = remember { comfortableWindowSize(1160, 800) }
    val windowState = rememberWindowState(size = initialSize, position = WindowPosition(Alignment.Center))
    Window(onCloseRequest = ::exitApplication, title = "WinDock", icon = AppIcon, state = windowState, resizable = true) {
        DisposableEffect(window) {
            window.minimumSize = java.awt.Dimension(minOf(800, initialSize.width.value.toInt()), minOf(520, initialSize.height.value.toInt()))
            onDispose { }
        }
        MaterialTheme { Surface(color = Color(0xFFF9FAFC)) { DockPreview(state, apps, artwork, hover, onEvent) } }
    }
}

/** Leave room for window borders and the taskbar, including on smaller or scaled displays. */
private fun comfortableWindowSize(width: Int, height: Int): DpSize {
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(screen)
    val availableWidth = screen.bounds.width - insets.left - insets.right
    val availableHeight = screen.bounds.height - insets.top - insets.bottom
    return DpSize(minOf(width.toFloat(), availableWidth * .92f).dp,
        minOf(height.toFloat(), availableHeight * .92f).dp)
}

/**
 * The real dock, as two borderless always-on-top windows along the bottom of the primary screen:
 * the clickable bar, whose height is reserved so maximized windows stop above it, and a taller
 * click-through window on top of it where the theme's scene plays, so the animation can rise above
 * the bar without blocking clicks on the windows behind it.
 */
@Composable
private fun DockWindow(state: DockUiState, apps: TaskbarApps, artwork: ImageBitmap?, hover: DockHover, menuOpen: Boolean, onGlassVisible: (Boolean) -> Unit, onEvent: (UiEvent) -> Unit) {
    val screen = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration }
    val bounds = screen.bounds
    val bottom = (bounds.y + bounds.height).dp
    // Flush with the bottom and side edges, like the native taskbar; exactly the bar's height is reserved.
    val barWindowHeight = state.barHeight()
    val sceneWindowHeight = SceneHeadroom + barWindowHeight
    var fullscreenAppInFront by remember { mutableStateOf(false) }
    var sceneHwnd by remember { mutableStateOf<HWND?>(null) }
    val keyboard = remember { KeyboardGrant() }
    val scale = screen.defaultTransform.scaleX
    val search = remember { SearchSession() }
    // Capsule themes don't reserve the screen edge: windows get the whole screen, and the dock steps aside
    // ("intellihide") while a window reaches down into it, coming back when the pointer touches the bottom edge.
    val capsule = state.visibleTheme().isCapsule()
    var revealed by remember { mutableStateOf(true) }

    // Window previews, as on the taskbar: hovering an app with open windows shows them after a short pause;
    // clicking an app that has several windows shows them instead of jumping to the last one.
    var previewFor by remember { mutableStateOf<String?>(null) }
    var previewAnchor by remember { mutableStateOf(0f) }
    var previewHovered by remember { mutableStateOf(false) }
    val hoveredApp = hover.target?.takeIf { t -> state.items.any { it.id == t.id && it.isRunning } }
    LaunchedEffect(hoveredApp?.id, previewHovered) {
        when {
            hoveredApp != null -> {
                // Hover intent: don't flash previews while the pointer just sweeps across the bar.
                if (previewFor == null) delay(450)
                previewAnchor = hoveredApp.centerX
                previewFor = hoveredApp.id
            }
            !previewHovered -> {
                // Grace period to travel from the icon up into the previews.
                delay(350)
                previewFor = null
            }
        }
    }
    val barEvents: (UiEvent) -> Unit = { event ->
        if (event is UiEvent.LaunchItem && apps.windowCount(event.itemId) > 1) {
            hover.target?.let { previewAnchor = it.centerX }
            previewFor = event.itemId
        } else {
            if (event is UiEvent.LaunchItem) previewFor = null
            onEvent(event)
        }
    }
    // Re-read on every app-list update, so a window closed from the preview disappears from it.
    val previewWindows = remember(previewFor, state.items) { previewFor?.let(apps::windowsOf).orEmpty() }

    // Never hide mid-use: while searching, showing previews or with a menu open the dock stays put.
    val busy by rememberUpdatedState(keyboard.granted || previewFor != null || hover.target != null || menuOpen)
    LaunchedEffect(capsule) {
        if (!capsule) {
            revealed = true
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val bottomPx = ((bounds.y + bounds.height) * scale).roundToInt()
            val bandTop = bottomPx - (BarHeight.value * scale).roundToInt()
            val centerPx = ((bounds.x + bounds.width / 2) * scale).roundToInt()
            val handleHalfPx = (HandleZoneWidth.value / 2 * scale).roundToInt()
            val handleTop = bottomPx - (HandleZoneHeight.value * scale).roundToInt()
            // Switching to a capsule theme gives the screen edge back, so maximized windows grow into the dock's
            // place at once; stay up a moment so the new theme is seen instead of flickering away.
            var lastNeeded = System.currentTimeMillis() + 2500
            revealed = true
            while (isActive) {
                val cursor = com.sun.jna.platform.win32.WinDef.POINT().also { User32.INSTANCE.GetCursorPos(it) }
                val now = System.currentTimeMillis()
                val covered = runCatching { apps.anyWindowReaches(bandTop) }.getOrDefault(false)
                // The very bottom edge anywhere, or the area around the handle shown while hidden.
                val atEdge = cursor.y >= bottomPx - 3 || (cursor.y >= handleTop && abs(cursor.x - centerPx) <= handleHalfPx)
                val overDock = cursor.y >= bandTop
                if (!covered || atEdge || busy || (revealed && overDock)) {
                    revealed = true
                    lastNeeded = now
                } else if (revealed && now - lastNeeded > 700) {
                    // A short grace after the pointer leaves, so it doesn't vanish on a twitch.
                    revealed = false
                }
                delay(120)
            }
        }
    }
    val slide by animateFloatAsState(if (revealed) 0f else 1f, tween(220, easing = FastOutSlowInEasing), label = "dock-slide")
    val onScreen = !fullscreenAppInFront && slide < .999f
    val liveGlass = state.visibleTheme() == DockTheme.GLASS
    LaunchedEffect(liveGlass, onScreen) { onGlassVisible(liveGlass && onScreen) }

    Window(
        onCloseRequest = { onEvent(UiEvent.RestoreNativeTaskbar) }, title = "WinDock",
        state = rememberWindowState().apply {
            // Follows the theme: the edge-to-edge bar is shorter than the capsule's window.
            position = WindowPosition(bounds.x.dp, bottom - barWindowHeight)
            size = DpSize(bounds.width.dp, barWindowHeight)
        },
        undecorated = true, transparent = true, resizable = false, alwaysOnTop = true, focusable = keyboard.granted,
        visible = onScreen,
    ) {
        val hwnd = remember { Native.getComponentPointer(window)?.let(::HWND) }
        // The bar never takes focus, except while the search field is in use: then it becomes an activatable
        // window just long enough to type, and goes back to "no-activate" as soon as focus moves elsewhere.
        LaunchedEffect(keyboard.granted, hwnd) {
            if (hwnd == null) return@LaunchedEffect
            val user32 = User32.INSTANCE
            val style = user32.GetWindowLong(hwnd, Win32.GWL_EXSTYLE)
            if (keyboard.granted) {
                // Enable AWT activation before asking Windows to focus this formerly non-activating bar.
                window.focusableWindowState = true
                user32.SetWindowLong(hwnd, Win32.GWL_EXSTYLE, style and Win32.WS_EX_NOACTIVATE.inv())
                window.toFront()
                user32.SetForegroundWindow(hwnd)
                window.requestFocus()
            } else {
                user32.SetWindowLong(hwnd, Win32.GWL_EXSTYLE, style or Win32.WS_EX_NOACTIVATE)
            }
        }
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    keyboard.granted = false
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        val appBar = remember(hwnd) { hwnd?.let(::DockAppBar) }
        // Glass shows what's behind the dock by capturing the screen, so the dock must not be in that capture.
        // Only while glass is on: hidden from captures, the dock is also missing from screenshots.
        DisposableEffect(hwnd, liveGlass) {
            if (liveGlass) hwnd?.let(LiveGlass::exclude)
            onDispose { if (liveGlass) hwnd?.let(LiveGlass::release) }
        }
        val reservedPx = (barWindowHeight.value * screen.defaultTransform.scaleY).roundToInt()
        DisposableEffect(appBar) {
            appBar?.makeToolWindow()
            NativeTaskbar.hide()
            onDispose {
                appBar?.release()
                NativeTaskbar.restore()
            }
        }
        // The capsule's window spans the whole screen width, but only the capsule should take clicks: while the
        // pointer is beside it, the window lets clicks through to whatever is underneath.
        LaunchedEffect(hwnd, capsule) {
            if (hwnd == null) return@LaunchedEffect
            val user32 = User32.INSTANCE
            fun passThrough(on: Boolean) {
                val style = user32.GetWindowLong(hwnd, Win32.GWL_EXSTYLE)
                val wanted = if (on) style or Win32.WS_EX_TRANSPARENT else style and Win32.WS_EX_TRANSPARENT.inv()
                if (wanted != style) user32.SetWindowLong(hwnd, Win32.GWL_EXSTYLE, wanted)
            }
            if (!capsule) {
                passThrough(false)
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                val leftPx = (bounds.x * scale).roundToInt()
                val bottomPx = ((bounds.y + bounds.height) * scale).roundToInt()
                val topPx = bottomPx - (BarHeight.value * scale).roundToInt()
                val slack = (4 * scale).roundToInt()
                var through = false
                while (isActive) {
                    val cursor = com.sun.jna.platform.win32.WinDef.POINT().also { user32.GetCursorPos(it) }
                    val span = hover.capsuleSpan
                    val overCapsule = span == null || (cursor.y >= topPx &&
                        cursor.x >= leftPx + span.start - slack && cursor.x <= leftPx + span.endInclusive + slack)
                    val wanted = !overCapsule
                    if (wanted != through) {
                        through = wanted
                        passThrough(wanted)
                        // The pointer may have slipped out without the window seeing it leave a tile.
                        if (wanted) javax.swing.SwingUtilities.invokeLater { hover.target = null }
                    }
                    delay(30)
                }
            }
        }
        val reserving by rememberUpdatedState(!capsule)
        val currentReservedPx by rememberUpdatedState(reservedPx)
        // Edge-to-edge themes reserve the strip so maximized windows stop above it; capsule themes give it back.
        LaunchedEffect(appBar, capsule, reservedPx) {
            withContext(Dispatchers.IO) {
                // Give Explorer a moment to release the native taskbar's space before claiming the edge.
                delay(500)
                runCatching { if (capsule) appBar?.release() else appBar?.reserve(reservedPx) }
            }
        }
        LaunchedEffect(appBar) {
            withContext(Dispatchers.IO) {
                while (isActive) {
                    runCatching {
                        // Explorer restarts drop every app bar, so claim the edge again whenever the taskbar comes back.
                        if (NativeTaskbar.keepHidden() && reserving) {
                            delay(500)
                            appBar?.reserve(currentReservedPx)
                        }
                        // Step aside for full-screen video and games, like the native taskbar does.
                        fullscreenAppInFront = hwnd != null && isFullscreenAppInFront(hwnd)
                        sceneHwnd?.let(::keepOnTop)
                    }
                    delay(1000)
                }
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = slide * size.height }) {
            CompositionLocalProvider(LocalSearchSession provides search) {
                DockBar(state, apps, artwork, hover, keyboard, barEvents, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    if (keyboard.granted) SearchResultsWindow(search, bounds, scale, bottom - barWindowHeight)

    if (previewFor != null && previewWindows.isNotEmpty()) {
        val size = PreviewLayout.size(previewWindows.size)
        // Centred over the icon (whose centre is in bar-window pixels), kept inside the screen.
        val anchorX = bounds.x.dp + (previewAnchor / scale.toFloat()).dp
        val left = (anchorX - size.width / 2).coerceIn(bounds.x.dp + 8.dp, bounds.x.dp + bounds.width.dp - size.width - 8.dp)
        val popupState = rememberWindowState(size = size)
        popupState.size = size
        popupState.position = WindowPosition(left, bottom - barWindowHeight - size.height - 8.dp)
        Window(
            onCloseRequest = { previewFor = null }, title = "WinDock Previews", state = popupState,
            undecorated = true, transparent = false, resizable = false, alwaysOnTop = true, focusable = false,
        ) {
            val popupHwnd = remember { Native.getComponentPointer(window)?.let(::HWND) }
            val thumbnails = remember(popupHwnd) { popupHwnd?.let(::WindowThumbnails) }
            LaunchedEffect(popupHwnd) {
                popupHwnd?.let { DockAppBar(it).makeToolWindow() }
                thumbnails?.roundCorners()
            }
            // Live images, re-registered whenever the set of windows (or the layout) changes.
            DisposableEffect(thumbnails, previewWindows.map { it.hwnd }) {
                previewWindows.forEachIndexed { i, w -> thumbnails?.show(w.hwnd, PreviewLayout.thumbRect(i, scale)) }
                onDispose { thumbnails?.clear() }
            }
            WindowPreviewStrip(
                windows = previewWindows,
                icon = previewFor?.let(apps::icon),
                isMinimized = { apps.isMinimized(it.hwnd) },
                onHoverChange = { previewHovered = it },
                onSelect = {
                    apps.focusWindow(it.hwnd)
                    previewFor = null
                    previewHovered = false
                },
                onClose = { apps.closeWindow(it.hwnd) },
            )
        }
    }

    // While the capsule is tucked away, a small handle at the bottom edge shows where it went; the first times it
    // hides, a hint says how to bring it back, so a vanished dock doesn't look like a crash.
    val tucked = capsule && !revealed && !fullscreenAppInFront
    var hintsShown by remember { mutableStateOf(0) }
    var handleHwnd by remember { mutableStateOf<HWND?>(null) }
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(tucked) {
        if (tucked && hintsShown < 3) {
            hintsShown++
            showHint = true
            delay(4500)
        }
        showHint = false
    }
    if (tucked) {
        Window(
            onCloseRequest = {}, title = "WinDock Handle",
            state = rememberWindowState().apply {
                size = DpSize(HandleWindowWidth, HandleWindowHeight)
                position = WindowPosition(bounds.x.dp + bounds.width.dp / 2 - HandleWindowWidth / 2, bottom - HandleWindowHeight)
            },
            undecorated = true, transparent = true, resizable = false, alwaysOnTop = true, focusable = false,
        ) {
            DisposableEffect(Unit) {
                // Purely a sign: clicks pass through to the window below, and the pointer is watched instead.
                handleHwnd = Native.getComponentPointer(window)?.let(::HWND)?.also(::makeClickThrough)
                onDispose { handleHwnd = null }
            }
            // Other always-on-top windows (Task Manager, players) must not cover it.
            LaunchedEffect(handleHwnd) {
                while (true) {
                    handleHwnd?.let(::keepOnTop)
                    delay(1000)
                }
            }
            DockHandle(showHint, Modifier.fillMaxSize())
        }
    }

    // Always present: besides the scene it carries the hover label, which has to rise above the bar.
    run {
        Window(
            onCloseRequest = {}, title = "WinDock Overlay",
            state = rememberWindowState().apply {
                position = WindowPosition(bounds.x.dp, bottom - sceneWindowHeight)
                size = DpSize(bounds.width.dp, sceneWindowHeight)
            },
            undecorated = true, transparent = true, resizable = false, alwaysOnTop = true, focusable = false,
            visible = onScreen,
        ) {
            DisposableEffect(Unit) {
                sceneHwnd = Native.getComponentPointer(window)?.let(::HWND)?.also(::makeClickThrough)
                onDispose { sceneHwnd = null }
            }
            DisposableEffect(sceneHwnd, liveGlass) {
                val overlay = sceneHwnd
                if (liveGlass) overlay?.let(LiveGlass::exclude)
                onDispose { if (liveGlass) overlay?.let(LiveGlass::release) }
            }
            // Slides with the bar, so the mascot rides down with it when the capsule tucks away.
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = slide * size.height }) {
                DockOverlay(state, hover, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/**
 * Search results, right above the search field. The window exists for as long as the field has the keyboard
 * and is only shown while there are results, so typing the first letter doesn't wait for a window to be built;
 * it's its own composable so each keystroke redraws just this panel, not the whole dock.
 */
@Composable
private fun SearchResultsWindow(search: SearchSession, bounds: java.awt.Rectangle, scale: Double, barTop: Dp) {
    val appIndex = LocalAppIndex.current ?: return
    val showing = search.query.isNotBlank() && search.results.isNotEmpty()
    val height = SearchLayout.height(search.results.size.coerceAtLeast(1))
    val left = (bounds.x.dp + (search.anchorX / scale.toFloat()).dp)
        .coerceIn(bounds.x.dp + 8.dp, bounds.x.dp + bounds.width.dp - SearchLayout.Width - 8.dp)
    val panelState = rememberWindowState(size = DpSize(SearchLayout.Width, height))
    panelState.size = DpSize(SearchLayout.Width, height)
    panelState.position = WindowPosition(left, barTop - height - 8.dp)
    Window(
        onCloseRequest = {}, title = "WinDock Search", state = panelState, visible = showing,
        undecorated = true, transparent = false, resizable = false, alwaysOnTop = true, focusable = false,
    ) {
        val panelHwnd = remember { Native.getComponentPointer(window)?.let(::HWND) }
        LaunchedEffect(panelHwnd) {
            panelHwnd?.let {
                DockAppBar(it).makeToolWindow()
                WindowThumbnails(it).roundCorners()
            }
        }
        SearchPanel(search, appIndex)
    }
}

/** A right-click menu and where it was asked for (the pointer, in physical pixels). */
private class OpenMenu(val menu: DockMenu, val x: Int, val y: Int)

/**
 * The right-click menu, in its own borderless window: centred on the pointer and, for the dock, resting just
 * above the bar like a jump list. It never takes focus (neither does the dock), so a click anywhere else or
 * Esc — watched for directly — closes it.
 */
@Composable
private fun MenuWindow(open: OpenMenu, aboveDock: Boolean, barHeight: Dp, onDismiss: () -> Unit) {
    val screen = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration }
    val bounds = screen.bounds
    val scale = screen.defaultTransform.scaleX.toFloat()
    val height = MenuLayout.height(open.menu)
    val pointerX = (open.x / scale).dp
    val pointerY = (open.y / scale).dp
    val left = (pointerX - MenuLayout.Width / 2).coerceIn(bounds.x.dp + 8.dp, (bounds.x + bounds.width).dp - MenuLayout.Width - 8.dp)
    val top = (if (aboveDock) (bounds.y + bounds.height).dp - barHeight - 8.dp - height else pointerY - 8.dp - height)
        .coerceAtLeast(bounds.y.dp + 8.dp)
    val windowState = rememberWindowState(size = DpSize(MenuLayout.Width, height), position = WindowPosition(left, top))
    Window(
        onCloseRequest = onDismiss, title = "WinDock Menu", state = windowState,
        undecorated = true, transparent = false, resizable = false, alwaysOnTop = true, focusable = false,
    ) {
        val menuHwnd = remember { Native.getComponentPointer(window)?.let(::HWND) }
        LaunchedEffect(menuHwnd) {
            menuHwnd?.let {
                DockAppBar(it).makeToolWindow()
                WindowThumbnails(it).roundCorners()
            }
        }
        LaunchedEffect(menuHwnd) {
            val user32 = User32.INSTANCE
            // The button that opened the menu may still be down; wait for it to come up first.
            while (user32.GetAsyncKeyState(VK_RBUTTON) < 0) delay(16)
            while (true) {
                delay(30)
                val pressed = user32.GetAsyncKeyState(VK_LBUTTON) < 0 || user32.GetAsyncKeyState(VK_RBUTTON) < 0
                if (user32.GetAsyncKeyState(VK_ESCAPE) < 0) break
                if (pressed && menuHwnd != null) {
                    val cursor = com.sun.jna.platform.win32.WinDef.POINT().also { user32.GetCursorPos(it) }
                    val rect = com.sun.jna.platform.win32.WinDef.RECT().also { user32.GetWindowRect(menuHwnd, it) }
                    if (cursor.x !in rect.left until rect.right || cursor.y !in rect.top until rect.bottom) break
                }
            }
            onDismiss()
        }
        DockMenuPopup(open.menu, onDismiss)
    }
}

private const val VK_LBUTTON = 0x01
private const val VK_RBUTTON = 0x02
private const val VK_ESCAPE = 0x1B

@Composable
private fun SettingsWindow(state: DockUiState, onEvent: (UiEvent) -> Unit, updateVisible: Boolean) {
    val initialSize = remember { comfortableWindowSize(680, 820) }
    Window(
        onCloseRequest = { onEvent(UiEvent.CancelThemeChanges) }, title = "Personalizar WinDock", icon = AppIcon,
        state = rememberWindowState(size = initialSize, position = WindowPosition(Alignment.Center)),
        resizable = true, alwaysOnTop = !updateVisible,
    ) {
        DisposableEffect(window) {
            window.minimumSize = java.awt.Dimension(minOf(520, initialSize.width.value.toInt()), minOf(480, initialSize.height.value.toInt()))
            onDispose { }
        }
        MaterialTheme { Surface(color = Color(0xFFFFFCF8)) { ThemeSettingsPanel(state, onEvent, Modifier.fillMaxSize()) } }
    }
}

@Composable
private fun DockPreview(state: DockUiState, apps: TaskbarApps, artwork: ImageBitmap?, hover: DockHover, onEvent: (UiEvent) -> Unit) {
    val previewTheme = state.visibleTheme()
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = if (state.settingsOpen) Arrangement.Top else Arrangement.Center) {
        DockStage(state, apps, artwork, hover, onEvent)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WINDOWS 11", color = Color(0xFF0066C0), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFEAF3FD)).padding(horizontal = 9.dp, vertical = 4.dp))
            Spacer(Modifier.width(10.dp))
            Text("Prévia — seus apps fixados e abertos já aparecem aqui", color = Color(0xFF59616E), fontSize = 12.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Tema ${previewTheme.label}", color = Color(0xFF59616E), fontSize = 12.sp,
                modifier = Modifier.clickable { onEvent(UiEvent.ToggleSettings) }.padding(6.dp))
            Text("◌", color = Color(0xFF59616E), fontSize = 17.sp,
                modifier = Modifier.clickable { onEvent(UiEvent.ToggleReducedMotion) }.padding(horizontal = 8.dp))
        }
        if (state.settingsOpen) {
            Spacer(Modifier.height(16.dp))
            Surface(modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally).widthIn(max = 920.dp).fillMaxWidth(),
                shape = RoundedCornerShape(22.dp), color = Color(0xFFFFFCF8), shadowElevation = 4.dp) {
                ThemeSettingsPanel(state, onEvent, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ThemeSettingsPanel(state: DockUiState, onEvent: (UiEvent) -> Unit, modifier: Modifier = Modifier) {
    val toSystem = state.draftTheme == DockTheme.SYSTEM
    Column(modifier.padding(top = 22.dp, bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Personalize seu dock", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(if (state.dockActive) "O dock está substituindo a barra de tarefas do Windows."
                else "Escolha um visual, confira a prévia e aplique ao Windows.", color = Color(0xFF59616E), fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        // One scrolling column, so the theme list gets room instead of being squeezed between the controls.
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            Column(
                Modifier.verticalScroll(scroll).padding(start = 22.dp, end = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemePreview(state.draftTheme, state.randomPaletteIndex)
                UpdateSettingsRow()
                SectionTitle("Tema")
                DockTheme.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { theme -> ThemeCard(theme, theme == state.draftTheme, state.randomPaletteIndex, Modifier.weight(1f)) { onEvent(UiEvent.SelectTheme(theme)) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (toSystem) {
                    Text(
                        "Ao aplicar, o WinDock devolve a barra de tarefas do Windows e fecha. Para usá-lo de novo, abra o WinDock pelo menu Iniciar.",
                        color = Color(0xFF59616E), fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF1F4F8)).padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                if (state.draftTheme.hasMascot()) MascotPicker(state.mascot) { onEvent(UiEvent.SelectMascot(it)) }
                if (!toSystem) {
                    SectionTitle("Preferências")
                    SettingSwitch("Animações decorativas", state.decorativeAnimationsEnabled) { onEvent(UiEvent.ToggleDecorativeAnimations) }
                    SettingSwitch("Iniciar com o Windows", state.startWithWindows) { onEvent(UiEvent.ToggleStartWithWindows) }
                    SettingSwitch("Detectar município pela localização do Windows", state.weatherAutoLocate) { onEvent(UiEvent.ToggleWeatherAutoLocate) }
                    if (state.weatherAutoLocate && state.locationDenied) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFF4E0)).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("A localização está desativada para apps da área de trabalho. Usando a cidade abaixo.",
                                color = Color(0xFF7A4B00), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onEvent(UiEvent.LaunchItem(ShellAction.LOCATION_SETTINGS)) }) { Text("Ativar") }
                        }
                    }
                    WeatherCityField(if (state.weatherAutoLocate) "Cidade se não houver localização" else "Cidade do clima", state.weatherCity) {
                        onEvent(UiEvent.SetWeatherCity(it))
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp).width(8.dp),
            )
        }
        // The actions stay put at the bottom.
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Color(0xFFECEEF2)))
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { onEvent(UiEvent.CancelThemeChanges) }) { Text("Cancelar") }
            Spacer(Modifier.width(8.dp))
            when {
                toSystem -> Button(onClick = { onEvent(UiEvent.ApplyTheme) }) { Text("Voltar à barra do Windows") }
                state.dockActive -> Button(onClick = { onEvent(UiEvent.ApplyTheme) }) { Text("Aplicar") }
                else -> Button(onClick = { onEvent(UiEvent.ApplyToWindows) }, enabled = Win32.isWindows) { Text("Aplicar no Windows") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Color(0xFF8A919C), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ThemeCard(theme: DockTheme, selected: Boolean, randomPaletteIndex: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(64.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFFEAF3FD) else Color.White)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Blue else Color(0xFFE5E7EB), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(themePalette(theme, randomPaletteIndex)))
                .border(1.dp, Color.Black.copy(alpha = .06f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // The System choice shows a little Windows logo: it means "the normal taskbar".
            if (theme == DockTheme.SYSTEM) Text("⊞", color = Color(0xFF0B6DDF), fontSize = 16.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(theme.label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(themeDescription(theme), color = Color(0xFF69717C), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp)
        }
    }
}

/** City for the weather tile; applied on Enter or with the button, not on every keystroke. */
@Composable
private fun WeatherCityField(label: String, city: String, onApply: (String) -> Unit) {
    var text by remember(city) { mutableStateOf(city) }
    val changed = text.trim().isNotEmpty() && text.trim() != city
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            modifier = Modifier.width(170.dp).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && changed) {
                    onApply(text)
                    true
                } else {
                    false
                }
            },
        )
        TextButton(onClick = { onApply(text) }, enabled = changed) { Text("Salvar") }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun ThemePreview(theme: DockTheme, randomPaletteIndex: Int) {
    Box(Modifier.fillMaxWidth().height(82.dp).clip(RoundedCornerShape(14.dp))
        .background(Brush.horizontalGradient(themePalette(theme, randomPaletteIndex))).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Color(0xFF1685F8), Color.White, Color.White, Color.White).forEachIndexed { index, color ->
                Box(Modifier.size(if (index == 0) 34.dp else 30.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .96f)))
            }
            Box(Modifier.width(90.dp).height(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF292331)))
        }
        Text(theme.label.uppercase(), color = barInk(theme, themePalette(theme, randomPaletteIndex)).content,
            fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

internal fun themePalette(theme: DockTheme, randomPaletteIndex: Int = 0): List<Color> = when (theme) {
    DockTheme.SYSTEM -> listOf(Color(0xFFE9ECF1), Color(0xFFF5F6F8), Color(0xFFE2E5EA))
    DockTheme.CASUAL -> listOf(Color(0xFFF4E9D9), Color(0xFFFFFCF7), Color(0xFFE8DCC8))
    DockTheme.HALLOWEEN_GHOST -> listOf(Color(0xFF171326), Color(0xFF352544), Color(0xFF75516E))
    DockTheme.HALLOWEEN_ZOMBIES -> listOf(Color(0xFF171C1D), Color(0xFF303A32), Color(0xFF663B34))
    DockTheme.HALLOWEEN_TRICK_OR_TREAT -> listOf(Color(0xFF241638), Color(0xFF3F1F4D), Color(0xFF7A3418))
    DockTheme.AUTUMN -> listOf(Color(0xFFF1D6AE), Color(0xFFF8E8D0), Color(0xFFE6B680))
    DockTheme.CHRISTMAS -> listOf(Color(0xFF0F3D30), Color(0xFF17573F), Color(0xFF7A1E2C))
    DockTheme.CHRISTMAS_WORKSHOP -> listOf(Color(0xFF6E2420), Color(0xFF5A3A24), Color(0xFF1F4A36))
    DockTheme.CHRISTMAS_COZY -> listOf(Color(0xFF3E2618), Color(0xFF5E3A22), Color(0xFF6B2A1E))
    DockTheme.DARK -> listOf(Color(0xFF343B48), Color(0xFF20242D), Color(0xFF303642))
    // Smoked glass: dark enough that white text reads over any wallpaper, light enough to see through.
    DockTheme.FLOATING -> listOf(Color(0x8C1C2030), Color(0x8C2A2F40), Color(0x8C1C2030))
    DockTheme.GLASS -> listOf(Color(0xB3E9EEF6), Color(0x80FFFFFF), Color(0xB3DCE6F2))
    DockTheme.RANDOM -> listOf(
        listOf(Color(0xFFD9C4FF), Color(0xFFFFD8C7), Color(0xFFB6E8D2)),
        listOf(Color(0xFFFFD166), Color(0xFFEF476F), Color(0xFF118AB2)),
        listOf(Color(0xFFB8E0D2), Color(0xFFD6CDEA), Color(0xFFEAC4D5)),
        listOf(Color(0xFFCDE7BE), Color(0xFFFAEDCB), Color(0xFFF2C6DE)),
    )[randomPaletteIndex.coerceIn(0, 3)]
}

private fun themeDescription(theme: DockTheme) = when (theme) {
    DockTheme.SYSTEM -> "Volta à barra de tarefas padrão do Windows"
    DockTheme.CASUAL -> "Cores quentes e neutras"
    DockTheme.HALLOWEEN_GHOST -> "Fantasma viajando pela noite"
    DockTheme.HALLOWEEN_ZOMBIES -> "Zumbis despertando no cemitério"
    DockTheme.HALLOWEEN_TRICK_OR_TREAT -> "Crianças fantasiadas pedindo doces"
    DockTheme.AUTUMN -> "Abóboras e folhas douradas caindo"
    DockTheme.CHRISTMAS -> "Pinheiro, neve e pisca-pisca"
    DockTheme.CHRISTMAS_WORKSHOP -> "Papai Noel e elfos na oficina"
    DockTheme.CHRISTMAS_COZY -> "Lareira crepitando e árvore piscando"
    DockTheme.DARK -> "Grafite com detalhes suaves"
    DockTheme.FLOATING -> "Ícones no centro, flutuando sobre o papel de parede"
    DockTheme.GLASS -> "Vidro fosco que deixa ver o papel de parede, como no macOS"
    DockTheme.RANDOM -> "Combinação surpresa de cores"
}
