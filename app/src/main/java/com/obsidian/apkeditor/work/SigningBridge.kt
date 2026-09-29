package com.obsidian.apkeditor.work

import apktools.ApkTools
import apktools.apk.ApkSignatures
import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Real signing over the shipped dev keystore (AOSP ApkSigner API via the
 * apksig-android fork) + verification via the ported ApkSignatures.
 * Blocking; callers use Dispatchers.IO.
 */
object SigningBridge {

    data class Verification(
        val verified: Boolean,
        val v1: Boolean,
        val v2: Boolean,
        val errors: List<String>,
        val signers: List<String>,
    )

    fun verify(apk: File): Verification {
        val sig = ApkSignatures.verify(apk)
        return Verification(
            verified = sig.verified,
            v1 = sig.v1,
            v2 = sig.v2,
            errors = sig.errors.toList(),
            signers = sig.signerSha256.toList(),
        )
    }

    fun sign(
        input: File,
        output: File,
        keystoreFile: File,
        storePassword: String,
        alias: String,
        keyPassword: String,
        v1: Boolean = true,
        v2: Boolean = true,
    ) {
        require(input.isFile) { "missing input" }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        keystoreFile.inputStream().use { ins -> ks.load(ins, storePassword.toCharArray()) }
        val key = ks.getKey(alias, keyPassword.toCharArray()) as? PrivateKey
            ?: throw IllegalStateException("no key for alias")
        val chain = ks.getCertificateChain(alias)
            ?.map { it as X509Certificate }
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("no cert chain")
        val config = ApkSigner.SignerConfig.Builder(alias, key, chain).build()
        output.parentFile?.mkdirs()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(v2)
            .build()
            .sign()
    }

    /** v1 META-INF presence scan (fast pre-check, not proof). */
    fun hasV1(apk: File): Boolean {
        return try {
            java.util.zip.ZipFile(apk).use { zip ->
                zip.entries().asSequence().any {
                    it.name.startsWith("META-INF/") &&
                        (it.name.endsWith(".SF") || it.name.endsWith(".RSA"))
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
