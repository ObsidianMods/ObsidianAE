package com.obsidian.apkeditor.work

/** Structural smali validation (no assembler dependency). */
object SmaliCheck {

    data class Verdict(val ok: Boolean, val errors: List<String>)

    fun validate(smali: String): Verdict {
        if (smali.toByteArray().size > 2 * 1024 * 1024) {
            return Verdict(false, listOf("too large"))
        }
        val errors = ArrayList<String>()
        if (!smali.contains(".class ")) errors.add("missing .class")
        if (!smali.contains(".super ")) errors.add("missing .super")
        var depth = 0
        for (line in smali.lineSequence()) {
            val t = line.trim()
            if (t.startsWith(".method ")) depth++
            else if (t.startsWith(".end method")) {
                depth--
                if (depth < 0) {
                    errors.add("unbalanced .method/.end method")
                    break
                }
            }
        }
        if (depth != 0) errors.add("unbalanced .method/.end method")
        return Verdict(errors.isEmpty(), errors.take(10))
    }
}
