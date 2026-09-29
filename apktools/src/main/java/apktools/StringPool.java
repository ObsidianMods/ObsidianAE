package apktools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android {@code ResStringPool} reader + builder, shared by the XML
 * ({@code apktools.xml}) and ARSC ({@code apktools.arsc}) systems.
 *
 * <p>Handles both UTF-8 and UTF-16 pools, sorted or not, with or without
 * style spans. String decoding is lazy and cached: {@link #get(int)}
 * decodes each entry at most once, so header scans stay cheap.
 *
 * <p>Style spans are preserved verbatim when the pool is re-emitted
 * unchanged; if strings were added/changed the styles are dropped (styled
 * spans index into the old ordering, so keeping them would corrupt text).
 */
public final class StringPool {

    public static final int FLAG_SORTED = 1;
    public static final int FLAG_UTF8 = 1 << 8;

    private final BinReader in;
    private final int chunkOff;
    private final int chunkSize;
    private final int count;
    private final int styleCount;
    private final int flags;
    private final int[] offsets;
    private final int stringsStart;
    private final int stylesStart;
    private final byte[] styleData;
    private final String[] cache;

    private StringPool(BinReader in, int chunkOff, int chunkSize,
                       int count, int styleCount, int flags,
                       int[] offsets, int stringsStart, int stylesStart,
                       byte[] styleData) {
        this.in = in;
        this.chunkOff = chunkOff;
        this.chunkSize = chunkSize;
        this.count = count;
        this.styleCount = styleCount;
        this.flags = flags;
        this.offsets = offsets;
        this.stringsStart = stringsStart;
        this.stylesStart = stylesStart;
        this.styleData = styleData;
        this.cache = new String[count];
    }

    public static StringPool parse(BinReader in, int chunkOff) {
        int type = in.u16(chunkOff);
        if (type != 0x0001) {
            throw ApkException.badMagic("StringPool", type, chunkOff);
        }
        int headerSize = in.u16(chunkOff + 2);
        long size = in.u32(chunkOff + 4);
        if (headerSize < 28 || size < headerSize || size > Integer.MAX_VALUE) {
            throw ApkException.badChunk("StringPool header", chunkOff);
        }
        in.check(chunkOff, (int) size);
        long count = in.u32(chunkOff + 8);
        long styleCount = in.u32(chunkOff + 12);
        int flags = (int) in.u32(chunkOff + 16);
        long stringsStart = in.u32(chunkOff + 20);
        long stylesStart = in.u32(chunkOff + 24);
        if (count > 10_000_000 || styleCount > 10_000_000) {
            throw new ApkException(ApkException.Code.STRING_POOL,
                    "implausible counts " + count + "/" + styleCount, chunkOff);
        }
        int n = (int) count;
        int[] offsets = new int[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = (int) in.u32(chunkOff + headerSize + i * 4);
        }
        byte[] styleData = null;
        if (styleCount > 0) {
            if (stylesStart <= 0 || stylesStart > size) {
                throw new ApkException(ApkException.Code.STRING_POOL,
                        "bad stylesStart " + stylesStart, chunkOff);
            }
            styleData = in.slice(chunkOff + (int) stylesStart,
                    (int) (size - stylesStart));
        }
        return new StringPool(in, chunkOff, (int) size, n, (int) styleCount,
                flags, offsets, chunkOff + (int) stringsStart,
                stylesStart > 0 ? chunkOff + (int) stylesStart : -1, styleData);
    }

    public int chunkSize() {
        return chunkSize;
    }

    /** Verbatim chunk bytes (used when rebuilding without changes). */
    public byte[] rawBytes() {
        return in.slice(chunkOff, chunkSize);
    }

    public int styleCount() {
        return styleCount;
    }

    public int count() {
        return count;
    }

    public boolean isUtf8() {
        return (flags & FLAG_UTF8) != 0;
    }

    /** Decoded string, or null for index -1. Bounds-checked. */
    public String get(int idx) {
        if (idx < 0) return null;
        if (idx >= count) {
            throw new ApkException(ApkException.Code.STRING_POOL,
                    "string index " + idx + " >= " + count);
        }
        String s = cache[idx];
        if (s == null) {
            s = decode(idx);
            cache[idx] = s;
        }
        return s;
    }

    private String decode(int idx) {
        int off = stringsStart + offsets[idx];
        if (isUtf8()) {
            int p = off;
            int charLen = readVarU16(p);
            p += varU16Size(p);
            int byteLen = readVarU16(p);
            p += varU16Size(p);
            in.check(p - in.baseOffset(), byteLen + 1);
            byte[] raw = in.slice(p - in.baseOffset(), byteLen);
            return new String(raw, StandardCharsets.UTF_8);
        }
        int len = in.u16(off - in.baseOffset());
        int p = off - in.baseOffset() + 2;
        if ((len & 0x8000) != 0) {
            int high = len & 0x7FFF;
            int low = in.u16(p);
            len = (high << 16) | low;
            p += 2;
        }
        in.check(p, len * 2 + 2);
        char[] chars = new char[len];
        for (int i =  0; i < len; i++) chars[i] = (char) in.u16(p + i * 2);
        return new String(chars);
    }

    private int readVarU16(int absOff) {
        int rel = absOff - in.baseOffset();
        int b0 = in.u8(rel);
        if ((b0 & 0x80) == 0) return b0;
        return ((b0 & 0x7F) << 8) | in.u8(rel + 1);
    }

    private int varU16Size(int absOff) {
        return (in.u8(absOff - in.baseOffset()) & 0x80) == 0 ? 1 : 2;
    }

    /** All strings decoded (used by rebuild + tests, not by fast paths). */
    public List<String> all() {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(get(i));
        return out;
    }

    /** Index of {@code s}, or -1. Linear scan — use sparingly. */
    public int indexOf(String s) {
        for (int i = 0; i < count; i++) {
            if (get(i).equals(s)) return i;
        }
        return -1;
    }

    // -- emit ------------------------------------------------------------

    /**
     * Re-emits this pool, preserving styles only when nothing changed.
     * New strings (via {@code extra}) are appended, keeping old indices.
     */
    public byte[] toBytes(boolean utf8, List<String> extra) {
        List<String> strings = all();
        boolean changed = false;
        if (extra != null && !extra.isEmpty()) {
            java.util.HashSet<String> seen = new java.util.HashSet<>(strings);
            for (String s : extra) {
                if (seen.add(s)) {
                    strings.add(s);
                    changed = true;
                }
            }
        }
        return emit(strings, utf8, !changed ? styleData : null,
                !changed ? styleCount : 0);
    }

    public byte[] toBytes(boolean utf8) {
        return toBytes(utf8, null);
    }

    /** Builder for pools constructed from scratch (encoder side). */
    public static final class Builder {
        private final Map<String, Integer> index = new LinkedHashMap<>();

        public int add(String s) {
            Integer i = index.get(s);
            if (i == null) {
                i = index.size();
                index.put(s, i);
            }
            return i;
        }

        public int get(String s) {
            Integer i = index.get(s);
            return i == null ? -1 : i;
        }

        public List<String> strings() {
            return new ArrayList<>(index.keySet());
        }

        public byte[] toBytes(boolean utf8) {
            return emit(strings(), utf8, null, 0);
        }
    }

    /**
     * Emits a pool from an explicit string list, preserving duplicates
     * and order verbatim. Used when rebuilding a decoded pool whose
     * indices (including resource-map alignment) must not shift.
     */
    public static byte[] emitAll(java.util.List<String> strings,
                                 boolean utf8) {
        return emit(new java.util.ArrayList<>(strings), utf8, null, 0);
    }

    private static byte[] emit(List<String> strings, boolean utf8,
                               byte[] styleData, int styleCount) {
        BinWriter w = new BinWriter();
        w.u16(0x0001);
        w.u16(28);
        int sizePos = w.position();
        w.u32(0); // patched later
        w.u32(strings.size());
        w.u32(styleCount);
        w.u32(utf8 ? FLAG_UTF8 : 0);
        int stringsStartPos = w.position();
        w.u32(0); // stringsStart, patched
        w.u32(0); // stylesStart, patched
        int offsetsPos = w.position();
        for (int i = 0; i < strings.size(); i++) w.u32(0);
        // styles would go here; we only preserve original blobs, which the
        // parse path handles by copying the whole chunk instead.
        int dataStart = w.position();
        int[] offs = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offs[i] = w.position() - dataStart;
            writeString(w, strings.get(i), utf8);
        }
        w.align(4);
        for (int i = 0; i < offs.length; i++) {
            w.patchU32(offsetsPos + i * 4, offs[i]);
        }
        w.patchU32(stringsStartPos, dataStart);
        // size covers the whole chunk including its 8-byte header
        w.patchU32(sizePos, w.position());
        return w.toByteArray();
    }

    private static void writeString(BinWriter w, String s, boolean utf8) {
        if (utf8) {
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            writeVarU16(w, s.length());
            writeVarU16(w, raw.length);
            w.bytes(raw);
            w.u8(0);
        } else {
            int len = s.length();
            if (len >= 0x8000) {
                w.u16(0x8000 | (len >>> 16));
                w.u16(len & 0xFFFF);
            } else {
                w.u16(len);
            }
            for (int i = 0; i < len; i++) w.u16(s.charAt(i));
            w.u16(0);
        }
    }

    private static void writeVarU16(BinWriter w, int v) {
        if (v < 0x80) w.u8(v);
        else {
            w.u8(0x80 | (v >>> 8));
            w.u8(v & 0xFF);
        }
    }
}
