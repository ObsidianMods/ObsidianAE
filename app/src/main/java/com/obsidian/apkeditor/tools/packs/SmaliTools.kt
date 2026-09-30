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
import com.obsidian.apkeditor.work.DexPatcher
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

    private companion object {
        val CLASS_RE = Regex("""^\.class\s+(?:[\w-]+\s+)*(L[^;\s]+;)""", RegexOption.MULTILINE)
        val DEX_RE = Regex("classes\\d*\\.dex")
    }

    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    private fun all(): List<ToolDefinition> = listOf(
        ToolDefinition("ae_smali_validate", "Validate smali",
            "Structural check PLUS a real smali assemble (syntax, registers, labels). " +
                "Nothing is staged.",
            listOf(ArgSpec("smali", true), ArgSpec("api", false)), Capability.DEX, { p ->
                val text = p.need("smali")
                val verdict = withContext(Dispatchers.Default) { SmaliCheck.validate(text) }
                val errors = verdict.errors.toMutableList()
                if (verdict.ok) {
                    val api = p.opt("api").toIntOrNull() ?: 21
                    runCatching {
                        withContext(Dispatchers.IO) { SmaliBridge.assemble(text, api, tmp) }
                    }.onFailure { errors.add(it.message.orEmpty()) }
                }
                ok("ok" to errors.isEmpty().toString(), "errors" to errors.joinToString(";"))
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
            "Assemble smali to a STANDALONE dex. This does not replace a class inside an " +
                "existing dex (use ae_smali_apply for that). Staging needs sessionId + path and " +
                "refuses to overwrite an entry that already exists in the APK.",
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
                    SmaliBridge.assemble(text, p.opt("api", "21").toIntOrNull() ?: 21, tmp)
                }
                val session = p.opt("sessionId")
                val target = p.opt("path")
                val staged: String = if (session.isNotEmpty() && target.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        val wsId = sessions.resolve(session)
                            ?: throw NoSuchElementException("unknown session")
                        val ws = workspaces.open(wsId)
                            ?: throw NoSuchElementException("unknown workspace")
                        val exists = runCatching { workspaces.readBytes(ws, target, 0, 4) }.isSuccess
                        check(!exists) {
                            "$target already exists; staging would overwrite it. " +
                                "Use ae_smali_apply to replace a class inside an existing dex."
                        }
                        workspaces.stageBytes(ws, target, dex)
                    }
                    target
                } else {
                    ""
                }
                ok("dexSize" to dex.size.toString(), "staged" to staged)
            }),
        ToolDefinition("ae_smali_apply", "Apply smali to dex",
            "Assemble smali and MERGE its class into the dex that already defines it " +
                "(or `dex`/last dex when it is new), keeping every other class and the dex " +
                "version. Verifies the merged dex, then stages it in the session. " +
                "Build afterwards with ae_apk_build.",
            listOf(ArgSpec("sessionId", true), ArgSpec("smali", true),
                ArgSpec("dex", false), ArgSpec("api", false)),
            Capability.DEX, { p ->
                val text = p.need("smali")
                check(text.toByteArray().size <= 2 * 1024 * 1024) { "too large" }
                val verdict = SmaliCheck.validate(text)
                if (!verdict.ok) {
                    return@ToolDefinition ToolResult.Err(
                        com.obsidian.apkeditor.tools.ToolErrorCode.BAD_ARGS,
                        "invalid smali: " + verdict.errors.joinToString(";"))
                }
                val descriptor = CLASS_RE.find(text)?.groupValues?.get(1)
                    ?: throw IllegalArgumentException("no .class directive with a type descriptor")
                val wsId = sessions.resolve(p.need("sessionId"))
                    ?: throw NoSuchElementException("unknown session")
                val ws = workspaces.open(wsId) ?: throw NoSuchElementException("unknown workspace")
                val result = withContext(Dispatchers.IO) {
                    val dexNames = dexNamesOf(ws)
                    check(dexNames.isNotEmpty()) { "no classes*.dex in apk" }
                    // 1. Which dex owns the class? (staged copies win over the original)
                    var owner: String? = null
                    var ownerBytes: ByteArray? = null
                    for (name in dexNames) {
                        val bytes = workspaces.readBytes(ws, name, 0, WorkLimits.ENTRY_BYTES.toInt())
                        if (DexPatcher.containsClass(bytes, descriptor, tmp)) {
                            owner = name
                            ownerBytes = bytes
                            break
                        }
                    }
                    val requested = p.opt("dex").takeIf { it.isNotEmpty() }
                    if (owner == null) {
                        owner = requested ?: dexNames.last()
                        check(owner in dexNames) { "no such dex: $owner" }
                        ownerBytes = workspaces.readBytes(ws, owner, 0, WorkLimits.ENTRY_BYTES.toInt())
                    } else if (requested != null && requested != owner) {
                        throw IllegalArgumentException(
                            "$descriptor lives in $owner, not $requested")
                    }
                    val ownerName: String = owner ?: error("owner missing")
                    val base: ByteArray = ownerBytes ?: error("dex bytes missing")
                    // 2. Assemble at the api that matches the target dex version.
                    val api = p.opt("api").toIntOrNull()
                        ?: DexPatcher.apiForDexVersion(DexPatcher.dexVersion(base))
                    val assembled = SmaliBridge.assemble(text, api, tmp)
                    // 3. Merge into the owning dex + verify + stage.
                    val merge = DexPatcher.replaceClasses(base, assembled, tmp)
                    workspaces.stageBytes(ws, ownerName, merge.dex)
                    Triple(ownerName, merge, api)
                }
                val (dexName, merge, api) = result
                ok("class" to descriptor, "dex" to dexName,
                    "replaced" to merge.replaced.joinToString(";"),
                    "added" to merge.added.joinToString(";"),
                    "classes" to merge.classCount.toString(),
                    "dexSize" to merge.dex.size.toString(),
                    "api" to api.toString(), "verified" to "true")
            }),
    )

    /** classes.dex, classes2.dex, ... in numeric order (original + staged). */
    private fun dexNamesOf(ws: com.obsidian.apkeditor.work.Workspace): List<String> {
        val names = LinkedHashSet<String>()
        var offset = 0
        while (true) {
            val page = workspaces.listEntries(ws, "classes", offset, WorkLimits.PAGE_LIMIT)
            page.entries.forEach { if (DEX_RE.matches(it.path)) names.add(it.path) }
            offset += page.entries.size
            if (page.entries.isEmpty() || offset >= page.total) break
        }
        workspaces.stagedPaths(ws).forEach { if (DEX_RE.matches(it)) names.add(it) }
        return names.sortedBy { n ->
            Regex("\\d+").find(n)?.value?.toIntOrNull() ?: 1
        }
    }

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
