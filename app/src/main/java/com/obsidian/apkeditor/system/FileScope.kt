package com.obsidian.apkeditor.system

import java.io.File

/**
 * Confines tool file access to the MCP root. One canonical-path guard
 * (the reference duplicated this check in two classes with drift).
 */
class FileScope(root: File = StorageDirs.mcpRoot()) {

    private val root: File = root.absoluteFile

    fun rootDir(): File = root

    /** Resolves [name] inside the root. Throws [ScopeViolation] on escape. */
    @Throws(ScopeViolation::class)
    fun resolve(name: String): File {
        require(name.isNotEmpty()) { "empty path" }
        require('\u0000' !in name) { "NUL in path" }
        val clean = name.replace('\\', '/').trim('/')
        require(clean.isNotEmpty() && clean != "." ) { "empty path" }
        val target = File(root, clean).canonicalFile
        if (target != root.canonicalFile && !target.path.startsWith(root.canonicalPath + "/")) {
            throw ScopeViolation("outside scope: $name")
        }
        return target
    }

    class ScopeViolation(message: String) : IllegalArgumentException(message)

    companion object {
        fun default(): FileScope = FileScope(StorageDirs.mcpRoot())
    }
}
