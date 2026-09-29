package apktools.apk;

import com.android.apksig.ApkVerifier;

import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Real signature verification via the apksig-android fork
 * (MuntashirAkon, Android port of AOSP apksig — public API only).
 *
 * <p>Reports per-scheme states: v1 (JAR), v2, v3, v3.1 and v4. v4 needs its
 * detached {@code .idsig} next to the APK; without it v4 simply reports
 * false (not an error). Every failure path returns an unverified result
 * with machine-readable issue strings — never throws on untrusted input.
 */
public final class ApkSignatures {

    public final boolean verified;
    public final boolean v1;
    public final boolean v2;
    public final boolean v3;
    public final boolean v31;
    public final boolean v4;
    public final List<String> errors;
    public final List<String> warnings;
    /** SHA-256 fingerprints of signer certificates, or empty. */
    public final List<String> signerSha256;

    private ApkSignatures(boolean verified, boolean v1, boolean v2,
                          boolean v3, boolean v31, boolean v4,
                          List<String> errors, List<String> warnings,
                          List<String> signerSha256) {
        this.verified = verified;
        this.v1 = v1;
        this.v2 = v2;
        this.v3 = v3;
        this.v31 = v31;
        this.v4 = v4;
        this.errors = errors;
        this.warnings = warnings;
        this.signerSha256 = signerSha256;
    }

    /** Unverified result with a single explanatory error (e.g. no on-disk file). */
    public static ApkSignatures unverified(String reason) {
        return new ApkSignatures(false, false, false, false, false, false,
                Collections.singletonList(reason),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    public static ApkSignatures verify(File apk) {        try {
            ApkVerifier.Result r = new ApkVerifier.Builder(apk).build().verify();
            List<String> errors = new ArrayList<>();
            for (Object issue : r.getErrors()) errors.add(String.valueOf(issue));
            List<String> warnings = new ArrayList<>();
            for (Object issue : r.getWarnings()) warnings.add(String.valueOf(issue));
            List<String> certs = new ArrayList<>();
            try {
                for (X509Certificate c : r.getSignerCertificates()) {
                    certs.add(sha256(c.getEncoded()));
                }
            } catch (Exception ignored) {
                // Signer certs unavailable (e.g. unverified) — fingerprints stay empty.
            }
            return new ApkSignatures(
                    r.isVerified(),
                    r.isVerifiedUsingV1Scheme(),
                    r.isVerifiedUsingV2Scheme(),
                    r.isVerifiedUsingV3Scheme(),
                    r.isVerifiedUsingV31Scheme(),
                    r.isVerifiedUsingV4Scheme(),
                    Collections.unmodifiableList(errors),
                    Collections.unmodifiableList(warnings),
                    Collections.unmodifiableList(certs));
        } catch (Throwable t) {
            return new ApkSignatures(false, false, false, false, false, false,
                    Collections.singletonList(
                            t.getClass().getSimpleName() + ": " + t.getMessage()),
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList());
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.US, "%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
