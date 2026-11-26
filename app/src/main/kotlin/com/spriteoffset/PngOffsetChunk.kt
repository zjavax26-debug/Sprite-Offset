package com.spriteoffset

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Reads and writes ZDoom/SLADE's private PNG grAb chunk.
 *
 * grAb stores two signed 32-bit values (X then Y), big-endian.
 * We place it after IHDR and before the first IDAT chunk.
 */
internal object PngOffsetChunk {
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private val grabType = byteArrayOf(0x67, 0x72, 0x41, 0x62) // "grAb"
    private val idatType = byteArrayOf(0x49, 0x44, 0x41, 0x54) // "IDAT"

    fun isPng(bytes: ByteArray): Boolean = hasPngSignature(bytes)

    fun readOffsets(png: ByteArray): Pair<Int, Int>? {
        if (!hasPngSignature(png)) return null

        var cursor = signature.size
        while (cursor <= png.size - 12) {
            val length = readU32(png, cursor)
            if (length > Int.MAX_VALUE.toLong()) return null
            val dataLength = length.toInt()
            val chunkEnd = cursor.toLong() + 12L + dataLength.toLong()
            if (chunkEnd > png.size.toLong()) return null

            val typeOffset = cursor + 4
            if (matches(png, typeOffset, grabType)) {
                // SLADE reads the grAb payload directly and does not reject it
                // because of its stored CRC. Some existing Doom PNGs have a
                // grAb chunk with a stale CRC but SLADE still displays the offsets.
                // Accept at least the two 32-bit offset fields.
                if (dataLength < 8) return null
                val dataOffset = cursor + 8
                return readI32(png, dataOffset) to readI32(png, dataOffset + 4)
            }

            // SLADE scans the ancillary chunks between IHDR and the first IDAT.
            if (matches(png, typeOffset, idatType)) return null
            cursor = chunkEnd.toInt()
        }
        return null
    }

    fun withOffsets(png: ByteArray, offsetX: Int, offsetY: Int): ByteArray {
        require(hasPngSignature(png)) { "El archivo generado no tiene una cabecera PNG válida" }

        val output = ByteArrayOutputStream(png.size + 20)
        output.write(signature)
        var cursor = signature.size
        var inserted = false

        while (cursor <= png.size - 12) {
            val length = readU32(png, cursor)
            require(length <= Int.MAX_VALUE.toLong()) { "Chunk PNG demasiado grande" }
            val dataLength = length.toInt()
            val chunkEndLong = cursor.toLong() + 12L + dataLength.toLong()
            require(chunkEndLong <= png.size.toLong()) { "Chunk PNG incompleto" }
            val chunkEnd = chunkEndLong.toInt()
            val typeOffset = cursor + 4

            if (matches(png, typeOffset, idatType) && !inserted) {
                output.write(createGrabChunk(offsetX, offsetY))
                inserted = true
            }

            // Replace any old grAb chunk, keeping all other PNG chunks intact.
            if (!matches(png, typeOffset, grabType)) {
                output.write(png, cursor, chunkEnd - cursor)
            }
            cursor = chunkEnd
        }

        require(inserted) { "El PNG no contiene un bloque IDAT" }
        require(cursor == png.size) { "El PNG contiene datos finales no válidos" }
        return output.toByteArray()
    }

    private fun createGrabChunk(offsetX: Int, offsetY: Int): ByteArray {
        val data = ByteArrayOutputStream(20)
        writeU32(data, 8L)
        data.write(grabType)
        writeI32(data, offsetX)
        writeI32(data, offsetY)

        val crc = CRC32()
        crc.update(grabType)
        crc.update(intToBytes(offsetX))
        crc.update(intToBytes(offsetY))
        writeU32(data, crc.value)
        return data.toByteArray()
    }

    private fun validCrc(png: ByteArray, chunkStart: Int, dataLength: Int): Boolean {
        val crc = CRC32()
        crc.update(png, chunkStart + 4, 4 + dataLength)
        val stored = readU32(png, chunkStart + 8 + dataLength)
        return crc.value == stored
    }

    private fun hasPngSignature(bytes: ByteArray): Boolean =
        bytes.size >= signature.size &&
            signature.indices.all { bytes[it] == signature[it] }

    private fun matches(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (offset < 0 || offset + expected.size > bytes.size) return false
        return expected.indices.all { bytes[offset + it] == expected[it] }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 3 < bytes.size)
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    private fun readI32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeI32(output: ByteArrayOutputStream, value: Int) {
        output.write(intToBytes(value))
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun writeU32(output: ByteArrayOutputStream, value: Long) {
        output.write(((value ushr 24) and 0xFF).toInt())
        output.write(((value ushr 16) and 0xFF).toInt())
        output.write(((value ushr 8) and 0xFF).toInt())
        output.write((value and 0xFF).toInt())
    }
}
