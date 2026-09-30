package com.obsidian.apkeditor.work

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Post-build gate: an APK that fails here would not install or would crash
 * on launch, so `ae_apk_build` refuses to report success for it.
 *
 * Checks (errors block, warnings inform):
 *  - zip opens; classes*.dex present
 *  - every dex that CHANGED versus the original is verified (magic, Adler-32,
 *    SHA-1, class table); unchanged dexes are trusted as shipped
 *  - AndroidManifest.xml decodes as binary XML
 *  - resources.arsc parses AND is STORED (required for targetSdk 30+)
 *  - no stale v1 signature files (they would not match the edited content)
 *  - STORED entries are aligned (4 bytes; 16 KiB for .so)
 *
 * Blocking; callers use Dispatchers.IO.
 */
object ApkValidator {

    data class Report(
        val errors: List<String>,
        val warnings: List<String>,
        val dexCount: Int,
        val entryCount: Int,
    ) {
        val ok: Boolean get() = errors.isEmpty()
        fun summary(): String =
            if (ok) "ok (${entryCount} entries, ${dexCount} dex)" else errors.joinToString("; ")
    }

    fun validate(apk: File, original: File?, tmp: File, expectSigned: Boolean = false): Report {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        var dexCount = 0
        var entryCount = 0
        try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence().toList()
                entryCount = entries.size
                val origCrc: Map<String, Long> = original?.takeIf { it.isFile }?.let { o ->
                    runCatching {
                        ZipFile(o).use { oz ->
                            oz.entries().asSequence().associate { it.name to it.crc }
                        }
                    }.getOrDefault(emptyMap())
                }.orEmpty()

                val dexEntries = entries.filter { DEX_RE.matches(it.name) }
                dexCount = dexEntries.size
                if (dexEntries.isEmpty()) warnings.add("no classes*.dex entries")

                for (e in dexEntries) {
                    if (origCrc[e.name] == e.crc) continue // untouched: trust as shipped
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    val problems = runCatching { DexPatcher.verify(bytes, tmp) }
                        .getOrElse { listOf("verify crashed: ${it.message}") }
                    for (p in problems) errors.add("${e.name}: $p")
                }

                val manifest = zip.getEntry("AndroidManifest.xml")
                if (manifest == null) {
                    errors.add("AndroidManifest.xml missing")
                } else {
                    val bytes = zip.getInputStream(manifest).use { it.readBytes() }
                    runCatching { apktools.ApkTools.decodeXml(bytes) }
                        .onFailure { errors.add("AndroidManifest.xml does not decode: ${it.message}") }
                }

                val arsc = zip.getEntry("resources.arsc")
                if (arsc != null) {
                    if (arsc.method != ZipEntry.STORED) {
                        errors.add("resources.arsc must be STORED (uncompressed) for targetSdk 30+")
                    }
                    if (origCrc["resources.arsc"] != arsc.crc) {
                        val bytes = zip.getInputStream(arsc).use { it.readBytes() }
                        runCatching { apktools.ApkTools.openArsc(bytes) }
                            .onFailure { errors.add("resources.arsc does not parse: ${it.message}") }
                    }
                }

                val staleV1 = entries.filter { V1_RE.matches(it.name) }
                if (staleV1.isNotEmpty() && !expectSigned) {
                    errors.add("stale v1 signature files present: " +
                        staleV1.take(3).joinToString { it.name })
                }
            }
            for (p in alignmentProblems(apk)) errors.add(p)
        } catch (t: Throwable) {
            errors.add("cannot read apk: ${t.message ?: t::class.java.simpleName}")
        }
        return Report(errors, warnings, dexCount, entryCount)
    }

    /**
     * Walks the central directory + local headers and reports STORED entries
     * whose data does not start on the required boundary (4 bytes; 16 KiB for
     * native libraries). Cheap, and independent of the aligner that made it.
     */
    fun alignmentProblems(apk: File): List<String> {
        val problems = ArrayList<String>()
        RandomAccessFile(apk, "r").use { raf ->
            val len = raf.length()
            if (len < 22) return listOf("file too small to be a zip")
            val tailLen = minOf(len, 66_000L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen)
            raf.readFully(tail)
            var eocd = -1
            for (i in tailLen - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) return listOf("zip end-of-central-directory not found")
            val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val total = bb.getShort(eocd + 10).toInt() and 0xffff
            val cdSize = bb.getInt(eocd + 12).toLong() and 0xffffffffL
            val cdOffset = bb.getInt(eocd + 16).toLong() and 0xffffffffL
            if (cdOffset + cdSize > len) return listOf("central directory out of range")
            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(cd)
            val c = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
            var p = 0
            var checked = 0
            val header = ByteArray(30)
            while (p + 46 <= cd.size && checked < total + 1) {
                if (c.getInt(p) != 0x02014b50) break
                val method = c.getShort(p + 10).toInt() and 0xffff
                val nameLen = c.getShort(p + 28).toInt() and 0xffff
                val extraLen = c.getShort(p + 30).toInt() and 0xffff
                val commentLen = c.getShort(p + 32).toInt() and 0xffff
                val lhOffset = c.getInt(p + 42).toLong() and 0xffffffffL
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                if (method == 0 && !name.endsWith("/")) {
                    raf.seek(lhOffset)
                    raf.readFully(header)
                    val lh = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val lhName = lh.getShort(26).toInt() and 0xffff
                    val lhExtra = lh.getShort(28).toInt() and 0xffff
                    val dataStart = lhOffset + 30 + lhName + lhExtra
                    val need = if (name.endsWith(".so")) 16_384L else 4L
                    if (dataStart % need != 0L) {
                        problems.add("$name not ${need}-byte aligned (offset $dataStart)")
                        if (problems.size >= 5) return problems
                    }
                }
                p += 46 + nameLen + extraLen + commentLen
                checked++
            }
        }
        return problems
    }

    private val DEX_RE = Regex("classes\\d*\\.dex")
    private val V1_RE = Regex(
        "META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))", RegexOption.IGNORE_CASE)
}
