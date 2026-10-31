package com.leatherbound.listening.data.local

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads chapter marks from M4B/M4A files: Nero `chpl` atoms and QuickTime chapter text tracks.
 * Returns (title, startMs) pairs, or an empty list if the file has none.
 */
object Mp4Chapters {

    private class Box(val type: String, val start: Long, val end: Long)

    fun read(context: Context, uri: Uri): List<Pair<String, Long>> = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch -> read(ch) }
        }
    }.getOrNull().orEmpty()

    fun read(channel: FileChannel): List<Pair<String, Long>> = Reader(channel).chapters()

    private class Reader(private val ch: FileChannel) {
        private val size = ch.size()

        private fun bytes(pos: Long, n: Int): ByteBuffer {
            val buf = ByteBuffer.allocate(n)
            var read = 0
            while (read < n) {
                val r = ch.read(buf, pos + read)
                if (r <= 0) break
                read += r
            }
            buf.flip()
            return buf
        }

        private fun children(start: Long, end: Long): List<Box> {
            val boxes = mutableListOf<Box>()
            var pos = start
            while (pos + 8 <= end) {
                val h = bytes(pos, 16)
                var len = h.int.toLong() and 0xFFFFFFFFL
                val type = String(ByteArray(4).also { h.get(it) }, Charsets.ISO_8859_1)
                var header = 8
                if (len == 1L) { len = h.long; header = 16 }
                if (len == 0L) len = end - pos
                if (len < header) break
                boxes += Box(type, pos + header, minOf(pos + len, end))
                pos += len
            }
            return boxes
        }

        private fun List<Box>.find(type: String) = firstOrNull { it.type == type }
        private fun path(from: Box, vararg types: String): Box? {
            var box = from
            for (t in types) box = children(box.start, box.end).find(t) ?: return null
            return box
        }

        fun chapters(): List<Pair<String, Long>> {
            val moov = children(0, size).find("moov") ?: return emptyList()
            neroChapters(moov).takeIf { it.isNotEmpty() }?.let { return it }
            return quickTimeChapters(moov)
        }

        private fun neroChapters(moov: Box): List<Pair<String, Long>> {
            val chpl = path(moov, "udta", "chpl") ?: return emptyList()
            val b = bytes(chpl.start, (chpl.end - chpl.start).toInt())
            val version = b.get().toInt()
            b.position(4)
            if (version == 1) b.position(8)
            val count = b.get().toInt() and 0xFF
            return List(count) {
                val start100ns = b.long
                val len = b.get().toInt() and 0xFF
                val title = String(ByteArray(len).also { b.get(it) }, Charsets.UTF_8)
                title to start100ns / 10_000
            }
        }

        private fun quickTimeChapters(moov: Box): List<Pair<String, Long>> {
            val traks = children(moov.start, moov.end).filter { it.type == "trak" }
            val chapterIds = traks.mapNotNull { path(it, "tref", "chap") }.flatMap { chap ->
                val b = bytes(chap.start, (chap.end - chap.start).toInt())
                List(b.remaining() / 4) { b.int }
            }.toSet()
            if (chapterIds.isEmpty()) return emptyList()
            val trak = traks.firstOrNull { trackId(it) in chapterIds } ?: return emptyList()
            val mdia = path(trak, "mdia") ?: return emptyList()
            val timescale = timescale(mdia) ?: return emptyList()
            val stbl = path(mdia, "minf", "stbl") ?: return emptyList()
            val tables = children(stbl.start, stbl.end)

            val times = mutableListOf<Long>()
            tables.find("stts")?.let { stts ->
                val b = bytes(stts.start + 4, (stts.end - stts.start - 4).toInt())
                var t = 0L
                repeat(b.int) {
                    val count = b.int; val delta = b.int.toLong() and 0xFFFFFFFFL
                    repeat(count) { times += t; t += delta }
                }
            } ?: return emptyList()

            val sizes = tables.find("stsz")?.let { stsz ->
                val b = bytes(stsz.start + 4, (stsz.end - stsz.start - 4).toInt())
                val fixed = b.int; val count = b.int
                List(count) { if (fixed != 0) fixed else b.int }
            } ?: return emptyList()

            val chunkOffsets = (tables.find("stco") ?: tables.find("co64"))?.let { box ->
                val b = bytes(box.start + 4, (box.end - box.start - 4).toInt())
                List(b.int) { if (box.type == "co64") b.long else b.int.toLong() and 0xFFFFFFFFL }
            } ?: return emptyList()

            val stscRuns = tables.find("stsc")?.let { stsc ->
                val b = bytes(stsc.start + 4, (stsc.end - stsc.start - 4).toInt())
                List(b.int) { Triple(b.int, b.int, b.int) }
            } ?: return emptyList()

            // Work out where each sample lives.
            val offsets = mutableListOf<Long>()
            var sample = 0
            for (chunk in chunkOffsets.indices) {
                val run = stscRuns.lastOrNull { it.first <= chunk + 1 } ?: continue
                var pos = chunkOffsets[chunk]
                repeat(run.second) {
                    if (sample < sizes.size) { offsets += pos; pos += sizes[sample]; sample++ }
                }
            }

            return offsets.indices.mapNotNull { i ->
                val b = bytes(offsets[i], minOf(sizes[i], 1024))
                if (b.remaining() < 2) return@mapNotNull null
                val len = minOf(b.short.toInt() and 0xFFFF, b.remaining())
                val raw = ByteArray(len).also { b.get(it) }
                val text = if (len >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) String(raw, Charsets.UTF_16) else String(raw, Charsets.UTF_8)
                text.trim() to (times.getOrElse(i) { 0L } * 1000 / timescale)
            }
        }

        private fun trackId(trak: Box): Int? {
            val tkhd = path(trak, "tkhd") ?: return null
            val b = bytes(tkhd.start, 24)
            val version = b.get().toInt()
            return b.getInt(if (version == 1) 20 else 12)
        }

        private fun timescale(mdia: Box): Long? {
            val mdhd = path(mdia, "mdhd") ?: return null
            val b = bytes(mdhd.start, 32)
            val version = b.get().toInt()
            return (b.getInt(if (version == 1) 20 else 12).toLong() and 0xFFFFFFFFL).takeIf { it > 0 }
        }
    }
}
