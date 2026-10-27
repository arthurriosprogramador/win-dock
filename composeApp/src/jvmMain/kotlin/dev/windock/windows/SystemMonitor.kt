package dev.windock.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import dev.windock.mvi.BatteryStatus
import dev.windock.mvi.NetworkKind
import dev.windock.mvi.SystemStatus
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
internal class SystemPowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

@Structure.FieldOrder("cbSize", "i64Size", "i64NumItems")
internal class RecycleBinInfo : Structure() {
    @JvmField var cbSize: Int = 0
    @JvmField var i64Size: Long = 0
    @JvmField var i64NumItems: Long = 0
}

@Suppress("FunctionName")
internal interface Kernel32Ex : StdCallLibrary {
    fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean
    /** With both sizes set to -1, trims the process's working set: idle pages go back to the OS. */
    fun SetProcessWorkingSetSize(process: com.sun.jna.platform.win32.WinNT.HANDLE, minimum: com.sun.jna.platform.win32.BaseTSD.SIZE_T, maximum: com.sun.jna.platform.win32.BaseTSD.SIZE_T): Boolean

    companion object {
        val INSTANCE: Kernel32Ex = Native.load("kernel32", Kernel32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

@Suppress("FunctionName")
internal interface Shell32Ex : StdCallLibrary {
    fun SHQueryRecycleBin(rootPath: String?, info: RecycleBinInfo): Int
    fun SHEmptyRecycleBin(owner: com.sun.jna.platform.win32.WinDef.HWND?, rootPath: String?, flags: Int): Int
    /** Extracts an icon at any size; unlike ExtractIconEx it understands negative resource ids like "imageres.dll,-54". */
    fun SHDefExtractIcon(iconFile: String, index: Int, flags: Int, large: PointerByReference, small: PointerByReference?, size: Int): Int

    companion object {
        val INSTANCE: Shell32Ex = Native.load("shell32", Shell32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/** A raw COM interface pointer whose methods are called by vtable slot. */
internal class ComObject(pointer: Pointer) : Unknown(pointer) {
    fun call(slot: Int, vararg args: Any?): Int = _invokeNativeInt(slot, arrayOf<Any?>(this.pointer, *args))
}

/** The default playback device's master volume, through Core Audio's IAudioEndpointVolume. Use from one COM thread. */
private class EndpointVolume {
    private var endpoint: ComObject? = null

    private fun open(): ComObject? {
        endpoint?.let { return it }
        val enumeratorRef = PointerByReference()
        val created = Ole32.INSTANCE.CoCreateInstance(
            GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}"), null, CLSCTX_ALL,
            GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}"), enumeratorRef,
        )
        if (created.toInt() < 0) return null
        val enumerator = ComObject(enumeratorRef.value)
        try {
            val deviceRef = PointerByReference()
            // IMMDeviceEnumerator::GetDefaultAudioEndpoint(eRender, eMultimedia, &device)
            if (enumerator.call(4, 0, 1, deviceRef) < 0) return null
            val device = ComObject(deviceRef.value)
            try {
                val volumeRef = PointerByReference()
                // IMMDevice::Activate(IID_IAudioEndpointVolume, CLSCTX_ALL, null, &volume)
                if (device.call(3, GUID("{5CDF2C82-841E-4546-9722-0CF74078229A}"), CLSCTX_ALL, null, volumeRef) < 0) return null
                return ComObject(volumeRef.value).also { endpoint = it }
            } finally {
                device.Release()
            }
        } finally {
            enumerator.Release()
        }
    }

    /** Drops the cached endpoint so the next read follows a newly selected output (e.g. headphones). */
    fun reset() {
        endpoint?.Release()
        endpoint = null
    }

    fun read(): Pair<Int, Boolean>? {
        val volume = open() ?: return null
        val level = FloatByReference()
        val mute = IntByReference()
        // GetMasterVolumeLevelScalar (slot 9) and GetMute (slot 15).
        if (volume.call(9, level) < 0 || volume.call(15, mute) < 0) {
            reset()
            return null
        }
        return Math.round(level.value * 100) to (mute.value != 0)
    }

    fun change(deltaPercent: Int) {
        val (current, muted) = read() ?: return
        val volume = endpoint ?: return
        val next = (current + deltaPercent).coerceIn(0, 100)
        // SetMasterVolumeLevelScalar (slot 7); raising the volume also unmutes, like the volume keys do.
        volume.call(7, next / 100f, null)
        if (muted && deltaPercent > 0) volume.call(14, 0, null)
    }

    companion object {
        const val CLSCTX_ALL = 0x17
    }
}

/**
 * Polls battery, network, volume and the recycle bin on one background thread (COM needs a stable
 * apartment). Volume is read every second so the wheel and the keyboard keys feel live; the rest every five.
 */
internal class SystemMonitor(private val onChange: (SystemStatus) -> Unit) {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "dock-system-monitor").apply { isDaemon = true } }
    private val volume = EndpointVolume()
    private var status = SystemStatus()
    private var tick = 0

    fun start() {
        if (!Win32.isWindows) return
        executor.execute { Ole32.INSTANCE.CoInitializeEx(null, 0) }
        executor.scheduleWithFixedDelay({ runCatching { poll() } }, 0, 1, TimeUnit.SECONDS)
    }

    fun changeVolume(deltaPercent: Int) {
        executor.execute {
            runCatching {
                volume.change(deltaPercent)
                publish(readVolume(status))
            }
        }
    }

    fun stop() = executor.shutdownNow()

    private fun poll() {
        var next = readVolume(status)
        if (tick % 5 == 0) {
            // Re-resolve the default output now and then, so a switch to headphones is picked up.
            if (tick % 10 == 0) volume.reset()
            next = next.copy(battery = readBattery(), network = readNetwork(), recycleBinFull = readRecycleBinFull())
        }
        tick++
        publish(next)
    }

    private fun publish(next: SystemStatus) {
        if (next != status) {
            status = next
            onChange(next)
        }
    }

    private fun readVolume(current: SystemStatus): SystemStatus {
        val (level, muted) = volume.read() ?: return current.copy(volumePercent = null)
        return current.copy(volumePercent = level, muted = muted)
    }

    private fun readBattery(): BatteryStatus? {
        val power = SystemPowerStatus()
        if (!Kernel32Ex.INSTANCE.GetSystemPowerStatus(power)) return null
        val flags = power.BatteryFlag.toInt() and 0xFF
        // 128 = no system battery, 255 = unknown.
        if (flags == 128 || flags == 255) return null
        val percent = (power.BatteryLifePercent.toInt() and 0xFF).takeIf { it <= 100 } ?: return null
        val pluggedIn = power.ACLineStatus.toInt() == 1
        val seconds = power.BatteryLifeTime
        return BatteryStatus(
            percent = percent,
            pluggedIn = pluggedIn,
            charging = flags and 8 != 0,
            minutesLeft = if (!pluggedIn && seconds > 0) seconds / 60 else null,
        )
    }

    private fun readNetwork(): NetworkKind {
        val virtual = Regex("virtual|hyper-v|vmware|virtualbox|vethernet|loopback|bluetooth|tap-|wan miniport|vpn|tunnel", RegexOption.IGNORE_CASE)
        val connected = NetworkInterface.networkInterfaces().toList().filter { nic ->
            nic.isUp && !nic.isLoopback && !nic.isVirtual && !virtual.containsMatchIn(nic.displayName ?: "") &&
                nic.inetAddresses.toList().any { !it.isLinkLocalAddress && !it.isLoopbackAddress }
        }
        val wireless = Regex("wi-?fi|wireless|802\\.11|wlan", RegexOption.IGNORE_CASE)
        return when {
            connected.any { wireless.containsMatchIn(it.displayName ?: "") || it.name.startsWith("wlan") } -> NetworkKind.WIFI
            connected.isNotEmpty() -> NetworkKind.ETHERNET
            else -> NetworkKind.NONE
        }
    }

    private fun readRecycleBinFull(): Boolean {
        val info = RecycleBinInfo().apply { cbSize = size() }
        return Shell32Ex.INSTANCE.SHQueryRecycleBin(null, info) >= 0 && info.i64NumItems > 0
    }
}
