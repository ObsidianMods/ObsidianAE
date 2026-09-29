package com.obsidian.apkeditor.tools

/** Stable capability groups. IDs double as the persisted gating keys. */
enum class Capability(val id: String, val title: String) {
    APK("ae_apk", "APK"),
    DEX("ae_dex", "DEX / Smali"),
    RES("ae_res", "Resources"),
    EDIT("ae_edit", "Edit / Build"),
    FILE_READ("file.read", "File read"),
    FILE_WRITE("file.write", "File write"),
    FILE_CREATE("file.create", "File create"),
    FILE_DELETE("file.delete", "File delete"),
    FILE_MOVE("file.move", "File move"),
    FILE_RENAME("file.rename", "File rename"),
}

data class ArgSpec(
    val name: String,
    val required: Boolean,
    val hint: String = "",
)

data class Page(
    val offset: Int,
    val limit: Int,
    val total: Int,
    val nextCursor: String? = null,
)

enum class ToolErrorCode {
    NOT_FOUND,
    BAD_ARGS,
    DISABLED,
    DISABLED_CAPABILITY,
    UNSUPPORTED,
    CORRUPT,
    CANCELLED,
    NEEDS_GRANT,
    INTERNAL,
}

/** Flat string map envelope (20k/value truncation at the transport edge). */
sealed interface ToolResult {
    data class Ok(val data: Map<String, String>, val page: Page? = null) : ToolResult
    data class Err(val code: ToolErrorCode, val message: String, val hint: String = "") : ToolResult
}

/** Per-call bounded context. Implementations must honor [ensureActive]. */
class ToolContext(val maxChars: Int = 20_000) {
    @Volatile
    var cancelled: Boolean = false

    fun ensureActive() {
        if (cancelled) throw java.util.concurrent.CancellationException()
    }

    fun String.capped(): String = if (length > maxChars) take(maxChars) else this
}

data class ToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val args: List<ArgSpec>,
    val capability: Capability,
    val invoke: suspend ToolContext.(params: Map<String, String>) -> ToolResult,
)

data class ToolCall(
    val seq: Long,
    val tool: String,
    val ok: Boolean,
    val ms: Long,
)

/** Standard argument helpers shared by every pack. */
internal fun Map<String, String>.need(key: String): String =
    this[key]?.takeIf { it.isNotEmpty() } ?: throw BadArgs("$key is required")

internal fun Map<String, String>.opt(key: String, default: String = ""): String =
    this[key] ?: default

internal fun Map<String, String>.boundedInt(key: String, default: Int, min: Int, max: Int): Int =
    (this[key]?.toIntOrNull() ?: default).coerceIn(min, max)

internal class BadArgs(message: String) : IllegalArgumentException(message)
