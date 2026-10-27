package dev.windock.windows

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HICON
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.PointerByReference
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.ImageIcon
import javax.swing.filechooser.FileSystemView

/** Icons are rendered at this size and scaled down by Compose, so they stay crisp on high-DPI screens. */
private const val ICON_PX = 128

/** The shell's icon for a file, folder or shortcut, taken from its 256px variant. */
internal fun systemIcon(file: File): ImageBitmap? {
    val icon = runCatching { FileSystemView.getFileSystemView().getSystemIcon(file, 256, 256) }.getOrNull() ?: return null
    val image = BufferedImage(ICON_PX, ICON_PX, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    if (icon is ImageIcon) g.drawImage(icon.image, 0, 0, ICON_PX, ICON_PX, null) else icon.paintIcon(null, g, 0, 0)
    g.dispose()
    return image.toComposeImageBitmap()
}

/** An icon from a resource spec such as "%SystemRoot%\System32\imageres.dll,-54". */
internal fun resourceIcon(spec: String): ImageBitmap? = runCatching {
    val path = expandEnvironment(spec.substringBeforeLast(',').trim('"', ' '))
    val index = spec.substringAfterLast(',', "0").trim().toIntOrNull() ?: 0
    val large = PointerByReference()
    if (Shell32Ex.INSTANCE.SHDefExtractIcon(path, index, 0, large, null, ICON_PX) < 0 || large.value == null) return null
    val hicon = HICON(large.value)
    try {
        iconPixels(hicon)?.toComposeImageBitmap()
    } finally {
        User32.INSTANCE.DestroyIcon(hicon)
    }
}.getOrNull()

private fun iconPixels(hicon: HICON): BufferedImage? {
    val info = WinGDI.ICONINFO()
    if (!User32.INSTANCE.GetIconInfo(hicon, info)) return null
    try {
        val header = WinGDI.BITMAPINFO()
        header.bmiHeader.biWidth = ICON_PX
        header.bmiHeader.biHeight = -ICON_PX // top-down rows
        header.bmiHeader.biPlanes = 1
        header.bmiHeader.biBitCount = 32
        header.bmiHeader.biCompression = WinGDI.BI_RGB
        val pixels = Memory(ICON_PX * ICON_PX * 4L)
        val dc = User32.INSTANCE.GetDC(null)
        val rows = GDI32.INSTANCE.GetDIBits(dc, info.hbmColor, 0, ICON_PX, pixels, header, WinGDI.DIB_RGB_COLORS)
        User32.INSTANCE.ReleaseDC(null, dc)
        if (rows == 0) return null
        // 32-bit DIB rows are BGRA, which read as little-endian ints are exactly ARGB.
        return BufferedImage(ICON_PX, ICON_PX, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, ICON_PX, ICON_PX, pixels.getIntArray(0, ICON_PX * ICON_PX), 0, ICON_PX)
        }
    } finally {
        info.hbmColor?.let { GDI32.INSTANCE.DeleteObject(it) }
        info.hbmMask?.let { GDI32.INSTANCE.DeleteObject(it) }
    }
}

/** Real Windows icons for the dock's fixed shell entries, resolved once and cached. */
internal object ShellIcons {
    private const val RECYCLE_BIN = "{645FF040-5081-101B-9F08-00AA002F954E}"
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    val downloads: ImageBitmap? get() = cached("downloads") { systemIcon(File(System.getProperty("user.home"), "Downloads")) }

    fun recycleBin(full: Boolean): ImageBitmap? = cached(if (full) "recycle-full" else "recycle-empty") {
        recycleBinIconSpec(if (full) "full" else "empty")?.let(::resourceIcon)
    }

    private fun cached(key: String, load: () -> ImageBitmap?): ImageBitmap? {
        if (!Win32.isWindows) return null
        cache[key]?.let { return it }
        return load()?.also { cache[key] = it }
    }

    /** Honours custom recycle-bin icons set in Personalization, falling back to the system default. */
    private fun recycleBinIconSpec(state: String): String? {
        val sources = listOf(
            WinReg.HKEY_CURRENT_USER to "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\CLSID\\$RECYCLE_BIN\\DefaultIcon",
            WinReg.HKEY_CLASSES_ROOT to "CLSID\\$RECYCLE_BIN\\DefaultIcon",
        )
        for ((root, key) in sources) {
            runCatching { Advapi32Util.registryGetStringValue(root, key, state) }.getOrNull()?.let { return it }
        }
        return null
    }
}
