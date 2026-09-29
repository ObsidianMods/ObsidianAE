package apktools.xml;

import apktools.ApkException;
import apktools.BinWriter;
import apktools.StringPool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recompiles an {@link AxmlDocument} to binary XML.
 *
 * <p>Output layout mirrors aapt/aapt2: file header, UTF-8 string pool,
 * resource map, then namespace + element chunks.
 *
 * <p>Index fidelity: the decoded pool is carried over <i>verbatim</i>
 * (duplicates and order kept) and decoded attributes reuse their
 * original pool indices, so duplicate strings with different
 * resource-map ids (e.g. bare {@code layout} on {@code <include>} vs
 * {@code android:layout}) round-trip exactly. New strings are appended;
 * hand-built documents simply start from an empty pool.
 */
public final class AxmlEncoder {

    /** Marker for pool slots with no resource-map id. */
    private static final int NO_MAP = -1;

    private AxmlEncoder() {}

    public static byte[] encode(AxmlDocument doc) {
        if (doc.root == null) {
            throw new ApkException(ApkException.Code.ENCODE, "no root element");
        }
        Ctx ctx = new Ctx();
        if (doc.pool != null) {
            List<String> seed = doc.pool.all();
            for (int i = 0; i < seed.size(); i++) {
                ctx.pool.add(seed.get(i));
                List<Integer> l = ctx.idxs.get(seed.get(i));
                if (l == null) {
                    l = new ArrayList<>();
                    ctx.idxs.put(seed.get(i), l);
                }
                l.add(i);
            }
        }
        if (doc.resourceMap != null) {
            for (int id : doc.resourceMap) ctx.map.add(id);
        }

        BinWriter w = new BinWriter();
        // File header.
        w.u16(Axml.CHUNK_XML);
        w.u16(8);
        int fileSizePos = w.position();
        w.u32(0);

        // Encode elements first into a side buffer: emitting them
        // discovers appended strings, and the pool must precede them.
        BinWriter body = new BinWriter();
        for (NsDecl d : doc.namespaces) {
            writeNamespace(body, ctx, d, true);
        }
        writeElement(body, ctx, doc.root);
        for (int i = doc.namespaces.size() - 1; i >= 0; i--) {
            writeNamespace(body, ctx, doc.namespaces.get(i), false);
        }

        // String pool (always UTF-8, like aapt2 output).
        w.bytes(StringPool.emitAll(ctx.pool, true));

        // Resource map sized exactly like the model's (extended only by
        // hand-built attributes that needed new slots).
        boolean hasIds = false;
        for (int id : ctx.map) {
            if (id != 0) {
                hasIds = true;
                break;
            }
        }
        if (hasIds) {
            w.u16(Axml.CHUNK_XML_RESOURCE_MAP);
            w.u16(8);
            w.u32(8L + ctx.map.size() * 4L);
            for (int id : ctx.map) w.i32(id);
        }

        w.bytes(body.toByteArray(), 0, body.position());
        w.patchU32(fileSizePos, w.position());
        return w.toByteArray();
    }

    // -- pool ------------------------------------------------------------

    private static final class Ctx {
        final List<String> pool = new ArrayList<>();
        final Map<String, List<Integer>> idxs = new HashMap<>();
        final List<Integer> map = new ArrayList<>();
    }

    private static void setMap(Ctx ctx, int idx, int id) {
        while (ctx.map.size() <= idx) ctx.map.add(0);
        ctx.map.set(idx, id);
    }

    /**
     * Index for {@code s}: reuses a slot whose map value already equals
     * {@code mapId} (or any slot when {@code mapId == NO_MAP}),
     * otherwise appends (duplicating the string when the ids differ —
     * exactly what aapt does for colliding names).
     */
    private static int lookup(Ctx ctx, String s, int mapId) {
        List<Integer> cand = ctx.idxs.get(s);
        if (cand != null) {
            for (int idx : cand) {
                int slot = idx < ctx.map.size() ? ctx.map.get(idx) : 0;
                if (mapId == NO_MAP || slot == mapId) {
                    if (mapId >= 0) setMap(ctx, idx, mapId);
                    return idx;
                }
            }
        }
        int idx = ctx.pool.size();
        ctx.pool.add(s);
        List<Integer> l = ctx.idxs.get(s);
        if (l == null) {
            l = new ArrayList<>();
            ctx.idxs.put(s, l);
        }
        l.add(idx);
        if (mapId >= 0) setMap(ctx, idx, mapId);
        return idx;
    }

    /** Attribute name index: stored exact index when still valid. */
    private static int nameIndex(Ctx ctx, AxmlAttribute a) {
        int stored = a.nameIndex;
        if (stored >= 0 && stored < ctx.pool.size()
                && ctx.pool.get(stored).equals(a.name)) {
            setMap(ctx, stored, a.resourceId);
            return stored;
        }
        return lookup(ctx, a.name, a.resourceId);
    }

    private static int storedOrLookup(Ctx ctx, String s, int stored) {
        if (stored >= 0 && stored < ctx.pool.size()
                && ctx.pool.get(stored).equals(s)) {
            return stored;
        }
        return lookup(ctx, s, NO_MAP);
    }

    // -- emission --------------------------------------------------------

    private static void writeNamespace(BinWriter w, Ctx ctx,
                                       NsDecl d, boolean start) {
        int chunkStart = w.position();
        w.u16(start ? Axml.CHUNK_XML_START_NAMESPACE
                : Axml.CHUNK_XML_END_NAMESPACE);
        w.u16(16);
        int sizePos = w.position();
        w.u32(0);
        w.i32(0); // lineNumber
        w.i32(-1); // comment
        w.i32(lookup(ctx, d.prefix, NO_MAP));
        w.i32(lookup(ctx, d.uri, NO_MAP));
        w.patchU32(sizePos, w.position() - chunkStart);
    }

    private static void writeElement(BinWriter w, Ctx ctx, AxmlElement e) {
        for (NsDecl d : e.namespaces) writeNamespace(w, ctx, d, true);

        int chunkStart = w.position();
        w.u16(Axml.CHUNK_XML_START_ELEMENT);
        w.u16(Axml.START_ELEMENT_HEADER_SIZE);
        int sizePos = w.position();
        w.u32(0);
        w.i32(0); // lineNumber
        w.i32(-1); // comment
        w.i32(e.namespace.isEmpty() ? -1
                : lookup(ctx, e.namespace, NO_MAP));
        w.i32(lookup(ctx, e.name, NO_MAP));
        w.u16(20); // attributeStart
        w.u16(Axml.ATTRIBUTE_SIZE);
        w.u16(e.attributes.size());
        w.u16(0xFFFF); // idIndex
        w.u16(0xFFFF); // classIndex
        w.u16(0xFFFF); // styleIndex
        for (AxmlAttribute a : e.attributes) {
            int nsIdx = a.namespace.isEmpty() ? -1
                    : storedOrLookup(ctx, a.namespace, a.nsIndex);
            w.i32(nsIdx);
            w.i32(nameIndex(ctx, a));
            int rawIdx;
            if (a.rawValue == null) {
                rawIdx = -1;
            } else {
                rawIdx = storedOrLookup(ctx, a.rawValue, a.rawIndex);
            }
            w.i32(rawIdx);
            w.u16(8); // typed value size
            w.u8(0); // res0
            w.u8(a.valueType);
            w.i32(a.valueData);
        }
        w.patchU32(sizePos, w.position() - chunkStart);

        for (AxmlNode n : e.children) {
            if (n instanceof AxmlElement) {
                writeElement(w, ctx, (AxmlElement) n);
            } else {
                writeCdata(w, ctx, ((AxmlText) n).text);
            }
        }

        int endStart = w.position();
        w.u16(Axml.CHUNK_XML_END_ELEMENT);
        w.u16(Axml.START_ELEMENT_HEADER_SIZE);
        int endSize = w.position();
        w.u32(0);
        w.i32(0); // lineNumber
        w.i32(-1); // comment
        w.i32(e.namespace.isEmpty() ? -1
                : lookup(ctx, e.namespace, NO_MAP));
        w.i32(lookup(ctx, e.name, NO_MAP));
        w.patchU32(endSize, w.position() - endStart);

        for (int i = e.namespaces.size() - 1; i >= 0; i--) {
            writeNamespace(w, ctx, e.namespaces.get(i), false);
        }
    }

    private static void writeCdata(BinWriter w, Ctx ctx, String text) {
        int chunkStart = w.position();
        w.u16(Axml.CHUNK_XML_CDATA);
        w.u16(Axml.CDATA_HEADER_SIZE);
        int sizePos = w.position();
        w.u32(0);
        w.i32(0); // lineNumber
        w.i32(-1); // comment
        int idx = lookup(ctx, text, NO_MAP);
        w.i32(idx);
        w.u16(8); // typed value size
        w.u8(0);
        w.u8(apktools.ResValue.TYPE_STRING);
        w.i32(idx);
        w.patchU32(sizePos, w.position() - chunkStart);
    }
}
