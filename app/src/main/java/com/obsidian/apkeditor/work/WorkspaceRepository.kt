package com.obsidian.apkeditor.work

import java.io.File

/** A single imported APK: immutable original + mutable overlay + outputs. */
data class Workspace(
    val id: String,
    val displayName: String,
    val dir: File,
) {
    fun original(): File = File(dir, "original.apk")
    fun overlayDir(): File = File(dir, "work")
    fun outputDir(): File = File(dir, "output")
}

data class ZipEntryView(
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
)

data class EntryPage(
    val total: Int,
    val offset: Int,
    val entries: List<ZipEntryView>,
    /** Opaque cursor for the next page, null when done. */
    val nextCursor: String?,
)

/** Storage contract. Implementations do blocking I/O; callers use Dispatchers.IO. */
interface WorkspaceRepository {
    fun list(): List<Workspace>
    fun open(id: String): Workspace?
    fun importCopy(apkFile: File, displayName: String): Workspace
    fun listEntries(ws: Workspace, prefix: String, offset: Int, limit: Int): EntryPage
    fun searchEntries(ws: Workspace, query: String, limit: Int): List<ZipEntryView>
    fun readTextPreview(ws: Workspace, path: String, maxChars: Int): String
    fun readBytes(ws: Workspace, path: String, offset: Long, maxBytes: Int): ByteArray
    fun stageBytes(ws: Workspace, path: String, data: ByteArray)
    fun stageDelete(ws: Workspace, path: String)
    fun stagedPaths(ws: Workspace): List<String>
    fun rebuild(ws: Workspace, outName: String): File
    fun close(ws: Workspace, delete: Boolean)
}
