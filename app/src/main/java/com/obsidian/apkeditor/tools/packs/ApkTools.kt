package com.obsidian.apkeditor.tools.packs

import android.content.Context
import com.obsidian.apkeditor.system.FileScope
import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.boundedInt
import com.obsidian.apkeditor.tools.need
import com.obsidian.apkeditor.tools.opt
import com.obsidian.apkeditor.work.ApkInspector
import com.obsidian.apkeditor.work.WorkLimits
import com.obsidian.apkeditor.work.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** APK inspect tools. Blocking zip I/O is confined to Dispatchers.IO. */
class ApkTools(
    app: Context,
    private val workspaces: WorkspaceRepository,
) {
    private val inspector = ApkInspector(app.applicationContext)
    private val scope = FileScope.default()

    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    private fun all(): List<ToolDefinition> = listOf(
        def("ae_apk_open", "Open APK", "Import an APK from the MCP folder (or reopen a workspace id).",
            listOf(ArgSpec("path", true, "APK file name or workspace id")), Capability.APK) { p ->
            val id = p.need("path")
            val ws = withContext(Dispatchers.IO) {
                workspaces.open(id) ?: run {
                    val file = scope.resolve(id)
                    require(file.isFile && file.name.endsWith(".apk")) { "not an APK: $id" }
                    workspaces.importCopy(file, file.name)
                }
            }
            ok("workspaceId" to ws.id, "apkFileName" to ws.displayName)
        },
        def("ae_apk_list_available_apks", "List APK files", "APKs present in the MCP folder.",
            listOf(ArgSpec("prefix", false), ArgSpec("limit", false)), Capability.APK) { p ->
            val prefix = p.opt("prefix")
            val limit = p.boundedInt("limit", 50, 1, 200)
            val items = withContext(Dispatchers.IO) {
                scope.rootDir().listFiles { f ->
                    f.isFile && f.name.endsWith(".apk") && f.name.startsWith(prefix)
                }.orEmpty().sortedBy { it.name }.take(limit)
                    .map { "${it.name}|${it.length()}|${it.lastModified()}" }
            }
            ok("count" to items.size.toString(), "items" to items.joinToString(";"))
        },
        def("ae_apk_list_workspaces", "List workspaces", "Imported APK workspaces.",
            listOf(ArgSpec("limit", false)), Capability.APK) { p ->
            val limit = p.boundedInt("limit", 50, 1, 200)
            val list = withContext(Dispatchers.IO) { workspaces.list().take(limit) }
            ok("count" to list.size.toString(),
                "workspaces" to list.joinToString(";") { "${it.id}|${it.displayName}" })
        },
        def("ae_apk_list", "List entries", "Paged zip entries, optional view filter.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("view", false, "all|dex|xml|res|native"),
                ArgSpec("prefix", false), ArgSpec("limit", false), ArgSpec("offset", false)),
            Capability.APK) { p ->
            val ws = wsOf(p.need("workspaceId"))
            val view = p.opt("view", "all")
            val limit = p.boundedInt("limit", 100, 1, WorkLimits.PAGE_LIMIT)
            val offset = p.opt("offset", "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
            val page = withContext(Dispatchers.IO) {
                workspaces.listEntries(ws, p.opt("prefix"), 0, WorkLimits.MAX_ENTRIES)
            }
            val filtered = page.entries.filter { e ->
                when (view) {
                    "dex" -> e.path.endsWith(".dex")
                    "xml" -> e.path.endsWith(".xml")
                    "res" -> e.path.startsWith("res/") || e.path == "resources.arsc"
                    "native" -> e.path.startsWith("lib/")
                    else -> true
                }
            }
            val slice = filtered.drop(offset).take(limit)
            val next = (offset + slice.size).takeIf { it < filtered.size }?.let { "offset:$it" }
            ok("total" to filtered.size.toString(), "offset" to offset.toString(),
                "nextCursor" to (next.orEmpty()),
                "entries" to slice.joinToString(";") { "${it.path}|${it.size}" },
                page = com.obsidian.apkeditor.tools.Page(offset, limit, filtered.size, next))
        },
        def("ae_apk_continue", "Continue listing", "Next page via nextCursor.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("nextCursor", true), ArgSpec("limit", false)),
            Capability.APK) { p ->
            val ws = wsOf(p.need("workspaceId"))
            val offset = p.need("nextCursor").removePrefix("offset:").toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = p.boundedInt("limit", 100, 1, WorkLimits.PAGE_LIMIT)
            val page = withContext(Dispatchers.IO) {
                workspaces.listEntries(ws, "", offset, limit)
            }
            ok("total" to page.total.toString(), "offset" to page.offset.toString(),
                "nextCursor" to (page.nextCursor.orEmpty()),
                "entries" to page.entries.joinToString(";") { "${it.path}|${it.size}" },
                page = com.obsidian.apkeditor.tools.Page(page.offset, limit, page.total, page.nextCursor))
        },
        def("ae_apk_search", "Search entries", "Substring search over entry names.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("query", true), ArgSpec("limit", false)),
            Capability.APK) { p ->
            val ws = wsOf(p.need("workspaceId"))
            val hits = withContext(Dispatchers.IO) {
                workspaces.searchEntries(ws, p.need("query"), p.boundedInt("limit", 50, 1, 500))
            }
            ok("count" to hits.size.toString(),
                "entries" to hits.joinToString(";") { "${it.path}|${it.size}" })
        },
        def("ae_apk_read_text", "Read text", "Bounded text/hex preview of an entry.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("path", true), ArgSpec("maxChars", false)),
            Capability.APK) { p ->
            val ws = wsOf(p.need("workspaceId"))
            val text = withContext(Dispatchers.IO) {
                workspaces.readTextPreview(ws, p.need("path"),
                    p.boundedInt("maxChars", 8000, 1, WorkLimits.VALUE_CHARS))
            }
            ok("path" to p.need("path"), "text" to text)
        },
        def("ae_apk_read_bytes", "Read bytes", "Hex slice of an entry.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("path", true),
                ArgSpec("offset", false), ArgSpec("maxBytes", false)), Capability.APK) { p ->
            val ws = wsOf(p.need("workspaceId"))
            val bytes = withContext(Dispatchers.IO) {
                workspaces.readBytes(ws, p.need("path"),
                    p.opt("offset", "0").toLongOrNull()?.coerceAtLeast(0) ?: 0,
                    p.boundedInt("maxBytes", 4096, 1, 65536))
            }
            ok("path" to p.need("path"), "size" to bytes.size.toString(), "hex" to bytes.toHex())
        },
        def("ae_apk_info", "APK info", "Structural flags + install metadata.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("full", false)), Capability.APK) { p ->
            val ws = wsOf(p.need("workspaceId"))
            val meta = withContext(Dispatchers.IO) { inspector.inspect(ws) }
            ok("package" to meta.packageName, "versionName" to meta.versionName,
                "versionCode" to meta.versionCode.toString(), "apkSize" to meta.apkSize.toString(),
                "entries" to meta.entryCount.toString(), "hasDex" to meta.hasDex.toString(),
                "hasArsc" to meta.hasArsc.toString(), "hasNativeLibs" to meta.hasNativeLibs.toString())
        },
        def("ae_apk_close", "Close workspace", "Optionally delete its directory.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("delete", false)), Capability.APK) { p ->
            val ws = wsOf(p.need("workspaceId"))
            withContext(Dispatchers.IO) { workspaces.close(ws, p.opt("delete") == "true") }
            ok("workspaceId" to ws.id, "deleted" to (p.opt("delete") == "true").toString())
        },
    )

    private fun wsOf(id: String) =
        workspaces.open(id) ?: throw NoSuchElementException("unknown workspace: $id")

    private fun def(
        name: String, title: String, desc: String, args: List<ArgSpec>,
        cap: Capability, fn: suspend ToolContext.(Map<String, String>) -> ToolResult,
    ) = ToolDefinition(name, title, desc, args, cap, fn)

    private fun ToolContext.ok(vararg pairs: Pair<String, String>,
        page: com.obsidian.apkeditor.tools.Page? = null) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() }, page)
}

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append("%02x".format(b))
    return sb.toString()
}
