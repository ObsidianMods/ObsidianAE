package apktools.apk;

import apktools.ApkException;
import apktools.ResValue;
import apktools.arsc.ArscEntry;
import apktools.arsc.ArscFile;
import apktools.arsc.ArscPackage;
import apktools.xml.AxmlAttribute;
import apktools.xml.AxmlDecoder;
import apktools.xml.AxmlDocument;
import apktools.xml.AxmlElement;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Fast APK metadata: manifest, DEX inventory, resource stats, icons.
 *
 * <p>Cost model: {@link #load} parses the ZIP central directory and the
 * manifest only. {@code resources.arsc} is loaded on first use
 * ({@link #arsc()}, icon/label getters, resource stats) and entry values
 * decode lazily inside the table. Nothing is ever fully inflated except
 * the single file being inspected.
 *
 * <p>All getters return sentinels instead of throwing on absent data:
 * {@code -1} for missing ints, {@code null}/empty for missing
 * strings/lists, {@link ApkIcon.Kind#MISSING} for unresolvable icons.
 */
public final class ApkInfo {

    /** One {@code <uses-feature>} entry. */
    public static final class Feature {
        public final String name; // may be null for glEsVersion-only
        public final int glEsVersion; // 0 when absent
        public final boolean required; // default true

        Feature(String name, int glEsVersion, boolean required) {
            this.name = name;
            this.glEsVersion = glEsVersion;
            this.required = required;
        }

        @Override
        public String toString() {
            if (name != null) return name + (required ? "" : " (optional)");
            return "glEsVersion=0x" + Integer.toHexString(glEsVersion);
        }
    }

    private final ApkReader zip;
    private final long apkSize;
    private final AxmlDocument manifest;

    // Manifest fields (eager).
    private String packageName = "";
    private int versionCode;
    private int versionCodeMajor;
    private String versionName;
    private int compileSdk = -1;
    private String compileSdkCodename;
    private int minSdk = -1;
    private int targetSdk = -1;
    private int maxSdk = -1;
    private final List<String> permissions = new ArrayList<>();
    private final List<Feature> features = new ArrayList<>();
    private final List<String> declaredPermissions = new ArrayList<>();
    private String appLabel;
    private int appLabelRef;
    private int iconId;
    private int roundIconId;
    private boolean debuggable;
    private boolean allowBackup = true;
    private boolean largeHeap;
    private boolean supportsRtl;
    private boolean extractNativeLibs;
    private boolean cleartextTraffic;
    private int activities;
    private int activityAliases;
    private int services;
    private int receivers;
    private int providers;
    private int metaData;
    private final List<String> providerAuthorities = new ArrayList<>();

    // Lazy state.
    private boolean arscLoaded;
    private ArscFile arsc;
    private ApkIcon icon;
    private ApkIcon roundIcon;
    private Boolean v1, v2, v3;
    private ApkSignatures signatures;

    private ApkInfo(ApkReader zip, long apkSize, AxmlDocument manifest) {
        this.zip = zip;
        this.apkSize = apkSize;
        this.manifest = manifest;
        parseManifest();
    }

    // -- load --------------------------------------------------------------

    public static ApkInfo load(File apk) {
        ApkReader zip = ApkReader.open(apk);
        return new ApkInfo(zip, apk.length(), manifestOf(zip));
    }

    public static ApkInfo load(String path) {
        return load(new File(path));
    }

    public static ApkInfo load(byte[] apk, String name) {
        ApkReader zip = ApkReader.open(apk, name);
        return new ApkInfo(zip, apk.length, manifestOf(zip));
    }

    private static AxmlDocument manifestOf(ApkReader zip) {
        if (!zip.has("AndroidManifest.xml")) {
            throw ApkException.notFound("AndroidManifest.xml");
        }
        return AxmlDecoder.decode(zip.readBytes("AndroidManifest.xml"));
    }

    // -- manifest ----------------------------------------------------------

    public AxmlDocument manifest() {
        return manifest;
    }

    public String manifestString() {
        return manifest.toXmlString();
    }

    /**
     * Manifest rendered with resource names instead of hex ids
     * ({@code @android:versionCode}, {@code @pkg:string/name}).
     */
    public String manifestStringResolved() {
        return manifest.toXmlString(ids());
    }

    /**
     * Id resolver backed by this APK's table (framework ids included).
     * Pass it to decoders/renderers to show names instead of hex.
     */
    public apktools.xml.IdResolver ids() {
        return new ArscIdResolver(arsc(), packageName);
    }

    public String packageName() {
        return packageName;
    }

    /** Low 32 bits of versionCode. */
    public int versionCode() {
        return versionCode;
    }

    public int versionCodeMajor() {
        return versionCodeMajor;
    }

    /** Full 64-bit versionCode (major << 32 | code). */
    public long longVersionCode() {
        return ((long) versionCodeMajor << 32) | (versionCode & 0xFFFFFFFFL);
    }

    public String versionName() {
        return versionName;
    }

    public int compileSdk() {
        return compileSdk;
    }

    public String compileSdkCodename() {
        return compileSdkCodename;
    }

    public int minSdk() {
        return minSdk;
    }

    public int targetSdk() {
        return targetSdk;
    }

    public int maxSdk() {
        return maxSdk;
    }

    public List<String> permissions() {
        return Collections.unmodifiableList(permissions);
    }

    public List<Feature> features() {
        return Collections.unmodifiableList(features);
    }

    public List<String> declaredPermissions() {
        return Collections.unmodifiableList(declaredPermissions);
    }

    /**
     * Application label: literal wins, otherwise resolved through the
     * table on first call (null when neither exists).
     */
    public String appLabel() {
        if (appLabel == null && appLabelRef != 0) {
            ArscFile t = arsc();
            if (t != null) {
                ArscEntry e = t.resolve(appLabelRef);
                if (e != null && !e.isComplex()
                        && e.valueType() == ResValue.TYPE_STRING) {
                    appLabel = e.stringValue();
                }
            }
        }
        return appLabel;
    }

    public int appLabelRef() {
        return appLabelRef;
    }

    public int iconId() {
        return iconId;
    }

    public int roundIconId() {
        return roundIconId;
    }

    public boolean debuggable() {
        return debuggable;
    }

    public boolean allowBackup() {
        return allowBackup;
    }

    public boolean largeHeap() {
        return largeHeap;
    }

    public boolean supportsRtl() {
        return supportsRtl;
    }

    public boolean extractNativeLibs() {
        return extractNativeLibs;
    }

    public boolean usesCleartextTraffic() {
        return cleartextTraffic;
    }

    public int activityCount() {
        return activities;
    }

    public int activityAliasCount() {
        return activityAliases;
    }

    public int serviceCount() {
        return services;
    }

    public int receiverCount() {
        return receivers;
    }

    public int providerCount() {
        return providers;
    }

    public int metaDataCount() {
        return metaData;
    }

    public List<String> providerAuthorities() {
        return Collections.unmodifiableList(providerAuthorities);
    }

    // -- package inventory (central directory only) ------------------------

    public long apkSize() {
        return apkSize;
    }

    public int zipEntryCount() {
        return zip.entries().size();
    }

    public List<String> dexNames() {
        return zip.dexNames();
    }

    public int dexCount() {
        return zip.dexNames().size();
    }

    public ApkReader zip() {
        return zip;
    }

    // -- resources (lazy) --------------------------------------------------

    /** The table, or null when the APK has no resources.arsc. */
    public synchronized ArscFile arsc() {
        if (!arscLoaded) {
            arscLoaded = true;
            if (zip.has("resources.arsc")) {
                arsc = ArscFile.open(zip.readBytes("resources.arsc"));
            }
        }
        return arsc;
    }

    public boolean hasResources() {
        return arsc() != null;
    }

    /** Live resource entries, or -1 without a table. */
    public int resourceCount() {
        ArscFile t = arsc();
        return t == null ? -1 : t.totalEntries();
    }

    public int resourceTypeCount() {
        ArscFile t = arsc();
        return t == null ? -1 : t.typeChunkCount();
    }

    public Set<String> resourceLocales() {
        ArscFile t = arsc();
        return t == null
                ? Collections.<String>emptySet() : t.locales();
    }

    // -- icons (lazy) ------------------------------------------------------

    /** Primary launcher icon (manifest {@code android:icon}). */
    public synchronized ApkIcon icon() {
        if (icon == null) {
            icon = new IconResolver(zip, arsc()).resolve(iconId);
        }
        return icon;
    }

    /** Round icon when declared, else the primary icon. */
    public synchronized ApkIcon roundIcon() {
        if (roundIcon == null) {
            roundIcon = roundIconId != 0
                    ? new IconResolver(zip, arsc()).resolve(roundIconId)
                    : icon();
        }
        return roundIcon;
    }

    /**
     * Resolves any drawable/mipmap resource id to an icon (widgets,
     * notifications, adaptive-icon previews). Never throws for a bad
     * id — returns {@link ApkIcon.Kind#MISSING} instead.
     */
    public ApkIcon resolveIcon(int resId) {
        return new IconResolver(zip, arsc()).resolve(resId);
    }

    public boolean hasAdaptiveIcon() {
        return icon().kind == ApkIcon.Kind.XML_ADAPTIVE;
    }

    public boolean iconFromXml() {
        ApkIcon.Kind k = icon().kind;
        return k == ApkIcon.Kind.XML_ADAPTIVE
                || k == ApkIcon.Kind.XML_VECTOR
                || k == ApkIcon.Kind.XML_DRAWABLE;
    }

    // -- signatures --------------------------------------------------------

    public boolean hasV1Signature() {
        scanSignatures();
        return v1;
    }

    public boolean hasV2Signature() {
        scanSignatures();
        return v2;
    }

    public boolean hasV3Signature() {
        scanSignatures();
        return v3;
    }

    /**
     * Real cryptographic verification via apksig (v1/v2/v3/v3.1/v4 states
     * plus issues and signer fingerprints). Lazily computed and cached;
     * safe on untrusted input (failures yield an unverified result).
     */
    public synchronized ApkSignatures signatures() {
        if (signatures == null) {
            File src = zip.file();
            signatures = src != null
                    ? ApkSignatures.verify(src)
                    : ApkSignatures.unverified("memory-backed APK has no on-disk file to verify");
        }
        return signatures;
    }

    private synchronized void scanSignatures() {
        if (v1 != null) return;
        boolean jar = false;
        for (ApkReader.Entry e : zip.entries()) {
            String n = e.name;
            if (n.startsWith("META-INF/") && (n.endsWith(".SF")
                    || n.endsWith(".RSA") || n.endsWith(".DSA")
                    || n.endsWith(".EC"))) {
                jar = true;
                break;
            }
        }
        boolean s2 = false, s3 = false;
        try {
            long cdOff = zip.centralDirOffset();
            if (cdOff >= 32) {
                byte[] tail = zip.readRawRange(cdOff - 32, 32);
                if (isSigMagic(tail, 16)) {
                    long blockLen = u64(tail, 8);
                    if (blockLen > 0 && blockLen < cdOff) {
                        long blockStart = cdOff - 32 - blockLen;
                        byte[] head = zip.readRawRange(blockStart,
                                (int) Math.min(blockLen + 8, 64));
                        if (u64(head, 0) == blockLen && blockLen <= 1 << 26) {
                            // Walk pair ids (cap: ids live up front).
                            long p = 8;
                            while (p + 12 <= head.length
                                    && p + 12 <= blockLen + 8) {
                                long pairLen = u64(head, (int) p);
                                long id = u32(head, (int) p + 8);
                                if (id == 0x7109871aL) s2 = true;
                                if (id == 0xf05368c0L
                                        || id == 0x1b93ad61L) s3 = true;
                                if (pairLen < 4 || pairLen > blockLen) break;
                                p += 8 + pairLen;
                            }
                            // Full walk when the window was too small.
                            if (!s2 && !s3 && blockLen + 8 > head.length) {
                                byte[] full = zip.readRawRange(blockStart,
                                        (int) (blockLen + 8));
                                long q = 8;
                                while (q + 12 <= full.length) {
                                    long pairLen = u64(full, (int) q);
                                    long id = u32(full, (int) q + 8);
                                    if (id == 0x7109871aL) s2 = true;
                                    if (id == 0xf05368c0L
                                            || id == 0x1b93ad61L) s3 = true;
                                    if (pairLen < 4 || pairLen > blockLen) break;
                                    q += 8 + pairLen;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Unsigned debug builds and odd packers: report JAR only.
        }
        v1 = jar;
        v2 = s2;
        v3 = s3;
    }

    private static final byte[] SIG_MAGIC = {
        0x41, 0x50, 0x4B, 0x20, 0x53, 0x69, 0x67, 0x20,
        0x42, 0x6C, 0x6F, 0x63, 0x6B, 0x20, 0x34, 0x32
    };

    private static boolean isSigMagic(byte[] b, int off) {
        for (int i = 0; i < 16; i++) {
            if (b[off + i] != SIG_MAGIC[i]) return false;
        }
        return true;
    }

    private static long u64(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24)
                | ((b[o + 4] & 0xFFL) << 32) | ((b[o + 5] & 0xFFL) << 40)
                | ((b[o + 6] & 0xFFL) << 48) | ((b[o + 7] & 0xFFL) << 56);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    // -- manifest walk -------------------------------------------------------

    private static final String NS =
            "http://schemas.android.com/apk/res/android";

    private void parseManifest() {
        AxmlElement root = manifest.root;
        if (root == null || !root.name.equals("manifest")) {
            throw new ApkException(ApkException.Code.XML,
                    "root is not <manifest>");
        }
        packageName = rawOf(root, "", "package", "");
        versionCode = intOf(root, "versionCode", 0);
        versionCodeMajor = intOf(root, "versionCodeMajor", 0);
        versionName = strOf(root, "versionName", null);
        compileSdk = intOf(root, "compileSdkVersion", -1);
        compileSdkCodename = strOf(root, "compileSdkVersionCodename", null);

        for (AxmlElement e : root.elements()) {
            String tag = e.name;
            if (tag.equals("uses-sdk")) {
                minSdk = intOf(e, "minSdkVersion", minSdk);
                targetSdk = intOf(e, "targetSdkVersion", targetSdk);
                maxSdk = intOf(e, "maxSdkVersion", maxSdk);
            } else if (tag.equals("uses-permission")
                    || tag.equals("uses-permission-sdk-23")
                    || tag.equals("uses-permission-sdk-m")) {
                String n = strOf(e, "name", null);
                if (n != null) permissions.add(n);
            } else if (tag.equals("permission")) {
                String n = strOf(e, "name", null);
                if (n != null) declaredPermissions.add(n);
            } else if (tag.equals("uses-feature")) {
                features.add(new Feature(
                        strOf(e, "name", null),
                        intOf(e, "glEsVersion", 0),
                        boolOf(e, "required", true)));
            } else if (tag.equals("application")) {
                parseApplication(e);
            }
        }
    }

    private void parseApplication(AxmlElement app) {
        String label = rawOf(app, NS, "label", null);
        if (label != null) {
            appLabel = label;
        } else {
            AxmlAttribute a = app.attr(NS, "label");
            if (a != null && a.valueType == ResValue.TYPE_REFERENCE) {
                appLabelRef = a.valueData;
            } else if (a != null && a.valueType == ResValue.TYPE_STRING) {
                appLabel = manifest.pool.get(a.valueData);
            }
        }
        iconId = refOf(app, "icon");
        roundIconId = refOf(app, "roundIcon");
        debuggable = boolOf(app, "debuggable", false);
        allowBackup = boolOf(app, "allowBackup", true);
        largeHeap = boolOf(app, "largeHeap", false);
        supportsRtl = boolOf(app, "supportsRtl", false);
        extractNativeLibs = boolOf(app, "extractNativeLibs", false);
        cleartextTraffic = boolOf(app, "usesCleartextTraffic", false);
        for (AxmlElement e : app.elements()) {
            String tag = e.name;
            if (tag.equals("activity")) activities++;
            else if (tag.equals("activity-alias")) activityAliases++;
            else if (tag.equals("service")) services++;
            else if (tag.equals("receiver")) receivers++;
            else if (tag.equals("provider")) {
                providers++;
                String auth = rawOf(e, NS, "authorities", null);
                if (auth == null) auth = rawOf(e, "", "authorities", null);
                if (auth != null) providerAuthorities.add(auth);
            } else if (tag.equals("meta-data")) metaData++;
        }
    }

    private String rawOf(AxmlElement e, String ns, String name, String def) {
        AxmlAttribute a = e.attr(ns, name);
        if (a == null) return def;
        return a.rawValue != null ? a.rawValue : def;
    }

    private String strOf(AxmlElement e, String name, String def) {
        // android:-namespaced first, then bare.
        AxmlAttribute a = e.attr(NS, name);
        if (a == null) a = e.attr("", name);
        if (a == null) return def;
        if (a.rawValue != null) return a.rawValue;
        if (a.valueType == ResValue.TYPE_STRING) {
            return manifest.pool.get(a.valueData);
        }
        return ResValue.toString(a.valueType, a.valueData, manifest.pool);
    }

    private int intOf(AxmlElement e, String name, int def) {
        AxmlAttribute a = e.attr(NS, name);
        if (a == null) a = e.attr("", name);
        if (a == null) return def;
        switch (a.valueType) {
            case ResValue.TYPE_INT_DEC:
            case ResValue.TYPE_INT_HEX:
            case ResValue.TYPE_INT_BOOLEAN:
                return a.valueData;
            default:
                if (a.rawValue != null) {
                    try {
                        return Integer.decode(a.rawValue);
                    } catch (NumberFormatException ignored) {
                        return def;
                    }
                }
                return def;
        }
    }

    private boolean boolOf(AxmlElement e, String name, boolean def) {
        AxmlAttribute a = e.attr(NS, name);
        if (a == null) a = e.attr("", name);
        if (a == null) return def;
        if (a.valueType == ResValue.TYPE_INT_BOOLEAN) return a.valueData != 0;
        if (a.rawValue != null) return Boolean.parseBoolean(a.rawValue);
        return def;
    }

    private int refOf(AxmlElement e, String name) {
        AxmlAttribute a = e.attr(NS, name);
        if (a != null && a.valueType == ResValue.TYPE_REFERENCE) {
            return a.valueData;
        }
        return 0;
    }

    // -- summary ---------------------------------------------------------------

    /** One-line summary for logs and tests. */
    public String summary() {
        return packageName + " " + versionName + " (" + versionCode + ")"
                + " sdk " + minSdk + "->" + targetSdk
                + " dex=" + dexCount() + " res=" + resourceCount()
                + " icon=" + icon();
    }
}
