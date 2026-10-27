package dev.windock

import dev.windock.windows.NativeTaskbar
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** Errors and hangs go to %APPDATA%\WinDock\dock.log, so a crash can be diagnosed after the fact. */
internal object CrashLog {
    private val file = File(AppFolder, "dock.log")

    @Synchronized
    fun write(title: String, detail: String) {
        runCatching {
            file.parentFile.mkdirs()
            // Keep the log small: start over once it passes 1 MB.
            if (file.length() > 1_000_000) file.delete()
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            file.appendText("[$stamp] $title\n$detail\n\n")
        }
    }

    fun exception(where: String, error: Throwable) = write("Erro em $where", error.stackTraceToString())
}

/**
 * Ends any other dock process still showing its bar — e.g. one started at sign-in by an older build, from
 * before the single-instance lock — so two bars never stack on top of each other.
 */
internal fun closeStaleDocks() {
    if (!dev.windock.windows.Win32.isWindows) return
    val me = ProcessHandle.current().pid()
    val stale = HashSet<Long>()
    val user32 = com.sun.jna.platform.win32.User32.INSTANCE
    user32.EnumWindows({ hwnd, _ ->
        val title = CharArray(64).also { user32.GetWindowText(hwnd, it, it.size) }.concatToString().trimEnd('\u0000')
        // Current and pre-rename ("MavOS Dock") window titles.
        if (title in setOf("WinDock", "WinDock Overlay", "MavOS Dock", "MavOS Dock Overlay")) {
            val pid = com.sun.jna.ptr.IntByReference().also { user32.GetWindowThreadProcessId(hwnd, it) }.value.toLong()
            if (pid != me) stale += pid
        }
        true
    }, null)
    stale.forEach { pid ->
        ProcessHandle.of(pid).ifPresent { process ->
            if (process.info().command().orElse("").contains("java", ignoreCase = true)) {
                CrashLog.write("Encerrando outra cópia do dock (pid $pid)", process.info().commandLine().orElse(""))
                process.destroyForcibly()
            }
        }
    }
}

/**
 * Watches the UI thread. If it stops answering for 40 s, the dock is frozen: its stack goes to the log, the
 * native taskbar is given back, and the process ends — better than a dead bar the user can't get rid of.
 * Time spent with the PC asleep is not counted as a hang.
 */
internal fun startUiWatchdog() = thread(isDaemon = true, name = "dock-ui-watchdog") {
    var strikes = 0
    while (true) {
        Thread.sleep(5_000)
        val answered = CountDownLatch(1)
        val sentAt = System.currentTimeMillis()
        SwingUtilities.invokeLater { answered.countDown() }
        val ok = answered.await(20, TimeUnit.SECONDS)
        // A wall-clock jump far beyond the wait means the machine slept; that isn't a hang.
        val slept = System.currentTimeMillis() - sentAt > 35_000
        strikes = if (ok || slept) 0 else strikes + 1
        if (strikes >= 2) {
            val ui = Thread.getAllStackTraces().entries.filter { it.key.name.startsWith("AWT-EventQueue") }
                .joinToString("\n\n") { (t, stack) -> "${t.name}:\n" + stack.joinToString("\n") { "    at $it" } }
            CrashLog.write("Interface travada por mais de 40 s; encerrando e devolvendo a barra do Windows", ui)
            runCatching { NativeTaskbar.restore() }
            Runtime.getRuntime().halt(2)
        }
    }
}
