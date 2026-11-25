package com.spriteoffset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class DoomPlaypalCodecTest {
    @Test
    fun parsesConsecutiveRawPalettesIntoRgbColors() {
        val raw = ByteArray(2 * 768)
        // Palette 0, color 0 = red.
        raw[0] = 255.toByte()
        raw[1] = 0
        raw[2] = 0
        // Palette 1, color 0 = green.
        raw[768] = 0
        raw[769] = 255.toByte()
        raw[770] = 0

        val palettes = DoomPlaypalCodec.parse(raw)

        assertEquals(2, palettes.size)
        assertEquals(256, palettes[0].size)
        assertEquals(0xFFFF0000.toInt(), palettes[0][0])
        assertEquals(0xFF00FF00.toInt(), palettes[1][0])
    }

    @Test
    fun extractsPlaypalLumpFromPwad() {
        val raw = ByteArray(768)
        raw[0] = 12
        raw[1] = 34
        raw[2] = 56
        val wad = makeWadWithPlaypal(raw)

        val palettes = DoomPlaypalCodec.parse(wad)

        assertEquals(1, palettes.size)
        assertEquals(0xFF0C2238.toInt(), palettes[0][0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompletePaletteData() {
        DoomPlaypalCodec.parse(ByteArray(767))
    }

    private fun makeWadWithPlaypal(playpal: ByteArray): ByteArray {
        val directoryOffset = 12 + playpal.size
        val out = ByteArrayOutputStream()
        out.write("PWAD".toByteArray(Charset.forName("US-ASCII")))
        writeU32LE(out, 1)
        writeU32LE(out, directoryOffset)
        out.write(playpal)

        writeU32LE(out, 12) // lump data offset
        writeU32LE(out, playpal.size)
        val name = ByteArray(8)
        val nameBytes = "PLAYPAL".toByteArray(Charset.forName("US-ASCII"))
        System.arraycopy(nameBytes, 0, name, 0, nameBytes.size)
        out.write(name)
        return out.toByteArray()
    }

    private fun writeU32LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
