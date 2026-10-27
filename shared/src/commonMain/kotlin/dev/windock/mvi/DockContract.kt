package dev.windock.mvi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DockTheme(val label: String) {
    /** Not a look: applying it hands the screen back to the native taskbar and closes the dock. */
    SYSTEM("Sistema"),
    CASUAL("Casual"),
    HALLOWEEN_GHOST("Halloween · Fantasma"),
    HALLOWEEN_ZOMBIES("Halloween · Zumbis"),
    HALLOWEEN_TRICK_OR_TREAT("Halloween · Doces ou travessuras"),
    AUTUMN("Outono"),
    CHRISTMAS("Natal · Boneco de neve"),
    CHRISTMAS_WORKSHOP("Natal · Oficina do Papai Noel"),
    CHRISTMAS_COZY("Natal · Casa aconchegante"),
    DARK("Escuro"),
    /** No edge-to-edge bar: a translucent capsule centred over the wallpaper. */
    FLOATING("Flutuante"),
    /** Frosted glass: a floating capsule showing the blurred wallpaper behind it, like the macOS dock. */
    GLASS("Vidro"),
    RANDOM("Aleatório"),
}

/**
 * An app on the dock: pinned to the taskbar, running, or both. [id] is stable per app (the host
 * uses the executable path) so pinned shortcuts and their open windows collapse into one icon.
 */
data class DockItem(
    val id: String,
    val label: String,
    val isRunning: Boolean = false,
    val isPinned: Boolean = false,
    val isActive: Boolean = false,
    val windowCount: Int = 0,
)

/** Shell actions the dock offers besides apps; the host maps each to the matching OS feature. */
object ShellAction {
    const val START = "shell:start"
    const val DOWNLOADS = "shell:downloads"
    const val RECYCLE_BIN = "shell:recycle-bin"
    const val QUICK_SETTINGS = "shell:quick-settings"
    const val NOTIFICATIONS = "shell:notifications"
    const val WIDGETS = "shell:widgets"
    const val SHOW_DESKTOP = "shell:show-desktop"
    const val LOCATION_SETTINGS = "shell:location-settings"
    /** The bar itself (empty space), for its context menu. */
    const val BAR = "dock:bar"
}

/** An app the user pinned from the dock's own menu. [target] is what launches it (an .exe or a shortcut). */
data class PinnedApp(val id: String, val label: String, val target: String)

data class BatteryStatus(val percent: Int, val pluggedIn: Boolean, val charging: Boolean, val minutesLeft: Int? = null)

enum class NetworkKind { WIFI, ETHERNET, NONE }

/** Live system readings shown in the dock's tray; supplied by the host. */
data class SystemStatus(
    /** Null on machines without a battery. */
    val battery: BatteryStatus? = null,
    val network: NetworkKind = NetworkKind.NONE,
    /** 0..100, or null when no audio endpoint could be read. */
    val volumePercent: Int? = null,
    val muted: Boolean = false,
    val recycleBinFull: Boolean = false,
)

enum class PlaybackState { PLAYING, PAUSED, STOPPED }

enum class MediaCommand { TOGGLE_PLAY_PAUSE, NEXT, PREVIOUS }

/**
 * What the system's current media session is playing. Position is a snapshot taken at [updatedAtMs];
 * while playing, the UI extrapolates from there instead of the host pushing every second.
 */
data class MediaStatus(
    val title: String,
    val artist: String,
    val app: String,
    val state: PlaybackState,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val canPrevious: Boolean,
    val canNext: Boolean,
    /** Bumped whenever the host has new cover art, so the UI knows to re-read it. */
    val artworkVersion: Int = 0,
)

/** Current conditions; [code] is a WMO weather code. [city] is the detected municipality or the typed city. */
data class WeatherStatus(
    val city: String,
    val temperatureC: Int,
    val code: Int,
    val isDay: Boolean,
    val updatedAtMs: Long,
    /** True when [city] came from the Windows location service rather than the settings. */
    val fromDevice: Boolean = false,
)

/** The animal that walks along the dock in the Casual, Floating and Glass themes. */
enum class Mascot(val label: String) {
    CAT("Gato"),
    DOG("Cachorro"),
    JAGUAR("Onça"),
    WESTERN_DRAGON("Dragão ocidental"),
    EASTERN_DRAGON("Dragão oriental"),
    NONE("Nenhum"),
}

data class DockUiState(
    val theme: DockTheme = DockTheme.CASUAL,
    val draftTheme: DockTheme = DockTheme.CASUAL,
    val randomPaletteIndex: Int = 0,
    val items: List<DockItem> = emptyList(),
    val decorativeAnimationsEnabled: Boolean = true,
    val reducedMotion: Boolean = false,
    val settingsOpen: Boolean = true,
    /** True while the dock replaces the native taskbar; false while it's only a preview window. */
    val dockActive: Boolean = false,
    val startWithWindows: Boolean = false,
    val system: SystemStatus = SystemStatus(),
    val media: MediaStatus? = null,
    val weather: WeatherStatus? = null,
    val weatherCity: String = "São Paulo",
    /** Locate the municipality through Windows; [weatherCity] is the fallback. */
    val weatherAutoLocate: Boolean = true,
    /** The last location request was refused (location off or blocked for desktop apps). */
    val locationDenied: Boolean = false,
    /** Pins added from the dock, shown after the ones imported from the native taskbar. */
    val pinnedApps: List<PinnedApp> = emptyList(),
    /** Native taskbar pins the user unpinned from the dock; the native taskbar itself is left alone. */
    val hiddenPins: Set<String> = emptySet(),
    val mascot: Mascot = Mascot.CAT,
)

sealed interface UiEvent {
    data class LaunchItem(val itemId: String) : UiEvent
    data class SelectTheme(val theme: DockTheme) : UiEvent
    data object ToggleSettings : UiEvent
    data object ApplyTheme : UiEvent
    /** Commits the draft theme and swaps the native taskbar for the dock. */
    data object ApplyToWindows : UiEvent
    /** Gives the screen edge back to the native taskbar and returns to the preview window. */
    data object RestoreNativeTaskbar : UiEvent
    data object CancelThemeChanges : UiEvent
    data object ToggleDecorativeAnimations : UiEvent
    data object ToggleReducedMotion : UiEvent
    data object ToggleStartWithWindows : UiEvent
    data class SelectMascot(val mascot: Mascot) : UiEvent
    /** Nudges the system volume by [deltaPercent], e.g. from the mouse wheel over the tray. */
    data class ChangeVolume(val deltaPercent: Int) : UiEvent
    data class ControlMedia(val command: MediaCommand) : UiEvent
    data class SetWeatherCity(val city: String) : UiEvent
    data object ToggleWeatherAutoLocate : UiEvent
    /** Right-click on an app, a shell entry or the bar ([ShellAction.BAR]). */
    data class OpenContextMenu(val itemId: String) : UiEvent
    data class PinApp(val app: PinnedApp) : UiEvent
    data class UnpinApp(val itemId: String) : UiEvent
    /** Opens a URI or command (an app, a Settings page, a search) — used by the dock's search results. */
    data class OpenTarget(val target: String) : UiEvent
}

sealed interface NavEvent {
    data class OpenSettings(val section: String = "appearance") : NavEvent
    data object CloseSettings : NavEvent
    /** The "System" theme was applied: give the native taskbar back for good and quit. */
    data object ReturnToSystem : NavEvent
    data class LaunchItem(val itemId: String) : NavEvent
    data class ChangeVolume(val deltaPercent: Int) : NavEvent
    data class ControlMedia(val command: MediaCommand) : NavEvent
    data class ContextMenu(val itemId: String) : NavEvent
    data class OpenTarget(val target: String) : NavEvent
}

class DockStore(initial: DockUiState = DockUiState()) {
    private val _state = MutableStateFlow(initial)
    val state = _state.asStateFlow()
    private val _navigation = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    val navigation = _navigation.asSharedFlow()

    fun dispatch(event: UiEvent) {
        when (event) {
            is UiEvent.LaunchItem -> _navigation.tryEmit(NavEvent.LaunchItem(event.itemId))
            is UiEvent.ChangeVolume -> _navigation.tryEmit(NavEvent.ChangeVolume(event.deltaPercent))
            is UiEvent.ControlMedia -> _navigation.tryEmit(NavEvent.ControlMedia(event.command))
            is UiEvent.SetWeatherCity -> {
                val city = event.city.trim()
                if (city.isNotEmpty() && city != _state.value.weatherCity) {
                    // A located forecast stays valid; a city-based one is now for the wrong place.
                    _state.update { it.copy(weatherCity = city, weather = it.weather?.takeIf { w -> w.fromDevice }) }
                }
            }
            UiEvent.ToggleWeatherAutoLocate -> _state.update { it.copy(weatherAutoLocate = !it.weatherAutoLocate, weather = null) }
            is UiEvent.OpenContextMenu -> _navigation.tryEmit(NavEvent.ContextMenu(event.itemId))
            is UiEvent.OpenTarget -> _navigation.tryEmit(NavEvent.OpenTarget(event.target))
            // Re-pinning a hidden native pin just un-hides it; anything else becomes a dock pin.
            is UiEvent.PinApp -> _state.update {
                when {
                    event.app.id in it.hiddenPins -> it.copy(hiddenPins = it.hiddenPins - event.app.id)
                    it.pinnedApps.any { p -> p.id == event.app.id } -> it
                    else -> it.copy(pinnedApps = it.pinnedApps + event.app)
                }
            }
            is UiEvent.UnpinApp -> _state.update {
                if (it.pinnedApps.any { p -> p.id == event.itemId }) it.copy(pinnedApps = it.pinnedApps.filterNot { p -> p.id == event.itemId })
                else it.copy(hiddenPins = it.hiddenPins + event.itemId)
            }
            is UiEvent.SelectTheme -> _state.value = _state.value.copy(draftTheme = event.theme)
            UiEvent.ToggleSettings -> {
                val open = !_state.value.settingsOpen
                _state.value = _state.value.copy(settingsOpen = open, draftTheme = _state.value.theme)
                _navigation.tryEmit(if (open) NavEvent.OpenSettings() else NavEvent.CloseSettings)
            }
            UiEvent.ApplyTheme -> commitDraft(dockActive = _state.value.dockActive)
            UiEvent.ApplyToWindows -> commitDraft(dockActive = true)
            UiEvent.RestoreNativeTaskbar -> _state.value = _state.value.copy(dockActive = false, settingsOpen = false)
            UiEvent.CancelThemeChanges -> {
                _state.value = _state.value.copy(draftTheme = _state.value.theme, settingsOpen = false)
                _navigation.tryEmit(NavEvent.CloseSettings)
            }
            UiEvent.ToggleDecorativeAnimations -> _state.value = _state.value.copy(decorativeAnimationsEnabled = !_state.value.decorativeAnimationsEnabled)
            UiEvent.ToggleReducedMotion -> _state.value = _state.value.copy(reducedMotion = !_state.value.reducedMotion)
            UiEvent.ToggleStartWithWindows -> _state.value = _state.value.copy(startWithWindows = !_state.value.startWithWindows)
            is UiEvent.SelectMascot -> _state.update { it.copy(mascot = event.mascot) }
        }
    }

    /** Host-side updates of the pinned/running apps; not user input, so it isn't a [UiEvent]. */
    fun updateItems(items: List<DockItem>) {
        // Called from a background poller, so use an atomic update rather than read-copy-write.
        _state.update { it.copy(items = items) }
    }

    fun updateSystemStatus(status: SystemStatus) {
        _state.update { it.copy(system = status) }
    }

    fun updateMedia(media: MediaStatus?) {
        _state.update { it.copy(media = media) }
    }

    fun updateWeather(weather: WeatherStatus?, locationDenied: Boolean) {
        _state.update { it.copy(weather = weather ?: it.weather, locationDenied = locationDenied) }
    }

    private fun commitDraft(dockActive: Boolean) {
        val current = _state.value
        if (current.draftTheme == DockTheme.SYSTEM) {
            _state.value = current.copy(theme = DockTheme.SYSTEM, settingsOpen = false, dockActive = false)
            _navigation.tryEmit(NavEvent.ReturnToSystem)
            return
        }
        val paletteIndex = if (current.draftTheme == DockTheme.RANDOM) kotlin.random.Random.nextInt(4) else current.randomPaletteIndex
        _state.value = current.copy(theme = current.draftTheme, randomPaletteIndex = paletteIndex, settingsOpen = false, dockActive = dockActive)
        _navigation.tryEmit(NavEvent.CloseSettings)
    }
}
