package com.obsidian.apkeditor.tools.packs

import apktools.ApkTools
import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.boundedInt
import com.obsidian.apkeditor.tools.need
import com.obsidian.apkeditor.tools.opt
import com.obsidian.apkeditor.work.WorkLimits
import com.obsidian.apkeditor.work.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resource tools over the ported apktools engines (owner AXML decoder with
 * id resolution, ARSC table with unknown-chunk-preserving model).
 * Entry bytes are capped before decode.
 */
class ResTools(private val workspaces: WorkspaceRepository) {

    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    private fun all(): List<ToolDefinition> = listOf(
        ToolDefinition("ae_xml_decode", "Decode XML",
            "Resolved XML text (binary AXML decoded with id resolution).",
            listOf(ArgSpec("workspaceId", true), ArgSpec("path", true),
                ArgSpec("maxChars", false)), Capability.RES, { p ->
                val ws = workspaces.open(p.need("workspaceId"))
                    ?: throw NoSuchElementException("unknown workspace")
                val max = p.boundedInt("maxChars", 8000, 1, 20_000)
                val xml = withContext(Dispatchers.IO) {
                    val bytes = workspaces.readBytes(ws, p.need("path"), 0,
                        WorkLimits.TEXT_PREVIEW_BYTES.toInt())
                    decodeXml(bytes, max)
                }
                ok("path" to p.need("path"), "xml" to xml)
            }),
        ToolDefinition("ae_apk_resource_read", "Read resource",
            "Entry XML by path, or ARSC key/value by resId (0x…).",
            listOf(ArgSpec("workspaceId", true), ArgSpec("path", false),
                ArgSpec("resId", false), ArgSpec("maxChars", false)), Capability.RES, { p ->
                val ws = workspaces.open(p.need("workspaceId"))
                    ?: throw NoSuchElementException("unknown workspace")
                val resId = p.opt("resId")
                if (resId.isNotEmpty()) {
                    val id = parseResId(resId)
                    val (key, value, complex) = withContext(Dispatchers.IO) {
                        val arsc = ApkTools.openArsc(arscBytes(ws.id))
                        val e = arsc.resolve(id)
                        Triple(e.key(), e.stringValue(), e.isComplex)
                    }
                    ok("resId" to resId, "key" to key,
                        "value" to value, "complex" to complex.toString())
                } else {
                    val max = p.boundedInt("maxChars", 8000, 1, 20_000)
                    val path = p.opt("path", "AndroidManifest.xml").takeIf { it.isNotEmpty() }
                        ?: "AndroidManifest.xml"
                    val xml = withContext(Dispatchers.IO) {
                        val bytes = workspaces.readBytes(ws, path, 0,
                            WorkLimits.TEXT_PREVIEW_BYTES.toInt())
                        decodeXml(bytes, max)
                    }
                    ok("path" to path, "xml" to xml)
                }
            }),
        ToolDefinition("ae_arsc_inspect", "Inspect ARSC",
            "Package/type/entry counts plus locales.",
            listOf(ArgSpec("workspaceId", true)), Capability.RES, { p ->
                val inv = withContext(Dispatchers.IO) {
                    val arsc = ApkTools.openArsc(arscBytes(p.need("workspaceId")))
                    val types = arsc.packages().flatMap { pkg ->
                        pkg.allTypes().map { t -> t.id() }
                            .distinct()
                            .map { tid -> "%02x/%s".format(pkg.id(), pkg.typeName(tid)) }
                    }
                    Inv(arsc.packages().size, arsc.totalEntries(),
                        arsc.locales().toList(), types)
                }
                ok("packages" to inv.packages.toString(), "entries" to inv.entries.toString(),
                    "locales" to inv.locales.joinToString(","),
                    "types" to inv.types.take(100).joinToString(";"))
            }),
        ToolDefinition("ae_arsc_resolve", "Resolve resource",
            "ARSC key/value for a resource ID.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("resId", true)),
            Capability.RES, { p ->
                val id = parseResId(p.need("resId"))
                val (key, value, complex) = withContext(Dispatchers.IO) {
                    val arsc = ApkTools.openArsc(arscBytes(p.need("workspaceId")))
                    val e = arsc.resolve(id)
                    Triple(e.key(), e.stringValue(), e.isComplex)
                }
                ok("resId" to p.need("resId"), "key" to key,
                    "value" to value, "complex" to complex.toString())
            }),
        ToolDefinition("ae_apk_resource_xref", "Resource xrefs",
            "Lines of the decoded manifest matching a query.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("query", true),
                ArgSpec("limit", false)), Capability.RES, { p ->
                val ws = workspaces.open(p.need("workspaceId"))
                    ?: throw NoSuchElementException("unknown workspace")
                val query = p.need("query")
                val limit = p.boundedInt("limit", 50, 1, 500)
                val hits = withContext(Dispatchers.IO) {
                    val bytes = workspaces.readBytes(ws, "AndroidManifest.xml", 0,
                        WorkLimits.TEXT_PREVIEW_BYTES.toInt())
                    decodeXml(bytes, WorkLimits.TEXT_PREVIEW_BYTES.toInt())
                        .lineSequence()
                        .filter { query in it }
                        .take(limit)
                        .mapIndexed { i, line -> "manifest|$i|${line.trim().take(160)}" }
                        .toList()
                }
                ok("count" to hits.size.toString(), "refs" to hits.joinToString(";"))
            }),
    )

    private data class Inv(
        val packages: Int,
        val entries: Int,
        val locales: List<String>,
        val types: List<String>,
    )

    private fun decodeXml(bytes: ByteArray, max: Int): String {
        if (bytes.size >= 8 && bytes[0] == 0x03.toByte() && bytes[1] == 0x00.toByte()) {
            return ApkTools.xmlToString(bytes).take(max)
        }
        return bytes.toString(Charsets.UTF_8).take(max)
    }

    private fun parseResId(raw: String): Int =
        raw.removePrefix("0x").removePrefix("0X").toUIntOrNull(16)?.toInt()
            ?: throw IllegalArgumentException("bad resId")

    private fun arscBytes(workspaceId: String): ByteArray {
        val ws = workspaces.open(workspaceId) ?: throw NoSuchElementException("unknown workspace")
        return workspaces.readBytes(ws, "resources.arsc", 0, WorkLimits.ENTRY_BYTES.toInt())
    }

    private fun ToolContext.ok(vararg pairs: Pair<String, String>) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() })
}
