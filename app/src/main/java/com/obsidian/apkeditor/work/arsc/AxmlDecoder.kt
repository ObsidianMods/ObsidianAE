package com.obsidian.apkeditor.work.arsc

/**
 * Binary Android XML (chunk 0x0003) to text. Resolves element/attribute names
 * through the file string pool and prints typed values readably
 * (`@string/…` stays an index; ints/bools/dimensions render as hex + type).
 * Text nodes are inlined. Unknown chunks are skipped by size (forward-safe).
 */
object AxmlDecoder {

    fun decode(bytes: ByteArray, maxChars: Int = 20_000): String {
        val r = ChunkReader(bytes)
        r.checkChunk(0, 0x0003, 8)
        val fileSize = r.i32(4)
        if (fileSize > bytes.size || fileSize < 8) throw ChunkReader.FormatException("axml size")

        var pool: StringPool? = null
        val out = StringBuilder(minOf(maxChars, 32_768))
        val nsStack = ArrayList<Pair<String, String>>()
        var depth = 0
        var off = 8
        val end = minOf(fileSize, bytes.size)
        var count = 0
        while (off + 8 <= end && out.length < maxChars && count++ < 20_000) {
            val (type, _, size) = r.header(off)
            if (size < 8 || off + size > end) break
            when (type) {
                0x0001 -> pool = runCatching { StringPool(r, off) }.getOrNull()
                0x0102 -> { // START_NAMESPACE prefix, uri
                    val p = pool
                    if (p != null) {
                        nsStack.add(p.getOrEmpty(r.i32(off + 8)) to p.getOrEmpty(r.i32(off + 12)))
                    }
                }
                0x0103 -> if (nsStack.isNotEmpty()) nsStack.removeAt(nsStack.size - 1)
                0x0104 -> {
                    val p = pool ?: break
                    appendStart(r, p, off, nsStack, depth, out, maxChars)
                    depth++
                }
                0x0105 -> {
                    val p = pool ?: break
                    depth = (depth - 1).coerceAtLeast(0)
                    indent(out, depth)
                    out.append("</").append(p.getOrEmpty(r.i32(off + 12))).append(">\n")
                }
                0x0106 -> {
                    val p = pool ?: break
                    val text = p.getOrEmpty(r.i32(off + 8)).trim()
                    if (text.isNotEmpty()) {
                        indent(out, depth)
                        out.append(escape(text.take(500))).append('\n')
                    }
                }
            }
            off += size
        }
        // Re-root namespaces that never closed (truncated files stay readable).
        return out.toString().take(maxChars)
    }

    private fun appendStart(
        r: ChunkReader, p: StringPool, off: Int,
        ns: List<Pair<String, String>>, depth: Int, out: StringBuilder, max: Int,
    ) {
        val name = p.getOrEmpty(r.i32(off + 12))
        val attrCount = r.u16(off + 20)
        indent(out, depth)
        out.append('<').append(name)
        if (depth == 0) {
            for ((prefix, uri) in ns) {
                if (out.length >= max) return
                out.append(" xmlns:").append(prefix.ifEmpty { "ns" })
                    .append("=\"").append(escape(uri)).append('"')
            }
        }
        val attrStart = off + 28
        for (i in 0 until attrCount.coerceAtMost(128)) {
            if (out.length >= max) return
            val a = attrStart + i * 20
            val attrNs = r.i32(a)
            val attrName = p.getOrEmpty(r.i32(a + 4))
            val raw = r.i32(a + 8)
            val value = if (raw != -1) {
                p.getOrEmpty(raw)
            } else {
                formatTyped(r, p, a + 12)
            }
            out.append(' ')
            if (attrNs != -1) {
                // Match prefix by uri when the pool stores one; else omit.
                out.append(attrName)
            } else {
                out.append(attrName)
            }
            out.append("=\"").append(escape(value.take(500))).append('"')
        }
        out.append(">\n")
    }

    private fun formatTyped(r: ChunkReader, p: StringPool, off: Int): String {
        // Res_value: size(u16) res0(u8) dataType(u8) data(u32).
        val dataType = r.u8(off + 3)
        val data = r.i32(off + 4)
        return when (dataType) {
            0x03 -> p.getOrEmpty(data) // TYPE_STRING
            0x01 -> "@0x%08x".format(data) // TYPE_REFERENCE
            0x10 -> data.toString() // TYPE_INT_DEC
            0x11 -> "0x%08x".format(data) // TYPE_INT_HEX
            0x12 -> if (data == -1) "true" else if (data == 0) "false" else "bool($data)"
            0x04 -> "floatbits(0x%08x)".format(data)
            0x05 -> "dim(0x%08x)".format(data)
            0x06 -> "frac(0x%08x)".format(data)
            0x1c -> "#%08x".format(data) // TYPE_INT_COLOR_ARGB8
            else -> "type%02x(0x%08x)".format(dataType, data)
        }
    }

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth.coerceAtMost(16)) { out.append("  ") }
    }

    private fun escape(s: String): String {
        if (s.indexOfAny(charArrayOf('&', '<', '>', '"')) < 0) return s
        return s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
    }
}
