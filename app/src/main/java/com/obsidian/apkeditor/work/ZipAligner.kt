package com.obsidian.apkeditor.work

import com.iyxan23.zipalignjava.ZipAlign
import java.io.File
import java.io.RandomAccessFile

/**
 * APK alignment via the vendored zipalign-java module (MIT): 4-byte data
 * alignment with native libraries on 16KiB page boundaries. Unaligned .so
 * entries are an install-time/load-time hazard (Android 15+ enforces 16KiB
 * pages), which the previous byte-copy stub did nothing about.
 */
object ZipAligner {

    fun alignInPlace(apk: File) {
        check(apk.isFile) { "not a file" }
        val tmp = File(apk.parentFile, apk.name + ".aligned")
        align(apk, tmp)
        check(tmp.isFile && tmp.length() > 0) { "align produced no output" }
        check(tmp.setLastModified(apk.lastModified())) { "touch failed" }
        check(apk.delete()) { "replace failed" }
        check(tmp.renameTo(apk)) { "rename failed" }
    }

    fun align(src: File, dst: File) {
        check(src.isFile) { "not a file: ${src.path}" }
        dst.parentFile?.mkdirs()
        try {
            RandomAccessFile(src, "r").use { ins ->
                dst.outputStream().buffered(65_536).use { out ->
                    ZipAlign.alignZip(ins, out)
                }
            }
        } catch (e: com.iyxan23.zipalignjava.InvalidZipException) {
            runCatching { dst.delete() }
            throw IllegalStateException("not a valid zip: ${e.message}")
        } catch (e: java.io.IOException) {
            runCatching { dst.delete() }
            throw IllegalStateException("align I/O failed: ${e.message}")
        }
        check(dst.isFile && dst.length() > 0) { "align produced no output" }
    }

    /** Result of a local-header alignment audit (no deps, pure parsing). */
    data class Audit(val stored: Int, val misaligned4: Int, val nativeLibs: Int, val pageMiss: Int)

    /**
     * Verifies data-offset alignment: STORED entries on 4-byte boundaries,
     * `.so` entries on 16KiB pages. Throws on unreadable/structural faults.
     */
    fun verify(apk: File): Audit {
        check(apk.isFile) { "not a file: ${apk.path}" }
        var stored = 0
        var misaligned4 = 0
        var nativeLibs = 0
        var pageMiss = 0
        RandomAccessFile(apk, "r").use { ins ->
            val buf = ByteArray(30)
            var off = 0L
            while (true) {
                ins.seek(off)
                if (ins.read(buf) < 30) break
                if (buf[0] != 0x50.toByte() || buf[1] != 0x4b.toByte()) break
                val sig = le32(buf, 0)
                if (sig != 0x04034b50) break // central dir / end reached
                val method = le16(buf, 8)
                val fnLen = le16(buf, 26)
                val extraLen = le16(buf, 28)
                val nameLen = fnLen.toLong() and 0xFFFF
                val extra = extraLen.toLong() and 0xFFFF
                val nameBytes = ByteArray(nameLen.toInt().coerceAtMost(1024))
                ins.read(nameBytes)
                val name = nameBytes.toString(Charsets.UTF_8)
                val dataOff = off + 30 + nameLen + extra
                if (method == 0 && !name.endsWith("/")) {
                    stored++
                    if (dataOff % 4 != 0L) misaligned4++
                    if (name.endsWith(".so")) {
                        nativeLibs++
                        if (dataOff % 16384 != 0L) pageMiss++
                    }
                }
                val compSize = le32url(buf, 18)
                off = dataOff + compSize
            }
        }
        return Audit(stored, misaligned4, nativeLibs, pageMiss)
    }

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int): Int =
        le16(b, i) or (le16(b, i + 2) shl 16)

    private fun le32url(b: ByteArray, i: Int): Long = le32(b, i).toLong() and 0xFFFFFFFFL
}
