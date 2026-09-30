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
}
