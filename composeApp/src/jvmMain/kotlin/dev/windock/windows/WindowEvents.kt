package dev.windock.windows

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LONG
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinUser
import kotlin.concurrent.thread

/**
 * Tells the dock when the set of app windows may have changed — a window opened, closed, was shown,
 * hidden, cloaked, renamed or brought to the front — so the window list is rebuilt on demand instead
 * of being polled. Hooks are out-of-context, delivered to a dedicated thread with its own message loop.
 */
internal class WindowEvents(private val onChange: () -> Unit) {
    @Volatile private var threadId = 0

    // Held in a field: JNA callbacks that get garbage-collected while hooked crash the process.
    private val callback = object : WinUser.WinEventProc {
        override fun callback(hook: HANDLE?, event: DWORD?, hwnd: HWND?, idObject: LONG?, idChild: LONG?, thread: DWORD?, time: DWORD?) {
            // Only whole windows matter (OBJID_WINDOW, CHILDID_SELF); carets, cursors and controls are ignored.
            if (idObject?.toInt() == 0 && idChild?.toInt() == 0) onChange()
        }
    }

    fun start() {
        if (!Win32.isWindows || threadId != 0) return
        thread(isDaemon = true, name = "dock-window-events") {
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val user32 = User32.INSTANCE
            val ranges = listOf(
                EVENT_SYSTEM_FOREGROUND to EVENT_SYSTEM_FOREGROUND,
                EVENT_SYSTEM_MINIMIZESTART to EVENT_SYSTEM_MINIMIZEEND,
                EVENT_OBJECT_CREATE to EVENT_OBJECT_HIDE,
                EVENT_OBJECT_NAMECHANGE to EVENT_OBJECT_NAMECHANGE,
                EVENT_OBJECT_CLOAKED to EVENT_OBJECT_UNCLOAKED,
            )
            val hooks = ranges.mapNotNull { (min, max) ->
                user32.SetWinEventHook(min, max, null, callback, 0, 0, WINEVENT_OUTOFCONTEXT or WINEVENT_SKIPOWNPROCESS)
            }
            val message = WinUser.MSG()
            while (user32.GetMessage(message, null, 0, 0) > 0) {
                user32.TranslateMessage(message)
                user32.DispatchMessage(message)
            }
            hooks.forEach { user32.UnhookWinEvent(it) }
        }
    }

    fun stop() {
        val id = threadId
        if (id != 0) User32.INSTANCE.PostThreadMessage(id, WM_QUIT, null, null)
    }

    private companion object {
        const val EVENT_SYSTEM_FOREGROUND = 0x0003
        const val EVENT_SYSTEM_MINIMIZESTART = 0x0016
        const val EVENT_SYSTEM_MINIMIZEEND = 0x0017
        const val EVENT_OBJECT_CREATE = 0x8000
        const val EVENT_OBJECT_HIDE = 0x8003
        const val EVENT_OBJECT_NAMECHANGE = 0x800C
        const val EVENT_OBJECT_CLOAKED = 0x8017
        const val EVENT_OBJECT_UNCLOAKED = 0x8018
        const val WINEVENT_OUTOFCONTEXT = 0x0000
        const val WINEVENT_SKIPOWNPROCESS = 0x0002
        const val WM_QUIT = 0x0012
    }
}
