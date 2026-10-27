package dev.windock.windows

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.windock.mvi.MediaCommand
import dev.windock.mvi.MediaStatus
import dev.windock.mvi.PlaybackState
import org.jetbrains.skia.Image
import java.io.File
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Answer from the Windows location service. */
internal sealed interface DeviceLocation {
    data class Found(val latitude: Double, val longitude: Double) : DeviceLocation
    /** Location is off, or desktop apps aren't allowed to use it (Settings → Privacy → Location). */
    data object Denied : DeviceLocation
    data object Unavailable : DeviceLocation
}

/**
 * The dock's way into WinRT: `winrt-bridge.ps1`, a Windows PowerShell child process (see that script for
 * the line protocol). It follows the system media session (what Spotify, a browser tab, Media Player… is
 * playing) and answers location requests. The child exits on its own when our end of its stdin closes,
 * so it never outlives the dock.
 */
internal class WinRtBridge(private val onMediaChange: (MediaStatus?) -> Unit) {
    @Volatile private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var ready = CountDownLatch(1)
    @Volatile private var pendingLocation: CompletableFuture<DeviceLocation>? = null

    @Volatile private var taskbarOriginal: Int? = null

    /**
     * Keeps the bridge informed about the native taskbar: if the dock dies while it's hidden — even killed
     * outright, which skips every shutdown hook — the bridge sees its stdin close and restores it.
     */
    fun reportTaskbar(originalState: Int?) {
        taskbarOriginal = originalState
        sendLine(if (originalState == null) "taskbar-restored" else "taskbar-hidden $originalState")
    }

    /** Names browser web apps ("Chrome._crx_<id>") after their pinned shortcut; set by the host. */
    @Volatile var webAppName: (String) -> String? = { null }
    @Volatile var artwork: ImageBitmap? = null
        private set
    private var artworkVersion = 0
    private var current: MediaStatus? = null

    fun start() {
        if (!Win32.isWindows || running) return
        running = true
        thread(isDaemon = true, name = "dock-winrt-bridge") {
            var failures = 0
            while (running) {
                val startedAt = System.currentTimeMillis()
                runCatching { runBridge() }
                if (!running) break
                // Back off if the bridge keeps dying (e.g. PowerShell blocked by policy), but keep trying.
                failures = if (System.currentTimeMillis() - startedAt > 60_000) 0 else failures + 1
                publish(null)
                Thread.sleep((5_000L * failures).coerceAtMost(60_000L))
            }
        }
    }

    fun stop() {
        running = false
        process?.let {
            runCatching { it.outputStream.close() }
            it.destroy()
        }
    }

    fun send(command: MediaCommand) {
        sendLine(
            when (command) {
                MediaCommand.TOGGLE_PLAY_PAUSE -> "toggle"
                MediaCommand.NEXT -> "next"
                MediaCommand.PREVIOUS -> "previous"
            },
        )
    }

    /** Asks Windows where this machine is. Blocks (call off the UI thread), waiting for the bridge to start if needed. */
    fun requestLocation(): DeviceLocation {
        if (!Win32.isWindows) return DeviceLocation.Unavailable
        // Re-read the latch each slice: a bridge that restarts replaces it, and the old one would never open.
        val deadline = System.currentTimeMillis() + 30_000
        while (!ready.await(500, TimeUnit.MILLISECONDS)) {
            if (System.currentTimeMillis() > deadline) return DeviceLocation.Unavailable
        }
        val answer = CompletableFuture<DeviceLocation>()
        pendingLocation = answer
        if (!sendLine("locate")) return DeviceLocation.Unavailable
        return runCatching { answer.get(40, TimeUnit.SECONDS) }.getOrDefault(DeviceLocation.Unavailable)
    }

    private fun sendLine(line: String): Boolean = runCatching {
        process!!.outputStream.apply {
            write("$line\n".toByteArray())
            flush()
        }
    }.isSuccess

    private fun runBridge() {
        val script = extractScript()
        val child = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.path)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process = child
        try {
            child.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val parts = line.removePrefix("﻿").split('\t')
                when (parts.firstOrNull()) {
                    "MEDIA" -> publish(parseMedia(parts))
                    "ART" -> updateArtwork(parts.getOrNull(1).orEmpty())
                    "READY" -> {
                        // A restarted bridge starts out not knowing; tell it again.
                        taskbarOriginal?.let { sendLine("taskbar-hidden $it") }
                        ready.countDown()
                    }
                    "LOCATION" -> pendingLocation?.complete(parseLocation(parts))
                }
            }
        } finally {
            child.destroy()
            process = null
            // Requests made from now on wait for the restarted bridge; the current latch may have been awaited already.
            ready = CountDownLatch(1)
        }
    }

    private fun parseLocation(parts: List<String>): DeviceLocation {
        if (parts.getOrNull(1) == "denied") return DeviceLocation.Denied
        val latitude = parts.getOrNull(1)?.toDoubleOrNull()
        val longitude = parts.getOrNull(2)?.toDoubleOrNull()
        return if (latitude != null && longitude != null) DeviceLocation.Found(latitude, longitude) else DeviceLocation.Unavailable
    }

    private fun parseMedia(parts: List<String>): MediaStatus? {
        if (parts.size < 10) return null
        val title = parts[3]
        if (title.isBlank()) return null
        return MediaStatus(
            title = title,
            artist = parts[4],
            app = appName(parts[2]),
            state = when (parts[1]) {
                "Playing" -> PlaybackState.PLAYING
                "Paused" -> PlaybackState.PAUSED
                else -> PlaybackState.STOPPED
            },
            positionMs = parts[5].toLongOrNull() ?: 0,
            durationMs = parts[6].toLongOrNull() ?: 0,
            updatedAtMs = parts[7].toLongOrNull() ?: System.currentTimeMillis(),
            canPrevious = parts[8].equals("True", ignoreCase = true),
            canNext = parts[9].equals("True", ignoreCase = true),
            artworkVersion = artworkVersion,
        )
    }

    private fun updateArtwork(base64: String) {
        artwork = base64.takeIf { it.isNotEmpty() }?.let {
            runCatching { Image.makeFromEncoded(Base64.getDecoder().decode(it)).toComposeImageBitmap() }.getOrNull()
        }
        artworkVersion++
        current?.let { publish(it.copy(artworkVersion = artworkVersion)) }
    }

    @Synchronized
    private fun publish(status: MediaStatus?) {
        val next = status?.copy(artworkVersion = artworkVersion)
        if (next != current) {
            current = next
            onMediaChange(next)
        }
    }

    /** Copies the script out of the jar, since PowerShell needs a real file to run. */
    private fun extractScript(): File {
        val folder = dev.windock.AppFolder
        folder.mkdirs()
        File(folder, "media-bridge.ps1").delete() // left behind by earlier versions
        val target = File(folder, "winrt-bridge.ps1")
        val bytes = javaClass.getResourceAsStream("/winrt-bridge.ps1")!!.use { it.readBytes() }
        if (!target.exists() || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
        return target
    }

    /** "Spotify.exe" → "Spotify", "Microsoft.ZuneMusic_8wekyb3d8bbwe!App" → "Media Player", "Chrome._crx_<id>" → its web app's name. */
    private fun appName(appId: String): String {
        if ("._crx_" in appId) {
            val browser = if (appId.startsWith("MSEdge", ignoreCase = true)) "Edge" else "Chrome"
            return webAppName(appId.substringAfter("._crx_")) ?: "App do $browser"
        }
        val known = mapOf(
            "microsoft.zunemusic" to "Media Player",
            "microsoft.zunevideo" to "Filmes e TV",
            "spotifyab.spotifymusic" to "Spotify",
            "msedge" to "Microsoft Edge",
            "chrome" to "Google Chrome",
            "firefox" to "Firefox",
        )
        val base = appId.substringBefore('!').substringBefore('_').removeSuffix(".exe").removeSuffix(".EXE")
        known[base.lowercase()]?.let { return it }
        return base.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { appId }
    }
}
