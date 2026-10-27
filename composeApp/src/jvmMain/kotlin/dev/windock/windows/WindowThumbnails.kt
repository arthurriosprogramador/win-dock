package dev.windock.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/** DWM_THUMBNAIL_PROPERTIES; dwmapi.h declares it with 1-byte packing. */
@Structure.FieldOrder("dwFlags", "rcDestination", "rcSource", "opacity", "fVisible", "fSourceClientAreaOnly")
internal class ThumbnailProperties : Structure(ALIGN_NONE) {
    @JvmField var dwFlags: Int = 0
    @JvmField var rcDestination: RECT = RECT()
    @JvmField var rcSource: RECT = RECT()
    @JvmField var opacity: Byte = 0
    @JvmField var fVisible: Int = 0
    @JvmField var fSourceClientAreaOnly: Int = 0
}

@Suppress("FunctionName")
private interface DwmThumbnailApi : StdCallLibrary {
    fun DwmRegisterThumbnail(destination: HWND, source: HWND, thumbnail: PointerByReference): Int
    fun DwmUnregisterThumbnail(thumbnail: Pointer): Int
    fun DwmUpdateThumbnailProperties(thumbnail: Pointer, properties: ThumbnailProperties): Int
    fun DwmQueryThumbnailSourceSize(thumbnail: Pointer, size: WinUser.SIZE): Int
    fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: IntByReference, size: Int): Int

    companion object {
        val INSTANCE: DwmThumbnailApi = Native.load("dwmapi", DwmThumbnailApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/**
 * Live window previews, drawn by the desktop compositor itself into rectangles of a window we own —
 * the same mechanism the taskbar uses. The image updates on its own while the source window changes.
 */
internal class WindowThumbnails(private val host: HWND) {
    private val api = DwmThumbnailApi.INSTANCE
    private val thumbnails = mutableListOf<Pointer>()

    /**
     * Shows [source] fitted (aspect ratio kept, centred) inside [slot], in physical pixels relative to the
     * host window. Returns false when the window can't be previewed (e.g. it closed meanwhile).
     */
    fun show(source: HWND, slot: RECT): Boolean {
        val ref = PointerByReference()
        if (api.DwmRegisterThumbnail(host, source, ref) < 0 || ref.value == null) return false
        val thumbnail = ref.value
        thumbnails += thumbnail
        val sourceSize = WinUser.SIZE()
        api.DwmQueryThumbnailSourceSize(thumbnail, sourceSize)
        val slotW = slot.right - slot.left
        val slotH = slot.bottom - slot.top
        val scale = if (sourceSize.cx > 0 && sourceSize.cy > 0) minOf(slotW.toFloat() / sourceSize.cx, slotH.toFloat() / sourceSize.cy) else 1f
        val w = (sourceSize.cx * scale).toInt().coerceAtMost(slotW)
        val h = (sourceSize.cy * scale).toInt().coerceAtMost(slotH)
        val properties = ThumbnailProperties().apply {
            dwFlags = DWM_TNP_RECTDESTINATION or DWM_TNP_VISIBLE or DWM_TNP_OPACITY or DWM_TNP_SOURCECLIENTAREAONLY
            rcDestination.left = slot.left + (slotW - w) / 2
            rcDestination.top = slot.top + (slotH - h) / 2
            rcDestination.right = rcDestination.left + w
            rcDestination.bottom = rcDestination.top + h
            opacity = 255.toByte()
            fVisible = 1
            fSourceClientAreaOnly = 0
        }
        return api.DwmUpdateThumbnailProperties(thumbnail, properties) >= 0
    }

    fun clear() {
        thumbnails.forEach { api.DwmUnregisterThumbnail(it) }
        thumbnails.clear()
    }

    /** Windows 11 rounded corners for an undecorated popup. */
    fun roundCorners() {
        api.DwmSetWindowAttribute(host, DWMWA_WINDOW_CORNER_PREFERENCE, IntByReference(DWMWCP_ROUND), 4)
    }

    private companion object {
        const val DWM_TNP_RECTDESTINATION = 0x1
        const val DWM_TNP_OPACITY = 0x4
        const val DWM_TNP_VISIBLE = 0x8
        const val DWM_TNP_SOURCECLIENTAREAONLY = 0x10
        const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
        const val DWMWCP_ROUND = 2
    }
}
