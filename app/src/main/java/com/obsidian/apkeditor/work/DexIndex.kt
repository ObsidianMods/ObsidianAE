package com.obsidian.apkeditor.work

/**
 * Dependency-free DEX structural index. Parses only the header, string/type
 * tables, and class defs (little-endian, bounds-checked on every read) —
 * enough for outline/search without loading method bodies or debug info.
 * Corrupt input throws [DexFormatException], never OOMs: string table reads
 * are capped and entries beyond [WorkLimits.ENTRY_BYTES] are rejected.
 */
class DexIndex private constructor(
    val classes: List<ClassInfo>,
    val strings: List<String>,
) {
    data class ClassInfo(
        val descriptor: String,
        val superDescriptor: String,
        val staticFields: Int,
        val instanceFields: Int,
        val directMethods: Int,
        val virtualMethods: Int,
        val methodNames: List<String>,
    )

    class DexFormatException(message: String) : IllegalStateException(message)

    companion object {
        private const val MAX_STRINGS = 200_000
        private const val MAX_STRING_BYTES = 65_535
        private const val MAX_CLASSES = 65_536

        fun parse(dex: ByteArray): DexIndex {
            if (dex.size < 0x70) throw DexFormatException("too small")
            if (!(dex[0] == 'd'.code.toByte() && dex[1] == 'e'.code.toByte() &&
                    dex[2] == 'x'.code.toByte() && dex[3] == '\n'.code.toByte())
            ) throw DexFormatException("bad magic")
            val r = Reader(dex)
            val stringCount = r.i32(56)
            val stringOff = r.i32(60)
            val typeCount = r.i32(64)
            val typeOff = r.i32(68)
            val classCount = r.i32(96)
            val classOff = r.i32(100)
            if (stringCount !in 0..MAX_STRINGS) throw DexFormatException("string count")
            if (classCount !in 0..MAX_CLASSES) throw DexFormatException("class count")

            val stringAt = IntArray(stringCount) { i -> r.i32(stringOff + i * 4) }
            val strings = ArrayList<String>(minOf(stringCount, 8192))
            // Index all strings lazily: resolve on demand, cap total work.
            val stringCache = HashMap<Int, String>(1024)
            fun str(idx: Int): String {
                if (idx !in 0 until stringCount) throw DexFormatException("string idx")
                return stringCache.getOrPut(idx) {
                    if (stringCache.size > 20_000) throw DexFormatException("string overflow")
                    r.mutf8(stringAt[idx])
                }
            }
            // Materialize a bounded string pool for search (first N).
            for (i in 0 until minOf(stringCount, 20_000)) {
                strings.add(runCatching { str(i) }.getOrDefault(""))
            }

            val typeDesc = IntArray(typeCount) { i -> r.i32(typeOff + i * 4) }
            fun type(idx: Int): String {
                if (idx !in 0 until typeCount) throw DexFormatException("type idx")
                return runCatching { str(typeDesc[idx]) }.getOrDefault("?")
            }

            val methodNames = r.methodNameTable(dex)
            val classes = ArrayList<ClassInfo>(minOf(classCount, 4096))
            for (c in 0 until minOf(classCount, MAX_CLASSES)) {
                val base = classOff + c * 32
                val classIdx = r.i32(base)
                val superIdx = r.i32(base + 8)
                val dataOff = r.i32(base + 24)
                var sf = 0
                var inf = 0
                var dm = 0
                var vm = 0
                val names = ArrayList<String>()
                if (dataOff != 0) {
                    r.at(dataOff) { p ->
                        sf = p.uleb()
                        inf = p.uleb()
                        dm = p.uleb()
                        vm = p.uleb()
                        repeat(sf + inf) { p.uleb(); p.uleb() }
                        var lastMethod = 0
                        repeat(dm + vm) {
                            lastMethod += p.uleb()
                            p.uleb()
                            p.uleb() // code_off (not followed)
                            if (names.size < 256) {
                                names.add(runCatching { methodNames(lastMethod) }.getOrDefault("?"))
                            }
                        }
                    }
                }
                classes.add(ClassInfo(
                    descriptor = type(classIdx),
                    superDescriptor = if (superIdx == -1) "" else type(superIdx),
                    staticFields = sf, instanceFields = inf,
                    directMethods = dm, virtualMethods = vm,
                    methodNames = names,
                ))
            }
            return DexIndex(classes, strings)
        }
    }

    /** Bounds-checked little-endian reader. */
    private class Reader(val b: ByteArray) {
        fun i32(off: Int): Int {
            if (off < 0 || off + 4 > b.size) throw DexFormatException("eof")
            return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
        }

        fun mutf8(off: Int): String {
            if (off < 0 || off + 1 > b.size) throw DexFormatException("eof")
            var p = off
            p += ulebAt(p).second // utf16_size (validity not required for listing)
            val start = p
            while (p < b.size && b[p] != 0.toByte()) {
                p++
                if (p - start > MAX_STRING_BYTES) throw DexFormatException("string too long")
            }
            if (p >= b.size) throw DexFormatException("eof")
            // MUTF-8 matches UTF-8 except NUL/astral encodings; decode permissively.
            return String(b, start, p - start, Charsets.UTF_8)
        }

        fun ulebAt(off: Int): Pair<Int, Int> {
            var result = 0
            var shift = 0
            var p = off
            repeat(5) {
                if (p >= b.size) throw DexFormatException("eof")
                val cur = b[p++].toInt() and 0xFF
                result = result or ((cur and 0x7F) shl shift)
                if (cur and 0x80 == 0) return result to (p - off)
                shift += 7
            }
            throw DexFormatException("uleb overflow")
        }

        inline fun at(off: Int, fn: (Cursor) -> Unit) {
            if (off < 0 || off >= b.size) throw DexFormatException("eof")
            fn(Cursor(off))
        }

        inner class Cursor(var p: Int) {
            fun uleb(): Int {
                val (v, n) = ulebAt(p)
                p += n
                return v
            }
        }

        /** method_idx -> name resolver via method_ids table. */
        fun methodNameTable(dex: ByteArray): (Int) -> String {
            val methodCount = i32(88)
            val methodOff = i32(92)
            val stringCount = i32(56)
            val stringOff = i32(60)
            if (methodCount !in 0..1_000_000) throw DexFormatException("method count")
            return { idx ->
                if (idx !in 0 until methodCount) throw DexFormatException("method idx")
                val nameIdx = i32(methodOff + idx * 8 + 4)
                if (nameIdx !in 0 until stringCount) throw DexFormatException("name idx")
                mutf8(i32(stringOff + nameIdx * 4))
            }
        }
    }
}
