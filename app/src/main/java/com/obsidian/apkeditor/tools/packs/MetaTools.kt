package com.obsidian.apkeditor.tools.packs

import com.obsidian.apkeditor.ops.OperationTracker
import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.boundedInt
import com.obsidian.apkeditor.tools.need

/** Server introspection: capabilities + operation polling. */
class MetaTools(
    private val registry: ToolRegistry,
    private val operations: OperationTracker,
) {
    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    private fun all(): List<ToolDefinition> = listOf(
        ToolDefinition("ae_mcp_list_capabilities", "List capabilities",
            "Capability ids, titles, tool counts.", emptyList(), Capability.APK, { _ ->
                val tools = registry.all()
                val rows = Capability.entries.map { cap ->
                    "${cap.id}|${cap.title}|${tools.count { it.capability == cap }}"
                }
                ok("count" to rows.size.toString(), "capabilities" to rows.joinToString(";"))
            }),
        ToolDefinition("ae_ops_list", "List operations",
            "Recent build operations.", listOf(ArgSpec("limit", false)), Capability.APK, { p ->
                val ops = operations.ops.value.take(p.boundedInt("limit", 20, 1, 100))
                ok("count" to ops.size.toString(),
                    "ops" to ops.joinToString(";") {
                        "${it.id}|${it.type}|${it.status}|${it.progress}|${it.label}"
                    })
            }),
        ToolDefinition("ae_ops_get", "Get operation", "Status + tail logs.",
            listOf(ArgSpec("operationId", true)), Capability.APK, { p ->
                val op = operations.get(p.need("operationId"))
                    ?: throw NoSuchElementException("unknown operation")
                ok("id" to op.id, "type" to op.type, "status" to op.status.name,
                    "progress" to op.progress.toString(), "error" to op.error,
                    "resultPath" to op.resultPath, "logs" to op.logs.takeLast(20).joinToString("\n"))
            }),
        ToolDefinition("ae_mcp_server_info", "Server info",
            "Endpoint status for agents.", emptyList(), Capability.APK, { _ ->
                ok("server" to "obsidian-ae", "version" to "2.0",
                    "protocol" to "2025-03-26")
            }),
    )

    private fun ToolContext.ok(vararg pairs: Pair<String, String>) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() })
}
