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

    /** In-flight calls (name + start timestamp). Debug panel + stuck-call triage. */
    data class ActiveCall(val seq: Long, val tool: String, val startedAt: Long)

    private val _active = MutableStateFlow<List<ActiveCall>>(emptyList())
    val active: StateFlow<List<ActiveCall>> = _active.asStateFlow()

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

    /** Live health overrides from probes (screen verify / startup smoke). */
    private val healthOverrides = mutableMapOf<String, ToolHealth>()

    @Synchronized
    fun healthOf(name: String): ToolHealth =
        healthOverrides[name] ?: tools[name]?.health ?: ToolHealth.UNVERIFIED

    /** Audit mark: exercised live, known good (see ToolPacks). */
    @Synchronized
    fun markVerified(vararg names: String) {
        for (n in names) healthOverrides[n] = ToolHealth.VERIFIED
    }

    /** Audit mark: safe to dry-run with [args] (read-only, no side effects). */
    @Synchronized
    fun markProbeSafe(name: String, args: Map<String, String> = emptyMap()) {
        val cur = tools[name] ?: return
        tools[name] = cur.copy(probeSafe = true, probeArgs = args)
    }

    /**
     * Dry-runs one probe-safe tool (no history record, no gating bypass:
     * disabled tools are skipped). Returns true on Ok. Callers use IO.
     */
    suspend fun probe(name: String): Boolean {
        val def = synchronized(this) { tools[name] } ?: return false
        if (!def.probeSafe || !isEnabled(def)) return false
        val ok = try {
            def.invoke(ToolContext(), def.probeArgs) is ToolResult.Ok
        } catch (_: Exception) {
            false
        }
        synchronized(this) {
            healthOverrides[name] =
                if (ok) ToolHealth.VERIFIED else ToolHealth.FAILED
        }
        return ok
    }

    /** Probes every safe+enabled tool. Returns name to pass/fail. Callers use IO. */
    suspend fun verifyAllSafe(): Map<String, Boolean> {
        val names = synchronized(this) {
            tools.values.filter { it.probeSafe && isEnabled(it) }.map { it.name }
        }
        return names.associateWith { probe(it) }
    }

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
        val id = seq.incrementAndGet()
        _active.update { it + ActiveCall(id, name, android.os.SystemClock.elapsedRealtime()) }
        try {
            return ToolExecutor.execute(tool, params) { t, ok, ms -> record(t, ok, ms) }
        } finally {
            _active.update { list -> list.filterNot { it.seq == id } }
        }
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
