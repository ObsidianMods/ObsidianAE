package apktools.xml;

import apktools.ResValue;
import apktools.StringPool;

/**
 * One decoded binary-XML attribute: namespace, name, typed value and the
 * optional resource id (from the resource-map chunk).
 */
public final class AxmlAttribute {

    /** Namespace URI, or null/empty when unqualified. */
    public final String namespace;
    public final String name;
    /** Resource id from the resource map, or 0 when absent. */
    public final int resourceId;
    /** Raw string value (rawValue index), may be null. */
    public final String rawValue;
    /** Res_value data type (see {@link ResValue}). */
    public final int valueType;
    /** Res_value data payload. */
    public final int valueData;
    /**
     * Original string-pool indices (set by the decoder, -1 when absent
     * or for hand-built attributes). The encoder reuses them so
     * duplicate pool strings and resource-map alignment survive a
     * rebuild byte-faithfully.
     */
    public int nameIndex = -1;
    public int nsIndex = -1;
    public int rawIndex = -1;

    public AxmlAttribute(String namespace, String name, int resourceId,
                         String rawValue, int valueType, int valueData) {
        this.namespace = namespace == null ? "" : namespace;
        this.name = name;
        this.resourceId = resourceId;
        this.rawValue = rawValue;
        this.valueType = valueType;
        this.valueData = valueData;
    }

    /** Display string: raw text when present, else typed formatting. */
    public String stringValue(StringPool pool) {
        return stringValue(pool, null);
    }

    /**
     * Same with optional id resolution: reference/attribute values show
     * resolved names ({@code @android:versionCode}) instead of hex
     * offsets when the resolver knows them.
     */
    public String stringValue(StringPool pool, IdResolver resolver) {
        if (rawValue != null) return rawValue;
        return ResValue.toString(valueType, valueData, pool, resolver);
    }

    /** Qualified name using the prefix registered for the namespace. */
    public String qualifiedName(java.util.Map<String, String> uriToPrefix) {
        if (namespace.isEmpty()) return name;
        String p = uriToPrefix.get(namespace);
        return p == null || p.isEmpty() ? name : p + ":" + name;
    }

    @Override
    public String toString() {
        return name + "=(type " + valueType + " data 0x"
                + Integer.toHexString(valueData) + " raw " + rawValue + ")";
    }
}
