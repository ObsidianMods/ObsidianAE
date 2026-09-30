package com.obsidian.apkeditor.tools.packs

import com.obsidian.apkeditor.system.FileScope
import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolErrorCode
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.boundedInt
import com.obsidian.apkeditor.tools.need
import com.obsidian.apkeditor.tools.opt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scoped file tools. Every read stats FIRST and streams bounded windows —
 * the reference loaded whole files then truncated (OOM vector).
 *
 * [FileScope.NeedsGrant] is mapped to NEEDS_GRANT (not a failure): the
 * [onNeedsGrant] callback raises the grant sheet/notification upstream.
 */
class FileTools(
    private val scope: FileScope = FileScope.default(),
    private val onNeedsGrant: (String) -> Unit = {},
) {

    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    /** Refreshes the visible scope (grants changed while running). */
    fun rootsSummary(): String =
        scope.allRoots().joinToString(";") {
            if (it.alias.isEmpty()) "mcp:" + it.dir.path else "@" + it.alias + ":" + it.dir.path
        }

    private fun all(): List<ToolDefinition> = listOf(
        def("ae_file_access_policy", "Access policy", "Scope roots and rules.",
            emptyList(), Capability.FILE_READ) {
            ok("roots" to rootsSummary(), "backend" to "scoped-io",
                "grant_hint" to "address extra folders as @Alias/path; ungranted paths return NEEDS_GRANT")
        },
        def("ae_file_list", "List files", "Names under a prefix (empty = scope root).",
            listOf(ArgSpec("prefix", false), ArgSpec("limit", false)), Capability.FILE_READ) { p ->
            val prefix = p.opt("prefix")
            val dir = withContext(Dispatchers.IO) {
                if (prefix.isEmpty()) scope.rootDir() else scope.resolve(prefix)
            }
            val names = withContext(Dispatchers.IO) {
                (if (dir.isDirectory) dir.listFiles().orEmpty().toList() else listOf(dir))
                    .sortedBy { it.name }
                    .take(p.boundedInt("limit", 50, 1, 200))
                    .map { (if (it.isDirectory) "d/" else "f/") + it.name }
            }
            ok("count" to names.size.toString(), "items" to names.joinToString(";"))
        },
        def("ae_file_stat", "Stat path", "Type, size, mtime.",
            listOf(ArgSpec("path", true)), Capability.FILE_READ) { p ->
            val f = withContext(Dispatchers.IO) { scope.resolve(p.need("path")) }
            require(f.exists()) { "not found: ${p.need("path")}" }
            ok("dir" to f.isDirectory.toString(), "size" to f.length().toString(),
                "mtime" to f.lastModified().toString())
        },
        def("ae_file_read_text", "Read text", "Bounded text read.",
            listOf(ArgSpec("path", true), ArgSpec("maxChars", false)), Capability.FILE_READ) { p ->
            val text = withContext(Dispatchers.IO) {
                val f = scope.resolve(p.need("path"))
                check(f.isFile && f.length() <= MAX_READ) { "too large" }
                f.readText().take(p.boundedInt("maxChars", 8000, 1, 20_000))
            }
            ok("path" to p.need("path"), "text" to text)
        },
        def("ae_file_read_bytes", "Read bytes", "Bounded hex window.",
            listOf(ArgSpec("path", true), ArgSpec("offset", false), ArgSpec("maxBytes", false)),
            Capability.FILE_READ) { p ->
            val hex = withContext(Dispatchers.IO) {
                val f = scope.resolve(p.need("path"))
                val off = p.opt("offset", "0").toLongOrNull()?.coerceAtLeast(0) ?: 0
                val max = p.boundedInt("maxBytes", 4096, 1, 65536).toLong()
                check(f.isFile) { "not a file" }
                f.inputStream().use { ins ->
                    var s = off
                    while (s > 0) {
                        val n = ins.skip(s)
                        if (n <= 0) break
                        s -= n
                    }
                    val buf = ByteArray(8192)
                    val out = StringBuilder()
                    var remaining = max
                    while (remaining > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        for (i in 0 until n) out.append("%02x".format(buf[i]))
                        remaining -= n
                    }
                    out.toString()
                }
            }
            ok("path" to p.need("path"), "hex" to hex)
        },
        def("ae_file_search", "Search names", "Filename substring search.",
            listOf(ArgSpec("query", true), ArgSpec("limit", false)), Capability.FILE_READ) { p ->
            val hits = withContext(Dispatchers.IO) {
                scope.rootDir().walkTopDown().filter { it.isFile && p.need("query") in it.name }
                    .take(p.boundedInt("limit", 50, 1, 200))
                    .map { it.relativeTo(scope.rootDir()).path }.toList()
            }
            ok("count" to hits.size.toString(), "items" to hits.joinToString(";"))
        },
        def("ae_file_edit_text", "Write text", "Create/replace a text file.",
            listOf(ArgSpec("path", true), ArgSpec("text", true)), Capability.FILE_WRITE) { p ->
            withContext(Dispatchers.IO) {
                val text = p.need("text")
                check(text.length <= MAX_READ) { "too large" }
                val f = scope.resolve(p.need("path"))
                f.parentFile?.mkdirs()
                f.writeText(text)
            }
            ok("path" to p.need("path"), "staged" to "true")
        },
        def("ae_file_write_bytes", "Write bytes", "Create/replace from hex.",
            listOf(ArgSpec("path", true), ArgSpec("hex", true)), Capability.FILE_WRITE) { p ->
            withContext(Dispatchers.IO) {
                val data = p.need("hex").hexToBytes()
                check(data.size <= MAX_READ) { "too large" }
                val f = scope.resolve(p.need("path"))
                f.parentFile?.mkdirs()
                f.writeBytes(data)
            }
            ok("path" to p.need("path"))
        },
        def("ae_file_copy", "Copy", "Copy inside the scope.",
            listOf(ArgSpec("src", true), ArgSpec("dst", true)), Capability.FILE_CREATE) { p ->
            withContext(Dispatchers.IO) {
                val src = scope.resolve(p.need("src"))
                val dst = scope.resolve(p.need("dst"))
                check(src.isFile && src.length() <= MAX_READ) { "too large or missing" }
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
            ok("dst" to p.need("dst"))
        },
        def("ae_file_move", "Move", "Move inside the scope.",
            listOf(ArgSpec("src", true), ArgSpec("dst", true)), Capability.FILE_MOVE) { p ->
            withContext(Dispatchers.IO) {
                val dst = scope.resolve(p.need("dst"))
                dst.parentFile?.mkdirs()
                check(scope.resolve(p.need("src")).renameTo(dst)) { "move failed" }
            }
            ok("dst" to p.need("dst"))
        },
        def("ae_file_rename", "Rename", "Rename without slashes.",
            listOf(ArgSpec("path", true), ArgSpec("name", true)), Capability.FILE_RENAME) { p ->
            val name = p.need("name")
            require('/' !in name && '\\' !in name && name.isNotEmpty()) { "bad name" }
            withContext(Dispatchers.IO) {
                val src = scope.resolve(p.need("path"))
                check(src.parentFile != null) { "no parent" }
                check(src.renameTo(java.io.File(src.parentFile, name))) { "rename failed" }
            }
            ok("name" to name)
        },
        def("ae_file_delete", "Delete file", "Delete a single file.",
            listOf(ArgSpec("path", true)), Capability.FILE_DELETE) { p ->
            val deleted = withContext(Dispatchers.IO) {
                scope.resolve(p.need("path")).takeIf { it.isFile }?.delete() ?: false
            }
            ok("deleted" to deleted.toString())
        },
        def("ae_file_create_directory", "Make directory", "mkdirs inside the scope.",
            listOf(ArgSpec("path", true)), Capability.FILE_CREATE) { p ->
            withContext(Dispatchers.IO) { scope.resolve(p.need("path")).mkdirs() }
            ok("path" to p.need("path"))
        },
        def("ae_file_delete_directory", "Delete directory", "Recursive delete.",
            listOf(ArgSpec("path", true)), Capability.FILE_DELETE) { p ->
            val deleted = withContext(Dispatchers.IO) {
                scope.resolve(p.need("path")).takeIf { it.isDirectory }?.deleteRecursively() ?: false
            }
            ok("deleted" to deleted.toString())
        },
        def("ae_file_patch_bytes", "Patch bytes", "In-place hex patch of a scoped file.",
            listOf(ArgSpec("path", true), ArgSpec("offset", true), ArgSpec("hex", true)),
            Capability.FILE_WRITE) { p ->
            val n = withContext(Dispatchers.IO) {
                val f = scope.resolve(p.need("path"))
                check(f.isFile && f.length() <= MAX_READ) { "too large or missing" }
                val offset = p.opt("offset", "0").toLongOrNull()?.coerceAtLeast(0) ?: 0
                val patch = p.need("hex").hexToBytes()
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    check(offset + patch.size <= raf.length()) { "patch overruns end" }
                    raf.seek(offset)
                    raf.write(patch)
                }
                patch.size
            }
            ok("path" to p.need("path"), "patched" to n.toString())
        },
    )

    private fun def(
        name: String, title: String, desc: String, args: List<ArgSpec>,
        cap: Capability, fn: suspend ToolContext.(Map<String, String>) -> ToolResult,
    ) = ToolDefinition(name, title, desc, args, cap, wrapped@{ params ->
        try {
            fn(this, params)
        } catch (e: FileScope.NeedsGrant) {
            runCatching { onNeedsGrant(e.path) }
            ToolResult.Err(
                ToolErrorCode.NEEDS_GRANT, e.message ?: "folder access needed",
                "A grant request was raised in the app (notification if backgrounded). " +
                    "Ask the user to grant the folder, then retry the call.",
            )
        }
    })

    private fun ToolContext.ok(vararg pairs: Pair<String, String>) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() })

    companion object {
        private const val MAX_READ = 8 * 1024 * 1024L
    }
}

private fun String.hexToBytes(): ByteArray {
    val clean = filter { it.isLetterOrDigit() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
