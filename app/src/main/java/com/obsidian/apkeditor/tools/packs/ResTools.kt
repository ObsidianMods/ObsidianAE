package com.obsidian.apkeditor.tools.packs

import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolErrorCode
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.boundedInt
import com.obsidian.apkeditor.tools.need
import com.obsidian.apkeditor.tools.opt
import com.obsidian.apkeditor.work.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resource tools. Plain-XML entries decode via bounded text read; binary AXML
 * and compiled ARSC need a dedicated engine (not bundled) and report
 * UNSUPPORTED honestly instead of stub output.
 */
class ResTools(private val workspaces: WorkspaceRepository) {

    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    private fun all(): List<ToolDefinition> = listOf(
        ToolDefinition("ae_xml_decode", "Decode XML",
            "Returns entry text when it is plain XML.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("path", true),
                ArgSpec("maxChars", false)), Capability.RES, { p ->
                val ws = workspaces.open(p.need("workspaceId"))
                    ?: throw NoSuchElementException("unknown workspace")
                val text = withContext(Dispatchers.IO) {
                    workspaces.readTextPreview(ws, p.need("path"),
                        p.boundedInt("maxChars", 8000, 1, 20_000))
                }
                if (text.trimStart().startsWith("<")) {
                    ok("path" to p.need("path"), "xml" to text)
                } else {
                    ToolResult.Err(ToolErrorCode.UNSUPPORTED,
                        "not plain XML (binary AXML engine not bundled)",
                        "path" toPathHint())
                }
            }),
        ToolDefinition("ae_apk_resource_read", "Read resource",
            "Plain-XML entry text by path. Compiled resource lookup is unsupported.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("path", false),
                ArgSpec("resId", false), ArgSpec("maxChars", false)), Capability.RES, { p ->
                val resId = p.opt("resId")
                if (resId.isNotEmpty()) {
                    ToolResult.Err(ToolErrorCode.UNSUPPORTED,
                        "compiled resource lookup needs an ARSC engine (not bundled)")
                } else {
                    val ws = workspaces.open(p.need("workspaceId"))
                        ?: throw NoSuchElementException("unknown workspace")
                    val text = withContext(Dispatchers.IO) {
                        workspaces.readTextPreview(ws,
                            p.opt("path", "AndroidManifest.xml").takeIf { it.isNotEmpty() }
                                ?: "AndroidManifest.xml",
                            p.boundedInt("maxChars", 8000, 1, 20_000))
                    }
                    ok("xml" to text)
                }
            }),
        ToolDefinition("ae_arsc_inspect", "Inspect ARSC (unsupported)",
            "Placeholder: no ARSC engine is bundled.",
            listOf(ArgSpec("workspaceId", true)), Capability.RES, { _ ->
                ToolResult.Err(ToolErrorCode.UNSUPPORTED,
                    "no ARSC engine bundled",
                    "ae_apk_info reports hasArsc structurally")
            }),
        ToolDefinition("ae_arsc_resolve", "Resolve resource (unsupported)",
            "Placeholder: no ARSC engine is bundled.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("resId", true)),
            Capability.RES, { _ ->
                ToolResult.Err(ToolErrorCode.UNSUPPORTED, "no ARSC engine bundled")
            }),
        ToolDefinition("ae_apk_resource_xref", "Resource xrefs (unsupported)",
            "Placeholder: needs an indexed resource table.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("query", true)),
            Capability.RES, { _ ->
                ToolResult.Err(ToolErrorCode.UNSUPPORTED,
                    "no resource index bundled",
                    "use ae_apk_search for entry names")
            }),
    )

    private fun ToolContext.ok(vararg pairs: Pair<String, String>) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() })

    private fun toPathHint(): String = "entry is binary; plain-XML entries decode directly"
}
