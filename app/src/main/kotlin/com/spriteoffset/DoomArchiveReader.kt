package com.spriteoffset

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal data class DoomArchiveSprite(
    val name: String,
    val bytes: ByteArray,
    val entryIndex: Int
)

internal data class DoomArchiveContents(
    val sprites: List<DoomArchiveSprite>,
    val playpalBytes: ByteArray?,
    val formatName: String
)

/**
 * Small, dependency-free reader for classic WAD archives and ZIP-based PK3s.
 *
 * WAD resources are selected from S_START/S_END sprite namespaces. If a WAD
 * has no sprite namespace, conventional Doom sprite lump names are used as a
 * fallback. PK3 images are selected from sprites paths or common image files.
 *
 * The class reads resources into memory only; it never extracts paths to disk.
 */
internal object DoomArchiveReader {
    private val ASCII = Charset.forName("US-ASCII")

    private const val MAX_WAD_LUMPS = 1_000_000L
    private const val MAX_ZIP_ENTRIES = 50_000
    private const val MAX_RESOURCE_BYTES = 32 * 1024 * 1024
    private const val MAX_SELECTED_BYTES = 128L * 1024L * 1024L

    private val imageExtensions = setOf(
        ".png", ".bmp", ".jpg", ".jpeg", ".gif", ".webp"
    )

    fun isWad(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val magic = String(bytes, 0, 4, ASCII)
        return magic == "IWAD" || magic == "PWAD"
    }

    fun isZip(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() ||
                bytes[2] == 0x05.toByte() ||
                bytes[2] == 0x07.toByte())
    }

    fun isArchive(bytes: ByteArray): Boolean = isWad(bytes) || isZip(bytes)

    fun read(bytes: ByteArray): DoomArchiveContents {
        require(bytes.isNotEmpty()) { "El archivo está vacío" }
        require(bytes.size <= 64 * 1024 * 1024) {
            "El archivo supera el límite de 64 MB"
        }

        return when {
            isWad(bytes) -> readWad(bytes)
            isZip(bytes) -> readZip(bytes)
            else -> throw IllegalArgumentException("El archivo no es un WAD ni un PK3/ZIP válido")
        }
    }

    private fun readWad(wad: ByteArray): DoomArchiveContents {
        require(wad.size >= 12) { "Cabecera WAD incompleta" }

        val lumpCount = readU32LE(wad, 4)
        val directoryOffset = readU32LE(wad, 8)
        require(lumpCount >= 1L && lumpCount <= MAX_WAD_LUMPS) {
            "Número de lumps WAD no válido"
        }

        val directoryEnd = directoryOffset + lumpCount * 16L
        require(directoryOffset >= 12L && directoryEnd <= wad.size.toLong()) {
            "Directorio WAD fuera del archivo"
        }

        val allLumps = ArrayList<RawWadLump>()
        var playpal: ByteArray? = null
        var insideSprites = false

        for (index in 0 until lumpCount.toInt()) {
            val entryOffset = directoryOffset.toInt() + index * 16
            val dataOffset = readU32LE(wad, entryOffset)
            val dataSize = readU32LE(wad, entryOffset + 4)
            val name = String(wad, entryOffset + 8, 8, ASCII)
                .trimEnd('\u0000', ' ')
                .uppercase(Locale.ROOT)

            require(dataOffset + dataSize <= wad.size.toLong()) {
                "El lump $name apunta fuera del WAD"
            }

            if (name == "S_START" || name == "SS_START" ||
                name == "S1_START" || name == "S2_START") {
                insideSprites = true
                continue
            }
            if (name == "S_END" || name == "SS_END" ||
                name == "S1_END" || name == "S2_END") {
                insideSprites = false
                continue
            }

            if (name == "PLAYPAL" && dataSize > 0L) {
                require(dataSize <= 2L * 1024L * 1024L) {
                    "El lump PLAYPAL es demasiado grande"
                }
                val start = dataOffset.toInt()
                playpal = wad.copyOfRange(start, start + dataSize.toInt())
            }

            if (dataSize <= 0L || name.isBlank()) continue
            allLumps.add(
                RawWadLump(
                    name = name,
                    offset = dataOffset.toInt(),
                    size = dataSize.toInt(),
                    inSpriteNamespace = insideSprites,
                    entryIndex = index
                )
            )
        }

        // Prefer the WAD's explicit sprite namespace. Some PWADs omit markers,
        // so fall back to conventional Doom sprite names when no namespace exists.
        val markedSprites = allLumps.filter { it.inSpriteNamespace }
        val selected = if (markedSprites.isNotEmpty()) {
            markedSprites
        } else {
            allLumps.filter { looksLikeSpriteName(it.name) }
        }

        val sprites = selected.map { lump ->
            DoomArchiveSprite(
                name = lump.name,
                bytes = wad.copyOfRange(lump.offset, lump.offset + lump.size),
                entryIndex = lump.entryIndex
            )
        }
        return DoomArchiveContents(sprites, playpal, "WAD")
    }

    private fun readZip(zipBytes: ByteArray): DoomArchiveContents {
        val spritesFolderEntries = ArrayList<DoomArchiveSprite>()
        val otherGraphicsEntries = ArrayList<DoomArchiveSprite>()
        var playpal: ByteArray? = null
        var entryCount = 0
        var selectedBytes = 0L

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { input ->
            while (true) {
                val entry: ZipEntry = input.nextEntry ?: break
                val currentEntryIndex = entryCount
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) {
                    "El PK3 contiene demasiados archivos"
                }

                if (entry.isDirectory) {
                    input.closeEntry()
                    continue
                }

                val normalizedName = entry.name.replace('\\', '/')
                val baseName = normalizedName.substringAfterLast('/').uppercase(Locale.ROOT)
                val isPlaypal = baseName == "PLAYPAL" || baseName == "PLAYPAL.PAL"
                val isSpritePath = normalizedName.split('/').any {
                    it.equals("sprites", ignoreCase = true)
                }
                val lowerName = normalizedName.lowercase(Locale.ROOT)
                val isImageFile = imageExtensions.any { lowerName.endsWith(it) }
                val isPatchFile = lowerName.endsWith(".lmp") ||
                    lowerName.endsWith(".patch")
                val hasNoExtension = normalizedName.substringAfterLast('/').let {
                    !it.contains('.')
                }
                val isExtensionlessSprite = isSpritePath && hasNoExtension
                val shouldRead = isPlaypal || isImageFile || isPatchFile || isExtensionlessSprite

                if (shouldRead) {
                    val data = readZipEntryLimited(input, MAX_RESOURCE_BYTES)
                    selectedBytes += data.size.toLong()
                    require(selectedBytes <= MAX_SELECTED_BYTES) {
                        "Los recursos gráficos del PK3 superan el límite de memoria"
                    }

                    if (isPlaypal) {
                        playpal = data
                    } else if (isSpritePath) {
                        spritesFolderEntries.add(DoomArchiveSprite(normalizedName, data, currentEntryIndex))
                    } else if (isImageFile || isPatchFile) {
                        otherGraphicsEntries.add(DoomArchiveSprite(normalizedName, data, currentEntryIndex))
                    }
                }

                input.closeEntry()
            }
        }

        // Prefer the explicit sprites/ folder when present so texture and UI
        // graphics elsewhere in the PK3 do not flood the sprite picker.
        val selectedSprites = if (spritesFolderEntries.isNotEmpty()) {
            spritesFolderEntries
        } else {
            otherGraphicsEntries
        }
        return DoomArchiveContents(selectedSprites, playpal, "PK3")
    }

    private fun readZipEntryLimited(input: ZipInputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) {
                "Un recurso del PK3 supera el límite de 32 MB"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun looksLikeSpriteName(name: String): Boolean {
        // Standard Doom sprite names: TROOA1 or TROOA2A8.
        // The final frame/rotation pair(s) distinguish sprites from ordinary lumps.
        return Regex("^[A-Z0-9]{4}[A-Z][0-8]([A-Z][0-8])?$").matches(name)
    }

    private data class RawWadLump(
        val name: String,
        val offset: Int,
        val size: Int,
        val inSpriteNamespace: Boolean,
        val entryIndex: Int
    )

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 3 < data.size)
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
