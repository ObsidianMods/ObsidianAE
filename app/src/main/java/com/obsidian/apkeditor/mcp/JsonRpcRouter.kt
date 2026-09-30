package com.obsidian.apkeditor.mcp

import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.Page
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-RPC 2.0 router over the tool registry. Pure functions + one suspend
 * entry — no sockets here (the reference mixed framing, routing, and I/O).
 */
class JsonRpcRouter(private val registry: ToolRegistry) {

    data class Outcome(val status: Int, val json: String?)

    suspend fun handleJsonRpc(body: String): Outcome {
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return Outcome(200, err(null, -32700, "parse error"))
        val id = if (req.isNull("id")) null else req.opt("id")
        val method = req.optString("method", "")
        if (method.isEmpty()) {
            // Notification: acknowledge without work.
            return Outcome(202, null)
        }
        // Legacy shape: {method:"tools/list"|"tools/call", params:{...}}.
        if (method == "tools/list" && req.has("params").not()) {
            return Outcome(200, ok(id, JSONObject().put("tools", toolArray())))
        }
        return when (method) {
            "initialize" -> Outcome(200, ok(id, JSONObject()
                .put("protocolVersion", PROTOCOL)
                .put("serverInfo", JSONObject()
                    .put("name", "obsidian-ae").put("version", "2.0"))))
            "ping" -> Outcome(200, ok(id, JSONObject()))
            "tools/list" -> Outcome(200, ok(id, JSONObject().put("tools", toolArray())))
            "tools/call" -> {
                val params = req.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name", params.optString("tool", ""))
                val args = paramMap(params.optJSONObject("arguments") ?: params.optJSONObject("params"))
                val result = registry.call(name, args)
                Outcome(200, ok(id, callResult(name, result)))
            }
            else -> Outcome(200, err(id, -32601, "unknown method: $method"))
        }
    }

    fun toolListJson(): String =
        JSONObject().put("tools", toolArray()).toString()

    private fun toolArray(): JSONArray {
        val arr = JSONArray()
        // Snapshot to avoid holding the registry lock during JSON build.
        val tools = registry.all().filter { registry.isEnabled(it) }
        for (t in tools) {
            val props = JSONObject()
            val required = JSONArray()
            for (a in t.args) {
                props.put(a.name, JSONObject()
                    .put("type", "string").put("description", a.hint))
                if (a.required) required.put(a.name)
            }
            arr.put(JSONObject()
                .put("name", t.name)
                .put("title", t.title)
                .put("description", t.description)
                .put("capability", t.capability.id)
                .put("inputSchema", JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", required)))
        }
        return arr
    }

    private fun callResult(name: String, result: ToolResult): JSONObject {
        val def = registry.find(name)
        return when (result) {
            is ToolResult.Ok -> {
                val text = JSONObject()
                for ((k, v) in result.data) text.put(k, v.take(VALUE_CHARS))
                result.page?.let { p: Page ->
                    text.put("page", JSONObject()
                        .put("offset", p.offset).put("limit", p.limit)
                        .put("total", p.total))
                    p.nextCursor?.let { text.put("nextCursor", it) }
                }
                text.put("_tool", name)
                text.put("_capability", def?.capability?.id ?: Capability.APK.id)
                content(text.toString(), isError = false)
            }
            is ToolResult.Err -> {
                val text = JSONObject()
                    .put("code", result.code.name)
                    .put("message", result.message.take(2000))
                    .put("_tool", name)
                    .put("_capability", def?.capability?.id ?: Capability.APK.id)
                if (result.hint.isNotEmpty()) text.put("hint", result.hint.take(500))
                content(text.toString(), isError = true)
            }
        }
    }

    private fun content(text: String, isError: Boolean): JSONObject =
        JSONObject().put("content", JSONArray().put(
            JSONObject().put("type", "text").put("text", text)))
            .put("isError", isError)

    private fun ok(id: Any?, result: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("result", result).toString()

    private fun err(id: Any?, code: Int, message: String): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message)).toString()

    private fun paramMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            // NOTE: no routing-key skipping here. The tool name is extracted
            // from params BEFORE this runs, so "name" inside arguments is a
            // legitimate tool argument (ae_file_rename broke on this).
            out[k] = obj.opt(k)?.toString().orEmpty()
        }
        return out
    }

    companion object {
        const val PROTOCOL = "2025-03-26"
        private const val VALUE_CHARS = 20_000
    }
}
