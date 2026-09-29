package com.obsidian.apkeditor.work

import android.content.Context
import android.content.pm.PackageManager
import java.util.zip.ZipFile

/** Lightweight APK metadata. Deep ARSC/DEX parsing is capability-gated (see tools). */
data class ApkMeta(
    val fileName: String,
    val apkSize: Long,
    val entryCount: Int,
    val hasDex: Boolean,
    val hasArsc: Boolean,
    val hasNativeLibs: Boolean,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

/** Reads install metadata via PackageManager + structural flags via Zip. */
class ApkInspector(private val app: Context) {

    fun inspect(ws: Workspace): ApkMeta {
        val file = ws.original()
        var dex = false
        var arsc = false
        var native = false
        var count = 0
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                count++
                if (count > WorkLimits.MAX_ENTRIES) break
                when {
                    name.endsWith(".dex") -> dex = true
                    name == "resources.arsc" -> arsc = true
                    name.startsWith("lib/") && name.endsWith(".so") -> native = true
                }
            }
        }
        val pm = app.packageManager
        val info = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_META_DATA)
        }.getOrNull()
        return ApkMeta(
            fileName = file.name,
            apkSize = file.length(),
            entryCount = count,
            hasDex = dex,
            hasArsc = arsc,
            hasNativeLibs = native,
            packageName = info?.packageName.orEmpty(),
            versionName = info?.versionName.orEmpty(),
            versionCode = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 28) info?.longVersionCode ?: 0
                else @Suppress("DEPRECATION") info?.versionCode?.toLong() ?: 0
            }.getOrDefault(0),
        )
    }
}
