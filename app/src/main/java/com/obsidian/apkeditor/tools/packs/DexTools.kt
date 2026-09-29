package com.obsidian.apkeditor.tools.packs

import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.boundedInt
import com.obsidian.apkeditor.tools.need
import com.obsidian.apkeditor.tools.opt
import com.obsidian.apkeditor.work.DexIndex
import com.obsidian.apkeditor.work.WorkLimits
import com.obsidian.apkeditor.work.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/**
 * DEX outline/search/xref over the dependency-free [DexIndex].
 * Per-workspace indexes are LRU-capped at 4 (the reference never evicted).
 */
class DexTools(private val workspaces: WorkspaceRepository) {

    private val indexCache = object : LinkedHashMap<String, Map<String, DexIndex>>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, Map<String, DexIndex>>): Boolean =
            size > 4
    }

    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    private fun all(): List<ToolDefinition> = listOf(
        def("ae_apk_dex_outline_class", "DEX outline", "List classes or describe one.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("descriptor", false),
                ArgSpec("dex", false), ArgSpec("prefix", false),
                ArgSpec("limit", false), ArgSpec("offset", false)), Capability.DEX) { p ->
            val ws = wsOf(p.need("workspaceId"))
            val dexName = p.opt("dex", "classes.dex").takeIf { it.isNotEmpty() } ?: "classes.dex"
            val limit = p.boundedInt("limit", 100, 1, WorkLimits.PAGE_LIMIT)
            val offset = p.opt("offset", "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
            val descriptor = p.opt("descriptor")
            val index = withContext(Dispatchers.IO) { indexOf(ws.id, dexName) { loadDex(ws.id, dexName) } }
            if (descriptor.isNotEmpty()) {
                val cls = index.classes.firstOrNull { it.descriptor == descriptor }
                    ?: throw NoSuchElementException("no such class")
                ok("class" to cls.descriptor, "super" to cls.superDescriptor,
                    "methods" to cls.methodNames.take(200).joinToString(";"),
                    "fields" to "static=${cls.staticFields} instance=${cls.instanceFields}")
            } else {
                val prefix = p.opt("prefix")
                val all = index.classes.filter { prefix.isEmpty() || it.descriptor.startsWith(prefix) }
                val slice = all.drop(offset).take(limit)
                ok("total" to all.size.toString(), "offset" to offset.toString(),
                    "classes" to slice.joinToString(";") {
                        "${it.descriptor}|m${it.directMethods + it.virtualMethods}" +
                            "/f${it.staticFields + it.instanceFields}"
                    })
            }
        },
        def("ae_dex_search", "DEX search", "Search classes/methods/strings.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("query", true),
                ArgSpec("kind", false, "class|method|string"), ArgSpec("dex", false),
                ArgSpec("limit", false)), Capability.DEX) { p ->
            val ws = wsOf(p.need("workspaceId"))
            val dexName = p.opt("dex", "classes.dex").takeIf { it.isNotEmpty() } ?: "classes.dex"
            val query = p.need("query")
            val kind = p.opt("kind", "class")
            val limit = p.boundedInt("limit", 50, 1, 500)
            val index = withContext(Dispatchers.IO) { indexOf(ws.id, dexName) { loadDex(ws.id, dexName) } }
            val hits = when (kind) {
                "method" -> index.classes.flatMap { c ->
                    c.methodNames.filter { query in it }.map { "${c.descriptor}#$it" }
                }.take(limit)
                "string" -> index.strings.filter { query in it }.take(limit)
                else -> index.classes.map { it.descriptor }.filter { query in it }.take(limit)
            }
            ok("count" to hits.size.toString(), "kind" to kind, "hits" to hits.joinToString(";"))
        },
        def("ae_apk_dex_xref", "DEX xrefs", "Occurrences across class/method/string tables.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("target", true),
                ArgSpec("dex", false), ArgSpec("limit", false)), Capability.DEX) { p ->
            val ws = wsOf(p.need("workspaceId"))
            val dexName = p.opt("dex", "classes.dex").takeIf { it.isNotEmpty() } ?: "classes.dex"
            val target = p.need("target")
            val limit = p.boundedInt("limit", 50, 1, 500)
            val index = withContext(Dispatchers.IO) { indexOf(ws.id, dexName) { loadDex(ws.id, dexName) } }
            val refs = ArrayList<String>()
            for (c in index.classes) {
                if (target in c.descriptor || target in c.superDescriptor) {
                    refs.add("type:${c.descriptor}")
                }
                for (m in c.methodNames) {
                    if (target in m) refs.add("method:${c.descriptor}#$m")
                }
                if (refs.size >= limit) break
            }
            if (refs.size < limit) {
                for (s in index.strings) {
                    if (target in s) refs.add("string:$s")
                    if (refs.size >= limit) break
                }
            }
            ok("count" to refs.size.toString(), "refs" to refs.joinToString(";"))
        },
    )

    private fun wsOf(id: String) =
        workspaces.open(id) ?: throw NoSuchElementException("unknown workspace")

    /** Double-checked load: expensive parse never runs under the lock. */
    private fun indexOf(wsId: String, dex: String, load: () -> DexIndex): DexIndex {
        synchronized(this) {
            indexCache[wsId]?.get(dex)?.let { return it }
        }
        val fresh = load()
        synchronized(this) {
            val perWs = indexCache[wsId]?.toMutableMap() ?: mutableMapOf()
            perWs[dex] = fresh
            indexCache[wsId] = perWs
            return fresh
        }
    }

    private fun loadDex(wsId: String, dexName: String): DexIndex {
        val ws = workspaces.open(wsId) ?: throw NoSuchElementException("unknown workspace")
        val bytes = workspaces.readBytes(ws, dexName, 0, WorkLimits.ENTRY_BYTES.toInt())
        return DexIndex.parse(bytes)
    }

    private fun def(
        name: String, title: String, desc: String, args: List<ArgSpec>,
        cap: Capability, fn: suspend ToolContext.(Map<String, String>) -> ToolResult,
    ) = ToolDefinition(name, title, desc, args, cap, fn)

    private fun ToolContext.ok(vararg pairs: Pair<String, String>) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() })
}
