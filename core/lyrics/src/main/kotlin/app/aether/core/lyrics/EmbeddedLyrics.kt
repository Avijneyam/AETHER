package app.aether.core.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The lyrics a music file carries in its own tags, as other players write them:
 *
 * - **FLAC** and **Ogg** (Vorbis, Opus): the Vorbis comments `LYRICS`, `UNSYNCEDLYRICS` or
 *   `SYNCEDLYRICS` (LRC, when it's timed);
 * - **MP3**: ID3v2's `SYLT` (timed, in milliseconds) or `USLT` (as written, often LRC);
 * - **M4A** (AAC, ALAC): the `©lyr` atom.
 *
 * Read from the file's first few megabytes, where tags live; a file's claims about its own sizes are
 * untrusted, so nothing past [MAX_BYTES] is read and nothing here throws. Null when the file carries
 * no words.
 */
object EmbeddedLyrics {
    const val MAX_BYTES = 6 * 1024 * 1024

    /** The words in [input]'s tags, read as LRC when timed, or as plain lines. */
    fun read(input: InputStream): Lyrics? = runCatching { raw(input) }.getOrNull()?.let(::lyrics)

    /** Lyrics text (LRC or plain) as a file or a sidecar holds it, read into lyrics. */
    fun lyrics(text: String): Lyrics? {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (clean.isEmpty()) return null
        return LrcParser.synced(clean) ?: LrcParser.plain(clean)
    }

    internal fun raw(input: InputStream): String? {
        val head = ByteArray(12)
        val bytes = BoundedReader(input, MAX_BYTES)
        if (!bytes.readFully(head, 0, 4)) return null
        return when {
            head.startsWith("fLaC") -> flac(bytes)
            head.startsWith("OggS") -> ogg(bytes, head)
            head.startsWith("ID3") -> id3(bytes, head)
            else -> {
                if (!bytes.readFully(head, 4, 8)) return null
                if (String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") mp4(bytes, sizeOf(head, 0)) else null
            }
        }
    }

    // --- FLAC: metadata blocks; block 4 is the Vorbis comment ---

    private fun flac(r: BoundedReader): String? {
        val header = ByteArray(4)
        while (r.readFully(header, 0, 4)) {
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (type == 4) return vorbisComment(r.read(length) ?: return null, 0)
            if (!r.skip(length)) return null
            if (last) return null
        }
        return null
    }

    // --- Ogg: the comment header is the stream's second packet (it may run over several pages) ---

    private fun ogg(r: BoundedReader, first: ByteArray): String? {
        val packets = ArrayList<ByteArray>()
        val current = ByteArrayOutputStream()
        var page = 0
        var head = first.copyOf(4)
        while (packets.size < 2 && page < 64) {
            if (page > 0) {
                head = ByteArray(4)
                if (!r.readFully(head, 0, 4) || !head.startsWith("OggS")) return null
            }
            val rest = r.read(23) ?: return null // version … segment count
            val segments = rest[22].toInt() and 0xFF
            val table = r.read(segments) ?: return null
            for (lace in table) {
                val n = lace.toInt() and 0xFF
                current.write(r.read(n) ?: return null)
                if (n < 255) {
                    packets += current.toByteArray()
                    current.reset()
                    if (packets.size == 2) break
                }
            }
            page++
            if (current.size() > MAX_BYTES) return null
        }
        val comment = packets.getOrNull(1) ?: return null
        return when {
            comment.startsWith("OpusTags") -> vorbisComment(comment, 8)
            comment.size > 7 && comment[0].toInt() == 3 && String(comment, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> vorbisComment(comment, 7)
            else -> null
        }
    }

    /** A Vorbis comment block from [at]: the vendor, then `KEY=value` pairs, little-endian lengths. */
    private fun vorbisComment(block: ByteArray, at: Int): String? {
        var p = at
        fun le32(): Int? {
            if (p + 4 > block.size) return null
            val v = (block[p].toInt() and 0xFF) or ((block[p + 1].toInt() and 0xFF) shl 8) or ((block[p + 2].toInt() and 0xFF) shl 16) or ((block[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendor = le32() ?: return null
        if (vendor < 0 || p + vendor > block.size) return null
        p += vendor
        val count = le32() ?: return null
        val found = HashMap<String, String>()
        repeat(count.coerceIn(0, 10_000)) {
            val length = le32() ?: return@repeat
            if (length < 0 || p + length > block.size) return@repeat
            val entry = String(block, p, length, Charsets.UTF_8)
            p += length
            val key = entry.substringBefore('=', "").uppercase()
            if (key in LYRIC_KEYS) found.putIfAbsent(key, entry.substringAfter('='))
        }
        return LYRIC_KEYS.firstNotNullOfOrNull { found[it]?.takeIf(String::isNotBlank) }
    }

    private val LYRIC_KEYS = listOf("SYNCEDLYRICS", "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")

    // --- ID3v2 (MP3): SYLT before USLT ---

    private fun id3(r: BoundedReader, first: ByteArray): String? {
        // "ID3" and the version were read; then the revision, the flags and the tag's size.
        val rest = r.read(6) ?: return null
        val version = first[3].toInt() and 0xFF // 3 or 4 (2 is too old to bother)
        val flags = rest[1].toInt() and 0xFF
        val size = syncsafe(rest, 2)
        if (version !in 3..4 || size <= 0) return null
        val tag = r.read(size) ?: return null
        val body = if (flags and 0x80 != 0 && version == 3) unsynchronise(tag) else tag
        var p = 0
        // An extended header, when there is one, is passed by.
        if (flags and 0x40 != 0 && body.size >= 4) p += if (version == 4) syncsafe(body, 0) else (be32(body, 0) + 4)
        var plain: String? = null
        var timed: String? = null
        while (p + 10 <= body.size) {
            val id = String(body, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val length = if (version == 4) syncsafe(body, p + 4) else be32(body, p + 4)
            val start = p + 10
            if (length <= 0 || start + length > body.size) break
            val frame = body.copyOfRange(start, start + length)
            when (id) {
                "USLT" -> if (plain == null) plain = uslt(frame)
                "SYLT" -> if (timed == null) timed = sylt(frame)
            }
            p = start + length
        }
        return timed ?: plain
    }

    /** USLT: encoding, language, a description, then the words. */
    private fun uslt(f: ByteArray): String? {
        if (f.size < 5) return null
        val enc = f[0].toInt()
        var p = 4
        p = afterTerminator(f, p, enc) ?: return null
        return decode(f, p, f.size, enc)?.takeIf { it.isNotBlank() }
    }

    /** SYLT in milliseconds: the words and their times, written back as LRC. */
    private fun sylt(f: ByteArray): String? {
        if (f.size < 7) return null
        val enc = f[0].toInt()
        val format = f[4].toInt()
        if (format != 2) return null // MPEG frames rather than milliseconds: not worth guessing
        var p = afterTerminator(f, 6, enc) ?: return null
        val out = StringBuilder()
        while (p < f.size) {
            val end = terminator(f, p, enc) ?: break
            val text = decode(f, p, end, enc).orEmpty()
            p = end + if (enc == 1 || enc == 2) 2 else 1
            if (p + 4 > f.size) break
            val ms = be32(f, p).toLong()
            p += 4
            val clean = text.removePrefix("\n").trimEnd()
            out.append('[').append(stamp(ms)).append(']').append(clean).append('\n')
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    private fun stamp(ms: Long): String = "%02d:%02d.%02d".format(ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10)

    private fun terminator(f: ByteArray, from: Int, enc: Int): Int? {
        val wide = enc == 1 || enc == 2
        var i = from
        while (i < f.size) {
            if (wide) {
                if (i + 1 < f.size && f[i].toInt() == 0 && f[i + 1].toInt() == 0) return i
                i += 2
            } else {
                if (f[i].toInt() == 0) return i
                i++
            }
        }
        return null
    }

    private fun afterTerminator(f: ByteArray, from: Int, enc: Int): Int? = terminator(f, from, enc)?.let { it + if (enc == 1 || enc == 2) 2 else 1 }

    private fun decode(f: ByteArray, from: Int, to: Int, enc: Int): String? {
        if (from >= to) return ""
        return runCatching {
            when (enc) {
                0 -> String(f, from, to - from, Charsets.ISO_8859_1)
                1 -> String(f, from, to - from, Charsets.UTF_16)
                2 -> String(f, from, to - from, Charsets.UTF_16BE)
                else -> String(f, from, to - from, Charsets.UTF_8)
            }.trimEnd('\u0000')
        }.getOrNull()
    }

    private fun unsynchronise(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // --- MP4: moov › udta › meta › ilst › ©lyr › data ---

    private fun mp4(r: BoundedReader, ftypSize: Long): String? {
        // Twelve bytes of the first box (its size, its name, four of its own) have been read.
        if (ftypSize < 12 || !r.skip((ftypSize - 12).toInt())) return null
        val header = ByteArray(8)
        while (r.readFully(header, 0, 8)) {
            val size = sizeOf(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size < 8) return null
            if (type == "moov") {
                val moov = r.read((size - 8).toInt()) ?: return null
                return atom(moov, 0, moov.size, listOf("udta", "meta", "ilst", "©lyr", "data"))
            }
            // The audio itself (mdat) comes first in some files: tags after it are past what's read.
            if (!r.skip((size - 8).toInt())) return null
        }
        return null
    }

    private fun atom(b: ByteArray, from: Int, to: Int, path: List<String>): String? {
        var p = from
        while (p + 8 <= to) {
            val size = sizeOf(b, p).toInt()
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || p + size > to) return null
            if (type == path.first()) {
                var inner = p + 8
                if (type == "meta") inner += 4 // a full atom: version and flags
                if (path.size == 1) {
                    // data: type (4 bytes), locale (4 bytes), then the text.
                    return if (inner + 8 <= p + size) String(b, inner + 8, p + size - inner - 8, Charsets.UTF_8).takeIf { it.isNotBlank() } else null
                }
                return atom(b, inner, p + size, path.drop(1))
            }
            p += size
        }
        return null
    }

    private fun sizeOf(b: ByteArray, at: Int): Long = be32(b, at).toLong() and 0xFFFFFFFFL
    private fun be32(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    private fun syncsafe(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)
    private fun ByteArray.startsWith(s: String): Boolean = size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }

    /** An input stream read in exact pieces, never past [limit] bytes in all. */
    private class BoundedReader(private val input: InputStream, private val limit: Int) {
        private var used = 0

        fun readFully(into: ByteArray, at: Int, n: Int): Boolean {
            if (n < 0 || used + n > limit) return false
            var off = 0
            while (off < n) {
                val got = input.read(into, at + off, n - off)
                if (got < 0) return false
                off += got
            }
            used += n
            return true
        }

        fun read(n: Int): ByteArray? = if (n < 0) null else ByteArray(n).takeIf { readFully(it, 0, n) }

        fun skip(n: Int): Boolean {
            if (n < 0 || used + n > limit) return false
            var left = n.toLong()
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) {
                    if (input.read() < 0) return false
                    left--
                } else left -= s
            }
            used += n
            return true
        }
    }
}
