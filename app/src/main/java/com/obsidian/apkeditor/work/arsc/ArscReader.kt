package com.obsidian.apkeditor.work.arsc

/**
 * Compiled resource table reader (chunk 0x0002). Read-only: package/type
 * inventory plus string value resolution by resource ID. Complex (bag)
 * entries report their key with a count instead of flattening.
 */
class ArscReader(bytes: ByteArray) {

    data class EntryValue(
        val key: String,
        val value: String,
        val complex: Boolean,
        val typeName: String,
    )

    data class Inventory(
        val packages: Int,
        val types: List<String>,
        val entries: Int,
        val locales: List<String>,
        val size: Int,
    )

    private val r = ChunkReader(bytes)
    private val globals: StringPool
    private val packages = ArrayList<Package>()

    private class Package(
        val id: Int,
        val name: String,
        val types: StringPool?,
        val keys: StringPool?,
        val typeChunks: List<TypeChunk>,
    )

    private class TypeChunk(
        val typeId: Int,
        val entryCount: Int,
        val offsets: IntArray,
        val base: Int,
        val entriesStart: Int,
        val locale: String,
    )

    init {
        r.checkChunk(0, 0x0002, 12)
        val declared = r.i32(4)
        if (declared > r.size || declared < 12) throw ChunkReader.FormatException("arsc size")
        globals = StringPool(r, 12)
        var off = nextChunk(12)
        var guard = 0
        while (off + 8 <= minOf(declared, r.size) && guard++ < 64) {
            val (type, _, size) = r.header(off)
            if (size < 8 || off + size > r.size) break
            if (type == 0x0200) packages.add(readPackage(off))
            off += size
        }
        if (packages.isEmpty()) throw ChunkReader.FormatException("no packages")
    }

    fun inventory(): Inventory {
        var entries = 0
        val types = ArrayList<String>()
        val locales = linkedSetOf<String>()
        for (pkg in packages) {
            for (t in pkg.typeChunks) {
                val name = pkg.types?.getOrEmpty(t.typeId - 1).orEmpty().ifEmpty { "type%02x".format(t.typeId) }
                types.add("%02x/%s".format(pkg.id, name))
                for (o in t.offsets) if (o != -1) entries++
                if (t.locale.isNotEmpty()) locales.add(t.locale)
            }
        }
        return Inventory(packages.size, types, entries, locales.toList(), r.size)
    }

    fun resolve(resId: Int): EntryValue {
        val pkgId = resId ushr 24
        val typeId = (resId ushr 16) and 0xFF
        val entryId = resId and 0xFFFF
        val pkg = packages.firstOrNull { it.id == pkgId }
            ?: throw NoSuchElementException("no package %02x".format(pkgId))
        val chunk = pkg.typeChunks.firstOrNull { it.typeId == typeId }
            ?: throw NoSuchElementException("no type %02x".format(typeId))
        if (entryId >= chunk.entryCount) throw NoSuchElementException("entry out of range")
        val at = chunk.offsets[entryId]
        if (at == -1) throw NoSuchElementException("absent entry")
        val typeName = pkg.types?.getOrEmpty(typeId - 1).orEmpty().ifEmpty { "type%02x".format(typeId) }
        return readEntry(pkg, chunk, at, typeName)
    }

    // ---- parsing ----

    private fun nextChunk(afterGlobals: Int): Int {
        // Globals pool chunk ends at its declared size.
        val (_, _, size) = r.header(afterGlobals)
        return afterGlobals + size
    }

    private fun readPackage(base: Int): Package {
        // Header(8) + id(4) + name(256) + typeStrings(4) + lastPublicType(4)
        //   + keyStrings(4) + lastPublicKey(4) + typeIdOffset(4) = 288.
        r.checkChunk(base, 0x0200, 288)
        val rawId = r.i32(base + 8)
        // Package id travels in the high byte on some writers, low byte on others.
        val id = when {
            (rawId ushr 24) != 0 -> (rawId ushr 24) and 0xFF
            (rawId and 0xFF) != 0 -> rawId and 0xFF
            else -> 0x7F
        }
        val nameChars = CharArray(128) { r.u16(base + 12 + it * 2).toChar() }
        val name = String(nameChars).trim { it <= ' ' || it == '\u0000' }
        val typeStringsOff = r.i32(base + 268)
        val keyStringsOff = r.i32(base + 276)
        val types = runCatching { StringPool(r, base + typeStringsOff) }.getOrNull()
        val keys = runCatching { StringPool(r, base + keyStringsOff) }.getOrNull()
        val chunks = ArrayList<TypeChunk>()
        var off = base + 288
        val end = base + r.i32(base + 4)
        var guard = 0
        while (off + 8 <= minOf(end, r.size) && guard++ < 512) {
            val (type, _, size) = r.header(off)
            if (size < 8 || off + size > r.size) break
            if (type == 0x0201) {
                runCatching { readType(off, size) }.getOrNull()?.let { chunks.add(it) }
            }
            off += size
        }
        return Package(id, name.ifEmpty { "pkg%02x".format(id) }, types, keys, chunks)
    }

    private fun readType(base: Int, size: Int): TypeChunk {
        // Header(8) + id(1)+res0(1)+res1(2) + entryCount(4) + entriesStart(4) + config(64).
        if (size < 20) throw ChunkReader.FormatException("type chunk")
        val typeId = r.u8(base + 8)
        val entryCount = r.i32(base + 12)
        if (entryCount !in 0..100_000) throw ChunkReader.FormatException("entry count")
        val entriesStart = r.i32(base + 16)
        val configOff = base + 20
        val locale = readLocale(configOff)
        val arrayOff = base + 20 + 64
        val offsets = IntArray(minOf(entryCount, 100_000)) { i ->
            r.i32(arrayOff + i * 4)
        }
        return TypeChunk(typeId, offsets.size, offsets, base, base + entriesStart, locale)
    }

    private fun readLocale(configOff: Int): String {
        return try {
            // ResTable_config: size(4) mcc(2) mnc(2) language[2] country[2] …
            val lang = "${r.u8(configOff + 8).toChar()}${r.u8(configOff + 9).toChar()}"
            val country = "${r.u8(configOff + 10).toChar()}${r.u8(configOff + 11).toChar()}"
            val l = lang.takeIf { it.all { c -> c in 'a'..'z' } }.orEmpty()
            val c = country.takeIf { it.all { ch -> ch in 'A'..'Z' } }.orEmpty()
            if (l.isEmpty()) "" else if (c.isEmpty()) l else "$l-r$c"
        } catch (_: Exception) {
            ""
        }
    }

    private fun readEntry(pkg: Package, chunk: TypeChunk, at: Int, typeName: String): EntryValue {
        val off = chunk.entriesStart + at
        val entrySize = r.u16(off)
        if (entrySize < 8) throw ChunkReader.FormatException("entry")
        val flags = r.u16(off + 2)
        val keyIdx = r.i32(off + 4)
        val key = pkg.keys?.getOrEmpty(keyIdx).orEmpty().ifEmpty { "key$keyIdx" }
        if (flags and 0x0001 == 0) {
            // Simple Res_value at off+8: size(u16) res0(u8) dataType(u8) data(u32).
            val dataType = r.u8(off + 11)
            val data = r.i32(off + 12)
            val value = valueString(dataType, data)
            return EntryValue(key, value, complex = false, typeName)
        }
        val count = r.i32(off + 12).coerceIn(0, 10_000)
        return EntryValue(key, "bag($count)", complex = true, typeName)
    }

    private fun valueString(dataType: Int, data: Int): String = when (dataType) {
        0x03 -> globals.getOrEmpty(data)
        0x01 -> "@0x%08x".format(data)
        0x10 -> data.toString()
        0x11 -> "0x%08x".format(data)
        0x12 -> if (data == -1) "true" else if (data == 0) "false" else "bool($data)"
        0x1c, 0x1d, 0x1e -> "#%08x".format(data)
        else -> "type%02x(0x%08x)".format(dataType, data)
    }
}
