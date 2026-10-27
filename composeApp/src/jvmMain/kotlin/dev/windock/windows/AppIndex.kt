package dev.windock.windows

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HBITMAP
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** An app as listed in the Start menu; [appId] opens it via shell:AppsFolder, Store apps included. */
internal data class StartApp(val name: String, val appId: String)

@Suppress("FunctionName")
private interface ShellItemApi : StdCallLibrary {
    fun SHCreateItemFromParsingName(path: String, bindContext: Pointer?, riid: GUID, item: PointerByReference): Int

    companion object {
        val INSTANCE: ShellItemApi = Native.load("shell32", ShellItemApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

@Structure.FieldOrder("cx", "cy")
internal class SizeByValue(@JvmField var cx: Int = 0, @JvmField var cy: Int = 0) : Structure(), Structure.ByValue

/**
 * Everything in the Start menu, for the dock's search. Names come from Windows' own `Get-StartApps`; icons are
 * the ones Explorer shows for each entry, fetched lazily on a single COM thread and cached.
 */
internal class AppIndex {
    @Volatile var apps: List<StartApp> = emptyList()
        private set

    /** Observable by Compose: an icon appearing here redraws the result that's waiting for it. */
    val icons = mutableStateMapOf<String, ImageBitmap>()
    private val requested = HashSet<String>()
    private val iconThread = Executors.newSingleThreadExecutor { Thread(it, "dock-app-icons").apply { isDaemon = true } }
        .also { it.execute { Ole32.INSTANCE.CoInitializeEx(null, 2) } } // apartment-threaded, as the shell expects

    /** Lists the Start menu apps (a second or two; call off the UI thread). */
    fun reload() {
        if (!Win32.isWindows) return
        runCatching {
            // -EncodedCommand (UTF-16LE, Base64) sidesteps Windows command-line quoting of the script.
            val script = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; Get-StartApps | ForEach-Object { \$_.Name + \"`t\" + \$_.AppID }"
            val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader(Charsets.UTF_8).readLines()
            process.waitFor(30, TimeUnit.SECONDS)
            val parsed = lines.mapNotNull { line ->
                val parts = line.removePrefix("﻿").split('\t')
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) StartApp(parts[0].trim(), parts[1].trim()) else null
            }.distinctBy { it.name.lowercase() }
            if (parsed.isNotEmpty()) apps = parsed
        }
    }

    /** The app's icon if loaded; otherwise starts loading it and returns null for now. */
    fun icon(appId: String): ImageBitmap? {
        icons[appId]?.let { return it }
        synchronized(requested) { if (!requested.add(appId)) return null }
        iconThread.execute {
            loadIcon("shell:AppsFolder\\$appId")?.let { icons[appId] = it }
        }
        return null
    }

    fun launch(appId: String) = shellOpen("shell:AppsFolder\\$appId")

    /** IShellItemImageFactory::GetImage, the API Explorer uses for its own icons. */
    private fun loadIcon(parsingName: String): ImageBitmap? = runCatching {
        val ref = PointerByReference()
        val iid = GUID("{bcc18b79-ba16-442f-80c4-8a59c30c463b}")
        if (ShellItemApi.INSTANCE.SHCreateItemFromParsingName(parsingName, null, iid, ref) < 0 || ref.value == null) return null
        val factory = ComObject(ref.value)
        try {
            val bitmapRef = PointerByReference()
            // GetImage(size, SIIGBF_ICONONLY, &hbitmap) is the factory's first method after IUnknown.
            if (factory.call(3, SizeByValue(ICON_PX, ICON_PX), SIIGBF_ICONONLY, bitmapRef) < 0 || bitmapRef.value == null) return null
            val bitmap = HBITMAP(bitmapRef.value)
            try {
                bitmapPixels(bitmap)?.toComposeImageBitmap()
            } finally {
                GDI32.INSTANCE.DeleteObject(bitmap)
            }
        } finally {
            factory.Release()
        }
    }.getOrNull()

    private fun bitmapPixels(bitmap: HBITMAP): BufferedImage? {
        val header = WinGDI.BITMAPINFO()
        header.bmiHeader.biWidth = ICON_PX
        header.bmiHeader.biHeight = -ICON_PX
        header.bmiHeader.biPlanes = 1
        header.bmiHeader.biBitCount = 32
        header.bmiHeader.biCompression = WinGDI.BI_RGB
        val pixels = Memory(ICON_PX * ICON_PX * 4L)
        val dc = User32.INSTANCE.GetDC(null)
        val rows = GDI32.INSTANCE.GetDIBits(dc, bitmap, 0, ICON_PX, pixels, header, WinGDI.DIB_RGB_COLORS)
        User32.INSTANCE.ReleaseDC(null, dc)
        if (rows == 0) return null
        val argb = pixels.getIntArray(0, ICON_PX * ICON_PX)
        // The factory hands back premultiplied alpha; undo it so soft edges don't darken.
        for (i in argb.indices) {
            val a = argb[i] ushr 24
            if (a in 1..254) {
                fun un(c: Int) = minOf(255, c * 255 / a)
                argb[i] = (a shl 24) or (un((argb[i] shr 16) and 0xFF) shl 16) or (un((argb[i] shr 8) and 0xFF) shl 8) or un(argb[i] and 0xFF)
            }
        }
        return BufferedImage(ICON_PX, ICON_PX, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, ICON_PX, ICON_PX, argb, 0, ICON_PX) }
    }

    private companion object {
        const val ICON_PX = 64
        const val SIIGBF_ICONONLY = 0x4
    }
}
