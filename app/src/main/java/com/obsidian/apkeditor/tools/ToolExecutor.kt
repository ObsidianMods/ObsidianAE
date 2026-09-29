package com.obsidian.apkeditor.tools

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CancellationException

/**
 * Single choke point for tool invocation: per-call timeout, error isolation
 * (one tool's bug becomes an Err, never a server crash), and timing.
 *
 * Fixes the reference gaps: no per-tool timeout existed, and unexpected
 * throwables propagated as untyped INTERNAL from scattered catch blocks.
 */
object ToolExecutor {

    /** Wall-clock budget per tool call. */
    const val TIMEOUT_MS = 30_000L

    suspend fun execute(
        tool: ToolDefinition,
        params: Map<String, String>,
        record: (tool: String, ok: Boolean, ms: Long) -> Unit,
    ): ToolResult {
        val ctx = ToolContext()
        val start = android.os.SystemClock.elapsedRealtime()
        val result = try {
            withTimeout(TIMEOUT_MS) {
                tool.invoke(ctx, params)
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.Err(ToolErrorCode.CANCELLED, "timed out after ${TIMEOUT_MS}ms")
        } catch (e: CancellationException) {
            ToolResult.Err(ToolErrorCode.CANCELLED, "cancelled")
        } catch (e: BadArgs) {
            ToolResult.Err(ToolErrorCode.BAD_ARGS, e.message.orEmpty())
        } catch (e: NoSuchElementException) {
            ToolResult.Err(ToolErrorCode.NOT_FOUND, e.message.orEmpty())
        } catch (e: UnsupportedOperationException) {
            ToolResult.Err(ToolErrorCode.UNSUPPORTED, e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            ToolResult.Err(ToolErrorCode.BAD_ARGS, e.message.orEmpty())
        } catch (e: IllegalStateException) {
            ToolResult.Err(ToolErrorCode.CORRUPT, e.message.orEmpty())
        } catch (e: Exception) {
            ToolResult.Err(ToolErrorCode.INTERNAL, e.javaClass.simpleName + ": " + e.message)
        }
        val ms = android.os.SystemClock.elapsedRealtime() - start
        record(tool.name, result is ToolResult.Ok, ms)
        return result
    }
}
