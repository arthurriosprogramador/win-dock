package dev.windock

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import dev.windock.mvi.DockTheme
import dev.windock.mvi.DockUiState
import dev.windock.mvi.Mascot
import dev.windock.mvi.PinnedApp
import dev.windock.windows.Win32
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * %APPDATA%\WinDock, where settings, the crash log and the bridge script live. Earlier builds were called
 * "MavOS Dock"; their folder is carried over once, so nobody loses their theme and pins to the rename.
 */
internal val AppFolder: File by lazy {
    val base = File(System.getenv("APPDATA") ?: System.getProperty("user.home"))
    val folder = File(base, "WinDock")
    val legacy = File(base, "MavOS Dock")
    if (!folder.exists() && legacy.isDirectory) {
        // A rename fails while an old copy still holds its lock file; then just bring the settings across.
        if (!legacy.renameTo(folder)) runCatching {
            folder.mkdirs()
            File(legacy, "settings.properties").takeIf { it.isFile }?.copyTo(File(folder, "settings.properties"))
        }
    }
    folder
}

private val DockFolder get() = AppFolder

/**
 * Preferences in %APPDATA%\WinDock\settings.properties. Besides the user's choices it records
 * the native taskbar's original state, so a crash while the dock is active can be undone on the next launch.
 */
internal object DockSettings {
    private val file = File(DockFolder, "settings.properties")
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    private var instanceLock: FileLock? = null

    /** Only one dock may run: the logon task and a manual launch would otherwise stack two docks. */
    fun acquireSingleInstance(): Boolean {
        DockFolder.mkdirs()
        val channel = FileChannel.open(File(DockFolder, "dock.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        instanceLock = runCatching { channel.tryLock() }.getOrNull()
        return instanceLock != null
    }

    fun initialState(): DockUiState {
        val theme = props.getProperty("theme")?.let { name -> DockTheme.entries.firstOrNull { it.name == name } } ?: DockTheme.CASUAL
        val dockActive = props.getProperty("dockActive").toBoolean()
        return DockUiState(
            theme = theme,
            draftTheme = theme,
            randomPaletteIndex = props.getProperty("randomPaletteIndex")?.toIntOrNull() ?: 0,
            decorativeAnimationsEnabled = props.getProperty("decorativeAnimations")?.toBoolean() ?: true,
            reducedMotion = props.getProperty("reducedMotion").toBoolean(),
            startWithWindows = props.getProperty("startWithWindows").toBoolean(),
            weatherCity = props.getProperty("weatherCity")?.takeIf { it.isNotBlank() } ?: "São Paulo",
            weatherAutoLocate = props.getProperty("weatherAutoLocate")?.toBoolean() ?: true,
            mascot = props.getProperty("mascot")?.let { name -> Mascot.entries.firstOrNull { it.name == name } } ?: Mascot.CAT,
            pinnedApps = generateSequence(0) { it + 1 }
                .map { i -> Triple(props.getProperty("pin.$i.id"), props.getProperty("pin.$i.label"), props.getProperty("pin.$i.target")) }
                .takeWhile { it.first != null }
                .mapNotNull { (id, label, target) -> if (label != null && target != null) PinnedApp(id!!, label, target) else null }
                .toList(),
            hiddenPins = props.getProperty("hiddenPins").orEmpty().split('|').filter { it.isNotBlank() }.toSet(),
            dockActive = dockActive,
            // First run opens the theme picker; once the dock has been applied, start straight into it.
            settingsOpen = !file.exists(),
        )
    }

    @Synchronized
    fun save(state: DockUiState) {
        props["theme"] = state.theme.name
        props["randomPaletteIndex"] = state.randomPaletteIndex.toString()
        props["decorativeAnimations"] = state.decorativeAnimationsEnabled.toString()
        props["reducedMotion"] = state.reducedMotion.toString()
        props["startWithWindows"] = state.startWithWindows.toString()
        props["dockActive"] = state.dockActive.toString()
        props["weatherCity"] = state.weatherCity
        props["weatherAutoLocate"] = state.weatherAutoLocate.toString()
        props["mascot"] = state.mascot.name
        props.keys.removeAll { (it as String).startsWith("pin.") }
        state.pinnedApps.forEachIndexed { i, pin ->
            props["pin.$i.id"] = pin.id
            props["pin.$i.label"] = pin.label
            props["pin.$i.target"] = pin.target
        }
        // Paths can't contain '|', so it's a safe separator.
        props["hiddenPins"] = state.hiddenPins.joinToString("|")
        write()
    }

    /** The taskbar's auto-hide state from before the dock took over, or null when the taskbar is untouched. */
    var nativeTaskbarState: Int?
        @Synchronized get() = props.getProperty("nativeTaskbarState")?.toIntOrNull()
        @Synchronized set(value) {
            if (value == null) props.remove("nativeTaskbarState") else props["nativeTaskbarState"] = value.toString()
            write()
        }

    private fun write() {
        DockFolder.mkdirs()
        file.outputStream().use { props.store(it, "WinDock") }
    }
}

/**
 * Starts the dock at sign-in. A per-user logon task in Task Scheduler runs right away, while entries under
 * HKCU\...\Run are deliberately held back by Windows for several seconds after logon; the Run key is only a
 * fallback for when the task can't be created.
 */
internal object Autostart {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "WinDock"
    /** The task and Run entry registered by builds from before the rename. */
    private const val LEGACY_NAME = "MavOS Dock"

    fun apply(enabled: Boolean) {
        if (!Win32.isWindows) return
        removeLegacy()
        if (enabled) {
            val (command, arguments) = launchCommand()
            if (createLogonTask(command, arguments)) removeRunKey()
            else Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME, "\"$command\" $arguments".trim())
        } else {
            schtasks("/Delete", "/TN", NAME, "/F")
            removeRunKey()
        }
    }

    private var legacyRemoved = false

    private fun removeLegacy() {
        if (legacyRemoved) return
        legacyRemoved = true
        schtasks("/Delete", "/TN", LEGACY_NAME, "/F")
        val hkcu = WinReg.HKEY_CURRENT_USER
        if (Advapi32Util.registryValueExists(hkcu, RUN_KEY, LEGACY_NAME)) Advapi32Util.registryDeleteValue(hkcu, RUN_KEY, LEGACY_NAME)
    }

    private fun removeRunKey() {
        val hkcu = WinReg.HKEY_CURRENT_USER
        if (Advapi32Util.registryValueExists(hkcu, RUN_KEY, NAME)) Advapi32Util.registryDeleteValue(hkcu, RUN_KEY, NAME)
    }

    /**
     * Registers the task from XML. The trigger is limited to this user, which needs no administrator rights,
     * and the defaults that would hurt a dock are overridden: it may start on battery, is never stopped after
     * three days, and runs at normal priority.
     */
    private fun createLogonTask(command: String, arguments: String): Boolean {
        val user = "${System.getenv("USERDOMAIN")}\\${System.getenv("USERNAME")}"
        fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val definition = """
            <?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
              <RegistrationInfo><Description>Starts WinDock at sign-in.</Description></RegistrationInfo>
              <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>${xml(user)}</UserId></LogonTrigger></Triggers>
              <Principals><Principal id="Author"><UserId>${xml(user)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>
              <Settings>
                <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                <Priority>4</Priority>
                <AllowStartOnDemand>true</AllowStartOnDemand>
              </Settings>
              <Actions Context="Author"><Exec><Command>${xml(command)}</Command><Arguments>${xml(arguments)}</Arguments></Exec></Actions>
            </Task>
        """.trimIndent()
        val xmlFile = File(DockFolder, "autostart-task.xml")
        DockFolder.mkdirs()
        // schtasks reads task XML as UTF-16LE with a byte-order mark.
        xmlFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + definition.toByteArray(Charsets.UTF_16LE))
        return try {
            schtasks("/Create", "/TN", NAME, "/XML", xmlFile.path, "/F")
        } finally {
            xmlFile.delete()
        }
    }

    private fun schtasks(vararg args: String): Boolean = runCatching {
        val process = ProcessBuilder(listOf("schtasks.exe") + args).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    /** The executable and its arguments for starting this same build of the dock. */
    private fun launchCommand(): Pair<String, String> {
        // Installed (jpackage/MSI) builds expose their own launcher.
        System.getProperty("jpackage.app-path")?.let { return it to "" }
        // Dev builds: javaw with an argument file, so the long classpath stays out of the task and no console flashes.
        val javaw = File(System.getProperty("java.home"), "bin\\javaw.exe")
        val argFile = File(DockFolder, "launch.args")
        DockFolder.mkdirs()
        val classPath = System.getProperty("java.class.path").replace("\\", "\\\\")
        // Start with the same memory/JIT tuning this process was launched with.
        val jvmFlags = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-X") || it.startsWith("--enable-native-access") }
        argFile.writeText((jvmFlags + "-cp \"$classPath\"" + "dev.windock.MainKt").joinToString("\n") + "\n")
        return javaw.path to "\"@${argFile.path}\""
    }
}
