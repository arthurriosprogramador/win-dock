package dev.windock.windows

import androidx.compose.ui.graphics.ImageBitmap
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Kernel32Util
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.Version
import com.sun.jna.Memory
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import dev.windock.mvi.DockItem
import dev.windock.mvi.PinnedApp
import dev.windock.mvi.ShellAction
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** A top-level window that the native taskbar would show a button for. */
internal data class AppWindow(val hwnd: HWND, val title: String, val exePath: String)

/** A shortcut pinned to the native taskbar. [target] is the resolved executable when the .lnk records one. */
internal data class PinnedShortcut(val file: File, val name: String, val target: String?, val arguments: String? = null)

/**
 * Mirrors what the native taskbar shows: shortcuts pinned to it plus every app with an open window,
 * merged by executable so a pinned Chrome and its open windows are one icon.
 */
internal class TaskbarApps {
    private val user32 = User32.INSTANCE
    private val pinnedFolder = File(System.getenv("APPDATA") ?: "", "Microsoft\\Internet Explorer\\Quick Launch\\User Pinned\\TaskBar")
    private var pinnedStamp = -1L
    private var pinned: List<PinnedShortcut> = emptyList()
    private val exeByPid = HashMap<Int, String>()
    private val icons = ConcurrentHashMap<String, ImageBitmap>()
    private val missingIcons = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var windowsByApp: Map<String, List<AppWindow>> = emptyMap()
    @Volatile private var launchTargets: Map<String, String> = emptyMap()
    @Volatile private var labels: Map<String, String> = emptyMap()

    /** Pins added from the dock and native pins hidden from it; set from the UI state, read on refresh. */
    @Volatile var dockPins: List<PinnedApp> = emptyList()
    @Volatile var hiddenPins: Set<String> = emptySet()

    fun icon(id: String): ImageBitmap? = icons[id]

    /** Rescans pinned shortcuts (only when the folder changed) and open windows. Call off the UI thread. */
    fun refresh(): List<DockItem> {
        if (!Win32.isWindows) return emptyList()
        val stamp = pinnedFolder.lastModified()
        if (stamp != pinnedStamp) {
            pinnedStamp = stamp
            pinned = pinnedFolder.listFiles { f -> f.extension.equals("lnk", true) }.orEmpty()
                .sortedBy { it.name.lowercase() }
                .map { PinnedShortcut(it, it.nameWithoutExtension, readShortcutTarget(it), readShortcutArguments(it)) }
        }

        val groups = LinkedHashMap<String, MutableList<AppWindow>>()
        enumerateAppWindows().forEach { groups.getOrPut(appId(it)) { mutableListOf() } += it }
        val foreground = user32.GetForegroundWindow()
        val launch = HashMap<String, String>()
        val items = mutableListOf<DockItem>()
        fun item(id: String, label: String, pinned: Boolean): DockItem {
            val windows = groups[id].orEmpty()
            return DockItem(id, label, isRunning = windows.isNotEmpty(), isPinned = pinned,
                isActive = windows.any { it.hwnd == foreground }, windowCount = windows.size)
        }

        // Native taskbar pins first (minus any hidden from the dock), then the dock's own pins,
        // then everything else that has a window open.
        pinned.forEach { shortcut ->
            val id = shortcut.target?.lowercase() ?: shortcut.file.path.lowercase()
            if (id in hiddenPins) return@forEach
            launch[id] = shortcut.file.path
            // The shortcut carries the icon the taskbar shows (PWAs and Store apps have no useful exe icon).
            loadIcon(id, shortcut.file)
            items += item(id, shortcut.name, pinned = true)
        }
        dockPins.forEach { pin ->
            if (items.any { it.id == pin.id }) return@forEach
            launch[pin.id] = pin.target
            loadIcon(pin.id, File(pin.target))
            items += item(pin.id, pin.label, pinned = true)
        }
        val pinnedIds = items.mapTo(HashSet()) { it.id }
        groups.filterKeys { it !in pinnedIds }.forEach { (id, windows) ->
            val exe = File(windows.first().exePath)
            launch[id] = exe.path
            loadIcon(id, exe)
            items += item(id, if (id.startsWith("hwnd:")) windows.first().title else friendlyName(exe), pinned = false)
        }
        windowsByApp = groups
        launchTargets = launch
        labels = items.associate { it.id to it.label }
        return items
    }

    /** What a pin of this app would launch, or null when it can't be pinned (e.g. a Store app's window). */
    fun pinFor(id: String): PinnedApp? {
        if (id.startsWith("hwnd:")) return null
        val target = launchTargets[id] ?: return null
        return PinnedApp(id, labels[id] ?: File(target).nameWithoutExtension, target)
    }

    fun windowCount(id: String) = windowsByApp[id].orEmpty().size

    /**
     * The app's jump list. Its file is keyed by the AppUserModelID, which may come from an open window, from the
     * pinned shortcut, or be derived from the executable's path; the first ID with a list wins.
     */
    fun jumpList(id: String): List<JumpCategory> {
        val candidates = LinkedHashSet<String>()
        windowsByApp[id].orEmpty().take(3).forEach { w -> JumpLists.appUserModelIdOf(w.hwnd)?.let(candidates::add) }
        pinned.firstOrNull { (it.target?.lowercase() ?: it.file.path.lowercase()) == id }?.let { JumpLists.appUserModelIdOf(it.file) }?.let(candidates::add)
        if (!id.startsWith("hwnd:")) {
            val exe = windowsByApp[id]?.firstOrNull()?.exePath ?: launchTargets[id]?.takeUnless { it.endsWith(".lnk", true) } ?: id
            candidates += JumpLists.defaultAppUserModelId(exe)
        }
        return candidates.firstNotNullOfOrNull { aumid -> JumpLists.read(aumid).takeIf { it.isNotEmpty() } }.orEmpty()
    }

    /**
     * The pinned name of a browser web app (e.g. "YouTube Music") from its extension id, as found in a
     * media session's AppUserModelID like "Chrome._crx_<id>". Pinned web-app shortcuts carry "--app-id=<id>".
     */
    fun webAppName(extensionId: String): String? =
        pinned.firstOrNull { it.arguments?.contains("--app-id=$extensionId") == true }?.name

    /** The app's open windows, most recently used first (EnumWindows walks the z-order top-down). */
    fun windowsOf(id: String): List<AppWindow> = windowsByApp[id].orEmpty()

    fun focusWindow(hwnd: HWND) = bringToFront(hwnd)

    /** True when a visible, non-minimized app window reaches below [topPx] (physical pixels) — e.g. a maximized one. */
    fun anyWindowReaches(topPx: Int): Boolean = windowsByApp.values.any { windows ->
        windows.any { w ->
            user32.IsWindowVisible(w.hwnd) && !User32Ex.INSTANCE.IsIconic(w.hwnd) &&
                com.sun.jna.platform.win32.WinDef.RECT().also { user32.GetWindowRect(w.hwnd, it) }.let { it.bottom > topPx && it.right > it.left }
        }
    }

    fun closeWindow(hwnd: HWND) {
        user32.PostMessage(hwnd, WM_CLOSE, null, null)
    }

    fun isMinimized(hwnd: HWND) = User32Ex.INSTANCE.IsIconic(hwnd)

    /** Starts another instance — a new window for most apps. */
    fun launchNew(id: String) {
        launchTargets[id]?.let(::shellOpen)
    }

    fun runAsAdministrator(id: String) {
        launchTargets[id]?.let { Shell32.INSTANCE.ShellExecute(null, "runas", it, null, null, Win32.SW_SHOWNORMAL) }
    }

    /** Opens Explorer with the app's executable (or its shortcut) selected. */
    fun openFileLocation(id: String) {
        val target = launchTargets[id] ?: return
        val file = if (target.endsWith(".lnk", ignoreCase = true)) readShortcutTarget(File(target))?.takeIf { File(it).exists() } ?: target else target
        Shell32.INSTANCE.ShellExecute(null, "open", "explorer.exe", "/select,\"$file\"", null, Win32.SW_SHOWNORMAL)
    }

    /** Asks every window of the app to close, as the taskbar's "Close window" does (unsaved work still prompts). */
    fun closeWindows(id: String) {
        windowsByApp[id].orEmpty().forEach { user32.PostMessage(it.hwnd, WM_CLOSE, null, null) }
    }

    /** Click on an app: launch it, bring its window forward, minimize it if already in front, or cycle its windows. */
    fun activate(id: String) {
        val windows = windowsByApp[id].orEmpty()
        if (windows.isEmpty()) {
            launchTargets[id]?.let(::shellOpen)
            return
        }
        val foreground = user32.GetForegroundWindow()
        val frontIndex = windows.indexOfFirst { it.hwnd == foreground }
        when {
            frontIndex < 0 -> bringToFront(windows.first().hwnd)
            windows.size == 1 -> user32.ShowWindow(windows[0].hwnd, Win32.SW_MINIMIZE)
            else -> bringToFront(windows[(frontIndex + 1) % windows.size].hwnd)
        }
    }

    fun runShellAction(action: String) {
        when (action) {
            ShellAction.START -> pressKeys(Win32.VK_LWIN)
            ShellAction.QUICK_SETTINGS -> pressKeys(Win32.VK_LWIN, 'A'.code)
            ShellAction.NOTIFICATIONS -> pressKeys(Win32.VK_LWIN, 'N'.code)
            ShellAction.WIDGETS -> pressKeys(Win32.VK_LWIN, 'W'.code)
            // Win+D toggles: minimizes everything to show the desktop, and a second press brings it all back.
            ShellAction.SHOW_DESKTOP -> pressKeys(Win32.VK_LWIN, 'D'.code)
            ShellAction.LOCATION_SETTINGS -> shellOpen("ms-settings:privacy-location")
            ShellAction.DOWNLOADS -> shellOpen("shell:Downloads")
            ShellAction.RECYCLE_BIN -> shellOpen("shell:RecycleBinFolder")
        }
    }

    fun openPowerUserMenu() = pressKeys(Win32.VK_LWIN, 'X'.code)

    /** Shows the standard "empty the Recycle Bin?" confirmation and empties it. */
    fun emptyRecycleBin() {
        Shell32Ex.INSTANCE.SHEmptyRecycleBin(null, null, 0)
    }


    private fun enumerateAppWindows(): List<AppWindow> {
        val ownPid = ProcessHandle.current().pid().toInt()
        val found = mutableListOf<AppWindow>()
        val livePids = HashSet<Int>()
        user32.EnumWindows({ hwnd, _ ->
            if (isTaskbarWindow(hwnd)) {
                val pidRef = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, pidRef)
                val pid = pidRef.value
                livePids += pid
                if (pid != ownPid) {
                    val exe = exeByPid.getOrPut(pid) { processPath(pid) }
                    if (exe.isNotEmpty()) found += AppWindow(hwnd, windowTitle(hwnd), exe)
                }
            }
            true
        }, null)
        exeByPid.keys.retainAll(livePids)
        return found
    }

    /** The same filter the taskbar uses: visible, unowned, not a tool window, titled and not cloaked by DWM. */
    private fun isTaskbarWindow(hwnd: HWND): Boolean {
        if (!user32.IsWindowVisible(hwnd)) return false
        if (user32.GetWindow(hwnd, DWORD(Win32.GW_OWNER.toLong())) != null) return false
        val exStyle = user32.GetWindowLong(hwnd, Win32.GWL_EXSTYLE)
        val appWindow = exStyle and Win32.WS_EX_APPWINDOW != 0
        if (!appWindow && (exStyle and Win32.WS_EX_TOOLWINDOW != 0 || exStyle and Win32.WS_EX_NOACTIVATE != 0)) return false
        if (user32.GetWindowTextLength(hwnd) == 0) return false
        val cloaked = IntByReference()
        if (Dwmapi.INSTANCE.DwmGetWindowAttribute(hwnd, Win32.DWMWA_CLOAKED, cloaked, 4) == 0 && cloaked.value != 0) return false
        return true
    }

    private fun appId(window: AppWindow): String {
        // Store (UWP) apps all run inside ApplicationFrameHost, so they're told apart by window.
        val exe = window.exePath.lowercase()
        return if (exe.endsWith("\\applicationframehost.exe")) "hwnd:${com.sun.jna.Pointer.nativeValue(window.hwnd.pointer)}" else exe
    }

    private fun windowTitle(hwnd: HWND): String {
        val buffer = CharArray(user32.GetWindowTextLength(hwnd) + 1)
        user32.GetWindowText(hwnd, buffer, buffer.size)
        return com.sun.jna.Native.toString(buffer)
    }

    private fun processPath(pid: Int): String {
        val process = Kernel32.INSTANCE.OpenProcess(Win32.PROCESS_QUERY_LIMITED_INFORMATION, false, pid) ?: return ""
        return try {
            Kernel32Util.QueryFullProcessImageName(process, 0)
        } catch (_: Exception) {
            ""
        } finally {
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }

    private fun bringToFront(hwnd: HWND) {
        if (User32Ex.INSTANCE.IsIconic(hwnd)) user32.ShowWindow(hwnd, Win32.SW_RESTORE)
        // SwitchToThisWindow is what Alt+Tab uses; it isn't subject to the foreground-lock that blocks SetForegroundWindow.
        User32Ex.INSTANCE.SwitchToThisWindow(hwnd, true)
    }

    private fun loadIcon(id: String, file: File) {
        if (icons.containsKey(id) || id in missingIcons) return
        val icon = systemIcon(file)
        if (icon == null) missingIcons += id else icons[id] = icon
    }

    private val names = HashMap<String, String>()

    /** The executable's FileDescription ("Android Studio"), falling back to its file name ("studio64"). */
    private fun friendlyName(exe: File): String = names.getOrPut(exe.path) {
        fileDescription(exe) ?: exe.nameWithoutExtension.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
    }
}

private fun fileDescription(exe: File): String? = runCatching {
    val version = Version.INSTANCE
    val size = version.GetFileVersionInfoSize(exe.path, null)
    if (size <= 0) return null
    val data = Memory(size.toLong())
    if (!version.GetFileVersionInfo(exe.path, 0, size, data)) return null
    val pointer = PointerByReference()
    val length = IntByReference()
    // The first language/code-page pair tells which StringFileInfo block holds the strings.
    if (!version.VerQueryValue(data, "\\VarFileInfo\\Translation", pointer, length) || length.value < 4) return null
    val language = pointer.value.getShort(0).toInt() and 0xFFFF
    val codePage = pointer.value.getShort(2).toInt() and 0xFFFF
    val key = "\\StringFileInfo\\%04x%04x\\FileDescription".format(language, codePage)
    if (!version.VerQueryValue(data, key, pointer, length) || length.value <= 1) return null
    pointer.value.getWideString(0).trim().takeIf { it.isNotEmpty() }
}.getOrNull()

internal fun shellOpen(target: String) {
    Shell32.INSTANCE.ShellExecute(null, "open", target, null, null, Win32.SW_SHOWNORMAL)
}

/** Presses the keys together and releases them in reverse, e.g. Win+A. */
private fun pressKeys(vararg keys: Int) {
    val user32 = User32Ex.INSTANCE
    keys.forEach { user32.keybd_event(it.toByte(), 0, 0, null) }
    keys.reversed().forEach { user32.keybd_event(it.toByte(), 0, Win32.KEYEVENTF_KEYUP, null) }
}

/**
 * Reads the executable a .lnk points to straight from the Shell Link binary format (MS-SHLLINK):
 * LinkInfo's local base path, or the environment-variable block (e.g. %windir%\explorer.exe).
 * Store-app and PWA shortcuts have neither, and stay matched by the shortcut file itself.
 */
/** The command-line arguments stored in a .lnk (e.g. a browser web app's "--app-id=…"). */
internal fun readShortcutArguments(file: File): String? = readShortcutStrings(file)?.get(0x20)

private fun readShortcutStrings(file: File): Map<Int, String>? = runCatching {
    val bytes = file.readBytes()
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (bytes.size < 0x4C || b.getInt(0) != 0x4C) return null
    val flags = b.getInt(0x14)
    var pos = 0x4C
    if (flags and 0x01 != 0) pos += 2 + (b.getShort(pos).toInt() and 0xFFFF)
    if (flags and 0x02 != 0) pos += b.getInt(pos)
    val unicode = flags and 0x80 != 0
    val strings = HashMap<Int, String>()
    for (bit in intArrayOf(0x04, 0x08, 0x10, 0x20, 0x40)) {
        if (flags and bit == 0) continue
        val length = (b.getShort(pos).toInt() and 0xFFFF) * if (unicode) 2 else 1
        strings[bit] = String(bytes, pos + 2, length, if (unicode) Charsets.UTF_16LE else Charsets.ISO_8859_1)
        pos += 2 + length
    }
    strings
}.getOrNull()

internal fun readShortcutTarget(file: File): String? = runCatching {
    val bytes = file.readBytes()
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (bytes.size < 0x4C || b.getInt(0) != 0x4C) return null
    val flags = b.getInt(0x14)
    var pos = 0x4C
    if (flags and 0x01 != 0) pos += 2 + (b.getShort(pos).toInt() and 0xFFFF)
    var target: String? = null
    if (flags and 0x02 != 0) {
        val infoSize = b.getInt(pos)
        val headerSize = b.getInt(pos + 4)
        if (b.getInt(pos + 8) and 0x01 != 0) {
            target = if (headerSize >= 0x24) utf16z(bytes, pos + b.getInt(pos + 0x1C)) else ansiz(bytes, pos + b.getInt(pos + 0x10))
        }
        pos += infoSize
    }
    val unicode = flags and 0x80 != 0
    // StringData: name, relative path, working dir, arguments, icon location — only the last one is kept.
    var iconLocation: String? = null
    for (bit in intArrayOf(0x04, 0x08, 0x10, 0x20, 0x40)) {
        if (flags and bit == 0) continue
        val length = (b.getShort(pos).toInt() and 0xFFFF) * if (unicode) 2 else 1
        if (bit == 0x40) iconLocation = String(bytes, pos + 2, length, if (unicode) Charsets.UTF_16LE else Charsets.ISO_8859_1)
        pos += 2 + length
    }
    while (target.isNullOrBlank() && pos + 8 <= bytes.size) {
        val size = b.getInt(pos)
        if (size < 8) break
        if (b.getInt(pos + 4) == 0xA0000001.toInt()) target = utf16z(bytes, pos + 268)
        pos += size
    }
    // Shell-item shortcuts (Windows 11's File Explorer pin) have no target, but their icon is the app's executable.
    if (target.isNullOrBlank()) target = iconLocation?.substringBefore(',')?.takeIf { it.endsWith(".exe", ignoreCase = true) }
    target?.takeIf { it.isNotBlank() }?.let(::expandEnvironment)
}.getOrNull()

private fun utf16z(bytes: ByteArray, start: Int): String {
    var end = start
    while (end + 1 < bytes.size && (bytes[end].toInt() != 0 || bytes[end + 1].toInt() != 0)) end += 2
    return String(bytes, start, end - start, Charsets.UTF_16LE)
}

private fun ansiz(bytes: ByteArray, start: Int): String {
    var end = start
    while (end < bytes.size && bytes[end].toInt() != 0) end++
    return String(bytes, start, end - start)
}

internal fun expandEnvironment(path: String): String =
    Regex("%([^%]+)%").replace(path) { System.getenv(it.groupValues[1]) ?: it.value }

private const val WM_CLOSE = 0x0010
