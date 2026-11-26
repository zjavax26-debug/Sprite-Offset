package com.spriteoffset

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rebuilds a WAD/PK3 with exactly one resource payload replaced.
 *
 * WAD entries are rebuilt in their original directory order with their names
 * and bytes retained. PK3 entries are streamed in their original order and
 * recompressed as DEFLATED entries; entry names, timestamps and comments are
 * retained. The source archive is never modified in place.
 */
internal object DoomArchiveWriter {
    private const val MAX_OUTPUT_BYTES = 256 * 1024 * 1024
    private const val MAX_WAD_LUMPS = 1_000_000L
    private const val MAX_ZIP_ENTRIES = 50_000
    private const val BUFFER_SIZE = 16 * 1024

    fun replaceEntry(
        originalArchive: ByteArray,
        entryIndex: Int,
        replacementBytes: ByteArray
    ): ByteArray {
        require(entryIndex >= 0) { "Índice de recurso no válido" }
        require(replacementBytes.size <= MAX_OUTPUT_BYTES) {
            "El recurso editado es demasiado grande"
        }

        return when {
            DoomArchiveReader.isWad(originalArchive) ->
                replaceWadEntry(originalArchive, entryIndex, replacementBytes)
            DoomArchiveReader.isZip(originalArchive) ->
                replaceZipEntry(originalArchive, entryIndex, replacementBytes)
            else -> throw IllegalArgumentException("El archivo no es un WAD ni un PK3 válido")
        }
    }

    private fun replaceWadEntry(
        original: ByteArray,
        targetIndex: Int,
        replacement: ByteArray
    ): ByteArray {
        require(original.size >= 12) { "Cabecera WAD incompleta" }

        val magic = original.copyOfRange(0, 4)
        val lumpCount = readU32LE(original, 4)
        val directoryOffset = readU32LE(original, 8)
        require(lumpCount in 1..MAX_WAD_LUMPS) {
            "Número de lumps WAD no válido"
        }

        val directoryEnd = directoryOffset + lumpCount * 16L
        require(directoryOffset >= 12L && directoryEnd <= original.size.toLong()) {
            "Directorio WAD fuera del archivo"
        }
        require(targetIndex < lumpCount.toInt()) {
            "El recurso seleccionado ya no existe en el WAD"
        }

        val output = ByteArrayOutputStream(original.size + 1024)
        output.write(magic)
        writeU32LE(output, lumpCount)
        writeU32LE(output, 0L) // Directory offset is filled after the lump data.

        val newDirectory = ArrayList<ByteArray>(lumpCount.toInt())

        for (index in 0 until lumpCount.toInt()) {
            val directoryEntry = directoryOffset.toInt() + index * 16
            val dataOffset = readU32LE(original, directoryEntry)
            val dataSize = readU32LE(original, directoryEntry + 4)
            require(dataOffset + dataSize <= original.size.toLong()) {
                "Un lump del WAD apunta fuera del archivo"
            }

            val payload = if (index == targetIndex) {
                replacement
            } else {
                original.copyOfRange(
                    dataOffset.toInt(),
                    (dataOffset + dataSize).toInt()
                )
            }

            val newDataOffset = output.size().toLong()
            require(newDataOffset + payload.size <= MAX_OUTPUT_BYTES.toLong()) {
                "El WAD reconstruido superaría el límite de 256 MB"
            }
            if (payload.isNotEmpty()) output.write(payload)

            val newEntry = ByteArrayOutputStream(16)
            writeU32LE(newEntry, newDataOffset)
            writeU32LE(newEntry, payload.size.toLong())
            // Preserve the original eight-byte lump name, including its padding.
            newEntry.write(original, directoryEntry + 8, 8)
            newDirectory.add(newEntry.toByteArray())
        }

        val newDirectoryOffset = output.size().toLong()
        require(newDirectoryOffset + lumpCount * 16L <= MAX_OUTPUT_BYTES.toLong()) {
            "El WAD reconstruido superaría el límite de 256 MB"
        }
        newDirectory.forEach(output::write)

        val result = output.toByteArray()
        require(newDirectoryOffset <= 0xFFFF_FFFFL) {
            "El directorio WAD excede el límite del formato"
        }
        writeU32LEAt(result, 8, newDirectoryOffset)
        return result
    }

    private fun replaceZipEntry(
        original: ByteArray,
        targetIndex: Int,
        replacement: ByteArray
    ): ByteArray {
        val output = ByteArrayOutputStream(original.size + 1024)
        var entryIndex = 0
        var foundTarget = false
        var expandedBytes = 0L

        ZipInputStream(ByteArrayInputStream(original)).use { input ->
            ZipOutputStream(output).use { zipOutput ->
                while (true) {
                    val sourceEntry = input.nextEntry ?: break
                    require(entryIndex < MAX_ZIP_ENTRIES) {
                        "El PK3 contiene demasiados archivos"
                    }

                    val thisIndex = entryIndex++
                    val outputEntry = ZipEntry(sourceEntry.name).apply {
                        if (sourceEntry.time >= 0L) time = sourceEntry.time
                        sourceEntry.comment?.let { comment = it }
                        // Rebuild all entries with DEFLATED compression so the
                        // ZIP writer can safely recalculate sizes and CRC values.
                        method = ZipEntry.DEFLATED
                    }

                    zipOutput.putNextEntry(outputEntry)

                    if (!sourceEntry.isDirectory) {
                        if (thisIndex == targetIndex) {
                            // Consume the old payload so the next ZIP entry starts correctly.
                            expandedBytes += drainEntry(input)
                            zipOutput.write(replacement)
                            foundTarget = true
                        } else {
                            expandedBytes += copyEntry(input, zipOutput)
                        }

                        require(expandedBytes + replacement.size <= MAX_OUTPUT_BYTES.toLong()) {
                            "El PK3 reconstruido superaría el límite de 256 MB"
                        }
                    }

                    zipOutput.closeEntry()
                    input.closeEntry()
                }
            }
        }

        require(foundTarget) {
            "No se encontró el recurso seleccionado dentro del PK3"
        }
        require(output.size() <= MAX_OUTPUT_BYTES) {
            "El PK3 reconstruido superaría el límite de 256 MB"
        }
        return output.toByteArray()
    }

    private fun copyEntry(input: ZipInputStream, output: ZipOutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_OUTPUT_BYTES.toLong()) {
                "Los recursos descomprimidos superan el límite de 256 MB"
            }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun drainEntry(input: ZipInputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_OUTPUT_BYTES.toLong()) {
                "Los recursos descomprimidos superan el límite de 256 MB"
            }
        }
        return total
    }

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 3 < data.size)
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun writeU32LE(output: ByteArrayOutputStream, value: Long) {
        require(value in 0L..0xFFFF_FFFFL) { "Valor WAD fuera de rango" }
        output.write((value and 0xFFL).toInt())
        output.write(((value ushr 8) and 0xFFL).toInt())
        output.write(((value ushr 16) and 0xFFL).toInt())
        output.write(((value ushr 24) and 0xFFL).toInt())
    }

    private fun writeU32LEAt(data: ByteArray, offset: Int, value: Long) {
        require(offset >= 0 && offset + 3 < data.size)
        require(value in 0L..0xFFFF_FFFFL)
        data[offset] = (value and 0xFFL).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
    }
}
