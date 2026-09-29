package com.obsidian.apkeditor.work.arsc

/**
 * Resource string pool (chunk 0x0001). Handles UTF-8 and UTF-16 storage,
 * sorted/unsorted pools. Out-of-range indexes throw [ChunkReader.FormatException].
 */
class StringPool(r: ChunkReader, base: Int) {

    private val strings: Array<String?>

    val size: Int get() = strings.size

    init {
        r.checkChunk(base, 0x0001, 28)
        val count = r.i32(base + 8)
        if (count !in 0..200_000) throw ChunkReader.FormatException("string count")
        val flags = r.i32(base + 16)
        val stringsStart = r.i32(base + 20)
        val isUtf8 = flags and (1 shl 8) != 0
        strings = arrayOfNulls(minOf(count, 200_000))
        for (i in strings.indices) {
            val at = r.i32(base + 28 + i * 4)
            strings[i] = runCatching {
                if (isUtf8) readUtf8(r, base + stringsStart + at)
                else readUtf16(r, base + stringsStart + at)
            }.getOrNull()
        }
    }

    operator fun get(idx: Int): String {
        if (idx < 0 || idx >= strings.size) throw ChunkReader.FormatException("string idx $idx")
        return strings[idx] ?: ""
    }

    fun getOrEmpty(idx: Int): String =
        if (idx < 0 || idx >= strings.size) "" else strings[idx].orEmpty()

    private fun readUtf16(r: ChunkReader, off: Int): String {
        val len = r.u16(off).coerceAtMost(8192)
        val chars = CharArray(len)
        for (i in 0 until len) chars[i] = r.u16(off + 2 + i * 2).toChar()
        return String(chars)
    }

    private fun readUtf8(r: ChunkReader, off: Int): String {
        var p = off
        p += ulebLen(r, p) // char count (informational)
        val (byteLen, n2) = uleb(r, p)
        p += n2
        val len = byteLen.coerceIn(0, 65_535)
        val raw = r.bytes(p, len)
        return String(raw, Charsets.UTF_8)
    }

    private fun uleb(r: ChunkReader, off: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var p = off
        repeat(5) {
            val cur = r.u8(p++)
            // One- and two-byte forms cover every real pool; reject the rest.
            if (shift >= 14) throw ChunkReader.FormatException("uleb")
            result = result or ((cur and 0x7F) shl shift)
            if (cur and 0x80 == 0) return result to (p - off)
            shift += 7
        }
        throw ChunkReader.FormatException("uleb")
    }

    private fun ulebLen(r: ChunkReader, off: Int): Int = uleb(r, off).second
}
