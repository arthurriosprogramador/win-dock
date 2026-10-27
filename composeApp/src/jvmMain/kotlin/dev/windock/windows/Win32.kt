package dev.windock.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/** user32 entry points that jna-platform's User32 doesn't declare. */
@Suppress("FunctionName")
internal interface User32Ex : StdCallLibrary {
    fun SwitchToThisWindow(hWnd: HWND, fUnknown: Boolean)
    fun IsIconic(hWnd: HWND): Boolean
    fun keybd_event(bVk: Byte, bScan: Byte, dwFlags: Int, dwExtraInfo: Pointer?)

    companion object {
        val INSTANCE: User32Ex = Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

@Suppress("FunctionName")
internal interface Dwmapi : StdCallLibrary {
    fun DwmGetWindowAttribute(hwnd: HWND, dwAttribute: Int, pvAttribute: IntByReference, cbAttribute: Int): Int

    companion object {
        val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

internal object Win32 {
    const val GW_OWNER = 4
    const val GWL_EXSTYLE = -20
    const val WS_EX_TOOLWINDOW = 0x00000080
    const val WS_EX_APPWINDOW = 0x00040000
    const val WS_EX_NOACTIVATE = 0x08000000
    const val WS_EX_TRANSPARENT = 0x00000020
    const val SWP_NOSIZE = 0x0001
    const val SWP_NOMOVE = 0x0002
    const val SWP_NOACTIVATE = 0x0010
    const val DWMWA_CLOAKED = 14
    const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    const val SW_HIDE = 0
    const val SW_SHOWNORMAL = 1
    const val SW_MINIMIZE = 6
    const val SW_SHOWNA = 8
    const val SW_RESTORE = 9
    const val KEYEVENTF_KEYUP = 0x0002
    const val VK_LWIN = 0x5B

    const val ABM_NEW = 0
    const val ABM_REMOVE = 1
    const val ABM_QUERYPOS = 2
    const val ABM_SETPOS = 3
    const val ABM_GETSTATE = 4
    const val ABM_SETSTATE = 10
    const val ABE_BOTTOM = 3
    const val ABS_AUTOHIDE = 1
    const val MONITOR_DEFAULTTOPRIMARY = 1

    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
