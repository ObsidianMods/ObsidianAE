package com.obsidian.apkeditor.work

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Real class-level DEX editing on top of dexlib2.
 *
 * Assembling smali yields a *standalone* dex holding only the edited class.
 * Dropping that in as `classesN.dex` changes nothing when the same class
 * already lives in another dex (the runtime takes the first definition), so
 * edits silently did not apply. [replaceClasses] instead merges the assembled
 * classes INTO the dex that owns them: every other class is re-interned
 * untouched, the edited class replaces the old definition, and the result is
 * written back at the original dex version (035/037/038/039) so minSdk
 * behaviour is preserved.
 *
 * Blocking; callers use Dispatchers.IO.
 */
object DexPatcher {

    data class Merge(
        val dex: ByteArray,
        val replaced: List<String>,
        val added: List<String>,
        val classCount: Int,
    )

    /** "035" -> 35. Falls back to 35 when the header is unreadable. */
    fun dexVersion(bytes: ByteArray): Int {
        if (bytes.size < 8) return 35
        val v = String(bytes, 4, 3, Charsets.US_ASCII).toIntOrNull()
        return v ?: 35
    }

    /** Smali api level matching a dex version, so instructions stay legal for it. */
    fun apiForDexVersion(version: Int): Int = when {
        version >= 39 -> 28
        version >= 38 -> 26
        version >= 37 -> 24
        else -> 21
    }

    private fun opcodesFor(version: Int): Opcodes =
        runCatching { Opcodes.forDexVersion(version) }
            .getOrElse { Opcodes.forApi(apiForDexVersion(version)) }

    private fun load(bytes: ByteArray, tmp: File, opcodes: Opcodes): Pair<DexFile, File> {
        tmp.mkdirs()
        val f = File(tmp, "dexpatch-${System.nanoTime()}.dex")
        f.writeBytes(bytes)
        return DexFileFactory.loadDexFile(f, opcodes) to f
    }

    /** True when [descriptor] (e.g. `Lcom/x/Foo;`) is defined in this dex. */
    fun containsClass(bytes: ByteArray, descriptor: String, tmp: File): Boolean {
        val (dex, f) = load(bytes, tmp, opcodesFor(dexVersion(bytes)))
        try {
            return dex.classes.any { it.type == descriptor }
        } finally {
            runCatching { f.delete() }
        }
    }

    /**
     * Merges every class of [patchDex] into [baseDex]: same-type classes are
     * replaced, new ones added. Throws with a precise reason if the merged
     * dex would be invalid (e.g. method/field index overflow).
     */
    fun replaceClasses(baseDex: ByteArray, patchDex: ByteArray, tmp: File): Merge {
        val version = dexVersion(baseDex)
        val opcodes = opcodesFor(version)
        val (base, baseFile) = load(baseDex, tmp, opcodes)
        val (patch, patchFile) = load(patchDex, tmp, opcodes)
        val out = File(tmp, "dexmerge-${System.nanoTime()}.dex")
        try {
            val patchTypes = patch.classes.map { it.type }.toSet()
            require(patchTypes.isNotEmpty()) { "assembled dex contains no classes" }
            val baseTypes = base.classes.map { it.type }.toSet()
            val pool = DexPool(opcodes)
            var count = 0
            for (c in base.classes) {
                if (c.type in patchTypes) continue
                pool.internClass(c)
                count++
            }
            for (c in patch.classes) {
                pool.internClass(c)
                count++
            }
            pool.writeTo(FileDataStore(out))
            check(out.isFile && out.length() > 112) { "dex writer produced no output" }
            val bytes = out.readBytes()
            val problems = verify(bytes, tmp)
            check(problems.isEmpty()) { "merged dex failed verification: " + problems.first() }
            return Merge(
                dex = bytes,
                replaced = patchTypes.filter { it in baseTypes }.sorted(),
                added = patchTypes.filter { it !in baseTypes }.sorted(),
                classCount = count,
            )
        } finally {
            runCatching { baseFile.delete() }
            runCatching { patchFile.delete() }
            runCatching { out.delete() }
        }
    }

    /**
     * Structural verification, independent of how the dex was produced:
     * magic, Adler-32 checksum, SHA-1 signature, then a full parse of every
     * class/method body (instruction decode + try blocks) through dexlib2
     * when [deep] is set (the merge path already decodes every method while
     * writing, so the default only walks the class table).
     * Returns human-readable problems; empty = structurally valid.
     * (It cannot run ART's type verifier; that only happens on a device.)
     */
    fun verify(bytes: ByteArray, tmp: File, deep: Boolean = false): List<String> {
        val problems = ArrayList<String>()
        if (bytes.size < 112) return listOf("dex shorter than header")
        if (!(bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
                bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte())) {
            return listOf("bad dex magic")
        }
        val adler = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value
        val stored = (bytes[8].toLong() and 0xff) or
            ((bytes[9].toLong() and 0xff) shl 8) or
            ((bytes[10].toLong() and 0xff) shl 16) or
            ((bytes[11].toLong() and 0xff) shl 24)
        if (adler != stored) problems.add("checksum mismatch")
        val sha = MessageDigest.getInstance("SHA-1").apply { update(bytes, 32, bytes.size - 32) }.digest()
        for (i in 0 until 20) {
            if (sha[i] != bytes[12 + i]) {
                problems.add("SHA-1 signature mismatch")
                break
            }
        }
        if (problems.isNotEmpty()) return problems
        try {
            val (dex, f) = load(bytes, tmp, opcodesFor(dexVersion(bytes)))
            try {
                for (c in dex.classes) {
                    c.type // touches the class_defs table + string/type ids
                    if (!deep) continue
                    for (m in c.methods) {
                        val impl = m.implementation ?: continue
                        // Force full instruction + try-block decode.
                        for (ins in impl.instructions) ins.opcode
                        impl.tryBlocks.size
                    }
                    if (problems.size >= 10) break
                }
            } finally {
                runCatching { f.delete() }
            }
        } catch (t: Throwable) {
            problems.add("parse failed: ${t.message ?: t::class.java.simpleName}")
        }
        return problems
    }
}
