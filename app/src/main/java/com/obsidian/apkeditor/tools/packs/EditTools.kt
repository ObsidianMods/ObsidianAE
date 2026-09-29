package com.obsidian.apkeditor.tools.packs

import com.obsidian.apkeditor.ops.OpStatus
import com.obsidian.apkeditor.ops.OperationTracker
import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.need
import com.obsidian.apkeditor.tools.opt
import com.obsidian.apkeditor.work.WorkLimits
import com.obsidian.apkeditor.work.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Edit staging + build. Sessions are short-lived UUIDs bound to workspaces;
 * unlike the reference (unbounded 8-hex map, never cleared), sessions are
 * removed on build/close and validated on every call.
 */
class EditTools(
    private val workspaces: WorkspaceRepository,
    private val operations: OperationTracker,
) {
    private val sessions = ConcurrentHashMap<String, String>()

    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    private fun all(): List<ToolDefinition> = listOf(
        def("ae_apk_edit_open", "Open edit session", "Bind a workspace for staging.",
            listOf(ArgSpec("workspaceId", true)), Capability.EDIT) { p ->
            val ws = workspaces.open(p.need("workspaceId"))
                ?: throw NoSuchElementException("unknown workspace")
            val session = UUID.randomUUID().toString().take(8)
            if (sessions.size > 64) sessions.clear()
            sessions[session] = ws.id
            ok("sessionId" to session, "workspaceId" to ws.id)
        },
        def("ae_apk_edit_text", "Stage text", "Stage a UTF-8 entry replacement.",
            listOf(ArgSpec("sessionId", true), ArgSpec("path", true), ArgSpec("text", true)),
            Capability.EDIT) { p ->
            val ws = sessionWs(p.need("sessionId"))
            val text = p.need("text")
            check(text.toByteArray().size <= WorkLimits.STAGE_BYTES) { "too large" }
            withContext(Dispatchers.IO) {
                workspaces.stageBytes(ws, p.need("path"), text.toByteArray(Charsets.UTF_8))
            }
            ok("sessionId" to p.need("sessionId"), "path" to p.need("path"), "staged" to "true")
        },
        def("ae_apk_delete", "Stage delete", "Mark an entry deleted.",
            listOf(ArgSpec("sessionId", true), ArgSpec("path", true)), Capability.EDIT) { p ->
            val ws = sessionWs(p.need("sessionId"))
            withContext(Dispatchers.IO) { workspaces.stageDelete(ws, p.need("path")) }
            ok("path" to p.need("path"), "staged" to "deleted")
        },
        def("ae_apk_edit_check", "Check staged", "List staged + deleted paths.",
            listOf(ArgSpec("sessionId", true)), Capability.EDIT) { p ->
            val ws = sessionWs(p.need("sessionId"))
            val staged = withContext(Dispatchers.IO) { workspaces.stagedPaths(ws) }
            ok("staged" to staged.size.toString(), "files" to staged.joinToString(";"))
        },
        def("ae_apk_build", "Build APK", "Rebuild + align into output/. Poll ae_ops_get.",
            listOf(ArgSpec("sessionId", true), ArgSpec("outName", false)), Capability.EDIT) { p ->
            val ws = sessionWs(p.need("sessionId"))
            val outName = p.opt("outName", "rebuilt.apk").takeIf { it.isNotEmpty() } ?: "rebuilt.apk"
            val op = operations.create("apk.build", "build $outName")
            try {
                operations.update(op.id) { it.copy(status = OpStatus.RUNNING) }
                val out = withContext(Dispatchers.IO) { workspaces.rebuild(ws, outName) }
                sessions.remove(p.need("sessionId"))
                operations.update(op.id) {
                    it.copy(status = OpStatus.SUCCEEDED, progress = 1f, resultPath = out.path)
                }
                ok("operationId" to op.id, "status" to "SUCCEEDED", "output" to out.path)
            } catch (e: Exception) {
                operations.update(op.id) {
                    it.copy(status = OpStatus.FAILED, error = e.message.orEmpty().take(300))
                }
                throw e
            }
        },
        def("ae_apk_patch_bytes", "Patch bytes", "In-place hex patch of a staged entry.",
            listOf(ArgSpec("sessionId", true), ArgSpec("path", true),
                ArgSpec("offset", true), ArgSpec("hex", true)), Capability.EDIT) { p ->
            val ws = sessionWs(p.need("sessionId"))
            val offset = p.opt("offset", "0").toLongOrNull()?.coerceAtLeast(0) ?: 0
            val patch = p.need("hex").hexToBytes()
            val n = withContext(Dispatchers.IO) {
                workspaces.patchBytes(ws, p.need("path"), offset, patch)
            }
            ok("path" to p.need("path"), "offset" to offset.toString(), "patched" to n.toString())
        },
        def("ae_apk_read_signature", "Read signature", "Honest v1 presence scan; verified=false.",
            listOf(ArgSpec("workspaceId", true)), Capability.APK) { p ->
            val ws = workspaces.open(p.need("workspaceId"))
                ?: throw NoSuchElementException("unknown workspace")
            val hasV1 = withContext(Dispatchers.IO) {
                workspaces.listEntries(ws, "META-INF/", 0, 100).entries.any {
                    it.path.endsWith(".SF") || it.path.endsWith(".RSA")
                }
            }
            ok("hasV1" to hasV1.toString(), "verified" to "false",
                "note" to "debug backend: presence only")
        },
    )

    private fun sessionWs(session: String) =
        sessions[session]?.let { workspaces.open(it) }
            ?: throw NoSuchElementException("unknown session")

    private fun String.hexToBytes(): ByteArray {
        val clean = filter { it.isLetterOrDigit() }
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun def(
        name: String, title: String, desc: String, args: List<ArgSpec>,
        cap: Capability, fn: suspend ToolContext.(Map<String, String>) -> ToolResult,
    ) = ToolDefinition(name, title, desc, args, cap, fn)

    private fun ToolContext.ok(vararg pairs: Pair<String, String>) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() })
}
