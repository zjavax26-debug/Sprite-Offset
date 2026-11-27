package com.spriteoffset

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DoomArchiveReaderTest {
    @Test
    fun readsSpriteNamespaceAndPlaypalFromWad() {
        val palette = ByteArray(768) { (it % 251).toByte() }
        val wad = makeWad(
            listOf(
                WadLump("S_START", ByteArray(0)),
                WadLump("TROOA1", byteArrayOf(1, 2, 3, 4)),
                WadLump("S_END", ByteArray(0)),
                WadLump("PLAYPAL", palette)
            )
        )

        val result = DoomArchiveReader.read(wad)

        assertEquals("WAD", result.formatName)
        assertEquals(1, result.sprites.size)
        assertEquals("TROOA1", result.sprites[0].name)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.sprites[0].bytes)
        assertArrayEquals(palette, result.playpalBytes)
    }

    @Test
    fun readsSpritesFolderAndPlaypalFromPk3() {
        val palette = ByteArray(768) { (it % 239).toByte() }
        val pk3 = makeZip(
            "PLAYPAL" to palette,
            "sprites/TROOA1.png" to byteArrayOf(9, 8, 7),
            "sprites/INFO.txt" to "not an image".toByteArray(),
            "textures/WALL.png" to byteArrayOf(6, 5, 4)
        )

        val result = DoomArchiveReader.read(pk3)

        assertEquals("PK3", result.formatName)
        assertEquals(1, result.sprites.size)
        assertEquals("sprites/TROOA1.png", result.sprites[0].name)
        assertArrayEquals(byteArrayOf(9, 8, 7), result.sprites[0].bytes)
        assertArrayEquals(palette, result.playpalBytes)
    }

    @Test
    fun rejectsPk3WithMoreThanFourThousandEntries() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            repeat(4001) { index ->
                zip.putNextEntry(ZipEntry("misc/file_$index.txt"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }

        try {
            DoomArchiveReader.read(output.toByteArray())
            throw AssertionError("Expected the PK3 entry-count limit to reject this archive")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("4000"))
        }
    }

    @Test
    fun rejectsWadWithMoreThanFifteenHundredSprites() {
        val lumps = ArrayList<WadLump>()
        lumps.add(WadLump("S_START", ByteArray(0)))
        repeat(1501) {
            // Duplicated names are valid for this reader-limit test; the archive
            // namespace contains more compatible image entries than allowed.
            lumps.add(WadLump("TROOA1", byteArrayOf(1)))
        }
        lumps.add(WadLump("S_END", ByteArray(0)))
        val wad = makeWad(lumps)

        try {
            DoomArchiveReader.read(wad)
            throw AssertionError("Expected the sprite-count limit to reject this WAD")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("1500"))
        }
    }

    private data class WadLump(val name: String, val bytes: ByteArray)

    private fun makeWad(lumps: List<WadLump>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("PWAD".toByteArray(Charset.forName("US-ASCII")))
        writeU32LE(output, lumps.size)
        writeU32LE(output, 0)

        val offsets = ArrayList<Int>()
        lumps.forEach { lump ->
            offsets.add(12 + output.size() - 12)
            output.write(lump.bytes)
        }

        val directoryOffset = output.size()
        lumps.forEachIndexed { index, lump ->
            writeU32LE(output, offsets[index])
            writeU32LE(output, lump.bytes.size)
            val name = ByteArray(8)
            val bytes = lump.name.toByteArray(Charset.forName("US-ASCII"))
            System.arraycopy(bytes, 0, name, 0, minOf(8, bytes.size))
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
