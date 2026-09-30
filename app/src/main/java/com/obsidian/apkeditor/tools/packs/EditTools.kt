package com.obsidian.apkeditor.tools.packs

import com.obsidian.apkeditor.ops.OpStatus
import com.obsidian.apkeditor.ops.OperationTracker
import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.SessionStore
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

/**
 * Edit staging + build + sign. Sessions live in the shared [SessionStore]
 * (capped, explicitly closed) instead of a pack-local unbounded map.
 */
class EditTools(
    private val workspaces: WorkspaceRepository,
    private val operations: OperationTracker,
    private val sessions: SessionStore,
    private val app: android.content.Context,
) {

    fun registerAll(r: ToolRegistry) {
        for (t in all()) r.register(t)
    }

    private fun all(): List<ToolDefinition> = listOf(
        def("ae_apk_edit_open", "Open edit session", "Bind a workspace for staging.",
            listOf(ArgSpec("workspaceId", true)), Capability.EDIT) { p ->
            val ws = workspaces.open(p.need("workspaceId"))
                ?: throw NoSuchElementException("unknown workspace")
            val session = sessions.open(ws.id)
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
        def("ae_apk_edit_resource", "Edit resource", "Set an ARSC string value, stage rebuilt table.",
            listOf(ArgSpec("sessionId", true), ArgSpec("resId", true), ArgSpec("value", true)),
            Capability.EDIT) { p ->
            val ws = sessionWs(p.need("sessionId"))
            val id = p.need("resId").removePrefix("0x").removePrefix("0X")
                .toUIntOrNull(16)?.toInt() ?: throw IllegalArgumentException("bad resId")
            val key = withContext(Dispatchers.IO) {
                val current = workspaces.readBytes(ws, "resources.arsc", 0,
                    WorkLimits.ENTRY_BYTES.toInt())
                val table = apktools.ApkTools.openArsc(current)
                val entry = try {
                    table.resolve(id)
                } catch (e: Exception) {
                    throw NoSuchElementException("no such resource")
                }
                entry.setString(p.need("value"))
                val rebuilt = apktools.ApkTools.rebuildArsc(table)
                workspaces.stageBytes(ws, "resources.arsc", rebuilt)
                entry.key()
            }
            ok("resId" to p.need("resId"), "key" to key, "staged" to "true")
        },
        def("ae_apk_build", "Build APK",
            "Rebuild (compression preserved, stale signatures dropped, aligned), validate " +
                "dex/manifest/arsc/alignment, then sign v1+v2 and verify. sign=false skips signing.",
            listOf(ArgSpec("sessionId", true), ArgSpec("outName", false),
                ArgSpec("sign", false, "true|false (default true)")), Capability.EDIT) { p ->
            val ws = sessionWs(p.need("sessionId"))
            val outName = p.opt("outName", "rebuilt.apk").takeIf { it.isNotEmpty() } ?: "rebuilt.apk"
            val wantSign = p.opt("sign", "true").lowercase() != "false"
            val op = operations.create("apk.build", "build $outName")
            try {
                operations.update(op.id) { it.copy(status = OpStatus.RUNNING) }
                val result = withContext(Dispatchers.IO) {
                    val built = workspaces.rebuild(ws, outName)
                    val tmp = java.io.File(app.cacheDir, "validate").apply { mkdirs() }
                    val report = com.obsidian.apkeditor.work.ApkValidator.validate(
                        built, ws.original(), tmp)
                    if (!report.ok) {
                        runCatching { built.delete() }
                        throw IllegalStateException("build invalid: " + report.summary())
                    }
                    var signed: java.io.File? = null
                    var signNote = ""
                    var verified = false
                    if (wantSign) {
                        val signedName = outName.removeSuffix(".apk") + "-signed.apk"
                        val target = guardedOutput(ws.outputDir(), signedName)
                        try {
                            val key = loadDevKey()
                            com.obsidian.apkeditor.work.SigningBridge.sign(
                                built, target, key.storeFile, key.storePassword,
                                key.alias, key.keyPassword)
                            val v = com.obsidian.apkeditor.work.SigningBridge.verify(target)
                            verified = v.verified
                            if (!v.verified) {
                                signNote = "signature failed verification: " +
                                    v.errors.take(3).joinToString(";")
                            }
                            val align = com.obsidian.apkeditor.work.ApkValidator
                                .alignmentProblems(target)
                            if (align.isNotEmpty()) {
                                signNote += (if (signNote.isEmpty()) "" else " | ") +
                                    "alignment after signing: " + align.first()
                            }
                            signed = target
                        } catch (e: Exception) {
                            runCatching { target.delete() }
                            signNote = "not signed: " + e.message.orEmpty().take(200)
                        }
                    }
                    arrayOf<Any?>(built, report, signed, verified, signNote)
                }
                val built = result[0] as java.io.File
                val report = result[1] as com.obsidian.apkeditor.work.ApkValidator.Report
                val signed = result[2] as java.io.File?
                val verified = result[3] as Boolean
                val signNote = result[4] as String
                sessions.close(p.need("sessionId"))
                val finalPath = (signed ?: built).path
                operations.update(op.id) {
                    it.copy(status = OpStatus.SUCCEEDED, progress = 1f, resultPath = finalPath)
                }
                val installable = signed != null && verified && signNote.isEmpty()
                ok("operationId" to op.id, "status" to "SUCCEEDED",
                    "output" to built.path,
                    "signedOutput" to signed?.path.orEmpty(),
                    "installable" to installable.toString(),
                    "validation" to report.summary(),
                    "warnings" to report.warnings.joinToString(";"),
                    "signature" to (if (signed == null) "unsigned" else if (verified) "v1+v2 verified" else "unverified"),
                    "note" to signNote)
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
        def("ae_apk_read_signature", "Read signature", "v1 presence + real verification.",
            listOf(ArgSpec("workspaceId", true)), Capability.APK) { p ->
            val ws = workspaces.open(p.need("workspaceId"))
                ?: throw NoSuchElementException("unknown workspace")
            val (hasV1, verified) = withContext(Dispatchers.IO) {
                val v = com.obsidian.apkeditor.work.SigningBridge.verify(ws.original())
                com.obsidian.apkeditor.work.SigningBridge.hasV1(ws.original()) to v.verified
            }
            ok("hasV1" to hasV1.toString(), "verified" to verified.toString())
        },
        def("ae_apk_sign", "Sign APK", "V1+V2 sign a built output with the dev key.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("input", false), ArgSpec("output", false)),
            Capability.EDIT) { p ->
            val ws = workspaces.open(p.need("workspaceId"))
                ?: throw NoSuchElementException("unknown workspace")
            val inputName = p.opt("input", "rebuilt.apk").takeIf { it.isNotEmpty() } ?: "rebuilt.apk"
            val outputName = p.opt("output", "signed.apk").takeIf { it.isNotEmpty() } ?: "signed.apk"
            val out = withContext(Dispatchers.IO) {
                val dir = ws.outputDir()
                val input = guardedOutput(dir, inputName)
                check(input.isFile) { "missing built file: $inputName (run ae_apk_build first)" }
                val output = guardedOutput(dir, outputName)
                val key = loadDevKey()
                com.obsidian.apkeditor.work.SigningBridge.sign(
                    input, output, key.storeFile, key.storePassword, key.alias, key.keyPassword)
                output
            }
            ok("input" to inputName, "output" to outputName, "size" to out.length().toString())
        },
        def("ae_apk_verify", "Verify APK", "Full v1/v2/v3 verification of a built file.",
            listOf(ArgSpec("workspaceId", true), ArgSpec("file", false)), Capability.APK) { p ->
            val ws = workspaces.open(p.need("workspaceId"))
                ?: throw NoSuchElementException("unknown workspace")
            val name = p.opt("file", "signed.apk").takeIf { it.isNotEmpty() } ?: "signed.apk"
            val v = withContext(Dispatchers.IO) {
                com.obsidian.apkeditor.work.SigningBridge.verify(guardedOutput(ws.outputDir(), name))
            }
            ok("file" to name, "verified" to v.verified.toString(),
                "v1" to v.v1.toString(), "v2" to v.v2.toString(),
                "errors" to v.errors.take(5).joinToString(";"),
                "signers" to v.signers.joinToString(";"))
        },
    )

    private fun sessionWs(session: String) =
        sessions.resolve(session)?.let { workspaces.open(it) }
            ?: throw NoSuchElementException("unknown session")

    /** Output names are bare file names, output/-prefixed, or absolute paths
     * inside the output dir — never traversals. */
    private fun guardedOutput(dir: java.io.File, name: String): java.io.File {
        require(name.isNotEmpty() && '\u0000' !in name) { "bad file name" }
        val stripped = name.removePrefix("output/").removePrefix("./")
        if (java.io.File(name).isAbsolute) {
            val base = dir.canonicalFile
            val target = java.io.File(name).canonicalFile
            require(target != base && target.path.startsWith(base.path + "/")) {
                "outside output dir"
            }
            require('/' !in target.name && target.name.isNotEmpty()) { "bad file name" }
            return target
        }
        require('/' !in stripped && '\\' !in stripped) { "bad file name" }
        require(stripped != "." && stripped != ".." && !stripped.startsWith(".")) {
            "bad file name"
        }
        return java.io.File(dir, stripped)
    }

    private data class DevKey(
        val storeFile: java.io.File,
        val storePassword: String,
        val alias: String,
        val keyPassword: String,
    )

    /**
     * Dev signing key, staged by the agent in the MCP folder
     * (`key.properties` + the keystore it points at). Nothing secret ships
     * inside the APK; absence reports UNSUPPORTED with placement guidance.
     * Tip: copy the project's own release.jks + key.properties template
     * into the MCP folder (storeFile is relative to that folder).
     */
    private fun loadDevKey(): DevKey {
        val root = com.obsidian.apkeditor.system.FileScope.default().rootDir()
        val propsFile = java.io.File(root, "key.properties")
        if (propsFile.isFile) {
            val props = java.util.Properties()
            propsFile.inputStream().use { props.load(it) }
            val store = java.io.File(root,
                props.getProperty("storeFile")?.takeIf { it.isNotEmpty() } ?: "release.jks")
            if (!store.isFile) {
                throw UnsupportedOperationException("keystore missing next to key.properties")
            }
            return devKeyOf(store, props)
        }
        return bundledDevKey() ?: throw UnsupportedOperationException(
            "no signing key: copy release.jks + key.properties into ${root.path}/ " +
                "(storeFile inside key.properties is relative to that folder)")
    }

    /** The project's shipped dev keystore, packaged as assets/devkey/ at build time. */
    private fun bundledDevKey(): DevKey? = runCatching {
        val dir = java.io.File(app.cacheDir, "devkey").apply { mkdirs() }
        val props = java.util.Properties()
        app.assets.open("devkey/key.properties").use { props.load(it) }
        val store = java.io.File(dir, "release.jks")
        app.assets.open("devkey/release.jks").use { ins ->
            store.outputStream().use { ins.copyTo(it) }
        }
        devKeyOf(store, props)
    }.getOrNull()

    private fun devKeyOf(store: java.io.File, props: java.util.Properties) = DevKey(
        storeFile = store,
        storePassword = props.getProperty("storePassword").orEmpty(),
        alias = props.getProperty("keyAlias")?.takeIf { it.isNotEmpty() } ?: "obsidian",
        keyPassword = props.getProperty("keyPassword").orEmpty(),
    )

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
