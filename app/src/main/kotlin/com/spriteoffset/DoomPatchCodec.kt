package com.spriteoffset

import android.graphics.Bitmap
import android.graphics.Color

internal data class DecodedDoomPatch(
    val width: Int,
    val height: Int,
    val leftOffset: Int,
    val topOffset: Int,
    val bitmap: Bitmap
)

/**
 * Reader for the classic Doom picture/patch column-post layout.
 * Updating offsets rewrites only the four offset bytes in the original header,
 * preserving the column directory and compressed posts byte-for-byte.
 */
internal object DoomPatchCodec {
    fun decode(bytes: ByteArray): DecodedDoomPatch {
        require(bytes.size >= 8) { "Archivo demasiado pequeño para ser un Doom Patch" }

        val width = readU16(bytes, 0)
        val height = readU16(bytes, 2)
        require(width in 1..8192 && height in 1..8192) {
            "Dimensiones de Doom Patch no válidas"
        }
        require(width.toLong() * height.toLong() <= 32_000_000L) {
            "Doom Patch demasiado grande"
        }

        val directoryEnd = 8L + width.toLong() * 4L
        require(directoryEnd <= bytes.size.toLong()) {
            "Directorio de columnas incompleto"
        }

        val leftOffset = readS16(bytes, 4)
        val topOffset = readS16(bytes, 6)
        val pixels = IntArray(width * height)

        for (column in 0 until width) {
            val columnOffset = readU32(bytes, 8 + column * 4)
            require(columnOffset >= directoryEnd && columnOffset < bytes.size.toLong()) {
                "Offset de columna fuera del archivo"
            }

            var cursor = columnOffset.toInt()
            var previousTop = -1
            var foundTerminator = false
            var postsRead = 0

            while (cursor < bytes.size) {
                val topDelta = bytes[cursor].toInt() and 0xFF
                cursor++
                if (topDelta == 0xFF) {
                    foundTerminator = true
                    break
                }

                require(cursor + 2 <= bytes.size) { "Cabecera de post incompleta" }
                val length = bytes[cursor].toInt() and 0xFF
                cursor += 2 // longitud y byte reservado
                require(cursor.toLong() + length.toLong() + 1L <= bytes.size.toLong()) {
                    "Datos de post incompletos"
                }

                // Compatibilidad con la convención de tall patches usada por Doom.
                val actualTop = if (previousTop >= 0 && topDelta <= previousTop) {
                    previousTop + topDelta
                } else {
                    topDelta
                }
                previousTop = actualTop

                for (row in 0 until length) {
                    val targetY = actualTop + row
                    if (targetY in 0 until height) {
                        val paletteIndex = bytes[cursor + row].toInt() and 0xFF
                        // PLAYPAL todavía no está integrado: usar escala de grises
                        // permite visualizar estructura y transparencia sin alterar el original.
                        pixels[targetY * width + column] =
                            Color.rgb(paletteIndex, paletteIndex, paletteIndex)
                    }
                }

                cursor += length + 1 // datos y byte reservado final
                postsRead++
                require(postsRead <= height + 1) { "Demasiados posts en una columna" }
            }

            require(foundTerminator) { "Columna sin terminador 0xFF" }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return DecodedDoomPatch(
            width = width,
            height = height,
            leftOffset = leftOffset,
            topOffset = topOffset,
            bitmap = bitmap
        )
    }

    fun withOffsets(original: ByteArray, leftOffset: Int, topOffset: Int): ByteArray {
        require(leftOffset in -32768..32767 && topOffset in -32768..32767) {
            "Los offsets Doom deben estar entre -32768 y 32767"
        }
        // Validate the complete structure before modifying the header.
        decode(original)
        val copy = original.copyOf()
        writeS16(copy, 4, leftOffset)
        writeS16(copy, 6, topOffset)
        return copy
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < data.size)
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readS16(data: ByteArray, offset: Int): Int {
        val raw = readU16(data, offset)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 3 < data.size)
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun writeS16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
