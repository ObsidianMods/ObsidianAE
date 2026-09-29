package com.obsidian.apkeditor.work

/** Hard bounds. Every read path checks size BEFORE materializing bytes. */
object WorkLimits {
    const val TEXT_PREVIEW_BYTES = 4 * 1024 * 1024
    const val STAGE_BYTES = 64L * 1024 * 1024
    const val ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRIES = 65_000
    const val PAGE_LIMIT = 500
    const val VALUE_CHARS = 20_000
}
