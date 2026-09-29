package com.obsidian.apkeditor.system

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Maps SAF tree URIs to plain [File]s where the provider allows it
 * (external-storage trees: primary volume + removable UUID volumes).
 * Other providers (downloads, media, third-party) have no stable file path
 * and resolve to null — the sheet marks those grants accordingly.
 */
object TreePaths {
    private const val EXT_AUTHORITY = "com.android.externalstorage.documents"

    fun toFile(treeUri: Uri): File? = runCatching {
        if (treeUri.authority != EXT_AUTHORITY) return null
        val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
        val volume = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        if (volume.equals("primary", ignoreCase = true)) {
            File(Environment.getExternalStorageDirectory(), rel)
        } else {
            File("/storage/$volume", rel).takeIf { it.exists() }
        }
    }.getOrNull()

    /** Short human label from the tree doc id (e.g. "Download"). */
    fun alias(treeUri: Uri): String = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri).orEmpty()
        docId.substringAfter(':').substringAfterLast('/').trim()
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(24).ifEmpty { "shared" }
    }.getOrDefault("shared")
}
