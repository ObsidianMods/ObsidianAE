package apktools;

/**
 * Android {@code Res_value} types + value-to-string formatting.
 *
 * <p>A value is {@code (dataType, data)} plus, for {@link #TYPE_STRING},
 * an index into a {@link StringPool}. Complex (bag) values are handled by
 * the ARSC layer; the 8-byte header size constant lives here.
 */
public final class ResValue {

    private ResValue() {}

    public static final int SIZE = 8;

    // Data types (Res_value::dataType).
    public static final int TYPE_NULL = 0x00;
    public static final int TYPE_REFERENCE = 0x01;
    public static final int TYPE_ATTRIBUTE = 0x02;
    public static final int TYPE_STRING = 0x03;
    public static final int TYPE_FLOAT = 0x04;
    public static final int TYPE_DIMENSION = 0x05;
    public static final int TYPE_FRACTION = 0x06;
    public static final int TYPE_DYNAMIC_REFERENCE = 0x07;
    public static final int TYPE_DYNAMIC_ATTRIBUTE = 0x08;
    public static final int TYPE_INT_DEC = 0x10;
    public static final int TYPE_INT_HEX = 0x11;
    public static final int TYPE_INT_BOOLEAN = 0x12;
    public static final int TYPE_INT_COLOR_ARGB8 = 0x1C;
    public static final int TYPE_INT_COLOR_RGB8 = 0x1D;
    public static final int TYPE_INT_COLOR_ARGB4 = 0x1E;
    public static final int TYPE_INT_COLOR_RGB4 = 0x1F;

    // Null data values.
    public static final int DATA_NULL_EMPTY = 0;
    public static final int DATA_NULL_UNDEFINED = 1;

    // Dimension unit nibbles.
    private static final String[] DIM_UNITS =
            {"px", "dp", "sp", "pt", "in", "mm"};
    // Mask-form multipliers by radix index (value = (data & 0xFFFFFF00)
    // * MULT; verified against aapt output, e.g. 0x3801 = 56dp).
    private static final float[] RADIX_MULTS =
            {1.0f / 256, 1.0f / 32768, 1.0f / 8388608, 1.0f / 2147483648L};

    /** Decode a packed complex number (dimension/fraction) to float. */
    public static float complexToFloat(int data) {
        int mantissa = data & 0xFFFFFF00;
        int radix = (data >> 4) & 3;
        return mantissa * RADIX_MULTS[radix];
    }

    /**
     * Packs a float + unit nibble into a complex number (inverse of
     * {@link #complexToFloat}), picking the finest radix whose signed
     * 24-bit mantissa fits. Decodes to the same float; bits may differ
     * from aapt's (coarsest-first) choice.
     */
    public static int complex(float value, int unit) {
        // Per-index float multipliers (2^0, 2^7, 2^15, 2^23).
        for (int index = 3; index >= 0; index--) {
            double mult;
            if (index == 0) mult = 1;
            else if (index == 1) mult = 128;
            else if (index == 2) mult = 32768;
            else mult = 8388608;
            long rounded = Math.round(value * mult);
            if (rounded >= -(1 << 23) && rounded < (1 << 23)) {
                return ((int) rounded << 8) | (index << 4) | (unit & 0xF);
            }
        }
        return (Math.round(value) << 8) | (3 << 4) | (unit & 0xF);
    }

    /** Human-readable form, mirroring aapt's output conventions. */
    public static String toString(int type, int data, StringPool pool) {
        return toString(type, data, pool, null);
    }

    /**
     * Same, but reference/attribute values use {@code resolver} to show
     * {@code @android:versionCode}-style names instead of
     * {@code @0x0101021b}. Null resolver or unknown id = hex fallback.
     */
    public static String toString(int type, int data, StringPool pool,
                                  apktools.xml.IdResolver resolver) {
        switch (type) {
            case TYPE_NULL:
                return data == DATA_NULL_UNDEFINED ? "@null" : "@empty";
            case TYPE_REFERENCE:
                return data == 0 ? "@null" : "@" + idOrHex(data, resolver);
            case TYPE_DYNAMIC_REFERENCE:
                return "@dyn:" + idOrHex(data, resolver);
            case TYPE_ATTRIBUTE:
                return data == 0 ? "?null" : "?" + idOrHex(data, resolver);
            case TYPE_DYNAMIC_ATTRIBUTE:
                return "?dyn:" + idOrHex(data, resolver);
            case TYPE_STRING:
                if (pool == null) return "@string/" + data;
                return pool.get(data);
            case TYPE_FLOAT:
                return Float.toString(Float.intBitsToFloat(data));
            case TYPE_DIMENSION: {
                float v = complexToFloat(data);
                String unit = DIM_UNITS[data & 0xF];
                return Float.toString(v) + unit;
            }
            case TYPE_FRACTION: {
                float v = complexToFloat(data);
                int unit = data & 0xF;
                return unit == 0 ? Float.toString(v * 100) + "%"
                        : Float.toString(v) + "%p";
            }
            case TYPE_INT_DEC:
                return Integer.toString(data);
            case TYPE_INT_HEX:
                return "0x" + Integer.toHexString(data);
            case TYPE_INT_BOOLEAN:
                return data != 0 ? "true" : "false";
            case TYPE_INT_COLOR_ARGB8:
            case TYPE_INT_COLOR_ARGB4:
            case TYPE_INT_COLOR_RGB4:
                // Full 32-bit data (aapt tags colors ARGB4/RGB4 even
                // when the payload is a whole int); opaque colors drop
                // the alpha like aapt source does.
                if ((data >>> 24) == 0xFF) {
                    return String.format("#%06x", data & 0xFFFFFF);
                }
                return String.format("#%08x", data);
            case TYPE_INT_COLOR_RGB8:
                return String.format("#%06x", data & 0xFFFFFF);
            default:
                return "(type 0x" + Integer.toHexString(type)
                        + " data 0x" + Integer.toHexString(data) + ")";
        }
    }

    private static String idOrHex(int id, apktools.xml.IdResolver resolver) {
        if (resolver != null) {
            String name = resolver.resolve(id);
            if (name != null) return name;
        }
        return "0x" + Integer.toHexString(id);
    }

    /** True for color ints (used by icon/layer classification). */
    public static boolean isColor(int type) {
        return type == TYPE_INT_COLOR_ARGB8 || type == TYPE_INT_COLOR_RGB8
                || type == TYPE_INT_COLOR_ARGB4 || type == TYPE_INT_COLOR_RGB4;
    }

    /** True for reference-like types (needs ARSC lookup to resolve). */
    public static boolean isReference(int type) {
        return type == TYPE_REFERENCE || type == TYPE_DYNAMIC_REFERENCE
                || type == TYPE_ATTRIBUTE || type == TYPE_DYNAMIC_ATTRIBUTE;
    }

    // -- bag pseudo-ids (AOSP ResTable_map internals) --------------------------

    /** 0x01000000: marks the attr-format mask item in attr bags. */
    public static final int BAG_ATTR_TYPE = 0x01000000;
    /** 0x01000001/2: integer attr min/max items (values are the bounds). */
    public static final int BAG_ATTR_MIN = 0x01000001;
    public static final int BAG_ATTR_MAX = 0x01000002;
    /** 0x01000003: attr localization mode (0/1). */
    public static final int BAG_ATTR_L10N = 0x01000003;
    /** First plural-quantity pseudo-id (other, zero, one, ...). */
    public static final int BAG_PLURALS_BASE = 0x01000004;

    // Attr format mask bits (AOSP, for use with BAG_ATTR_TYPE).
    public static final int FORMAT_REFERENCE = 1;
    public static final int FORMAT_STRING = 1 << 1;
    public static final int FORMAT_INTEGER = 1 << 2;
    public static final int FORMAT_BOOLEAN = 1 << 3;
    public static final int FORMAT_COLOR = 1 << 4;
    public static final int FORMAT_FLOAT = 1 << 5;
    public static final int FORMAT_DIMENSION = 1 << 6;
    public static final int FORMAT_FRACTION = 1 << 7;
    public static final int FORMAT_ENUM = 1 << 16;
    public static final int FORMAT_FLAGS = 1 << 17;

    private static final int[] FORMAT_BITS = {
        FORMAT_REFERENCE, FORMAT_STRING, FORMAT_INTEGER, FORMAT_BOOLEAN,
        FORMAT_COLOR, FORMAT_FLOAT, FORMAT_DIMENSION, FORMAT_FRACTION,
        FORMAT_ENUM, FORMAT_FLAGS
    };
    private static final String[] FORMAT_NAMES = {
        "reference", "string", "integer", "boolean", "color", "float",
        "dimension", "fraction", "enum", "flags"
    };

    /** {@code dimension|enum} for 0x10040; unknown bits stay hex. */
    public static String attrFormat(int mask) {
        StringBuilder sb = new StringBuilder();
        int rest = mask;
        for (int i = 0; i < FORMAT_BITS.length; i++) {
            if ((rest & FORMAT_BITS[i]) != 0) {
                if (sb.length() > 0) sb.append('|');
                sb.append(FORMAT_NAMES[i]);
                rest &= ~FORMAT_BITS[i];
            }
        }
        if (rest != 0) {
            if (sb.length() > 0) sb.append('|');
            sb.append("0x").append(Integer.toHexString(rest));
        }
        return sb.length() == 0 ? "0" : sb.toString();
    }

    private static final String[] QUANTITIES = {
        "other", "zero", "one", "two", "few", "many"
    };

    /**
     * Plural quantity for a bag-item pseudo-id (0x01000004 = other ...
     * 0x01000009 = many), or null when not a quantity.
     */
    public static String pluralQuantity(int nameId) {
        int i = nameId - BAG_PLURALS_BASE;
        return i >= 0 && i < QUANTITIES.length ? QUANTITIES[i] : null;
    }
}
