package dev.windock.windows

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShellAPI.APPBARDATA
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinDef.UINT
import com.sun.jna.platform.win32.WinUser.MONITORINFO
import dev.windock.DockSettings

/**
 * Hides the Windows taskbar while the dock replaces it, and gives it back afterwards.
 *
 * Hiding the window alone would leave its screen space reserved, so the taskbar is also switched to
 * auto-hide; the original state is persisted first so [restore] (or the next launch) can undo everything.
 */
internal object NativeTaskbar {
    private val user32 = User32.INSTANCE

    private fun trayWindow(): HWND? = user32.FindWindow("Shell_TrayWnd", null)

    private fun appBarData(hwnd: HWND?) = APPBARDATA().apply {
        cbSize = DWORD(size().toLong())
        hWnd = hwnd
    }

    /**
     * True between [hide] and [restore]. Once restored, background watchdogs that are still winding
     * down (e.g. during JVM shutdown) must not hide the taskbar again.
     */
    private var takenOver = false

    /** Told the original state when the taskbar is hidden, and null when it's given back. */
    @Volatile var listener: ((Int?) -> Unit)? = null

    @Synchronized
    fun hide() {
        if (!Win32.isWindows) return
        takenOver = true
        val tray = trayWindow() ?: return
        val original = DockSettings.nativeTaskbarState
            ?: Shell32.INSTANCE.SHAppBarMessage(DWORD(Win32.ABM_GETSTATE.toLong()), appBarData(tray)).toInt()
                .also { DockSettings.nativeTaskbarState = it }
        setState(tray, original or Win32.ABS_AUTOHIDE)
        user32.ShowWindow(tray, Win32.SW_HIDE)
        listener?.invoke(original)
    }

    /**
     * Explorer re-shows the taskbar after restarts, the Win key and some shell events; call this periodically.
     * Returns true when it had to hide the taskbar again; does nothing once [restore] has run.
     */
    @Synchronized
    fun keepHidden(): Boolean {
        if (!Win32.isWindows || !takenOver) return false
        val tray = trayWindow() ?: return false
        if (!user32.IsWindowVisible(tray)) return false
        hide()
        return true
    }

    @Synchronized
    fun restore() {
        if (!Win32.isWindows) return
        takenOver = false
        val original = DockSettings.nativeTaskbarState ?: return
        trayWindow()?.let { tray ->
            setState(tray, original)
            user32.ShowWindow(tray, Win32.SW_SHOWNA)
        }
        DockSettings.nativeTaskbarState = null
        listener?.invoke(null)
    }

    private fun setState(tray: HWND, state: Int) {
        val data = appBarData(tray).apply { lParam = LPARAM(state.toLong()) }
        Shell32.INSTANCE.SHAppBarMessage(DWORD(Win32.ABM_SETSTATE.toLong()), data)
    }
}

/** Registers the dock window as a bottom app bar so maximized windows stop above it. */
internal class DockAppBar(private val hwnd: HWND) {
    private val shell = Shell32.INSTANCE
    private var registered = false

    private fun data() = APPBARDATA().apply {
        cbSize = DWORD(size().toLong())
        hWnd = hwnd
        uEdge = UINT(Win32.ABE_BOTTOM.toLong())
    }

    /** Reserves [heightPx] physical pixels at the bottom of the dock's monitor. */
    fun reserve(heightPx: Int) {
        // Re-sent on every call: an Explorer restart forgets all app bars, and a repeat ABM_NEW is harmless.
        shell.SHAppBarMessage(DWORD(Win32.ABM_NEW.toLong()), data().apply { uCallbackMessage = UINT(0x8000L + 0x4D) })
        registered = true
        val info = MONITORINFO()
        User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromWindow(hwnd, Win32.MONITOR_DEFAULTTOPRIMARY), info)
        val request = data().apply {
            rc.left = info.rcMonitor.left
            rc.right = info.rcMonitor.right
            rc.bottom = info.rcMonitor.bottom
            rc.top = info.rcMonitor.bottom - heightPx
        }
        shell.SHAppBarMessage(DWORD(Win32.ABM_QUERYPOS.toLong()), request)
        // QUERYPOS would stack us above the hidden native taskbar's old slot; the dock window sits on
        // the very bottom edge, so that's what must be reserved.
        request.rc.bottom = info.rcMonitor.bottom
        request.rc.top = info.rcMonitor.bottom - heightPx
        shell.SHAppBarMessage(DWORD(Win32.ABM_SETPOS.toLong()), request)
    }

    fun release() {
        if (!registered) return
        shell.SHAppBarMessage(DWORD(Win32.ABM_REMOVE.toLong()), data())
        registered = false
    }

    /** Keeps the dock out of Alt+Tab and from stealing focus from the app the user is switching away from. */
    fun makeToolWindow() {
        val user32 = User32.INSTANCE
        val style = user32.GetWindowLong(hwnd, Win32.GWL_EXSTYLE)
        user32.SetWindowLong(hwnd, Win32.GWL_EXSTYLE, style or Win32.WS_EX_TOOLWINDOW or Win32.WS_EX_NOACTIVATE)
    }
}


/** True when the foreground window covers its whole monitor (video, games, presentations). */
internal fun isFullscreenAppInFront(dock: HWND): Boolean {
    val user32 = User32.INSTANCE
    val foreground = user32.GetForegroundWindow() ?: return false
    if (foreground == dock) return false
    val className = CharArray(64)
    user32.GetClassName(foreground, className, className.size)
    // The desktop itself is "full screen" but should never hide the dock.
    if (com.sun.jna.Native.toString(className) in setOf("Progman", "WorkerW", "Shell_TrayWnd")) return false
    // A maximized window with a title bar isn't full screen, even when nothing reserves the screen edge and it
    // covers the whole monitor (as with the floating and glass docks). Videos and games drop the caption.
    val style = user32.GetWindowLong(foreground, -16 /* GWL_STYLE */)
    if (style and WS_MAXIMIZE != 0 && style and WS_CAPTION == WS_CAPTION) return false
    val rect = RECT()
    user32.GetWindowRect(foreground, rect)
    val info = MONITORINFO()
    user32.GetMonitorInfo(user32.MonitorFromWindow(foreground, 2 /* MONITOR_DEFAULTTONEAREST */), info)
    val screen = info.rcMonitor
    return rect.left <= screen.left && rect.top <= screen.top && rect.right >= screen.right && rect.bottom >= screen.bottom
}

private const val WS_MAXIMIZE = 0x01000000
private const val WS_CAPTION = 0x00C00000

/** Lets every click fall through the window to whatever is underneath; for purely decorative overlays. */
internal fun makeClickThrough(hwnd: HWND) {
    val user32 = User32.INSTANCE
    user32.SetWindowLong(hwnd, Win32.GWL_EXSTYLE, user32.GetWindowLong(hwnd, Win32.GWL_EXSTYLE) or Win32.WS_EX_TRANSPARENT or Win32.WS_EX_TOOLWINDOW)
}

/** Raises the window to the top of the always-on-top band without activating it. */
internal fun keepOnTop(hwnd: HWND) {
    User32.INSTANCE.SetWindowPos(hwnd, HWND(Pointer.createConstant(-1)), 0, 0, 0, 0,
        Win32.SWP_NOSIZE or Win32.SWP_NOMOVE or Win32.SWP_NOACTIVATE)
}
