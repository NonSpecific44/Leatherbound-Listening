package com.leatherbound.listening

import com.leatherbound.listening.data.local.Mp4Chapters
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class Mp4ChaptersTest {

    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = cat(*payload)
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    }
    private fun u8(v: Int) = byteArrayOf(v.toByte())
    private fun u16(v: Int) = ByteBuffer.allocate(2).putShort(v.toShort()).array()
    private fun u32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
    private fun u64(v: Long) = ByteBuffer.allocate(8).putLong(v).array()
    private val ftyp = box("ftyp", "M4B ".toByteArray(), u32(0))

    private fun read(bytes: ByteArray): List<Pair<String, Long>> {
        val file = File.createTempFile("chapters", ".m4b").apply { writeBytes(bytes); deleteOnExit() }
        return FileInputStream(file).channel.use { Mp4Chapters.read(it) }
    }

    @Test fun readsNeroChapters() {
        fun entry(ms: Long, title: String) = cat(u64(ms * 10_000), u8(title.length), title.toByteArray())
        val chpl = box("chpl", u8(1), ByteArray(3), ByteArray(4), u8(2), entry(0, "Prologue"), entry(90_000, "Chapter 1"))
        val file = cat(ftyp, box("moov", box("udta", chpl)))
        assertEquals(listOf("Prologue" to 0L, "Chapter 1" to 90_000L), read(file))
    }

    @Test fun readsQuickTimeChapterTrack() {
        val samples = listOf("Intro", "Chapter One").map { cat(u16(it.length), it.toByteArray()) }
        fun moov(firstSampleOffset: Int) = box(
            "moov",
            box("trak", box("tkhd", ByteArray(4), u32(0), u32(0), u32(1), ByteArray(8)), box("tref", box("chap", u32(2)))),
            box(
                "trak",
                box("tkhd", ByteArray(4), u32(0), u32(0), u32(2), ByteArray(8)),
                box(
                    "mdia",
                    box("mdhd", ByteArray(4), u32(0), u32(0), u32(1000), u32(12_000), ByteArray(4)),
                    box(
                        "minf",
                        box(
                            "stbl",
                            box("stts", ByteArray(4), u32(2), u32(1), u32(5_000), u32(1), u32(7_000)),
                            box("stsz", ByteArray(4), u32(0), u32(2), u32(samples[0].size), u32(samples[1].size)),
                            box("stsc", ByteArray(4), u32(1), u32(1), u32(2), u32(1)),
                            box("stco", ByteArray(4), u32(1), u32(firstSampleOffset)),
                        ),
                    ),
                ),
            ),
        )
        // The sample offset doesn't change moov's size, so measure once and build again with the real offset.
        val offset = ftyp.size + moov(0).size + 8
        val file = cat(ftyp, moov(offset), box("mdat", *samples.toTypedArray()))
        assertEquals(listOf("Intro" to 0L, "Chapter One" to 5_000L), read(file))
    }

    @Test fun noChaptersInPlainFile() {
        assertEquals(emptyList<Pair<String, Long>>(), read(cat(ftyp, box("moov", box("mvhd", ByteArray(100))))))
    }
}
