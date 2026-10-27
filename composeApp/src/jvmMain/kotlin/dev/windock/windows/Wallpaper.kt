package dev.windock.windows

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** The frosted-glass backdrop: the blurred wallpaper strip behind the dock and its average luminance (0..1). */
internal class GlassBackdrop(val image: ImageBitmap, val luminance: Float, val source: String, val upscale: Int = 1)

/**
 * Reads the current desktop wallpaper and renders the strip of it that sits behind the dock, blurred and
 * saturated — macOS-style frosted glass. The fallback for [LiveGlass], when the dock can't be kept out of
 * screen captures (Windows before 10 2004): over the desktop it looks the same, just not over windows.
 */
internal object Wallpaper {
    private const val DESKTOP_KEY = "Control Panel\\Desktop"

    /** A key that changes whenever the wallpaper does, so callers can tell when to rebuild. */
    fun signature(): String {
        val file = currentFile()
        return "${file?.path}|${file?.lastModified()}|${style()}"
    }

    /**
     * The strip [stripHeightPx] tall at the bottom of a [screenWidthPx]×[screenHeightPx] screen, blurred by
     * [blurPx]. Null when there's no wallpaper image (solid colour) or it can't be decoded.
     */
    fun backdrop(screenWidthPx: Int, screenHeightPx: Int, stripHeightPx: Int, blurPx: Float): GlassBackdrop? = runCatching {
        val file = currentFile() ?: return null
        val image = Image.makeFromEncoded(file.readBytes())
        val placed = placement(image.width.toFloat(), image.height.toFloat(), screenWidthPx.toFloat(), screenHeightPx.toFloat(), style())
        // Map the strip's screen rectangle back into the image's own pixels.
        val scaleX = image.width / placed.width
        val scaleY = image.height / placed.height
        val stripTop = (screenHeightPx - stripHeightPx).toFloat()
        // Grab a margin around the strip so the blur doesn't fade to transparent at its edges.
        val margin = blurPx * 3
        val src = Rect.makeLTRB(
            ((0f - margin - placed.left) * scaleX).coerceIn(0f, image.width.toFloat()),
            ((stripTop - margin - placed.top) * scaleY).coerceIn(0f, image.height.toFloat()),
            ((screenWidthPx + margin - placed.left) * scaleX).coerceIn(0f, image.width.toFloat()),
            ((screenHeightPx + margin - placed.top) * scaleY).coerceIn(0f, image.height.toFloat()),
        )
        val surface = Surface.makeRasterN32Premul(screenWidthPx, stripHeightPx)
        val paint = Paint().apply {
            imageFilter = ImageFilter.makeBlur(blurPx, blurPx, FilterTileMode.CLAMP)
            // Extra saturation, like macOS vibrancy, so the glass picks up the colour of what's behind it.
            colorFilter = ColorFilter.makeMatrix(saturation(VIBRANCY))
        }
        val dst = Rect.makeLTRB(
            src.left / scaleX + placed.left, src.top / scaleY + placed.top - stripTop,
            src.right / scaleX + placed.left, src.bottom / scaleY + placed.top - stripTop,
        )
        surface.canvas.drawImageRect(image, src, dst, paint)
        val strip = surface.makeImageSnapshot()
        GlassBackdrop(strip.toComposeImageBitmap(), averageLuminance(strip), file.path)
    }.getOrNull()

    private fun currentFile(): File? {
        val configured = runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, DESKTOP_KEY, "WallPaper") }.getOrNull()
        configured?.let(::File)?.takeIf { it.isFile }?.let { return it }
        // Windows keeps a processed copy of the current wallpaper here (also used for slideshows and Spotlight).
        val transcoded = File(System.getenv("APPDATA") ?: return null, "Microsoft\\Windows\\Themes\\TranscodedWallpaper")
        return transcoded.takeIf { it.isFile }
    }

    /** WallpaperStyle: 0 centre (2 stretch when tiled is off), 6 fit, 10 fill, 22 span. Fill is the default. */
    private fun style(): Int =
        runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, DESKTOP_KEY, "WallpaperStyle").trim().toInt() }.getOrDefault(10)

    private fun placement(imageW: Float, imageH: Float, screenW: Float, screenH: Float, style: Int): Rect {
        val scale = when (style) {
            6 -> min(screenW / imageW, screenH / imageH)            // fit
            0 -> 1f                                                  // centre
            2 -> return Rect.makeXYWH(0f, 0f, screenW, screenH)      // stretch
            else -> max(screenW / imageW, screenH / imageH)          // fill (10), span (22), tile
        }
        val w = imageW * scale
        val h = imageH * scale
        return Rect.makeXYWH((screenW - w) / 2, (screenH - h) / 2, w, h)
    }

    const val VIBRANCY = 1.7f

    fun saturation(amount: Float): ColorMatrix {
        val r = .2126f * (1 - amount)
        val g = .7152f * (1 - amount)
        val b = .0722f * (1 - amount)
        return ColorMatrix(
            r + amount, g, b, 0f, 0f,
            r, g + amount, b, 0f, 0f,
            r, g, b + amount, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun averageLuminance(image: Image): Float {
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(image.width, image.height) }
        image.readPixels(bitmap, 0, 0)
        var sum = 0.0
        var count = 0
        val stepX = max(1, image.width / 64)
        val stepY = max(1, image.height / 8)
        for (y in 0 until image.height step stepY) for (x in 0 until image.width step stepX) {
            val c = bitmap.getColor(x, y)
            sum += (.2126 * ((c shr 16) and 0xFF) + .7152 * ((c shr 8) and 0xFF) + .0722 * (c and 0xFF)) / 255.0
            count++
        }
        return if (count == 0) .5f else (sum / count).toFloat()
    }
}
