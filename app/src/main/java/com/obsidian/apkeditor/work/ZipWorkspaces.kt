package com.obsidian.apkeditor.work

import android.content.Context
import com.obsidian.apkeditor.system.StorageDirs
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `java.util.zip` workspace store. Never mutates the original; edits land in
 * `work/` and materialize only via [rebuild]. All sizes are pre-checked
 * against [WorkLimits] before any byte array is allocated.
 */
class ZipWorkspaces(app: Context) : WorkspaceRepository {

    private val root: File = StorageDirs.workspaces(app).apply { mkdirs() }

    override fun list(): List<Workspace> {
        val dirs = root.listFiles { f -> f.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            val meta = File(dir, "meta.txt")
            val name = runCatching { meta.readText().lineSequence().firstOrNull().orEmpty() }
                .getOrDefault("").ifEmpty { dir.name }
            if (!File(dir, "original.apk").exists()) null
            else Workspace(dir.name, name, dir)
        }.sortedBy { it.displayName }
    }

    override fun open(id: String): Workspace? {
        if (!id.matches(ID_RE)) return null
        val dir = File(root, id)
        if (!dir.canonicalFile.path.startsWith(root.canonicalPath + "/")) return null
        if (!File(dir, "original.apk").exists()) return null
        return Workspace(id, readName(dir), dir)
    }

    override fun importCopy(apkFile: File, displayName: String): Workspace {
        require(apkFile.isFile) { "not a file" }
        val id = UUID.randomUUID().toString().take(8)
        val dir = File(root, id)
        check(dir.mkdirs()) { "cannot create workspace" }
        apkFile.copyTo(File(dir, "original.apk"))
        File(dir, "meta.txt").writeText(displayName.take(128) + "\n")
        File(dir, "work").mkdirs()
        File(dir, "output").mkdirs()
        return Workspace(id, displayName, dir)
    }

    override fun listEntries(ws: Workspace, prefix: String, offset: Int, limit: Int): EntryPage {
        // Store layer honors up to MAX_ENTRIES: paging to transport-sized
        // slices is the TOOL layer's job (ae_apk_list/continue). Coercing
        // here to PAGE_LIMIT hid every entry past #500 (live 899-entry APK).
        val safeLimit = limit.coerceIn(1, WorkLimits.MAX_ENTRIES)
        val safeOffset = offset.coerceAtLeast(0)
        ZipFile(ws.original()).use { zip ->
            val all = zip.entries().asSequence()
                .map { e -> ZipEntryView(e.name, e.size.coerceAtLeast(0), e.isDirectory) }
                .filter { prefix.isEmpty() || it.path.startsWith(prefix) }
                .sortedBy { it.path }
                .toList()
            val page = all.drop(safeOffset).take(safeLimit)
            val next = (safeOffset + page.size).takeIf { it < all.size }?.let { "offset:$it" }
            return EntryPage(all.size, safeOffset, page, next)
        }
    }

    override fun searchEntries(ws: Workspace, query: String, limit: Int): List<ZipEntryView> {
        require(query.isNotEmpty()) { "empty query" }
        val safeLimit = limit.coerceIn(1, WorkLimits.PAGE_LIMIT)
        ZipFile(ws.original()).use { zip ->
            return zip.entries().asSequence()
                .map { e -> ZipEntryView(e.name, e.size.coerceAtLeast(0), e.isDirectory) }
                .filter { query in it.path }
                .take(safeLimit)
                .toList()
        }
    }

    override fun readTextPreview(ws: Workspace, path: String, maxChars: Int): String {
        val bytes = readBounded(ws, path, 0, WorkLimits.TEXT_PREVIEW_BYTES.toLong())
        val text = if (bytes.hasNul()) bytes.toHexPreview() else bytes.toString(Charsets.UTF_8)
        return text.take(maxChars.coerceIn(1, WorkLimits.VALUE_CHARS))
    }

    override fun readBytes(ws: Workspace, path: String, offset: Long, maxBytes: Int): ByteArray {
        return readBounded(ws, path, offset, maxBytes.toLong().coerceIn(1, WorkLimits.ENTRY_BYTES))
    }

    override fun stageBytes(ws: Workspace, path: String, data: ByteArray) {
        checkName(path)
        require(data.size.toLong() <= WorkLimits.STAGE_BYTES) { "too large" }
        val target = File(ws.overlayDir(), safeRel(path))
        target.parentFile?.mkdirs()
        target.writeBytes(data)
    }

    override fun patchBytes(ws: Workspace, path: String, offset: Long, patch: ByteArray): Int {
        checkName(path)
        require(offset >= 0) { "negative offset" }
        require(patch.size.toLong() <= WorkLimits.STAGE_BYTES) { "patch too large" }
        val staged = File(ws.overlayDir(), safeRel(path))
        val base: ByteArray = if (staged.isFile) {
            check(staged.length() <= WorkLimits.ENTRY_BYTES) { "too large" }
            staged.readBytes()
        } else {
            readBounded(ws, path, 0, WorkLimits.ENTRY_BYTES)
        }
        check(offset <= base.size) { "offset past end" }
        check(offset + patch.size <= base.size) { "patch overruns end" }
        val merged = base.copyOf()
        patch.copyInto(merged, offset.toInt())
        staged.parentFile?.mkdirs()
        staged.writeBytes(merged)
        return patch.size
    }

    override fun stageDelete(ws: Workspace, path: String) {
        checkName(path)
        File(ws.overlayDir(), safeRel(path) + DELETED_SUFFIX).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(0))
        }
    }

    override fun stagedPaths(ws: Workspace): List<String> {
        return ws.overlayDir().walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(ws.overlayDir()).path.removeSuffix(DELETED_SUFFIX) }
            .sorted()
            .toList()
    }

    override fun rebuild(ws: Workspace, outName: String): File {
        checkName(outName)
        val overlay = collectOverlay(ws)
        ws.outputDir().mkdirs()
        val out = File(ws.outputDir(), outName)
        ZipFile(ws.original()).use { zin ->
            ZipOutputStream(FileOutputStream(out)).use { zout ->
                val entries = zin.entries().asSequence().toList()
                check(entries.size <= WorkLimits.MAX_ENTRIES) { "too many entries" }
                for (e in entries) {
                    val rel = e.name
                    if (overlay.deleted.contains(rel)) continue
                    val staged = overlay.files[rel]
                    zout.putNextEntry(ZipEntry(rel).apply { time = e.time })
                    if (staged != null) zout.write(staged) else zin.getInputStream(e).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
                for ((rel, data) in overlay.files) {
                    if (entries.any { it.name == rel }) continue
                    zout.putNextEntry(ZipEntry(rel))
                    zout.write(data)
                    zout.closeEntry()
                }
            }
        }
        ZipAligner.alignInPlace(out)
        return out
    }

    override fun close(ws: Workspace, delete: Boolean) {
        if (delete) ws.dir.deleteRecursively()
    }

    // ---- internals ----

    private data class Overlay(val files: Map<String, ByteArray>, val deleted: Set<String>)

    private fun collectOverlay(ws: Workspace): Overlay {
        val files = mutableMapOf<String, ByteArray>()
        val deleted = mutableSetOf<String>()
        ws.overlayDir().walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(ws.overlayDir()).path
            if (rel.endsWith(DELETED_SUFFIX)) deleted.add(rel.removeSuffix(DELETED_SUFFIX))
            else {
                check(f.length() <= WorkLimits.STAGE_BYTES) { "staged file too large" }
                files[rel] = f.readBytes()
            }
        }
        return Overlay(files, deleted)
    }

    private fun readBounded(ws: Workspace, path: String, offset: Long, max: Long): ByteArray {
        checkName(path)
        val staged = File(ws.overlayDir(), safeRel(path)).takeIf { it.isFile }
        if (staged != null) {
            check(staged.length() <= WorkLimits.ENTRY_BYTES) { "too large" }
            val all = staged.readBytes()
            return slice(all, offset, max)
        }
        ZipFile(ws.original()).use { zip ->
            val entry = zip.getEntry(path) ?: throw NoSuchElementException("no such entry: $path")
            check(!entry.isDirectory) { "is directory" }
            check(entry.size <= WorkLimits.ENTRY_BYTES || entry.size == -1L) { "too large" }
            zip.getInputStream(entry).use { ins ->
                var skipped = offset
                while (skipped > 0) {
                    val n = ins.skip(skipped)
                    if (n <= 0) break
                    skipped -= n
                }
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32_768)
                var remaining = max
                while (remaining > 0) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                return out.toByteArray()
            }
        }
    }

    private fun slice(all: ByteArray, offset: Long, max: Long): ByteArray {
        val from = offset.coerceIn(0, all.size.toLong()).toInt()
        val to = minOf(all.size.toLong(), from + max).toInt()
        return all.copyOfRange(from, to)
    }

    private fun checkName(path: String) {
        require(path.isNotEmpty() && '\u0000' !in path) { "bad path" }
        require(!path.startsWith("/")) { "absolute path" }
        require(".." !in path.split('/')) { "traversal" }
    }

    private fun safeRel(path: String): String = path.trim('/')

    private fun readName(dir: File): String =
        runCatching { File(dir, "meta.txt").readText().lineSequence().firstOrNull().orEmpty() }
            .getOrDefault("").ifEmpty { dir.name }

    companion object {
        private val ID_RE = Regex("[a-f0-9-]{4,64}")
        private const val DELETED_SUFFIX = ".deleted"
    }
}

private fun ByteArray.hasNul(): Boolean {
    val n = minOf(size, 8192)
    for (i in 0 until n) if (this[i] == 0.toByte()) return true
    return false
}

private fun ByteArray.toHexPreview(): String {
    val sb = StringBuilder(minOf(size, 4096) * 2 + 16)
    for (b in take(2048)) sb.append("%02x".format(b))
    if (size > 2048) sb.append("…")
    return sb.toString()
}
