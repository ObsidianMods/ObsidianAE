package com.obsidian.apkeditor.tools.packs

import android.content.Context
import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.SessionStore
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.boundedInt
import com.obsidian.apkeditor.tools.need
import com.obsidian.apkeditor.tools.opt
import com.obsidian.apkeditor.work.SmaliBridge
import com.obsidian.apkeditor.work.SmaliCheck
import com.obsidian.apkeditor.work.WorkLimits
import com.obsidian.apkeditor.work.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Smali tools over the google/smali 3.0.9 toolchain (baksmali disassembler,
 * smali assembler). Temp files stay under the app cache dir and are deleted
 * after every call; DEX entry bytes are capped before parsing.
 */
class SmaliTools(
    app: Context,
    private val workspaces: WorkspaceRepository,
    private val sessions: SessionStore,
) {

    private val tmp = File(app.applicationContext.cacheDir, "smali").apply { mkdirs() }

    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    private fun all(): List<ToolDefinition> = listOf(
        ToolDefinition("ae_smali_validate", "Validate smali",
            "Structural check: .class/.super presence, .method balance.",
            listOf(ArgSpec("smali", true)), Capability.DEX, { p ->
                val verdict = withContext(Dispatchers.Default) {
                    SmaliCheck.validate(p.need("smali"))
                }
                ok("ok" to verdict.ok.toString(), "errors" to verdict.errors.joinToString(";"))
            }),
        ToolDefinition("ae_smali_disassemble", "Disassemble class",
            "baksmali disassembly of one class (optional method filter).",
            listOf(ArgSpec("workspaceId", true), ArgSpec("descriptor", true),
                ArgSpec("method", false), ArgSpec("dex", false), ArgSpec("maxChars", false)),
            Capability.DEX, { p ->
                val ws = workspaces.open(p.need("workspaceId"))
                    ?: throw NoSuchElementException("unknown workspace")
                val dexName = p.opt("dex", "classes.dex").takeIf { it.isNotEmpty() } ?: "classes.dex"
                val max = p.boundedInt("maxChars", 12000, 1, 20_000)
                val method = p.opt("method")
                val text = withContext(Dispatchers.IO) {
                    val bytes = workspaces.readBytes(ws, dexName, 0, WorkLimits.ENTRY_BYTES.toInt())
                    // Disassemble wide, filter, THEN clip: filtering the
                    // maxChars-truncated text lost methods past the cut.
                    var out = SmaliBridge.disassembleClass(bytes, p.need("descriptor"), 100_000, tmp)
                    if (method.isNotEmpty()) {
                        out = filterMethod(out, method) ?: throw NoSuchElementException("no such method")
                    }
                    out.take(max)
                }
                ok("class" to p.need("descriptor"), "smali" to text)
            }),
        ToolDefinition("ae_smali_assemble", "Assemble smali",
            "Assemble smali text to DEX and stage it as an entry.",
            listOf(ArgSpec("sessionId", false), ArgSpec("workspaceId", false),
                ArgSpec("path", false), ArgSpec("smali", true), ArgSpec("api", false)),
            Capability.DEX, { p ->
                val max = 2 * 1024 * 1024
                val text = p.need("smali")
                check(text.toByteArray().size <= max) { "too large" }
                val verdict = SmaliCheck.validate(text)
                if (!verdict.ok) {
                    return@ToolDefinition ToolResult.Err(
                        com.obsidian.apkeditor.tools.ToolErrorCode.BAD_ARGS,
                        "invalid smali: " + verdict.errors.joinToString(";"))
                }
                val dex = withContext(Dispatchers.IO) {
                    SmaliBridge.assemble(text, p.opt("api", "34").toIntOrNull() ?: 34, tmp)
                }
                val session = p.opt("sessionId")
                val staged: String = if (session.isNotEmpty()) {
                    val target = p.opt("path", "classes2.dex").takeIf { it.isNotEmpty() }
                        ?: "classes2.dex"
                    withContext(Dispatchers.IO) {
                        val wsId = sessions.resolve(session)
                            ?: throw NoSuchElementException("unknown session")
                        val ws = workspaces.open(wsId)
                            ?: throw NoSuchElementException("unknown workspace")
                        workspaces.stageBytes(ws, target, dex)
                    }
                    target
                } else {
                    ""
                }
                ok("dexSize" to dex.size.toString(), "staged" to staged)
            }),
    )

    private fun filterMethod(smali: String, method: String): String? {
        val lines = smali.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim().startsWith(".method ") && method in it }
        if (start < 0) return null
        val end = lines.drop(start + 1).indexOfFirst { it.trim() == ".end method" }
            .let { if (it < 0) lines.size else start + 1 + it + 1 }
        return lines.subList(start, end).joinToString("\n").take(20_000)
    }

    private fun ToolContext.ok(vararg pairs: Pair<String, String>) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() })
}
