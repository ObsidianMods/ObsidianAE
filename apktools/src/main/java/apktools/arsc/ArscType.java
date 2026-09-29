package apktools.arsc;

import apktools.ApkException;
import apktools.BinReader;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code RES_TABLE_TYPE} chunk: all entries of one type id sharing a
 * single {@link ResConfig}. Entry values decode lazily — holding a type
 * costs two small int arrays, not the values.
 */
public final class ArscType {

    static final long NO_ENTRY = 0xFFFFFFFFL;

    /** Offsets array storage (TypeHeader flags byte). */
    public static final int OFFSET_32 = 0;
    public static final int OFFSET_SPARSE = 1;
    public static final int OFFSET_16 = 2;
    /** Entry flag bits (AOSP ResTable_entry). */
    static final int FLAG_COMPLEX = 0x01;
    static final int FLAG_PUBLIC = 0x02;
    static final int FLAG_WEAK = 0x04;
    static final int FLAG_COMPACT = 0x08;
    static final int FLAG_FEATURE = 0x10;
    /** Flag bits a value edit preserves (complex/compact are recomputed). */
    static final int PRESERVED_FLAGS =
            FLAG_PUBLIC | FLAG_WEAK | FLAG_FEATURE;

    final ArscPackage pkg;
    final int id;
    final ResConfig config;
    final int entryCount;
    final int chunkBase;
    final int chunkSize;
    final int entriesBase; // absolute offset of the offsets array
    final int valuesBase; // absolute offset entry offsets are relative to
    final int offsetFormat;

    private long[] offsets; // decoded entry byte-offsets or NO_ENTRY
    private java.util.Map<Integer, Long> sparse; // sparse index -> offset
    private ArscEntry[] entries; // decoded entry views, or null
    private java.util.Map<Integer, ArscEntry> sparseEntries;

    ArscType(ArscPackage pkg, int id, ResConfig config, int entryCount,
             int chunkBase, int chunkSize, int entriesBase, int valuesBase,
             int offsetFormat) {
        this.pkg = pkg;
        this.id = id;
        this.config = config;
        this.entryCount = entryCount;
        this.chunkBase = chunkBase;
        this.chunkSize = chunkSize;
        this.entriesBase = entriesBase;
        this.valuesBase = valuesBase;
        this.offsetFormat = offsetFormat;
    }

    public int id() {
        return id;
    }

    public ResConfig config() {
        return config;
    }

    public int entryCount() {
        return entryCount;
    }

    /** True when the slot holds no entry (sparse tables). */
    public boolean isEmpty(int index) {
        ensureOffsets();
        if (offsetFormat == OFFSET_SPARSE) return !sparse.containsKey(index);
        return index < 0 || index >= entryCount
                || offsets[index] == NO_ENTRY;
    }

    /** Entry view, or null for sparse slots. Decoded once, then cached. */
    public ArscEntry get(int index) {
        if (index < 0) return null;
        ensureOffsets();
        if (offsetFormat == OFFSET_SPARSE) {
            if (sparseEntries == null) {
                sparseEntries = new java.util.HashMap<>();
            }
            if (!sparseEntries.containsKey(index)) {
                Long off = sparse.get(index);
                sparseEntries.put(index,
                        off == null ? null : decode(index, off));
            }
            return sparseEntries.get(index);
        }
        if (index >= entryCount) return null;
        if (entries == null) entries = new ArscEntry[entryCount];
        ArscEntry e = entries[index];
        if (e == null && offsets[index] != NO_ENTRY) {
            e = decode(index, offsets[index]);
            entries[index] = e;
        }
        return e;
    }

    /** Number of live (non-sparse) entries. Reads offsets only. */
    public int liveCount() {
        ensureOffsets();
        if (offsetFormat == OFFSET_SPARSE) return sparse.size();
        int n = 0;
        for (int i = 0; i < entryCount; i++) {
            if (offsets[i] != NO_ENTRY) n++;
        }
        return n;
    }

    private void ensureOffsets() {
        if (offsets != null) return;
        if (offsetFormat == OFFSET_SPARSE) {
            ensureSparse();
            return;
        }
        BinReader in = pkg.file().reader();
        offsets = new long[entryCount];
        for (int i = 0; i < entryCount; i++) offsets[i] = NO_ENTRY;
        if (offsetFormat == OFFSET_32) {
            for (int i = 0; i < entryCount; i++) {
                offsets[i] = in.u32(entriesBase + i * 4);
            }
        } else {
            // OFFSET_16: stored value * 4, 0xFFFF = absent.
            for (int i = 0; i < entryCount; i++) {
                int v = in.u16(entriesBase + i * 2);
                offsets[i] = v == 0xFFFF ? NO_ENTRY : (v & 0xFFFFL) * 4;
            }
        }
    }

    /**
     * SPARSE: entryCount packed u32s (index u16 + offset/4 u16), sorted
     * by index. Slots never mentioned stay absent.
     */
    private void ensureSparse() {
        BinReader in = pkg.file().reader();
        sparse = new java.util.LinkedHashMap<>();
        offsets = new long[entryCount];
        for (int i = 0; i < entryCount; i++) offsets[i] = NO_ENTRY;
        for (int i = 0; i < entryCount; i++) {
            long packed = in.u32(entriesBase + i * 4);
            int idx = (int) (packed & 0xFFFF);
            int v = (int) ((packed >>> 16) & 0xFFFF);
            if (v == 0xFFFF) continue;
            long off = (v & 0xFFFFL) * 4;
            sparse.put(idx, off);
            if (idx < entryCount) offsets[idx] = off;
        }
    }

    private ArscEntry decode(int index, long offset) {
        BinReader in = pkg.file().reader();
        int valueOff = (int) (valuesBase + offset);
        if (valueOff < valuesBase || valueOff + 8 > chunkBase + chunkSize) {
            throw new ApkException(ApkException.Code.ARSC,
                    "entry value out of chunk", valueOff);
        }
        int size = in.u16(valueOff);
        int flags = in.u16(valueOff + 2);
        if ((flags & FLAG_COMPACT) != 0) {
            // Compact form (AOSP): key u16, flags u16 with the data
            // type in the high byte, data u32 — no Res_value follows.
            int keyIndex = in.u16(valueOff);
            int valueType = in.u8(valueOff + 3);
            int valueData = in.i32(valueOff + 4);
            return new ArscEntry(pkg, id, index, keyIndex,
                    pkg.specFlags(id, index), config,
                    false, 0, valueType, valueData, null,
                    valueOff, 8, flags);
        }
        int keyIndex = (int) in.u32(valueOff + 4);
        boolean complex = (flags & 1) != 0;
        int specFlags = pkg.specFlags(id, index);
        if (!complex) {
            int valueType = in.u8(valueOff + 8 + 3);
            int valueData = in.i32(valueOff + 8 + 4);
            return new ArscEntry(pkg, id, index, keyIndex, specFlags, config,
                    false, 0, valueType, valueData, null,
                    valueOff, 16, flags); // entry header (8) + Res_value (8)
        }
        int parent = (int) in.u32(valueOff + 8);
        long count = in.u32(valueOff + 12);
        if (count > 100000) {
            throw new ApkException(ApkException.Code.ARSC,
                    "implausible bag size " + count, valueOff);
        }
        List<BagItem> items = new ArrayList<>((int) count);
        int p = valueOff + 16;
        for (long i = 0; i < count; i++) {
            int name = (int) in.u32(p);
            int vt = in.u8(p + 4 + 3);
            int vd = in.i32(p + 4 + 4);
            items.add(new BagItem(name, vt, vd));
            p += 12;
        }
        return new ArscEntry(pkg, id, index, keyIndex, specFlags, config,
                true, parent, 0, 0, items, valueOff, p - valueOff, flags);
    }

    /** Raw value bytes of entry {@code index} (for verbatim rebuild). */
    int[] rawRange(int index) {
        ArscEntry e = get(index);
        if (e == null) return null;
        return new int[]{e.rawOffset, e.rawLength};
    }
}
