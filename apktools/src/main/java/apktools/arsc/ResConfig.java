package apktools.arsc;

import apktools.BinReader;

/**
 * Read-only view of a {@code ResTable_config} struct, plus parsing of the
 * directory suffixes aapt uses ({@code drawable-hdpi-v4}, ...).
 *
 * <p>Structs evolve across platform releases, so every getter is guarded
 * by the struct size and unknown tails are preserved verbatim by the
 * rebuilder. A config parsed from a path suffix only fills the fields
 * that directory names can express.
 */
public final class ResConfig {

    // Density buckets.
    public static final int DENSITY_DEFAULT = 0;
    public static final int DENSITY_LOW = 120;
    public static final int DENSITY_MEDIUM = 160;
    public static final int DENSITY_TV = 213;
    public static final int DENSITY_HIGH = 240;
    public static final int DENSITY_XHIGH = 320;
    public static final int DENSITY_XXHIGH = 480;
    public static final int DENSITY_XXXHIGH = 640;
    public static final int DENSITY_ANY = 0xFFFE;
    public static final int DENSITY_NONE = 0xFFFF;

    public int size;
    public int mcc;
    public int mnc;
    public String language = "";
    public String country = "";
    public String localeScript = "";
    public String localeVariant = "";
    public int orientation;
    public int touchscreen;
    public int density;
    public int keyboard;
    public int navigation;
    public int inputFlags;
    public int screenWidth;
    public int screenHeight;
    public int sdkVersion;
    public int minorVersion;
    public int screenLayout;
    public int uiMode;
    public int smallestScreenWidthDp;
    public int screenWidthDp;
    public int screenHeightDp;
    public int screenLayout2;
    public int colorMode;

    /** Raw struct bytes (exact size), used to re-emit configs verbatim. */
    public byte[] raw;

    private ResConfig() {}

    public static ResConfig parse(BinReader in, int off) {
        ResConfig c = new ResConfig();
        int size = (int) in.u32(off);
        if (size < 8 || size > 256) size = 64; // corrupt guard; read common part
        c.size = size;
        c.raw = in.slice(off, Math.min(size, in.length() - off));
        int n = c.raw.length;
        c.mcc = g16(c.raw, 4);
        c.mnc = g16(c.raw, 6);
        c.language = chars(c.raw, 8, 2);
        c.country = chars(c.raw, 10, 2);
        if (n >= 14) {
            c.orientation = g8(c.raw, 12);
            c.touchscreen = g8(c.raw, 13);
        }
        if (n >= 16) c.density = g16(c.raw, 14);
        if (n >= 20) {
            c.keyboard = g8(c.raw, 16);
            c.navigation = g8(c.raw, 17);
            c.inputFlags = g8(c.raw, 18);
        }
        if (n >= 24) {
            c.screenWidth = g16(c.raw, 20);
            c.screenHeight = g16(c.raw, 22);
        }
        if (n >= 28) {
            c.sdkVersion = g16(c.raw, 24);
            c.minorVersion = g16(c.raw, 26);
        }
        if (n >= 30) {
            c.screenLayout = g8(c.raw, 28);
            c.uiMode = g8(c.raw, 29);
        }
        if (n >= 32) c.smallestScreenWidthDp = g16(c.raw, 30);
        if (n >= 36) {
            c.screenWidthDp = g16(c.raw, 32);
            c.screenHeightDp = g16(c.raw, 34);
        }
        if (n >= 40) c.localeScript = chars(c.raw, 36, 4);
        if (n >= 48) c.localeVariant = chars(c.raw, 40, 8);
        if (n >= 50) {
            c.screenLayout2 = g8(c.raw, 48);
            c.colorMode = g8(c.raw, 49);
        }
        return c;
    }

    private static int g8(byte[] b, int o) {
        return o < b.length ? b[o] & 0xFF : 0;
    }

    private static int g16(byte[] b, int o) {
        return o + 1 < b.length
                ? (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) : 0;
    }

    private static String chars(byte[] b, int o, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && o + i < b.length; i++) {
            char ch = (char) (b[o + i] & 0xFF);
            if (ch == 0) break;
            sb.append(ch);
        }
        return sb.toString();
    }

    // -- matching ----------------------------------------------------------

    /**
     * Sort rank for density preference: scalable (any) first, then higher
     * dpi, default(0) as ~mdpi, nodpi last.
     */
    public int densityRank() {
        if (density == DENSITY_ANY) return Integer.MAX_VALUE;
        if (density == DENSITY_NONE) return Integer.MIN_VALUE;
        return density == 0 ? DENSITY_MEDIUM : density;
    }

    /**
     * Best-match pick: returns the better of {@code a}/{@code b} for an
     * icon request at {@code wantDensity} (0 = highest available).
     */
    public static boolean betterThan(ResConfig a, ResConfig b, int wantDensity) {
        if (b == null) return true;
        if (a == null) return false;
        int ar = rankFor(a.density, wantDensity);
        int br = rankFor(b.density, wantDensity);
        if (ar != br) return ar > br;
        if (a.sdkVersion != b.sdkVersion) return a.sdkVersion > b.sdkVersion;
        return false;
    }

    private static int rankFor(int dpi, int want) {
        if (dpi == DENSITY_ANY) return want == 0 ? Integer.MAX_VALUE : -want;
        if (dpi == DENSITY_NONE) return Integer.MIN_VALUE / 2;
        int d = dpi == 0 ? DENSITY_MEDIUM : dpi;
        if (want == 0) return d; // highest available wins
        // Closest to requested, ties prefer higher dpi.
        return -(Math.abs(d - want) * 1024) + (d >= want ? 1 : 0);
    }

    // -- display ------------------------------------------------------------

    /** aapt-style qualifier string, e.g. {@code -en-rUS-hdpi-v21}. */
    public String qualifierString() {
        StringBuilder sb = new StringBuilder();
        if (mcc != 0) sb.append("-mcc").append(mcc);
        if (mnc != 0) sb.append("-mnc").append(mnc);
        if (!language.isEmpty()) {
            sb.append('-').append(language);
            if (!country.isEmpty()) sb.append("-r").append(country);
        } else if (!localeScript.isEmpty() || !localeVariant.isEmpty()) {
            sb.append("-b+").append(language.isEmpty() ? "en" : language);
            if (!localeScript.isEmpty()) sb.append('+').append(localeScript);
            if (!country.isEmpty()) sb.append('+').append(country);
            if (!localeVariant.isEmpty()) sb.append('+').append(localeVariant);
        }
        int layoutDir = (screenLayout >> 6) & 3;
        if (layoutDir == 1) sb.append("-ldltr");
        else if (layoutDir == 2) sb.append("-ldrtl");
        if (smallestScreenWidthDp != 0) sb.append("-sw").append(smallestScreenWidthDp).append("dp");
        if (screenWidthDp != 0) sb.append("-w").append(screenWidthDp).append("dp");
        if (screenHeightDp != 0) sb.append("-h").append(screenHeightDp).append("dp");
        switch (screenLayout & 0x0F) {
            case 1: sb.append("-small"); break;
            case 2: sb.append("-normal"); break;
            case 3: sb.append("-large"); break;
            case 4: sb.append("-xlarge"); break;
            default: break;
        }
        if ((screenLayout & 0x30) == 0x20) sb.append("-long");
        if ((screenLayout2 & 0x03) == 0x02) sb.append("-round");
        else if ((screenLayout2 & 0x03) == 0x01) sb.append("-notround");
        switch (orientation) {
            case 1: sb.append("-port"); break;
            case 2: sb.append("-land"); break;
            case 3: sb.append("-square"); break;
            default: break;
        }
        switch (uiMode & 0x0F) {
            case 2: sb.append("-desk"); break;
            case 3: sb.append("-car"); break;
            case 4: sb.append("-television"); break;
            case 5: sb.append("-appliance"); break;
            case 6: sb.append("-watch"); break;
            case 7: sb.append("-vrheadset"); break;
            default: break;
        }
        if ((uiMode & 0x30) == 0x20) sb.append("-night");
        if (density != 0) sb.append('-').append(densityName(density));
        switch (touchscreen) {
            case 1: sb.append("-notouch"); break;
            case 2: sb.append("-stylus"); break;
            case 3: sb.append("-finger"); break;
            default: break;
        }
        switch (inputFlags & 0x30) {
            case 0x10: sb.append("-keyshidden"); break;
            case 0x20: sb.append("-keyssoft"); break;
            case 0x30: sb.append("-keysexposed"); break;
            default: break;
        }
        switch (navigation) {
            case 1: sb.append("-dpad"); break;
            case 2: sb.append("-trackball"); break;
            case 3: sb.append("-wheel"); break;
            case 4: sb.append("-nonav"); break;
            default: break;
        }
        if (sdkVersion != 0) sb.append("-v").append(sdkVersion);
        return sb.toString();
    }

    private static String densityName(int d) {
        switch (d) {
            case DENSITY_LOW: return "ldpi";
            case DENSITY_MEDIUM: return "mdpi";
            case DENSITY_TV: return "tvdpi";
            case DENSITY_HIGH: return "hdpi";
            case DENSITY_XHIGH: return "xhdpi";
            case DENSITY_XXHIGH: return "xxhdpi";
            case DENSITY_XXXHIGH: return "xxxhdpi";
            case DENSITY_ANY: return "anydpi";
            case DENSITY_NONE: return "nodpi";
            default: return d + "dpi";
        }
    }

    /** Locale tag for stats, e.g. {@code en-rUS} or {@code default}. */
    public String localeTag() {
        if (language.isEmpty()) return "default";
        return country.isEmpty() ? language : language + "-r" + country;
    }

    // -- path suffix parsing -------------------------------------------------

    /**
     * Parses an aapt directory suffix such as {@code -en-rUS-hdpi-v21}
     * (the part after {@code res/<type>}) for matching ZIP paths back to
     * table configs. Unknown tokens are ignored.
     */
    public static ResConfig fromPathSuffix(String suffix) {
        ResConfig c = new ResConfig();
        c.size = 64;
        if (suffix == null || suffix.isEmpty()) return c;
        String s = suffix.startsWith("-") ? suffix.substring(1) : suffix;
        String[] toks = s.split("-");
        for (int i = 0; i < toks.length; i++) {
            String t = toks[i];
            if (t.startsWith("mcc")) c.mcc = num(t, 3, 0);
            else if (t.startsWith("mnc")) c.mnc = num(t, 3, 0);
            else if (t.startsWith("sw") && t.endsWith("dp")) c.smallestScreenWidthDp = num(t, 2, 2);
            else if (t.startsWith("w") && t.endsWith("dp") && t.length() > 2
                    && Character.isDigit(t.charAt(1))) c.screenWidthDp = num(t, 1, 2);
            else if (t.startsWith("h") && t.endsWith("dp") && t.length() > 2
                    && Character.isDigit(t.charAt(1))) c.screenHeightDp = num(t, 1, 2);
            else if (t.equals("ldrtl")) c.screenLayout |= 0x80;
            else if (t.equals("ldltr")) c.screenLayout |= 0x40;
            else if (t.equals("small")) c.screenLayout |= 0x01;
            else if (t.equals("normal")) c.screenLayout |= 0x02;
            else if (t.equals("large")) c.screenLayout |= 0x03;
            else if (t.equals("xlarge")) c.screenLayout |= 0x04;
            else if (t.equals("long")) c.screenLayout |= 0x20;
            else if (t.equals("round")) c.screenLayout2 |= 0x02;
            else if (t.equals("notround")) c.screenLayout2 |= 0x01;
            else if (t.equals("port")) c.orientation = 1;
            else if (t.equals("land")) c.orientation = 2;
            else if (t.equals("square")) c.orientation = 3;
            else if (t.equals("desk")) c.uiMode |= 0x02;
            else if (t.equals("car")) c.uiMode |= 0x03;
            else if (t.equals("television")) c.uiMode |= 0x04;
            else if (t.equals("appliance")) c.uiMode |= 0x05;
            else if (t.equals("watch")) c.uiMode |= 0x06;
            else if (t.equals("vrheadset")) c.uiMode |= 0x07;
            else if (t.equals("night")) c.uiMode |= 0x20;
            else if (t.equals("notnight")) c.uiMode |= 0x10;
            else if (t.equals("ldpi")) c.density = DENSITY_LOW;
            else if (t.equals("mdpi")) c.density = DENSITY_MEDIUM;
            else if (t.equals("tvdpi")) c.density = DENSITY_TV;
            else if (t.equals("hdpi")) c.density = DENSITY_HIGH;
            else if (t.equals("xhdpi")) c.density = DENSITY_XHIGH;
            else if (t.equals("xxhdpi")) c.density = DENSITY_XXHIGH;
            else if (t.equals("xxxhdpi")) c.density = DENSITY_XXXHIGH;
            else if (t.equals("anydpi")) c.density = DENSITY_ANY;
            else if (t.equals("nodpi")) c.density = DENSITY_NONE;
            else if (t.endsWith("dpi") && t.length() > 3) c.density = num(t, 0, 3);
            else if (t.equals("notouch")) c.touchscreen = 1;
            else if (t.equals("stylus")) c.touchscreen = 2;
            else if (t.equals("finger")) c.touchscreen = 3;
            else if (t.equals("keysexposed")) c.inputFlags |= 0x30;
            else if (t.equals("keyshidden")) c.inputFlags |= 0x10;
            else if (t.equals("keyssoft")) c.inputFlags |= 0x20;
            else if (t.equals("nokeys")) c.keyboard = 1;
            else if (t.equals("qwerty")) c.keyboard = 2;
            else if (t.equals("12key")) c.keyboard = 3;
            else if (t.equals("navexposed")) c.navigation = 5;
            else if (t.equals("navhidden")) c.navigation = 6;
            else if (t.equals("dpad")) c.navigation = 1;
            else if (t.equals("trackball")) c.navigation = 2;
            else if (t.equals("wheel")) c.navigation = 3;
            else if (t.equals("nonav")) c.navigation = 4;
            else if (t.startsWith("v") && t.length() > 1
                    && Character.isDigit(t.charAt(1))) c.sdkVersion = num(t, 1, 0);
            else if (t.startsWith("b+")) {
                String[] parts = t.substring(2).split("\\+");
                if (parts.length > 0) c.language = parts[0];
                if (parts.length > 1 && parts[1].length() == 4) c.localeScript = parts[1];
                if (parts.length > 2) c.country = parts[2];
            } else if (t.length() == 2 && Character.isLowerCase(t.charAt(0))) {
                c.language = t;
                if (i + 1 < toks.length && toks[i + 1].startsWith("r")
                        && toks[i + 1].length() == 3) {
                    c.country = toks[i + 1].substring(1);
                    i++;
                }
            }
        }
        return c;
    }

    private static int num(String t, int from, int trimEnd) {
        try {
            return Integer.parseInt(t.substring(from, t.length() - trimEnd));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
