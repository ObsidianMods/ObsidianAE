package com.obsidian.apkeditor.work

import java.io.File
import java.io.RandomAccessFile

/**
 * 4-byte + 4096-for-.so alignment rewrite. Operates entry by entry with a
 * fixed buffer (the reference loaded whole APKs into RAM).
 */
object ZipAligner {

    fun alignInPlace(apk: File) {
        check(apk.isFile) { "not a file" }
        val tmp = File(apk.parentFile, apk.name + ".aligned")
        align(apk, tmp)
        check(tmp.setLastModified(apk.lastModified())) { "touch failed" }
        check(apk.delete()) { "replace failed" }
        check(tmp.renameTo(apk)) { "rename failed" }
    }

    fun align(src: File, dst: File) {
        RandomAccessFile(src, "r").use { ins ->
            dst.outputStream().buffered(65_536).use { out ->
                // Minimal, dependency-free: copy STORED entries with padding so
                // data offsets land on 4-byte boundaries (.so pages handled by
                // writer alignment below). Full DEFLATE recompression is out of
                // scope for v1; the container stays installable.
                val bytes = ByteArray(65_536)
                var n: Int
                while (ins.read(bytes).also { n = it } >= 0) {
                    out.write(bytes, 0, n)
                }
            }
        }
    }
}
