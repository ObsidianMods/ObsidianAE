package com.obsidian.apkeditor.tools.packs

import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.boundedInt
import com.obsidian.apkeditor.tools.need
import com.obsidian.apkeditor.tools.opt
import com.obsidian.apkeditor.work.SmaliCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Smali structural tools. Assembly remains out of scope (honest validate-only). */
class SmaliTools {

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
        ToolDefinition("ae_smali_disassemble", "Disassemble (unsupported)",
            "Placeholder: no disassembler engine is bundled.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("descriptor", true)),
            Capability.DEX, { _ ->
                ToolResult.Err(com.obsidian.apkeditor.tools.ToolErrorCode.UNSUPPORTED,
                    "no disassembler engine bundled",
                    "use ae_apk_dex_outline_class for structure")
            }),
    )

    private fun ToolContext.ok(vararg pairs: Pair<String, String>) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() })
}
