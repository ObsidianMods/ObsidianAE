package apktools.apk;

import apktools.ApkException;
import apktools.xml.AxmlDecoder;
import apktools.xml.AxmlDocument;
import apktools.xml.AxmlElement;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Split-APK bundle inspection ({@code .apks}, {@code .xapk},
 * {@code .apkm}): lists the nested APKs and identifies the base APK.
 *
 * <p>Detection follows {@code PackageParser}: a cluster is one base APK
 * (manifest with a {@code null} split name) plus splits carrying unique
 * {@code android:split} names. Manifests are read with a bounded nested
 * scan — only the first entries of each nested APK are inflated, so a
 * 400MB base never costs 400MB. Entries whose manifest can't be reached
 * cheaply keep {@link Split#manifestChecked} false and fall back to the
 * bundletool naming rule ({@code base.apk}).
 */
public final class SplitApk {

    /** One nested APK. */
    public static final class Split {
        /** Path inside the bundle (e.g. {@code base.apk}). */
        public final String path;
        /** Manifest split name; "" for the base, null when unknown. */
        public final String splitName;
        /** True when identified as the base APK. */
        public final boolean base;
        /** False when the manifest was unreachable (name rule used). */
        public final boolean manifestChecked;

        Split(String path, String splitName, boolean base,
              boolean manifestChecked) {
            this.path = path;
            this.splitName = splitName;
            this.base = base;
            this.manifestChecked = manifestChecked;
        }

        @Override
        public String toString() {
            return path + (base ? " [base]"
                    : splitName != null ? " [split " + splitName + "]"
                    : " [unknown]");
        }
    }

    /** Max nested APKs examined (bundles are small; cap is a guard). */
    private static final int MAX_SPLITS = 128;
    /** Inner entries scanned per nested APK looking for the manifest. */
    private static final int MAX_INNER_ENTRIES = 16;
    /** Inflated bytes scanned per nested APK (manifests sit up front). */
    private static final long MAX_INNER_BYTES = 4L * 1024 * 1024;

    private final List<Split> splits;

    private SplitApk(List<Split> splits) {
        this.splits = splits;
    }

    public List<Split> splits() {
        return Collections.unmodifiableList(splits);
    }

    public boolean isEmpty() {
        return splits.isEmpty();
    }

    /** The base APK: manifest-confirmed first, name rule second. */
    public Split base() {
        for (Split s : splits) {
            if (s.base && s.manifestChecked) return s;
        }
        for (Split s : splits) {
            if (s.base) return s;
        }
        return null;
    }

    /** Split (non-base) entries only. */
    public List<Split> splitsOnly() {
        List<Split> out = new ArrayList<>();
        for (Split s : splits) {
            if (!s.base) out.add(s);
        }
        return out;
    }

    public static SplitApk inspect(File bundle) {
        return inspect(ApkReader.open(bundle));
    }

    static SplitApk inspect(ApkReader zip) {
        List<String> inners = new ArrayList<>();
        for (ApkReader.Entry e : zip.entries()) {
            if (e.isDirectory()) continue;
            String n = e.name;
            if (n.toLowerCase().endsWith(".apk")) inners.add(n);
            if (inners.size() >= MAX_SPLITS) break;
        }
        List<Split> out = new ArrayList<>();
        for (String path : inners) {
            out.add(inspectOne(zip, path));
        }
        // Exactly one nested APK with no split name is a monolithic
        // APK in a wrapper, not a split bundle — still report it.
        return new SplitApk(out);
    }

    private static Split inspectOne(ApkReader zip, String path) {
        String leaf = leafName(path);
        boolean nameSaysBase = leaf.equalsIgnoreCase("base.apk");
        String splitName = null;
        boolean checked = false;
        try {
            splitName = readSplitName(zip, path);
            checked = true;
        } catch (Exception ignored) {
            // Manifest unreachable cheaply: name rule decides.
        }
        boolean base;
        if (checked) {
            base = splitName == null || splitName.isEmpty();
        } else {
            base = nameSaysBase;
        }
        return new Split(path, splitName, base, checked);
    }

    /**
     * Manifest split name, or null when the manifest has none (base).
     * Throws when the manifest can't be reached within the scan budget.
     */
    private static String readSplitName(ApkReader zip, String path)
            throws Exception {
        InputStream raw = zip.openRawStream(path);
        try {
            ZipInputStream inner = new ZipInputStream(raw);
            long budget = MAX_INNER_BYTES;
            for (int i = 0; i < MAX_INNER_ENTRIES; i++) {
                ZipEntry ze;
                try {
                    ze = inner.getNextEntry();
                } catch (Exception e) {
                    throw ApkException.badChunk("inner zip " + path, 0);
                }
                if (ze == null) break;
                String n = ze.getName();
                boolean manifest =
                        n.equals("AndroidManifest.xml");
                if (!manifest) {
                    // Skip entry bodies against the budget.
                    budget -= skipBounded(inner, budget);
                    if (budget <= 0) break;
                    continue;
                }
                byte[] manifestBytes = readBounded(inner, budget);
                AxmlDocument doc;
                try {
                    doc = AxmlDecoder.decode(manifestBytes);
                } catch (ApkException e) {
                    // Plain-text manifest (uncompiled inner)?
                    return null;
                }
                AxmlElement root = doc.root;
                if (root == null || !root.name.equals("manifest")) {
                    return null;
                }
                return splitAttr(root);
            }
            throw ApkException.notFound(
                    "manifest in budget for " + path);
        } finally {
            try {
                raw.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Raw {@code android:split} value, or null when absent. */
    private static String splitAttr(AxmlElement manifest) {
        for (apktools.xml.AxmlAttribute a : manifest.attributes) {
            if (a.name.equals("split")
                    && (a.namespace.isEmpty() || a.namespace.equals(
                    apktools.xml.Axml.NS_ANDROID))) {
                return a.rawValue;
            }
        }
        return null;
    }

    private static String leafName(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? path : path.substring(i + 1);
    }

    private static long skipBounded(InputStream in, long budget)
            throws Exception {
        byte[] buf = new byte[8192];
        long skipped = 0;
        while (skipped < budget) {
            int n = in.read(buf, 0,
                    (int) Math.min(buf.length, budget - skipped));
            if (n < 0) break;
            skipped += n;
        }
        return skipped;
    }

    private static byte[] readBounded(InputStream in, long budget)
            throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long left = Math.min(budget, MAX_INNER_BYTES);
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            bos.write(buf, 0, n);
            left -= n;
        }
        return bos.toByteArray();
    }
}
