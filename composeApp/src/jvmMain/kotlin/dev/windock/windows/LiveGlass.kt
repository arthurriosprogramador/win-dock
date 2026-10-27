package dev.windock.windows

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HDC
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import java.util.concurrent.ConcurrentHashMap

@Suppress("FunctionName")
private interface CaptureApi : StdCallLibrary {
    fun SetWindowDisplayAffinity(hwnd: HWND, affinity: Int): Boolean
    fun StretchBlt(dest: HDC, x: Int, y: Int, w: Int, h: Int, src: HDC, sx: Int, sy: Int, sw: Int, sh: Int, rop: Int): Boolean
    fun SetStretchBltMode(dc: HDC, mode: Int): Int

    companion object {
        val USER32: CaptureApi = Native.load("user32", CaptureApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        val GDI32: CaptureApi = Native.load("gdi32", CaptureApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/**
 * Real see-through glass: the dock's own windows are kept out of screen captures, so a capture of the strip
 * behind the dock shows exactly what's under it — wallpaper or windows — which is then frosted like macOS.
 * The capture is taken at a quarter resolution (it's blurred anyway) and only republished when it changed.
 */
internal object LiveGlass {
    private const val WDA_EXCLUDEFROMCAPTURE = 0x11
    private const val WDA_NONE = 0x0
    private const val HALFTONE = 4
    private const val SRCCOPY = 0x00CC0020
    private const val DOWNSCALE = 4

    private val excluded = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var failed = false
    private var lastHash = 0

    /** Live glass works once the dock's windows are hidden from captures (needs Windows 10 2004 or later). */
    val available get() = !failed && excluded.isNotEmpty()

    /** Hides [hwnd] from screen captures; returns false (and disables live glass) when Windows refuses. */
    fun exclude(hwnd: HWND): Boolean {
        val ok = runCatching { CaptureApi.USER32.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE) }.getOrDefault(false)
        if (ok) excluded += Pointer.nativeValue(hwnd.pointer) else failed = true
        return ok
    }

    fun release(hwnd: HWND) {
        excluded -= Pointer.nativeValue(hwnd.pointer)
        runCatching { CaptureApi.USER32.SetWindowDisplayAffinity(hwnd, WDA_NONE) }
    }

    /**
     * The frosted strip [stripPx] tall at the bottom of the screen, or null when nothing changed since the last
     * call (or the capture failed). Call off the UI thread.
     */
    fun capture(screenWidthPx: Int, screenHeightPx: Int, stripPx: Int, blurPx: Float): GlassBackdrop? = runCatching {
        val w = (screenWidthPx / DOWNSCALE).coerceAtLeast(1)
        val h = (stripPx / DOWNSCALE).coerceAtLeast(1)
        val pixels = grab(screenHeightPx - stripPx, screenWidthPx, stripPx, w, h) ?: return null
        val hash = pixels.contentHashCode()
        if (hash == lastHash) return null
        lastHash = hash
        val small = Image.makeRaster(ImageInfo.makeN32(w, h, ColorAlphaType.OPAQUE), pixels, w * 4)
        // Blur at the small size: cheap, and the blur hides the low resolution.
        val blurred = Surface.makeRasterN32Premul(w, h).apply {
            canvas.drawImage(small, 0f, 0f, Paint().apply {
                imageFilter = ImageFilter.makeBlur(blurPx / DOWNSCALE, blurPx / DOWNSCALE, FilterTileMode.CLAMP)
                colorFilter = ColorFilter.makeMatrix(Wallpaper.saturation(Wallpaper.VIBRANCY))
            })
        }.makeImageSnapshot()
        // Kept small: the dock scales it up as it draws, smoothly, since a blurred image has no detail to lose.
        GlassBackdrop(blurred.toComposeImageBitmap(), Wallpaper.averageLuminance(blurred), "live", upscale = DOWNSCALE)
    }.getOrNull()

    /** Forgets the last capture, so the next one is published even if the screen looks the same. */
    fun invalidate() {
        lastHash = 0
    }

    /** Copies the screen rectangle (0, [top], [width]×[height]) into a [w]×[h] BGRA buffer with GDI. */
    private fun grab(top: Int, width: Int, height: Int, w: Int, h: Int): ByteArray? {
        val gdi = GDI32.INSTANCE
        val screen = User32.INSTANCE.GetDC(null) ?: return null
        val memory = gdi.CreateCompatibleDC(screen)
        val bitmap = gdi.CreateCompatibleBitmap(screen, w, h)
        val previous = gdi.SelectObject(memory, bitmap)
        try {
            CaptureApi.GDI32.SetStretchBltMode(memory, HALFTONE)
            if (!CaptureApi.GDI32.StretchBlt(memory, 0, 0, w, h, screen, 0, top, width, height, SRCCOPY)) return null
            gdi.SelectObject(memory, previous)
            val info = WinGDI.BITMAPINFO().apply {
                bmiHeader.biWidth = w
                bmiHeader.biHeight = -h
                bmiHeader.biPlanes = 1
                bmiHeader.biBitCount = 32
                bmiHeader.biCompression = WinGDI.BI_RGB
            }
            val buffer = Memory(w * h * 4L)
            if (gdi.GetDIBits(memory, bitmap, 0, h, buffer, info, WinGDI.DIB_RGB_COLORS) == 0) return null
            val bytes = buffer.getByteArray(0, w * h * 4)
            // GDI leaves alpha at zero; the image is opaque.
            for (i in 3 until bytes.size step 4) bytes[i] = -1
            return bytes
        } finally {
            gdi.DeleteObject(bitmap)
            gdi.DeleteDC(memory)
            User32.INSTANCE.ReleaseDC(null, screen)
        }
    }
}
