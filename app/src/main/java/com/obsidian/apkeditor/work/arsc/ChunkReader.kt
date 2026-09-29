package com.obsidian.apkeditor.work.arsc

/**
 * Bounds-checked little-endian reader shared by the AXML/ARSC decoders.
 * Every access throws [FormatException] instead of indexing out of range.
 */
class ChunkReader(val bytes: ByteArray) {

    class FormatException(message: String) : IllegalStateException(message)

    val size: Int get() = bytes.size

    fun u8(off: Int): Int {
        if (off < 0 || off + 1 > bytes.size) throw FormatException("eof@$off")
        return bytes[off].toInt() and 0xFF
    }

    fun u16(off: Int): Int {
        if (off < 0 || off + 2 > bytes.size) throw FormatException("eof@$off")
        return (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
    }

    fun i32(off: Int): Int {
        if (off < 0 || off + 4 > bytes.size) throw FormatException("eof@$off")
        return (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or ((bytes[off + 3].toInt() and 0xFF) shl 24)
    }

    fun bytes(off: Int, len: Int): ByteArray {
        if (off < 0 || len < 0 || off + len > bytes.size) throw FormatException("eof@$off+$len")
        return bytes.copyOfRange(off, off + len)
    }

    /** Chunk header at [off]: (type, headerSize, totalSize). */
    fun header(off: Int): Triple<Int, Int, Int> =
        Triple(u16(off), u16(off + 2), i32(off + 4))

    fun checkChunk(off: Int, want: Int, minSize: Int) {
        val (type, _, size) = header(off)
        if (type != want) throw FormatException("want chunk %04x".format(want))
        if (size < minSize || off + size > bytes.size) throw FormatException("bad chunk size")
    }
}
