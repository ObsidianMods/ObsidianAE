package com.obsidian.apkeditor.tools.packs

import com.obsidian.apkeditor.system.FileScope
import com.obsidian.apkeditor.tools.ArgSpec
import com.obsidian.apkeditor.tools.Capability
import com.obsidian.apkeditor.tools.ToolContext
import com.obsidian.apkeditor.tools.ToolDefinition
import com.obsidian.apkeditor.tools.ToolErrorCode
import com.obsidian.apkeditor.tools.ToolRegistry
import com.obsidian.apkeditor.tools.ToolResult
import com.obsidian.apkeditor.tools.boundedInt
import com.obsidian.apkeditor.tools.need
import com.obsidian.apkeditor.tools.opt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mini-shell over the granted scope. Pure Kotlin builtins — no JNI, no
 * `Runtime.exec`, no root: `ls/find/grep/stat/head/tail/wc/du` implemented
 * on java.io over [FileScope], so every path is grant-checked and every
 * output is bounded before it reaches the transport.
 */
class ShellTools(
    private val scope: FileScope = FileScope.default(),
    private val onNeedsGrant: (String) -> Unit = {},
) {

    fun registerAll(r: ToolRegistry) {
        r.register(ToolDefinition(
            "ae_shell", "Mini shell", SHELL_HELP,
            listOf(ArgSpec("cmd", true), ArgSpec("maxLines", false)),
            Capability.FILE_READ,
            { params ->
                try {
                    run(params)
                } catch (e: FileScope.NeedsGrant) {
                    runCatching { onNeedsGrant(e.path) }
                    ToolResult.Err(
                        ToolErrorCode.NEEDS_GRANT, e.message ?: "folder access needed",
                        "A grant request was raised in the app (notification if backgrounded). " +
                            "Ask the user to grant the folder, then retry the call.",
                    )
                }
            },
        ))
    }

    private suspend fun ToolContext.run(p: Map<String, String>): ToolResult {
        val argv = split(p.need("cmd"))
        require(argv.isNotEmpty()) { "empty command (try: help)" }
        val maxLines = p.boundedInt("maxLines", 120, 1, 400)
        val out = withContext(Dispatchers.IO) {
            when (argv[0].lowercase()) {
                "help" -> SHELL_HELP
                "ls" -> ls(argv.drop(1))
                "find" -> find(argv.drop(1))
                "grep" -> grep(argv.drop(1))
                "stat" -> stat(argv.drop(1))
                "head" -> headTail(argv.drop(1), head = true)
                "tail" -> headTail(argv.drop(1), head = false)
                "wc" -> wc(argv.drop(1))
                "du" -> du(argv.drop(1))
                else -> throw IllegalArgumentException(
                    "unknown command '${argv[0]}' (try: help)")
            }
        }
        val lines = out.lines()
        val clipped = lines.size > maxLines
        return ok(
            "command" to argv[0].lowercase(),
            "lines" to minOf(lines.size, maxLines).toString(),
            "truncated" to clipped.toString(),
            "output" to lines.take(maxLines).joinToString("\n"),
        )
    }

    // ── commands (IO thread) ──

    private fun ls(args: List<String>): String {
        var recursive = false
        var path = ""
        for (a in args) {
            if (a == "-R" || a == "--recursive") recursive = true
            else if (a.startsWith("-")) throw IllegalArgumentException("ls: bad flag $a")
            else if (path.isEmpty()) path = a
            else throw IllegalArgumentException("ls: one path only")
        }
        val root = if (path.isEmpty()) scope.rootDir() else scope.resolve(path)
        require(root.isDirectory || root.isFile) { "ls: not found: ${path.ifEmpty { "." }}" }
        val files: List<File> = if (!recursive) {
            if (root.isFile) listOf(root) else root.listFiles().orEmpty().sortedBy { it.name }
        } else {
            root.walkTopDown().take(1500).toList()
        }
        return files.take(300).joinToString("\n") { f ->
            val rel = runCatching { f.relativeTo(scopeRootFor(f)).path }.getOrDefault(f.name)
            (if (f.isDirectory) "d/ " else "f/ ") + rel +
                (if (f.isFile) " (${f.length()}b)" else "")
        }.ifEmpty { "(empty)" }
    }

    private fun find(args: List<String>): String {
        var path = ""
        var query = ""
        for (a in args) {
            if (a.startsWith("-")) throw IllegalArgumentException("find: no flags supported")
            else if (path.isEmpty()) path = a
            else if (query.isEmpty()) query = a
            else throw IllegalArgumentException("find: usage: find [path] [name-substring]")
        }
        val root = if (path.isEmpty()) scope.rootDir() else scope.resolve(path)
        require(root.isDirectory) { "find: not a directory: ${path.ifEmpty { "." }}" }
        return root.walkTopDown().take(2000)
            .filter { it.isFile && (query.isEmpty() || query in it.name) }
            .take(200)
            .map { runCatching { it.relativeTo(scopeRootFor(it)).path }.getOrDefault(it.name) }
            .joinToString("\n").ifEmpty { "(no matches)" }
    }

    private fun grep(args: List<String>): String {
        var ignoreCase = false
        val rest = mutableListOf<String>()
        for (a in args) {
            if (a == "-i") ignoreCase = true
            else if (a.startsWith("-")) throw IllegalArgumentException("grep: bad flag $a (only -i)")
            else rest.add(a)
        }
        require(rest.isNotEmpty()) { "grep: usage: grep [-i] <pattern> [path]" }
        val pattern = rest[0]
        val root = if (rest.size > 1) scope.resolve(rest[1]) else scope.rootDir()
        val rx = runCatching {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }.getOrNull() ?: throw IllegalArgumentException("grep: bad regex")
        val files: List<File> = if (root.isFile) listOf(root)
        else {
            require(root.isDirectory) { "grep: not found: ${rest.getOrElse(1) { "." }}" }
            root.walkTopDown().take(500).filter { it.isFile }.toList()
        }
        val hits = mutableListOf<String>()
        outer@ for (f in files) {
            if (f.length() > MAX_GREP_FILE) continue
            if (isBinary(f)) continue
            var lineNo = 0
            for (raw in f.bufferedReader().lineSequence()) {
                lineNo++
                if (raw.length > 2000 || !rx.containsMatchIn(raw)) continue
                val rel = runCatching { f.relativeTo(scopeRootFor(f)).path }.getOrDefault(f.name)
                hits.add("$rel:$lineNo:${raw.take(300)}")
                if (hits.size >= 100) break@outer
            }
        }
        return hits.joinToString("\n").ifEmpty { "(no matches)" }
    }

    private fun stat(args: List<String>): String {
        require(args.size == 1 && !args[0].startsWith("-")) { "stat: usage: stat <path>" }
        val f = scope.resolve(args[0])
        require(f.exists()) { "stat: not found: ${args[0]}" }
        val rel = runCatching { f.relativeTo(scopeRootFor(f)).path }.getOrDefault(f.path)
        return "path=$rel\ntype=${if (f.isDirectory) "dir" else "file"}\n" +
            "size=${f.length()}\nmtime=${f.lastModified()}"
    }

    private fun headTail(args: List<String>, head: Boolean): String {
        var n = 20
        var path = ""
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "-n" && i + 1 < args.size) {
                n = args[i + 1].toIntOrNull()?.coerceIn(1, 200)
                    ?: throw IllegalArgumentException("head/tail: bad -n")
                i += 2
            } else if (a.startsWith("-")) {
                throw IllegalArgumentException("head/tail: usage: ${if (head) "head" else "tail"} [-n N] <path>")
            } else if (path.isEmpty()) {
                path = a; i++
            } else throw IllegalArgumentException("head/tail: one path only")
        }
        require(path.isNotEmpty()) { "head/tail: usage: ${if (head) "head" else "tail"} [-n N] <path>" }
        val f = scope.resolve(path)
        require(f.isFile && f.length() <= MAX_READ) { "head/tail: too large or missing" }
        val lines = f.bufferedReader().lineSequence().map { it.take(2000) }.toList()
        val picked = if (head) lines.take(n) else lines.takeLast(n)
        return picked.joinToString("\n").ifEmpty { "(empty)" }
    }

    private fun wc(args: List<String>): String {
        require(args.size == 1 && !args[0].startsWith("-")) { "wc: usage: wc <path>" }
        val f = scope.resolve(args[0])
        require(f.isFile && f.length() <= MAX_READ) { "wc: too large or missing" }
        var lines = 0L
        var words = 0L
        var chars = 0L
        for (raw in f.bufferedReader().lineSequence()) {
            lines++
            chars += raw.length + 1
            words += raw.split(WHITESPACE).count { it.isNotEmpty() }
        }
        return "lines=$lines words=$words chars=$chars bytes=${f.length()}"
    }

    private fun du(args: List<String>): String {
        require(args.size <= 1) { "du: usage: du [path]" }
        val root = if (args.isEmpty()) scope.rootDir() else scope.resolve(args[0])
        require(root.exists()) { "du: not found" }
        var bytes = 0L
        var files = 0L
        for (f in root.walkTopDown().take(3000)) {
            if (f.isFile) {
                bytes += f.length()
                files++
            }
        }
        return "bytes=$bytes files=$files"
    }

    // ── helpers ──

    /** Nearest granted root containing [f] (for display-relative paths). */
    private fun scopeRootFor(f: File): File {
        val canon = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)
        return scope.allRoots()
            .map { runCatching { it.dir.canonicalFile }.getOrDefault(it.dir) }
            .sortedByDescending { it.path.length }
            .find { canon == it || canon.path.startsWith(it.path + "/") }
            ?: scope.rootDir()
    }

    private fun isBinary(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val buf = ByteArray(4096)
            val n = ins.read(buf)
            if (n <= 0) return false
            for (i in 0 until n) if (buf[i] == 0.toByte()) return true
            false
        }
    }.getOrDefault(true)

    /** Quote-aware split (single/double quotes, backslash escapes). */
    private fun split(cmd: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote = ' '
        var esc = false
        var has = false
        for (c in cmd) {
            when {
                esc -> { cur.append(c); esc = false; has = true }
                c == '\\' && quote == ' ' -> { esc = true; has = true }
                (c == '\'' || c == '"') && quote == ' ' -> { quote = c; has = true }
                c == quote -> quote = ' '
                quote != ' ' -> { cur.append(c); has = true }
                c.isWhitespace() -> { if (has) { out.add(cur.toString()); cur.clear(); has = false } }
                else -> { cur.append(c); has = true }
            }
        }
        if (has) out.add(cur.toString())
        return out
    }

    private fun ToolContext.ok(vararg pairs: Pair<String, String>) =
        ToolResult.Ok(pairs.associate { it.first to it.second.capped() })

    companion object {
        private const val MAX_READ = 8 * 1024 * 1024L
        private const val MAX_GREP_FILE = 2 * 1024 * 1024L
        private val WHITESPACE = Regex("\\s+")
        private const val SHELL_HELP =
            "Mini shell (granted scope only). Commands: " +
                "ls [-R] [path] | find [path] [name-substring] | grep [-i] <pattern> [path] | " +
                "stat <path> | head [-n N] <path> | tail [-n N] <path> | wc <path> | du [path]. " +
                "Extra folders as @Alias/path."
    }
}
