package com.obsidian.apkeditor.system

import java.io.File

/**
 * Confines tool file access to granted roots: the default MCP root plus
 * user-granted folders (SAF trees resolved to files).
 *
 * Address forms (all agent-facing):
 * - `rel/path` → inside the default root.
 * - `@Alias/rel/path` → inside the granted root with that alias
 *   (case-insensitive; `@Alias` alone addresses the root itself).
 * - `/storage/...` absolute → allowed when it falls inside any granted
 *   root; otherwise [NeedsGrant] naming the top folder to grant.
 *
 * [NeedsGrant] (not [ScopeViolation]) means "ask the user, then retry" —
 * callers map it to a NEEDS_GRANT tool error and raise the grant sheet.
 * `..` escapes stay hard [ScopeViolation] failures.
 */
class FileScope(
    root: File = StorageDirs.mcpRoot(),
    private val extras: () -> List<Root> = { emptyList() },
) {

    data class Root(val alias: String, val dir: File)

    private val defaultRoot: File = root.absoluteFile

    fun rootDir(): File = defaultRoot

    fun allRoots(): List<Root> = listOf(Root("", defaultRoot)) + extras()

    /** Resolves [name] inside the granted roots. Throws [NeedsGrant]/[ScopeViolation]. */
    @Throws(NeedsGrant::class, ScopeViolation::class)
    fun resolve(name: String): File {
        require(name.isNotEmpty()) { "empty path" }
        require('\u0000' !in name) { "NUL in path" }
        val forward = name.replace('\\', '/')
        if (forward.startsWith("@")) {
            val alias = forward.drop(1).substringBefore('/').trim()
            val rest = forward.drop(1).substringAfter('/', "")
            val hit = extras().find { it.alias.equals(alias, ignoreCase = true) }
                ?: throw NeedsGrant(name, "folder '@$alias' is not granted — pick it in Storage access, then retry")
            if (rest.trim().trim('/').isEmpty()) return hit.dir.canonicalFile
            return resolveIn(hit.dir, rest, name)
        }
        if (forward.startsWith("/")) {
            val target = File(forward).canonicalFile
            val hit = allRoots().find { r ->
                val base = r.dir.canonicalFile
                target == base || target.path.startsWith(base.path + "/")
            }
            if (hit != null) return target
            val top = "/" + forward.trim('/').substringBefore('/')
            throw NeedsGrant(name, "outside granted folders — grant '$top' in Storage access, then retry")
        }
        val clean = forward.trim('/')
        // Friendly redirect: a first segment matching a granted alias
        // addresses that root even without the '@' prefix.
        val first = clean.substringBefore('/')
        val aliasHit = extras().find { it.alias.equals(first, ignoreCase = true) }
        if (aliasHit != null) {
            val rest = clean.substringAfter('/', "")
            if ('/' !in clean) return aliasHit.dir.canonicalFile
            return resolveIn(aliasHit.dir, rest, name)
        }
        return resolveIn(defaultRoot, clean, name)
    }

    private fun resolveIn(base: File, clean: String, original: String): File {
        val rel = clean.trim('/').trim()
        require(rel.isNotEmpty() && rel != ".") { "empty path" }
        val target = File(base, rel).canonicalFile
        val canon = base.canonicalFile
        if (target != canon && !target.path.startsWith(canon.path + "/")) {
            throw ScopeViolation("outside scope: $original")
        }
        return target
    }

    class ScopeViolation(message: String) : IllegalArgumentException(message)

    class NeedsGrant(val path: String, message: String) : Exception(message)

    companion object {
        fun default(): FileScope = FileScope(StorageDirs.mcpRoot())
    }
}
