package com.spriteoffset

import java.nio.charset.Charset
import java.util.Locale
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Reads a raw PLAYPAL lump or locates PLAYPAL inside IWAD/PWAD and ZIP/PK3 archives.
 *
 * A palette is 256 RGB triples (768 bytes). Each PLAYPAL palette is stored
 * consecutively, without a per-palette header.
 */
internal object DoomPlaypalCodec {
    private const val BYTES_PER_COLOR = 3
    private const val COLORS_PER_PALETTE = 256
    private const val BYTES_PER_PALETTE = BYTES_PER_COLOR * COLORS_PER_PALETTE
    private const val MAX_PALETTES = 256
    private val ASCII = Charset.forName("US-ASCII")

    fun parse(rawOrWad: ByteArray): List<IntArray> {
        require(rawOrWad.isNotEmpty()) { "El archivo PLAYPAL está vacío" }

        val playpalBytes = when {
            isWad(rawOrWad) -> extractPlaypalFromWad(rawOrWad)
            isZip(rawOrWad) -> extractPlaypalFromZip(rawOrWad)
            else -> rawOrWad
        }

        require(playpalBytes.isNotEmpty()) { "El lump PLAYPAL está vacío" }
        require(playpalBytes.size % BYTES_PER_PALETTE == 0) {
            "PLAYPAL debe contener paletas completas de 768 bytes"
        }

        val paletteCount = playpalBytes.size / BYTES_PER_PALETTE
        require(paletteCount in 1..MAX_PALETTES) {
            "Cantidad de paletas no válida: $paletteCount"
        }

        return List(paletteCount) { paletteIndex ->
            val palette = IntArray(COLORS_PER_PALETTE)
            val base = paletteIndex * BYTES_PER_PALETTE
            for (colorIndex in 0 until COLORS_PER_PALETTE) {
                val position = base + colorIndex * BYTES_PER_COLOR
                val red = playpalBytes[position].toInt() and 0xFF
                val green = playpalBytes[position + 1].toInt() and 0xFF
                val blue = playpalBytes[position + 2].toInt() and 0xFF
                palette[colorIndex] = (0xFF shl 24) or
                    (red shl 16) or (green shl 8) or blue
            }
            palette
        }
    }

    private fun isZip(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() ||
                bytes[2] == 0x05.toByte() ||
                bytes[2] == 0x07.toByte())
    }

    private fun extractPlaypalFromZip(zipBytes: ByteArray): ByteArray {
        var result: ByteArray? = null
        var entries = 0

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries++
                require(entries <= 50_000) { "El PK3 contiene demasiados archivos" }

                if (!entry.isDirectory) {
                    val baseName = entry.name.replace('\\\\', '/')
                        .substringAfterLast('/')
                        .uppercase(Locale.ROOT)
                    if (baseName == "PLAYPAL" || baseName == "PLAYPAL.PAL") {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 2 * 1024 * 1024) {
                                "El recurso PLAYPAL del PK3 supera 2 MB"
                            }
                            output.write(buffer, 0, count)
                        }
                        result = output.toByteArray()
                    }
                }
                input.closeEntry()
            }
        }

        return result ?: throw IllegalArgumentException("No se encontró PLAYPAL dentro del PK3")
    }

    private fun isWad(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val magic = String(bytes, 0, 4, ASCII)
        return magic == "IWAD" || magic == "PWAD"
    }

    private fun extractPlaypalFromWad(wad: ByteArray): ByteArray {
        require(wad.size >= 12) { "Cabecera WAD incompleta" }

        val lumpCount = readU32LE(wad, 4)
        val directoryOffset = readU32LE(wad, 8)
        require(lumpCount >= 1L && lumpCount <= 1_000_000L) {
            "Número de lumps WAD no válido"
        }

        val directoryEnd = directoryOffset + lumpCount * 16L
        require(directoryOffset >= 12L && directoryEnd <= wad.size.toLong()) {
            "Directorio WAD fuera del archivo"
        }

        var playpalOffset = -1L
        var playpalSize = -1L

        // Later directory entries take precedence when a WAD contains duplicate
        // lump names, matching the usual archive override ordering.
        for (index in 0 until lumpCount.toInt()) {
            val entryOffset = directoryOffset.toInt() + index * 16
            val lumpOffset = readU32LE(wad, entryOffset)
            val lumpSize = readU32LE(wad, entryOffset + 4)
            val lumpName = String(wad, entryOffset + 8, 8, ASCII)
                .trimEnd('\u0000', ' ')
                .uppercase(Locale.ROOT)

            if (lumpName == "PLAYPAL") {
                require(lumpOffset + lumpSize <= wad.size.toLong()) {
                    "El lump PLAYPAL apunta fuera del WAD"
                }
                playpalOffset = lumpOffset
                playpalSize = lumpSize
            }
        }

        require(playpalOffset >= 0L && playpalSize >= 0L) {
            "No se encontró un lump PLAYPAL dentro del WAD"
        }
        require(playpalSize <= Int.MAX_VALUE.toLong()) { "PLAYPAL demasiado grande" }

        val start = playpalOffset.toInt()
        val end = start + playpalSize.toInt()
        return wad.copyOfRange(start, end)
    }

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 3 < data.size)
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
