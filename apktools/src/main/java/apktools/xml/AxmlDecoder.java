package apktools.xml;

import apktools.ApkException;
import apktools.BinReader;
import apktools.ResValue;
import apktools.StringPool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Decodes binary Android XML (AXML) into an {@link AxmlDocument}.
 *
 * <p>Tolerates real-world files: unknown chunks are skipped by size,
 * style spans are ignored, end-namespace mismatches pop the matching
 * declaration instead of failing. Strict where it matters (truncation,
 * tag mismatch, attribute overflow) with offsets in every error.
 */
public final class AxmlDecoder {

    private AxmlDecoder() {}

    public static AxmlDocument decode(byte[] data) {
        return decode(new BinReader(data), 0, null);
    }

    /**
     * Decodes with optional id resolution: when {@code resolver} is
     * non-null it is stored on the document and
     * {@link AxmlDocument#toXmlString()} shows resolved resource names
     * instead of hex ids.
     */
    public static AxmlDocument decode(byte[] data, IdResolver resolver) {
        return decode(new BinReader(data), 0, resolver);
    }

    public static AxmlDocument decode(BinReader in, int offset) {
        return decode(in, offset, null);
    }

    public static AxmlDocument decode(BinReader in, int offset,
                                      IdResolver resolver) {
        int type = in.u16(offset);
        if (type != Axml.CHUNK_XML) {
            throw ApkException.badMagic("AXML", type, offset);
        }
        int headerSize = in.u16(offset + 2);
        long fileSize = in.u32(offset + 4);
        if (headerSize < 8 || fileSize < headerSize || fileSize > Integer.MAX_VALUE) {
            throw ApkException.badChunk("AXML header", offset);
        }
        in.check(offset, (int) fileSize);
        int end = offset + (int) fileSize;

        AxmlDocument doc = new AxmlDocument();
        doc.idResolver = resolver;
        Deque<AxmlElement> stack = new ArrayDeque<>();
        List<NsDecl> pendingNs = new ArrayList<>();
        List<NsDecl> nsStack = new ArrayList<>();
        boolean seenRoot = false;

        int pos = offset + headerSize;
        while (pos < end) {
            in.check(pos, 8);
            int chunkType = in.u16(pos);
            int chunkHeader = in.u16(pos + 2);
            long chunkSize = in.u32(pos + 4);
            if (chunkHeader < 8 || chunkSize < chunkHeader
                    || chunkSize > Integer.MAX_VALUE || pos + chunkSize > end) {
                throw ApkException.badChunk(
                        "chunk 0x" + Integer.toHexString(chunkType), pos);
            }
            switch (chunkType) {
                case Axml.CHUNK_STRING_POOL:
                    if (doc.pool == null) {
                        doc.pool = StringPool.parse(in, pos);
                    }
                    break;
                case Axml.CHUNK_XML_RESOURCE_MAP: {
                    int count = ((int) chunkSize - chunkHeader) / 4;
                    doc.resourceMap = new int[count];
                    for (int i = 0; i < count; i++) {
                        doc.resourceMap[i] = in.i32(pos + chunkHeader + i * 4);
                    }
                    break;
                }
                case Axml.CHUNK_XML_START_NAMESPACE: {
                    requirePool(doc, pos);
                    int prefix = in.i32(pos + chunkHeader);
                    int uri = in.i32(pos + chunkHeader + 4);
                    NsDecl decl = new NsDecl(
                            doc.pool.get(prefix), doc.pool.get(uri));
                    nsStack.add(decl);
                    pendingNs.add(decl);
                    break;
                }
                case Axml.CHUNK_XML_END_NAMESPACE: {
                    requirePool(doc, pos);
                    String uri = doc.pool.get(in.i32(pos + chunkHeader + 4));
                    // Pop the matching declaration; real files nest
                    // properly, but never fail a decode over this.
                    for (int i = nsStack.size() - 1; i >= 0; i--) {
                        if (nsStack.get(i).uri.equals(uri)) {
                            nsStack.remove(i);
                            break;
                        }
                    }
                    break;
                }
                case Axml.CHUNK_XML_START_ELEMENT: {
                    requirePool(doc, pos);
                    AxmlElement e = readElement(in, pos, chunkHeader,
                            (int) chunkSize, doc);
                    e.namespaces.addAll(pendingNs);
                    pendingNs.clear();
                    if (!stack.isEmpty()) {
                        stack.peek().children.add(e);
                    } else {
                        if (seenRoot) {
                            throw new ApkException(ApkException.Code.XML,
                                    "second root element", pos);
                        }
                        doc.root = e;
                        seenRoot = true;
                    }
                    stack.push(e);
                    break;
                }
                case Axml.CHUNK_XML_END_ELEMENT: {
                    requirePool(doc, pos);
                    int nsIdx = in.i32(pos + chunkHeader);
                    int nameIdx = in.i32(pos + chunkHeader + 4);
                    String ns = nsIdx >= 0 ? doc.pool.get(nsIdx) : "";
                    String name = doc.pool.get(nameIdx);
                    if (stack.isEmpty()) {
                        throw new ApkException(ApkException.Code.XML,
                                "end element without start: " + name, pos);
                    }
                    AxmlElement top = stack.pop();
                    if (!top.name.equals(name) || !top.namespace.equals(ns)) {
                        throw new ApkException(ApkException.Code.XML,
                                "tag mismatch: <" + top.name + "> closed by <"
                                        + name + ">", pos);
                    }
                    break;
                }
                case Axml.CHUNK_XML_CDATA: {
                    requirePool(doc, pos);
                    int dataIdx = in.i32(pos + chunkHeader);
                    // typed value: size u16, res0 u8, dataType u8, data u32
                    int valueType = in.u8(pos + chunkHeader + 7);
                    int valueData = in.i32(pos + chunkHeader + 8);
                    String text;
                    if (dataIdx >= 0) {
                        text = doc.pool.get(dataIdx);
                    } else {
                        text = ResValue.toString(valueType, valueData, doc.pool);
                    }
                    if (!stack.isEmpty()) {
                        stack.peek().children.add(new AxmlText(text));
                    }
                    break;
                }
                default:
                    // Unknown chunk: skipped by size (forward compatibility).
                    break;
            }
            pos += (int) chunkSize;
        }
        if (doc.root == null) {
            throw new ApkException(ApkException.Code.XML, "no root element", offset);
        }
        if (!stack.isEmpty()) {
            throw new ApkException(ApkException.Code.XML,
                    "unclosed element <" + stack.peek().name + ">", offset);
        }
        // Namespace decls that never attached to an element (declared
        // before the root) belong to the document.
        doc.namespaces.addAll(pendingNs);
        return doc;
    }

    private static void requirePool(AxmlDocument doc, long pos) {
        if (doc.pool == null) {
            throw new ApkException(ApkException.Code.XML,
                    "element chunk before string pool", pos);
        }
    }

    private static AxmlElement readElement(BinReader in, int pos, int header,
                                           int chunkSize, AxmlDocument doc) {
        // ext starts right after the 16-byte node header
        // (chunk header + lineNumber + comment): ns, name,
        // attributeStart/Size/Count, idIndex, classIndex, styleIndex.
        int p = pos + header;
        int nsIdx = in.i32(p);
        int nameIdx = in.i32(p + 4);
        int attributeStart = in.u16(p + 8);
        int attributeSize = in.u16(p + 10);
        int attributeCount = in.u16(p + 12);
        // Forward compatibility: stride by the declared size, clamped
        // to the 20-byte struct when a writer emits something smaller.
        int stride = attributeSize < Axml.ATTRIBUTE_SIZE
                ? Axml.ATTRIBUTE_SIZE : attributeSize;
        long attrsEnd = (long) pos + header + attributeStart
                + (long) attributeCount * stride;
        if (attrsEnd > pos + chunkSize) {
            throw new ApkException(ApkException.Code.XML,
                    "attributes overflow chunk (" + attributeCount + "x"
                            + stride + ")", pos);
        }
        AxmlElement e = new AxmlElement(
                nsIdx >= 0 ? doc.pool.get(nsIdx) : "",
                doc.pool.get(nameIdx));
        for (int i = 0; i < attributeCount; i++) {
            int a = pos + header + attributeStart + i * stride;
            int aNs = in.i32(a);
            int aName = in.i32(a + 4);
            int aRaw = in.i32(a + 8);
            // typed value: size u16, res0 u8, dataType u8, data u32
            // (offsets relative to the attribute start).
            int valueType = in.u8(a + 15);
            int valueData = in.i32(a + 16);
            int resId = 0;
            if (doc.resourceMap != null
                    && aName >= 0 && aName < doc.resourceMap.length) {
                resId = doc.resourceMap[aName];
            }
            AxmlAttribute attr = new AxmlAttribute(
                    aNs >= 0 ? doc.pool.get(aNs) : "",
                    doc.pool.get(aName), resId,
                    aRaw >= 0 ? doc.pool.get(aRaw) : null,
                    valueType, valueData);
            attr.nameIndex = aName;
            attr.nsIndex = aNs;
            attr.rawIndex = aRaw;
            e.attributes.add(attr);
        }
        return e;
    }

    /** Decode + render in one call (inspection fast path). */
    public static String decodeToString(byte[] data) {
        return decode(data).toXmlString();
    }

    /** Decode + render with id resolution in one call. */
    public static String decodeToString(byte[] data, IdResolver resolver) {
        return decode(data, resolver).toXmlString();
    }
}
