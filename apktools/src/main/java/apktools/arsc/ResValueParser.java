package apktools.arsc;

import apktools.ApkException;
import apktools.ResValue;
import apktools.xml.FrameworkIds;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inverse of value formatting: parses editor text back to typed
 * values. Understands references ({@code @string/x},
 * {@code @android:drawable/y}, {@code @pkg:type/z}, {@code @null}),
 * theme attributes ({@code ?attr/x}), colors, booleans, integers,
 * floats, dimensions, fractions, enum symbols (with attr context) and
 * {@code format} masks. Anything else is a plain string.
 *
 * <p>Names resolve against the live table, so obfuscated file names
 * and arbitrary packages work. Throws {@link ApkException} on bad
 * input — never guesses.
 */
public final class ResValueParser {

    private ResValueParser() {}

    /** Parsed value: typed data, or {@link #string} for strings. */
    public static final class Result {
        public final int type;
        public final int data;
        /** Non-null only for strings (pool index assigned on write). */
        public final String string;

        public Result(int type, int data, String string) {
            this.type = type;
            this.data = data;
            this.string = string;
        }

        public boolean isString() {
            return string != null;
        }
    }

    /**
     * Name-resolution context: table + owning package. Reuse one per
     * editing session (it caches the reverse id map).
     */
    public static final class Ctx {
        final ArscFile table;
        final String packageName;
        private Map<String, Integer> reverse;
        private Map<String, Integer> frameworks;

        public Ctx(ArscFile table, String packageName) {
            this.table = table;
            this.packageName =
                    packageName == null ? "" : packageName;
        }

        /** Resolve {@code [pkg:]type/name} to an id, or 0. */
        synchronized int idFor(String ref) {
            String type;
            String name;
            String pkg = packageName;
            int slash = ref.lastIndexOf('/');
            if (slash < 0) return 0;
            String head = ref.substring(0, slash);
            name = ref.substring(slash + 1);
            int colon = head.indexOf(':');
            if (colon >= 0) {
                pkg = head.substring(0, colon);
                type = head.substring(colon + 1);
            } else {
                type = head;
            }
            if (pkg.equals("android")) {
                if (frameworks == null) {
                    frameworks = new LinkedHashMap<>();
                    for (Map.Entry<Integer, String> e
                            : FrameworkIds.all().entrySet()) {
                        frameworks.put(e.getValue()
                                .substring("android:".length()), e.getKey());
                    }
                }
                Integer id = frameworks.get(type + "/" + name);
                return id == null ? 0 : id;
            }
            if (table == null) return 0;
            if (reverse == null) {
                reverse = new LinkedHashMap<>();
                for (ArscPackage p : table.packages()) {
                    String owner = p.displayName();
                    for (ArscType t : p.allTypes()) {
                        String tname = p.typeName(t.id());
                        for (int i = 0; i < t.entryCount(); i++) {
                            ArscEntry e = t.get(i);
                            if (e == null) continue;
                            try {
                                String key = e.key();
                                reverse.put(tname + "/" + key,
                                        e.resourceId());
                                reverse.put(owner + ":" + tname + "/"
                                        + key, e.resourceId());
                                if (key.startsWith("$")) {
                                    // Sanitized alias (MT form).
                                    String clean = String.format(
                                            "%s%04x", tname, i);
                                    reverse.put(tname + "/" + clean,
                                            e.resourceId());
                                }
                            } catch (Exception ignored) {
                                // Unreadable key: not name-addressable.
                            }
                        }
                    }
                }
            }
            Integer id = reverse.get(
                    pkg.equals(packageName) || pkg.isEmpty()
                            ? type + "/" + name
                            : pkg + ":" + type + "/" + name);
            if (id == null && pkg.equals(packageName)) {
                id = reverse.get(type + "/" + name);
            }
            return id == null ? 0 : id;
        }

        /** Enum/flag symbol → value for an attr declaration, or null. */
        synchronized Integer enumFor(int attrId, String symbol) {
            Integer fromBag = enumFromBag(attrId, symbol);
            if (fromBag != null) return fromBag;
            if (ResourceId.pkg(attrId) == 0x01) {
                return apktools.xml.FrameworkEnums.value(attrId, symbol);
            }
            return null;
        }

        private Integer enumFromBag(int attrId, String symbol) {
            if (table == null) return null;
            ArscPackage pkg =
                    table.findPackage(ResourceId.pkg(attrId));
            if (pkg == null) return null;
            ArscEntry attr = pkg.entry(ResourceId.type(attrId),
                    ResourceId.entry(attrId));
            if (attr == null || !attr.isComplex()) return null;
            for (BagItem b : attr.bag()) {
                String keyName = null;
                try {
                    ArscPackage kp = table.findPackage(
                            ResourceId.pkg(b.name));
                    if (kp != null) {
                        ArscEntry ke = kp.entry(
                                ResourceId.type(b.name),
                                ResourceId.entry(b.name));
                        if (ke != null) keyName = ke.key();
                    }
                } catch (Exception ignored) {
                    continue;
                }
                if (symbol.equals(keyName)) return b.valueData;
            }
            return null;
        }
    }

    /**
     * Parses editor text. {@code attrId} enables enum/flag symbols for
     * values of that attribute declaration (0 = numeric only).
     */
    public static Result parse(String text, Ctx ctx, int attrId) {
        if (text == null) {
            throw new ApkException(ApkException.Code.ENCODE,
                    "null value text");
        }
        String s = text.trim();
        if (s.equals("@null")) {
            return new Result(ResValue.TYPE_REFERENCE, 0, null);
        }
        if (s.equals("@empty")) {
            return new Result(ResValue.TYPE_NULL,
                    ResValue.DATA_NULL_EMPTY, null);
        }
        if (s.equals("?null")) {
            return new Result(ResValue.TYPE_ATTRIBUTE, 0, null);
        }
        if (s.startsWith("@")) {
            String body = stripMarker(s);
            if (body.startsWith("0x") || body.startsWith("0X")) {
                return new Result(ResValue.TYPE_REFERENCE,
                        parseHexId(body, s), null);
            }
            int id = ctx == null ? 0 : ctx.idFor(body);
            if (id == 0) {
                throw new ApkException(ApkException.Code.ENCODE,
                        "unresolvable reference " + s);
            }
            return new Result(ResValue.TYPE_REFERENCE, id, null);
        }
        if (s.startsWith("?")) {
            String body = stripMarker(s);
            if (body.startsWith("0x") || body.startsWith("0X")) {
                return new Result(ResValue.TYPE_ATTRIBUTE,
                        parseHexId(body, s), null);
            }
            int id = ctx == null ? 0 : ctx.idFor(body);
            if (id == 0) {
                throw new ApkException(ApkException.Code.ENCODE,
                        "unresolvable attribute " + s);
            }
            return new Result(ResValue.TYPE_ATTRIBUTE, id, null);
        }
        if (s.startsWith("#")) return parseColor(s);
        if (s.equals("true") || s.equals("false")) {
            return new Result(ResValue.TYPE_INT_BOOLEAN,
                    s.equals("true") ? -1 : 0, null);
        }
        if (s.startsWith("0x") || s.startsWith("0X")) {
            try {
                long v = Long.parseLong(s.substring(2), 16);
                if ((v & 0xFFFFFFFF00000000L) != 0) {
                    throw new ApkException(ApkException.Code.ENCODE,
                            "hex out of range " + s);
                }
                return new Result(ResValue.TYPE_INT_HEX, (int) v, null);
            } catch (NumberFormatException e) {
                throw new ApkException(ApkException.Code.ENCODE,
                        "bad hex " + s);
            }
        }
        Result dim = tryDimension(s);
        if (dim != null) return dim;
        if (isDecimal(s)) {
            try {
                long v = Long.parseLong(s);
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                    throw new ApkException(ApkException.Code.ENCODE,
                            "int out of range " + s);
                }
                return new Result(ResValue.TYPE_INT_DEC, (int) v, null);
            } catch (NumberFormatException e) {
                throw new ApkException(ApkException.Code.ENCODE,
                        "bad int " + s);
            }
        }
        if (isFloat(s)) {
            try {
                float f = Float.parseFloat(s);
                return new Result(ResValue.TYPE_FLOAT,
                        Float.floatToIntBits(f), null);
            } catch (NumberFormatException e) {
                throw new ApkException(ApkException.Code.ENCODE,
                        "bad float " + s);
            }
        }
        if (attrId != 0 && ctx != null) {
            Integer v = ctx.enumFor(attrId, s);
            if (v != null) {
                return new Result(ResValue.TYPE_INT_DEC, v, null);
            }
        }
        return new Result(ResValue.TYPE_STRING, 0, text);
    }

    private static String stripMarker(String s) {
        String r = s.substring(1);
        if (r.startsWith("*")) r = r.substring(1); // private refs
        return r;
    }

    private static int parseHexId(String body, String s) {
        try {
            long v = Long.parseLong(body.substring(2), 16);
            if ((v & 0xFFFFFFFF00000000L) != 0) throw new Exception();
            return (int) v;
        } catch (Exception e) {
            throw new ApkException(ApkException.Code.ENCODE,
                    "bad id " + s);
        }
    }

    private static Result parseColor(String s) {
        String hex = s.substring(1);
        int len = hex.length();
        if (len != 3 && len != 4 && len != 6 && len != 8) {
            throw new ApkException(ApkException.Code.ENCODE,
                    "bad color " + s);
        }
        long wide;
        try {
            wide = Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new ApkException(ApkException.Code.ENCODE,
                    "bad color " + s);
        }
        // 8-digit colors exceed int range; the bits are the value.
        if (wide < 0 || wide > 0xFFFFFFFFL) {
            throw new ApkException(ApkException.Code.ENCODE,
                    "bad color " + s);
        }
        int v = (int) wide;
        int type;
        if (len == 3) type = ResValue.TYPE_INT_COLOR_RGB4;
        else if (len == 4) type = ResValue.TYPE_INT_COLOR_ARGB4;
        else if (len == 6) type = ResValue.TYPE_INT_COLOR_RGB8;
        else type = ResValue.TYPE_INT_COLOR_ARGB8;
        return new Result(type, v, null);
    }

    private static final String[] DIM_UNITS = {
        "px", "dip", "dp", "sp", "pt", "in", "mm"
    };
    // Unit nibbles matching ResValue.DIM_UNITS order (dip == dp).
    private static final int[] DIM_NIBBLES = {0, 1, 1, 2, 3, 4, 5};

    private static Result tryDimension(String s) {
        boolean percent = false;
        String num = s;
        int unit = -1;
        if (s.endsWith("%p")) {
            percent = true;
            num = s.substring(0, s.length() - 2);
            unit = 1;
        } else if (s.endsWith("%")) {
            percent = true;
            num = s.substring(0, s.length() - 1);
            unit = 0;
        } else {
            for (int i = 0; i < DIM_UNITS.length; i++) {
                if (s.endsWith(DIM_UNITS[i])) {
                    num = s.substring(0,
                            s.length() - DIM_UNITS[i].length());
                    unit = DIM_NIBBLES[i];
                    break;
                }
            }
        }
        if (unit < 0 || !isFloat(num)) return null;
        float f;
        try {
            f = Float.parseFloat(num);
        } catch (NumberFormatException e) {
            return null;
        }
        if (percent && unit == 0) f = f / 100f; // "%" is scaled ×100
        return new Result(percent ? ResValue.TYPE_FRACTION
                : ResValue.TYPE_DIMENSION,
                ResValue.complex(f, unit), null);
    }

    private static boolean isDecimal(String s) {
        if (s.isEmpty()) return false;
        int i = s.charAt(0) == '-' || s.charAt(0) == '+' ? 1 : 0;
        if (i == s.length()) return false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isFloat(String s) {
        if (s.isEmpty()) return false;
        boolean dot = false;
        boolean digit = false;
        int i = s.charAt(0) == '-' || s.charAt(0) == '+' ? 1 : 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (dot) return false;
                dot = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c == 'e' || c == 'E') {
                return digit && i + 1 < s.length();
            } else {
                return false;
            }
        }
        return digit && dot;
    }

    /** Parses {@code dimension|enum} back to a format mask. */
    public static int parseFormatMask(String text) {
        if (text == null) {
            throw new ApkException(ApkException.Code.ENCODE,
                    "null format mask");
        }
        int mask = 0;
        boolean any = false;
        for (String part : text.split("\\|")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            any = true;
            if (p.equals("reference")) mask |= ResValue.FORMAT_REFERENCE;
            else if (p.equals("string")) mask |= ResValue.FORMAT_STRING;
            else if (p.equals("integer")) mask |= ResValue.FORMAT_INTEGER;
            else if (p.equals("boolean")) mask |= ResValue.FORMAT_BOOLEAN;
            else if (p.equals("color")) mask |= ResValue.FORMAT_COLOR;
            else if (p.equals("float")) mask |= ResValue.FORMAT_FLOAT;
            else if (p.equals("dimension")) mask |= ResValue.FORMAT_DIMENSION;
            else if (p.equals("fraction")) mask |= ResValue.FORMAT_FRACTION;
            else if (p.equals("enum")) mask |= ResValue.FORMAT_ENUM;
            else if (p.equals("flags")) mask |= ResValue.FORMAT_FLAGS;
            else if (p.startsWith("0x") || p.startsWith("0X")) {
                try {
                    mask |= (int) Long.parseLong(p.substring(2), 16);
                } catch (NumberFormatException e) {
                    throw new ApkException(ApkException.Code.ENCODE,
                            "bad format part " + p);
                }
            } else {
                throw new ApkException(ApkException.Code.ENCODE,
                        "bad format part " + p);
            }
        }
        if (!any) {
            throw new ApkException(ApkException.Code.ENCODE,
                    "empty format mask");
        }
        return mask;
    }
}
