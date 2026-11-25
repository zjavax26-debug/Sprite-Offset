package com.spriteoffset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class PngOffsetChunkTest {
    @Test
    fun readsGrAbEvenWhenItsStoredCrcIsStale() {
        val png = makePng(
            chunk("grAb", intBytes(23) + intBytes(50), corruptCrc = true)
        )

        val offsets = PngOffsetChunk.readOffsets(png)

        assertNotNull(offsets)
        assertEquals(23, offsets!!.first)
        assertEquals(50, offsets.second)
    }

    @Test
    fun writesAndReplacesGrAbAndPreservesSignedOffsets() {
        val original = makePng(
            chunk("grAb", intBytes(1) + intBytes(2))
        )

        val updated = PngOffsetChunk.withOffsets(original, -23, 50)
        val offsets = PngOffsetChunk.readOffsets(updated)

        assertNotNull(offsets)
        assertEquals(-23, offsets!!.first)
        assertEquals(50, offsets.second)
        assertEquals(1, countChunks(updated, "grAb"))
    }

    private fun makePng(vararg extraChunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        out.write(chunk("IHDR", ByteArray(13)))
        extraChunks.forEach { out.write(it) }
        out.write(chunk("IDAT", byteArrayOf(1, 2, 3)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun countChunks(png: ByteArray, wanted: String): Int {
        var count = 0
        var cursor = PNG_SIGNATURE.size
        while (cursor + 12 <= png.size) {
            val length = readU32(png, cursor).toInt()
            val type = String(png, cursor + 4, 4, Charsets.US_ASCII)
            if (type == wanted) count++
            cursor += 12 + length
            if (type == "IEND") break
        }
        return count
    }

    private fun chunk(type: String, data: ByteArray, corruptCrc: Boolean = false): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        writeU32(out, data.size.toLong())
        out.write(typeBytes)
        out.write(data)

        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val value = if (corruptCrc) crc.value xor 0x01L else crc.value
        writeU32(out, value)
        return out.toByteArray()
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
