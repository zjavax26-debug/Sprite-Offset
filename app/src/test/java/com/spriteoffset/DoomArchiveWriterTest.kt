package com.spriteoffset

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DoomArchiveWriterTest {
    @Test
    fun replacesWadSpriteAndRetainsOtherLumpsAndTheirOrder() {
        val originalSprite = byteArrayOf(1, 2, 3, 4, 5)
        val replacementSprite = byteArrayOf(9, 8, 7, 6, 5, 4)
        val playpal = ByteArray(768) { (it % 251).toByte() }
        val original = makeWad(
            listOf(
                WadLump("S_START", ByteArray(0)),
                WadLump("TROOA1", originalSprite),
                WadLump("S_END", ByteArray(0)),
                WadLump("PLAYPAL", playpal)
            )
        )

        val contents = DoomArchiveReader.read(original)
        assertEquals(1, contents.sprites.size)
        assertEquals(1, contents.sprites[0].entryIndex)

        val updated = DoomArchiveWriter.replaceEntry(
            original,
            contents.sprites[0].entryIndex,
            replacementSprite
        )
        val updatedContents = DoomArchiveReader.read(updated)

        assertEquals("WAD", updatedContents.formatName)
        assertEquals("TROOA1", updatedContents.sprites[0].name)
        assertEquals(1, updatedContents.sprites[0].entryIndex)
        assertArrayEquals(replacementSprite, updatedContents.sprites[0].bytes)
        assertArrayEquals(playpal, updatedContents.playpalBytes)
        assertArrayEquals("PWAD".toByteArray(Charset.forName("US-ASCII")), updated.copyOfRange(0, 4))
    }

    @Test
    fun replacesPk3SpriteAndKeepsOtherFiles() {
        val originalSprite = byteArrayOf(1, 3, 5)
        val replacementSprite = byteArrayOf(2, 4, 6, 8)
        val playpal = ByteArray(768) { (it % 239).toByte() }
        val original = makeZip(
            "README.txt" to "keep this".toByteArray(),
            "sprites/TROOA1.png" to originalSprite,
            "PLAYPAL" to playpal,
            "textures/WALL.png" to byteArrayOf(7, 7, 7)
        )

        val contents = DoomArchiveReader.read(original)
        assertEquals(1, contents.sprites.size)
        assertEquals(1, contents.sprites[0].entryIndex)

        val updated = DoomArchiveWriter.replaceEntry(
            original,
            contents.sprites[0].entryIndex,
            replacementSprite
        )
        val updatedContents = DoomArchiveReader.read(updated)

        assertEquals("PK3", updatedContents.formatName)
        assertEquals(1, updatedContents.sprites.size)
        assertEquals("sprites/TROOA1.png", updatedContents.sprites[0].name)
        assertArrayEquals(replacementSprite, updatedContents.sprites[0].bytes)
        assertArrayEquals(playpal, updatedContents.playpalBytes)

        val entries = readZipEntries(updated)
        assertEquals("keep this", String(entries["README.txt"]!!))
        assertArrayEquals(byteArrayOf(7, 7, 7), entries["textures/WALL.png"])
    }

    private data class WadLump(val name: String, val bytes: ByteArray)

    private fun makeWad(lumps: List<WadLump>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("PWAD".toByteArray(Charset.forName("US-ASCII")))
        writeU32LE(output, lumps.size)
        writeU32LE(output, 0)

        val offsets = ArrayList<Int>()
        lumps.forEach { lump ->
            offsets.add(output.size())
            output.write(lump.bytes)
        }

        val directoryOffset = output.size()
        lumps.forEachIndexed { index, lump ->
            writeU32LE(output, offsets[index])
            writeU32LE(output, lump.bytes.size)
            val name = ByteArray(8)
            val nameBytes = lump.name.toByteArray(Charset.forName("US-ASCII"))
            System.arraycopy(nameBytes, 0, name, 0, minOf(8, nameBytes.size))
            output.write(name)
        }

        val result = output.toByteArray()
        writeU32LEAt(result, 8, directoryOffset)
        return result
    }

    private fun makeZip(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory) {
                    result[entry.name] = input.readBytes()
                }
                input.closeEntry()
            }
        }
        return result
    }

    private fun writeU32LE(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun writeU32LEAt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
