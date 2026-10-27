package dev.windock.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One entry of a jump list: what to show and how to open it. */
internal data class JumpItem(
    val title: String,
    /** Executable (or document) to run, and its arguments — set for shortcut-style entries such as Chrome's. */
    val target: String?,
    val arguments: String?,
    val workingDir: String?,
    /** The file or folder the entry stands for — set for "recent"/"pinned" entries. */
    val path: String?,
    /** "file,index" icon resource, when the entry carries one. */
    val iconSpec: String?,
)

internal data class JumpCategory(val title: String, val items: List<JumpItem>)

/**
 * Reads an app's jump list the way the taskbar shows it: custom categories (e.g. Chrome's "Mais visitadas")
 * and tasks from `CustomDestinations`, recent/frequent/pinned entries from `AutomaticDestinations`. Each file
 * is named after a CRC-64 of the app's AppUserModelID; there is no public API to read other apps' lists.
 */
internal object JumpLists {
    private val recentFolder = File(System.getenv("APPDATA") ?: "", "Microsoft\\Windows\\Recent")
    private const val MAX_PER_CATEGORY = 10
    private const val MAX_ITEMS = 14

    /** The jump list file name for an AppUserModelID: reflected CRC-64 (0x92C64265D32139A4) of its upper-case UTF-16LE. */
    fun appIdHash(appUserModelId: String): String {
        var crc = -1L
        for (byte in appUserModelId.uppercase().toByteArray(Charsets.UTF_16LE)) {
            crc = crc xor (byte.toLong() and 0xFF)
            repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor -0x6d39bd9a2cdec65cL else crc ushr 1 }
        }
        return "%016x".format(crc)
    }

    /** Categories in the order the taskbar would show them; tasks come last. */
    fun read(appUserModelId: String): List<JumpCategory> {
        val hash = appIdHash(appUserModelId)
        val automatic = File(recentFolder, "AutomaticDestinations\\$hash.automaticDestinations-ms")
        val custom = File(recentFolder, "CustomDestinations\\$hash.customDestinations-ms")
        val auto = if (automatic.isFile) runCatching { readAutomatic(automatic.readBytes()) }.getOrNull() else null
        val categories = ArrayList<JumpCategory>()
        auto?.pinned?.takeIf { it.isNotEmpty() }?.let { categories += JumpCategory("Fixado", it) }
        val customCategories = if (custom.isFile) runCatching { readCustom(custom.readBytes()) }.getOrNull() else null
        if (customCategories == null) {
            // Without a custom list, Windows shows the automatic "Recent" category.
            auto?.recent?.takeIf { it.isNotEmpty() }?.let { categories += JumpCategory("Recente", it.take(MAX_PER_CATEGORY)) }
        } else {
            for (category in customCategories) {
                when (category) {
                    is CustomCategory.Known -> {
                        val items = when (category.id) {
                            1 -> auto?.frequent
                            else -> auto?.recent
                        }.orEmpty().filterNot { item -> auto?.pinned.orEmpty().any { it.path == item.path } }
                        if (items.isNotEmpty()) categories += JumpCategory(if (category.id == 1) "Mais usados" else "Recente", items.take(MAX_PER_CATEGORY))
                    }
                    is CustomCategory.Items -> if (category.items.isNotEmpty()) categories += JumpCategory(category.title, category.items.take(MAX_PER_CATEGORY))
                }
            }
        }
        // Keep the menu within a screen's height, as the taskbar does: tasks always fit, the lists share the rest.
        var budget = MAX_ITEMS - categories.filter { it.title == "Tarefas" }.sumOf { it.items.size }
        return categories.mapNotNull { category ->
            if (category.title == "Tarefas") return@mapNotNull category
            val items = category.items.distinctBy { it.path ?: (it.target + it.arguments) }.take(budget.coerceAtLeast(0))
            budget -= items.size
            items.takeIf { it.isNotEmpty() }?.let { JumpCategory(category.title, it) }
        }
    }

    /** Opens an entry the way the taskbar would: shortcut-style entries run their target, the rest open their file. */
    fun open(item: JumpItem) {
        val target = item.target
        if (target != null && (item.arguments != null || item.path == null)) {
            com.sun.jna.platform.win32.Shell32.INSTANCE.ShellExecute(null, "open", target, item.arguments, item.workingDir, Win32.SW_SHOWNORMAL)
        } else {
            (item.path ?: target)?.let(::shellOpen)
        }
    }

    // ---------------------------------------------------------------- AppUserModelID

    /** The ID Windows groups a window under: explicit when the app set one, otherwise derived from its executable. */
    fun appUserModelIdOf(window: HWND): String? = runCatching {
        val ref = PointerByReference()
        if (ShellPropertyApi.INSTANCE.SHGetPropertyStoreForWindow(window, GUID("{886d8eeb-8cf2-4446-8d02-cdba1dbdcf99}"), ref) < 0 || ref.value == null) return null
        val store = ComObject(ref.value)
        try {
            val key = PropertyKey(GUID("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}"), 5)
            val value = Memory(24).apply { clear() }
            // IPropertyStore::GetValue(key, &propvariant)
            if (store.call(5, key, value) < 0) return null
            try {
                if (value.getShort(0).toInt() == VT_LPWSTR) value.getPointer(8)?.getWideString(0)?.takeIf { it.isNotBlank() } else null
            } finally {
                ShellPropertyApi.OLE32.PropVariantClear(value)
            }
        } finally {
            store.Release()
        }
    }.getOrNull()

    /** The ID Windows assigns to an executable that doesn't declare one: its path with the known folder as a GUID. */
    fun defaultAppUserModelId(exePath: String): String {
        val windows = System.getenv("WINDIR") ?: "C:\\Windows"
        val folders = listOf(
            "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}" to "$windows\\System32",
            "{F38BF404-1D43-42F2-9305-67DE0B28FC23}" to windows,
            "{6D809377-6AF0-444B-8957-A3773F02200E}" to (System.getenv("ProgramW6432") ?: System.getenv("ProgramFiles") ?: "C:\\Program Files"),
            "{7C5A40EF-A0FB-4BFC-874A-C0F2E0B9FA8E}" to (System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"),
            "{F1B32785-6FBA-4FCF-9D55-7B8E7F157091}" to (System.getenv("LOCALAPPDATA") ?: ""),
            "{3EB685DB-65F9-4CF6-A03A-E3EF65729F3D}" to (System.getenv("APPDATA") ?: ""),
        ).filter { it.second.isNotEmpty() }
        val match = folders.filter { exePath.startsWith(it.second + "\\", ignoreCase = true) }.maxByOrNull { it.second.length } ?: return exePath
        return match.first + exePath.substring(match.second.length)
    }

    // ---------------------------------------------------------------- Formats

    private sealed interface CustomCategory {
        data class Known(val id: Int) : CustomCategory
        data class Items(val title: String, val items: List<JumpItem>) : CustomCategory
    }

    private val ShellLinkClsid = guidBytes("00021401-0000-0000-C000-000000000046")
    private const val FOOTER = 0xBABFFBAB.toInt()

    /** `.customDestinations-ms`: a header, then categories of embedded .lnk files, each closed by a footer. */
    private fun readCustom(bytes: ByteArray): List<CustomCategory> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.getInt(4)
        var pos = 12
        val out = ArrayList<CustomCategory>()
        repeat(count) {
            if (pos + 4 > bytes.size) return out
            val type = b.getInt(pos).also { pos += 4 }
            when (type) {
                1 -> out += CustomCategory.Known(b.getInt(pos).also { pos += 4 })
                0, 2 -> {
                    val title = if (type == 0) {
                        val length = b.getShort(pos).toInt() and 0xFFFF
                        String(bytes, pos + 2, length * 2, Charsets.UTF_16LE).also { pos += 2 + length * 2 }
                    } else {
                        "Tarefas"
                    }
                    val entries = b.getInt(pos).also { pos += 4 }
                    val items = ArrayList<JumpItem>()
                    repeat(entries) {
                        if (pos + 16 > bytes.size || !bytes.copyOfRange(pos, pos + 16).contentEquals(ShellLinkClsid)) return out
                        val link = readLink(bytes, pos + 16) ?: return out
                        pos = link.end
                        val title = link.title ?: link.name ?: link.target?.let { File(it).nameWithoutExtension }
                        // Separators in a task list are empty links; skip them.
                        if (!title.isNullOrBlank()) items += JumpItem(title, link.target, link.arguments, link.workingDir, null, link.iconSpec)
                    }
                    out += CustomCategory.Items(resolveTitle(title), items)
                }
                else -> return out
            }
            if (pos + 4 <= bytes.size && b.getInt(pos) == FOOTER) pos += 4
        }
        return out
    }

    private class Automatic(val pinned: List<JumpItem>, val recent: List<JumpItem>, val frequent: List<JumpItem>)

    /** `.automaticDestinations-ms`: one .lnk stream per entry plus a "DestList" index with order, pins and use counts. */
    private fun readAutomatic(bytes: ByteArray): Automatic {
        val streams = CompoundFile.streams(bytes)
        val list = streams["DestList"] ?: return Automatic(emptyList(), emptyList(), emptyList())
        val b = ByteBuffer.wrap(list).order(ByteOrder.LITTLE_ENDIAN)
        val version = b.getInt(0)
        val count = b.getInt(4)
        class Entry(val item: JumpItem, val pin: Int, val lastAccess: Long, val uses: Int)
        val entries = ArrayList<Entry>()
        var pos = 32
        repeat(count) {
            if (pos + 130 > list.size) return@repeat
            // Windows 7 (v1) entries are shorter; Windows 10/11 (v3+) add a score and a use count.
            val modern = version >= 3
            val number = b.getInt(pos + 88)
            val lastAccess = b.getLong(pos + if (modern) 100 else 96)
            val pin = b.getInt(pos + if (modern) 108 else 104)
            val uses = if (modern) b.getInt(pos + 116) else 0
            val pathLengthAt = pos + if (modern) 128 else 112
            val pathLength = b.getShort(pathLengthAt).toInt() and 0xFFFF
            if (pathLengthAt + 2 + pathLength * 2 > list.size) return@repeat
            val path = String(list, pathLengthAt + 2, pathLength * 2, Charsets.UTF_16LE)
            pos = pathLengthAt + 2 + pathLength * 2 + if (modern) 4 else 0
            val link = streams[number.toString(16)]?.let { readLink(it, 0) }
            // Some apps (JetBrains IDEs) add shell links rather than files: the DestList path is then just a hash.
            val file = link?.target?.takeIf { link.arguments.isNullOrBlank() } ?: path.takeIf { it.contains('\\') || it.contains("://") }
            val title = link?.title ?: link?.name ?: file?.trimEnd('\\')?.substringAfterLast('\\')?.ifBlank { file } ?: return@repeat
            val item = JumpItem(title, link?.target, link?.arguments?.takeIf { it.isNotBlank() }, link?.workingDir, file, link?.iconSpec)
            entries += Entry(item, pin, lastAccess, uses)
        }
        return Automatic(
            pinned = entries.filter { it.pin >= 0 }.sortedBy { it.pin }.map { it.item },
            recent = entries.sortedByDescending { it.lastAccess }.map { it.item },
            frequent = entries.sortedByDescending { it.uses }.map { it.item },
        )
    }

    /** Category names may be resource references such as "@C:\…\app.dll,-123"; show something readable either way. */
    private fun resolveTitle(title: String): String = when {
        !title.startsWith("@") -> title
        else -> runCatching { ShellPropertyApi.SHLWAPI.loadIndirect(title) }.getOrNull() ?: "Itens"
    }

    // ---------------------------------------------------------------- Shell links

    private class Link(
        val end: Int, val target: String?, val arguments: String?, val workingDir: String?, val name: String?,
        val iconSpec: String?, val title: String?,
    )

    /** Parses a [MS-SHLLINK] shell link starting at [start]; [Link.end] is where it stops. */
    private fun readLink(bytes: ByteArray, start: Int): Link? = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(start) != 0x4C) return null
        val flags = b.getInt(start + 0x14)
        val iconIndex = b.getInt(start + 0x38)
        var pos = start + 0x4C
        if (flags and 0x01 != 0) pos += 2 + (b.getShort(pos).toInt() and 0xFFFF)
        var target: String? = null
        if (flags and 0x02 != 0) {
            val infoSize = b.getInt(pos)
            val headerSize = b.getInt(pos + 4)
            if (b.getInt(pos + 8) and 0x01 != 0) {
                // The full path is a local base ("C:\Users\") plus a common suffix ("me\app.exe").
                target = if (headerSize >= 0x24) {
                    utf16zAt(bytes, pos + b.getInt(pos + 0x1C)) + utf16zAt(bytes, pos + b.getInt(pos + 0x20))
                } else {
                    ansizAt(bytes, pos + b.getInt(pos + 0x10)) + ansizAt(bytes, pos + b.getInt(pos + 0x18))
                }
            }
            pos += infoSize
        }
        val unicode = flags and 0x80 != 0
        val strings = HashMap<Int, String>()
        for (bit in intArrayOf(0x04, 0x08, 0x10, 0x20, 0x40)) {
            if (flags and bit == 0) continue
            val length = (b.getShort(pos).toInt() and 0xFFFF) * if (unicode) 2 else 1
            strings[bit] = String(bytes, pos + 2, length, if (unicode) Charsets.UTF_16LE else Charsets.ISO_8859_1)
            pos += 2 + length
        }
        var title: String? = null
        var envTarget: String? = null
        while (pos + 4 <= bytes.size) {
            val size = b.getInt(pos)
            if (size < 8) {
                pos += 4 // terminal block
                break
            }
            when (b.getInt(pos + 4)) {
                0xA0000001.toInt() -> envTarget = utf16zAt(bytes, pos + 268)
                0xA0000009.toInt() -> title = propertyString(bytes, pos + 8, pos + size, TitleKey)
            }
            pos += size
        }
        val icon = strings[0x40]?.takeIf { it.isNotBlank() }?.let { "${expandEnvironment(it)},$iconIndex" }
        Link(pos, (target ?: envTarget)?.takeIf { it.isNotBlank() }?.let(::expandEnvironment), strings[0x20], strings[0x10],
            strings[0x04]?.takeIf { !it.startsWith("@") }, icon, title?.takeIf { it.isNotBlank() })
    }.getOrNull()

    /** The AppUserModelID stored in a shortcut's property block, if any (e.g. "Chrome._crx_…", "Microsoft.Windows.Explorer"). */
    fun appUserModelIdOf(shortcut: File): String? = runCatching {
        val bytes = shortcut.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val link = readLink(bytes, 0) ?: return null
        // Walk the extra-data blocks again to find the property store; readLink keeps only the title from it.
        var pos = 0x4C
        val flags = b.getInt(0x14)
        if (flags and 0x01 != 0) pos += 2 + (b.getShort(pos).toInt() and 0xFFFF)
        if (flags and 0x02 != 0) pos += b.getInt(pos)
        val unicode = flags and 0x80 != 0
        for (bit in intArrayOf(0x04, 0x08, 0x10, 0x20, 0x40)) if (flags and bit != 0) pos += 2 + (b.getShort(pos).toInt() and 0xFFFF) * if (unicode) 2 else 1
        while (pos + 8 <= link.end) {
            val size = b.getInt(pos)
            if (size < 8) break
            if (b.getInt(pos + 4) == 0xA0000009.toInt()) return propertyString(bytes, pos + 8, pos + size, AppIdKey)
            pos += size
        }
        null
    }.getOrNull()

    private class Key(val format: ByteArray, val id: Int)
    private val TitleKey = Key(guidBytes("F29F85E0-4FF9-1068-AB91-08002B27B3D9"), 2)
    private val AppIdKey = Key(guidBytes("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), 5)
    private const val VT_LPWSTR = 0x1F

    /** Reads a string value from a [MS-PROPSTORE] serialized property store between [from] and [to]. */
    private fun propertyString(bytes: ByteArray, from: Int, to: Int, key: Key): String? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var storage = from
        while (storage + 24 <= to) {
            val storageSize = b.getInt(storage)
            if (storageSize == 0) break
            val format = bytes.copyOfRange(storage + 8, storage + 24)
            var value = storage + 24
            while (value + 4 <= storage + storageSize) {
                val valueSize = b.getInt(value)
                if (valueSize == 0) break
                if (format.contentEquals(key.format) && b.getInt(value + 4) == key.id) {
                    val type = b.getShort(value + 9).toInt() and 0xFFFF
                    if (type == VT_LPWSTR) {
                        val chars = b.getInt(value + 13)
                        return String(bytes, value + 17, (chars * 2).coerceAtMost(valueSize), Charsets.UTF_16LE).trimEnd('\u0000')
                    }
                }
                value += valueSize
            }
            storage += storageSize
        }
        return null
    }

    private fun guidBytes(text: String): ByteArray {
        val hex = text.replace("-", "")
        fun part(from: Int, to: Int) = hex.substring(from, to).chunked(2).map { it.toInt(16).toByte() }
        return (part(0, 8).reversed() + part(8, 12).reversed() + part(12, 16).reversed() + part(16, 32)).toByteArray()
    }

    private fun utf16zAt(bytes: ByteArray, start: Int): String {
        var end = start
        while (end + 1 < bytes.size && (bytes[end].toInt() != 0 || bytes[end + 1].toInt() != 0)) end += 2
        return String(bytes, start, end - start, Charsets.UTF_16LE)
    }

    private fun ansizAt(bytes: ByteArray, start: Int): String {
        var end = start
        while (end < bytes.size && bytes[end].toInt() != 0) end++
        return String(bytes, start, end - start)
    }
}

@Structure.FieldOrder("fmtid", "pid")
internal class PropertyKey(@JvmField var fmtid: GUID = GUID(), @JvmField var pid: Int = 0) : Structure()

@Suppress("FunctionName")
private interface ShellPropertyApi : StdCallLibrary {
    fun SHGetPropertyStoreForWindow(hwnd: HWND, riid: GUID, store: PointerByReference): Int

    interface Ole32Ex : StdCallLibrary {
        fun PropVariantClear(value: Pointer): Int
    }

    interface ShlwapiEx : StdCallLibrary {
        fun SHLoadIndirectString(source: String, output: CharArray, size: Int, reserved: Pointer?): Int
    }

    companion object {
        val INSTANCE: ShellPropertyApi = Native.load("shell32", ShellPropertyApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        val OLE32: Ole32Ex = Native.load("ole32", Ole32Ex::class.java)
        val SHLWAPI: ShlwapiEx = Native.load("shlwapi", ShlwapiEx::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/** Resolves "@file,-id" resource strings through the shell. */
private fun ShellPropertyApi.ShlwapiEx.loadIndirect(source: String): String? {
    val buffer = CharArray(512)
    return if (SHLoadIndirectString(source, buffer, buffer.size, null) >= 0) Native.toString(buffer).takeIf { it.isNotBlank() } else null
}
