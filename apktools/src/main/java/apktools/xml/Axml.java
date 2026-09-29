package apktools.xml;

/**
 * Binary XML (AXML) chunk types and attribute sub-indices.
 * Values match {@code androidfw/ResourceTypes.h}.
 */
public final class Axml {

    private Axml() {}

    public static final int CHUNK_XML = 0x0003;
    public static final int CHUNK_STRING_POOL = 0x0001;
    public static final int CHUNK_XML_START_NAMESPACE = 0x0100;
    public static final int CHUNK_XML_END_NAMESPACE = 0x0101;
    public static final int CHUNK_XML_START_ELEMENT = 0x0102;
    public static final int CHUNK_XML_END_ELEMENT = 0x0103;
    public static final int CHUNK_XML_CDATA = 0x0104;
    public static final int CHUNK_XML_RESOURCE_MAP = 0x0180;

    /** Start-element extended header: lineNumber + comment + ns/name + ... */
    public static final int START_ELEMENT_HEADER_SIZE = 16;
    /** CDATA extended header: data + typedValue. */
    public static final int CDATA_HEADER_SIZE = 16;
    /** One attribute is always 20 bytes. */
    public static final int ATTRIBUTE_SIZE = 20;

    /** Well-known Android attribute resource ids used by ApkInfo fast paths. */
    public static final int ATTR_PACKAGE = 0x01010003; // manifest: package? (literal, not in map)
    public static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
}
