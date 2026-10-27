package dev.windock.windows

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A minimal, read-only reader for OLE compound files ([MS-CFB]), the container format of
 * `.automaticDestinations-ms` jump lists: returns every stream in the root storage by name.
 */
internal object CompoundFile {
    fun streams(bytes: ByteArray): Map<String, ByteArray> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 512 || b.getLong(0) != 0xE11AB1A1E011CFD0uL.toLong()) return emptyMap() // D0 CF 11 E0 A1 B1 1A E1
        val sectorSize = 1 shl b.getShort(0x1E).toInt()
        val miniSectorSize = 1 shl b.getShort(0x20).toInt()
        val fatSectorCount = b.getInt(0x2C)
        val firstDirSector = b.getInt(0x30)
        val miniCutoff = b.getInt(0x38)
        val firstMiniFatSector = b.getInt(0x3C)
        var difatSector = b.getInt(0x44)
        fun sectorOffset(sector: Int) = (sector + 1) * sectorSize

        // The FAT's own sectors are listed in the header (first 109) and then in DIFAT sectors.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) b.getInt(0x4C + i * 4).takeIf { it >= 0 }?.let(fatSectors::add)
        while (difatSector >= 0 && fatSectors.size < fatSectorCount) {
            val base = sectorOffset(difatSector)
            for (i in 0 until sectorSize / 4 - 1) b.getInt(base + i * 4).takeIf { it >= 0 }?.let(fatSectors::add)
            difatSector = b.getInt(base + sectorSize - 4)
        }
        val fat = IntArray(fatSectors.size * sectorSize / 4)
        fatSectors.forEachIndexed { i, s ->
            val base = sectorOffset(s)
            for (j in 0 until sectorSize / 4) if (base + j * 4 + 4 <= bytes.size) fat[i * sectorSize / 4 + j] = b.getInt(base + j * 4)
        }
        fun chain(start: Int): List<Int> {
            val out = ArrayList<Int>()
            var s = start
            while (s >= 0 && s < fat.size && out.size < fat.size) {
                out += s
                s = fat[s]
            }
            return out
        }
        fun readChain(start: Int, size: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (s in chain(start)) {
                val off = sectorOffset(s)
                out.write(bytes, off, minOf(sectorSize, bytes.size - off))
            }
            return out.toByteArray().copyOf(minOf(size, out.size().toLong()).toInt())
        }

        val directory = ByteBuffer.wrap(readChain(firstDirSector, Long.MAX_VALUE)).order(ByteOrder.LITTLE_ENDIAN)
        class Entry(val name: String, val type: Int, val start: Int, val size: Long)
        val entries = (0 until directory.capacity() / 128).map { i ->
            val base = i * 128
            val nameLength = (directory.getShort(base + 64).toInt() - 2).coerceIn(0, 62)
            val name = String(directory.array(), base, nameLength, Charsets.UTF_16LE)
            Entry(name, directory.get(base + 66).toInt(), directory.getInt(base + 116), directory.getLong(base + 120) and 0xFFFFFFFFL)
        }
        val root = entries.firstOrNull { it.type == 5 } ?: return emptyMap()
        // Small streams live in the "mini stream" (the root entry's data), addressed through the mini FAT.
        val miniStream = readChain(root.start, root.size)
        val miniFatBytes = if (firstMiniFatSector >= 0) readChain(firstMiniFatSector, Long.MAX_VALUE) else ByteArray(0)
        val miniFat = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN).let { mb -> IntArray(miniFatBytes.size / 4) { mb.getInt(it * 4) } }
        fun readMini(start: Int, size: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && s < miniFat.size && guard++ < miniFat.size) {
                val off = s * miniSectorSize
                if (off >= miniStream.size) break
                out.write(miniStream, off, minOf(miniSectorSize, miniStream.size - off))
                s = miniFat[s]
            }
            return out.toByteArray().copyOf(minOf(size, out.size().toLong()).toInt())
        }
        return entries.filter { it.type == 2 }.associate { e ->
            e.name to if (e.size < miniCutoff) readMini(e.start, e.size) else readChain(e.start, e.size)
        }
    }
}
