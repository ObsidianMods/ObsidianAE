package com.obsidian.apkeditor.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Tool registry with capability gating. History is a bounded ring
 * (the reference grew a list per call until OOM).
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, ToolDefinition>()
    private val disabledTools = mutableSetOf<String>()
    private val disabledCaps = mutableSetOf<String>()
    private val seq = AtomicLong(0)

    private val _history = MutableStateFlow<List<ToolCall>>(emptyList())
    val history: StateFlow<List<ToolCall>> = _history.asStateFlow()

    @Synchronized
    fun register(tool: ToolDefinition) {
        require(tool.name.matches(NAME_RE)) { "bad tool name: ${tool.name}" }
        require(!tools.containsKey(tool.name)) { "duplicate tool: ${tool.name}" }
        tools[tool.name] = tool
    }

    @Synchronized
    fun list(): List<ToolDefinition> = tools.values.filter { isEnabled(it) }.toList()

    @Synchronized
    fun all(): List<ToolDefinition> = tools.values.toList()

    @Synchronized
    fun find(name: String): ToolDefinition? = tools[name]

    @Synchronized
    fun isEnabled(tool: ToolDefinition): Boolean =
        tool.name !in disabledTools && tool.capability.id !in disabledCaps

    @Synchronized
    fun syncDisabled(tools: Set<String>, caps: Set<String>) {
        disabledTools.clear()
        disabledTools.addAll(tools)
        disabledCaps.clear()
        disabledCaps.addAll(caps)
    }

    suspend fun call(name: String, params: Map<String, String>): ToolResult {
        val tool = synchronized(this) { tools[name] }
            ?: return ToolResult.Err(ToolErrorCode.NOT_FOUND, "unknown tool: $name")
        val gate = synchronized(this) {
            when {
                tool.capability.id in disabledCaps -> ToolResult.Err(
                    ToolErrorCode.DISABLED_CAPABILITY,
                    "capability off: ${tool.capability.id}",
                )
                tool.name in disabledTools -> ToolResult.Err(
                    ToolErrorCode.DISABLED, "tool off: $name",
                )
                else -> null
            }
        }
        if (gate != null) {
            record(name, ok = false, ms = 0)
            return gate
        }
        for (arg in tool.args) {
            if (arg.required && params[arg.name].isNullOrEmpty()) {
                record(name, ok = false, ms = 0)
                return ToolResult.Err(ToolErrorCode.BAD_ARGS, "${arg.name} is required")
            }
        }
        return ToolExecutor.execute(tool, params, ::record)
    }

    private fun record(tool: String, ok: Boolean, ms: Long) {
        _history.update { (listOf(ToolCall(seq.incrementAndGet(), tool, ok, ms)) + it)
            .take(MAX_HISTORY) }
    }

    companion object {
        private val NAME_RE = Regex("ae_[a-z0-9]+(_[a-z0-9]+)*")
        private const val MAX_HISTORY = 100
    }
}
