package com.obsidian.apkeditor.work

import java.io.BufferedWriter
import java.io.File
import java.io.StringWriter

/**
 * baksmali/smali toolchain bridge (google/smali 3.0.9, Maven Central).
 * All calls block on temp files under [tmpDir]; callers use Dispatchers.IO.
 * Temp files are deleted in `finally` — never leaked into the workspace.
 */
object SmaliBridge {

    fun disassembleClass(
        dexBytes: ByteArray,
        descriptor: String,
        maxChars: Int,
        tmpDir: File,
    ): String {
        require(descriptor.startsWith("L") && descriptor.endsWith(";")) { "bad descriptor" }
        val dexFile = tmpDir.resolve("bridge.dex").apply { writeBytes(dexBytes) }
        try {
            val dex = com.android.tools.smali.dexlib2.DexFileFactory.loadDexFile(
                dexFile, com.android.tools.smali.dexlib2.Opcodes.forApi(34))
            val classDef = dex.classes.firstOrNull { it.type == descriptor }
                ?: throw NoSuchElementException("no such class")
            val writer = StringWriter(32_768)
            val baksmaliWriter = com.android.tools.smali.baksmali.formatter.BaksmaliWriter(
                BufferedWriter(writer, 8192), null)
            try {
                com.android.tools.smali.baksmali.Adaptors.ClassDefinition(
                    com.android.tools.smali.baksmali.BaksmaliOptions(), classDef)
                    .writeTo(baksmaliWriter)
            } finally {
                runCatching { baksmaliWriter.close() }
            }
            return writer.toString().take(maxChars.coerceIn(1, 100_000))
        } finally {
            runCatching { dexFile.delete() }
        }
    }

    /** smali writes syntax errors to System.err; serialize + capture them. */
    private val errLock = Any()

    /**
     * Assembles with the real smali assembler. On failure the thrown message
     * carries the assembler's own diagnostics (line/column), not a bare
     * "assembly failed".
     */
    fun assemble(smaliText: String, apiLevel: Int, tmpDir: File): ByteArray {
        require(smaliText.toByteArray().size <= 2 * 1024 * 1024) { "too large" }
        tmpDir.mkdirs()
        val work = tmpDir.resolve("smali-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = work.resolve("input.smali").apply { writeText(smaliText) }
            val out = work.resolve("out.dex")
            val options = com.android.tools.smali.smali.SmaliOptions().apply {
                this.apiLevel = apiLevel.coerceIn(21, 35)
                this.jobs = 1
                this.outputDexFile = out.path
            }
            val captured = java.io.ByteArrayOutputStream()
            var ok = false
            synchronized(errLock) {
                val prev = System.err
                System.setErr(java.io.PrintStream(captured, true, "UTF-8"))
                try {
                    ok = com.android.tools.smali.smali.Smali.assemble(
                        options, listOf(input.path))
                } finally {
                    System.setErr(prev)
                }
            }
            if (!ok || !out.isFile) {
                val diag = captured.toString("UTF-8").trim()
                    .replace(input.path, "input.smali").take(1500)
                throw IllegalStateException(
                    "smali assembly failed" + if (diag.isNotEmpty()) ":\n$diag" else "")
            }
            check(out.length() <= WorkLimits.ENTRY_BYTES) { "output too large" }
            return out.readBytes()
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }
}
