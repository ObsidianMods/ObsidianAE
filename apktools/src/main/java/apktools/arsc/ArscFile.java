package apktools.arsc;

import apktools.ApkException;
import apktools.BinReader;
import apktools.StringPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed {@code resources.arsc} table.
 *
 * <p>Parsing is lazy by design: {@link #open(byte[])} reads the header,
 * the string pools and a table of contents (package/type chunks with
 * offsets). Type entries and values decode on first access and are then
 * cached. Fast paths ({@link #totalEntries}, {@link #locales},
 * {@link #configCount}) never touch entry values.
 *
 * <p>Unknown chunks are recorded with their byte ranges so
 * {@link #toBytes()} round-trips future platform additions verbatim.
 */
public final class ArscFile {

    public static final int CHUNK_TABLE = 0x0002;
    public static final int CHUNK_PACKAGE = 0x0200;
    public static final int CHUNK_TYPE = 0x0201;
    public static final int CHUNK_TYPE_SPEC = 0x0202;
    public static final int CHUNK_LIBRARY = 0x0203;
    public static final int CHUNK_OVERLAYABLE = 0x0204;
    public static final int CHUNK_OVERLAYABLE_POLICY = 0x0205;
    public static final int CHUNK_STAGED_ALIAS = 0x0206;

    /** Table-of-contents record for a rebuildable chunk. */
    static final class ChunkToc {
        int kind; // CHUNK_TYPE_SPEC / CHUNK_TYPE / other (raw)
        int offset;
        int size;
        ArscType type; // when kind == CHUNK_TYPE
        int specId; // when kind == CHUNK_TYPE_SPEC
    }

    /** Unknown top-level chunk preserved verbatim on rebuild. */
    static final class RawChunk {
        final int offset;
        final int size;

        RawChunk(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }

    private final byte[] data;
    private final BinReader in;
    private final StringPool strings;
    private final byte[] secondPoolRaw; // optional styles pool, verbatim
    private final List<ArscPackage> packages = new ArrayList<>();
    private final List<RawChunk> unknownChunks = new ArrayList<>();
    /** Shared-library package names from library chunks (0x203). */
    private final Map<Integer, String> libNames = new LinkedHashMap<>();
    /** Staged (unfinalized) id -> finalized id (0x206 chunks). */
    private final Map<Integer, Integer> stagedAliases = new LinkedHashMap<>();

    /** Strings appended by edits; appended to the pool on rebuild. */
    private final LinkedHashSet<String> pendingStrings = new LinkedHashSet<>();

    private ArscFile(byte[] data, BinReader in, StringPool strings,
                     byte[] secondPoolRaw) {
        this.data = data;
        this.in = in;
        this.strings = strings;
        this.secondPoolRaw = secondPoolRaw;
    }

    public static ArscFile open(byte[] data) {
        BinReader in = new BinReader(data);
        int type = in.u16(0);
        if (type != CHUNK_TABLE) {
            throw ApkException.badMagic("ARSC", type, 0);
        }
        int headerSize = in.u16(2);
        long size = in.u32(4);
        long packageCount = in.u32(8);
        if (headerSize < 12 || size < headerSize
                || size > Integer.MAX_VALUE || packageCount > 1000) {
            throw ApkException.badChunk("ARSC header", 0);
        }
        in.check(0, (int) size);
        int end = (int) size;

        int pos = headerSize;
        if (in.u16(pos) != 0x0001) {
            throw new ApkException(ApkException.Code.ARSC,
                    "first chunk is not a string pool", pos);
        }
        StringPool strings = StringPool.parse(in, pos);
        pos += strings.chunkSize();

        byte[] secondPoolRaw = null;
        if (pos < end && in.u16(pos) == 0x0001) {
            StringPool second = StringPool.parse(in, pos);
            secondPoolRaw = in.slice(pos, second.chunkSize());
            pos += second.chunkSize();
        }

        ArscFile file = new ArscFile(data, in, strings, secondPoolRaw);
        while (pos < end) {
            in.check(pos, 8);
            int ct = in.u16(pos);
            int ch = in.u16(pos + 2);
            long cs = in.u32(pos + 4);
            if (ch < 8 || cs < ch || cs > Integer.MAX_VALUE
                    || pos + cs > end || cs == 0) {
                throw ApkException.badChunk(
                        "chunk 0x" + Integer.toHexString(ct), pos);
            }
            if (ct == CHUNK_PACKAGE) {
                file.packages.add(parsePackage(file, pos, (int) cs));
            } else {
                file.unknownChunks.add(new RawChunk(pos, (int) cs));
            }
            pos += (int) cs;
        }
        return file;
    }

    // -- access ------------------------------------------------------------

    BinReader reader() {
        return in;
    }

    byte[] bytes() {
        return data;
    }

    /** Global (value) string pool. */
    public StringPool strings() {
        return strings;
    }

    public List<ArscPackage> packages() {
        return Collections.unmodifiableList(packages);
    }

    public ArscPackage findPackage(int id) {
        for (ArscPackage p : packages) {
            if (p.id() == id) return p;
        }
        return null;
    }

    /** First package (normal apps have exactly one, id 0x7F). */
    public ArscPackage mainPackage() {
        if (packages.isEmpty()) return null;
        ArscPackage app = findPackage(0x7F);
        return app != null ? app : packages.get(0);
    }

    /** Resolve a resource id to its default-config entry, or null. */
    public ArscEntry resolve(int resId) {
        return resolve(resId, null);
    }

    /** Resolve with config preference (see {@link ResConfig#betterThan}). */
    public ArscEntry resolve(int resId, ResConfig want) {
        resId = finalizeId(resId);
        ArscPackage p = findPackage(ResourceId.pkg(resId));
        if (p == null) return null;
        return p.entry(ResourceId.type(resId), ResourceId.entry(resId), want);
    }

    /** Shared-library display name for a package id, or null. */
    public String libName(int pkgId) {
        return libNames.get(pkgId);
    }

    /**
     * Maps a staged (unfinalized) resource id to its finalized id.
     * Returns the input unchanged when no alias is recorded.
     */
    public int finalizeId(int resId) {
        Integer fin = stagedAliases.get(resId);
        return fin == null ? resId : fin;
    }

    void addStagedAlias(int staged, int finalized) {
        stagedAliases.put(staged, finalized);
    }

    /** Resolve + require (throws {@link ApkException} when missing). */
    public ArscEntry require(int resId) {
        ArscEntry e = resolve(resId);
        if (e == null) {
            throw ApkException.notFound(
                    "resource " + ResourceId.hex(resId));
        }
        return e;
    }

    // -- fast stats (no value decoding) --------------------------------------

    /** Live entries across all packages (offset arrays only). */
    public int totalEntries() {
        int n = 0;
        for (ArscPackage p : packages) n += p.liveEntryCount();
        return n;
    }

    public int typeChunkCount() {
        int n = 0;
        for (ArscPackage p : packages) n += p.allTypes().size();
        return n;
    }

    public int configCount() {
        return typeChunkCount();
    }

    /** Distinct locale tags across all type configs. */
    public Set<String> locales() {
        Set<String> out = new LinkedHashSet<>();
        for (ArscPackage p : packages) {
            for (ArscType t : p.allTypes()) out.add(t.config().localeTag());
        }
        return out;
    }

    // -- editing ---------------------------------------------------------------

    /**
     * Interns a string for {@link ArscEntry#setString}: returns the index
     * it will occupy after {@link #toBytes()} (existing or appended).
     */
    int internString(String s) {
        int idx = strings.indexOf(s);
        if (idx >= 0) return idx;
        int i = 0;
        for (String p : pendingStrings) {
            if (p.equals(s)) return strings.count() + i;
            i++;
        }
        pendingStrings.add(s);
        return strings.count() + pendingStrings.size() - 1;
    }

    List<String> pendingStrings() {
        return new ArrayList<>(pendingStrings);
    }

    List<RawChunk> unknownChunks() {
        return unknownChunks;
    }

    // -- rebuild -----------------------------------------------------------------

    /** Re-emits the table, applying any entry edits. */
    public byte[] toBytes() {
        return ArscBuilder.build(this);
    }

    // -- parse ---------------------------------------------------------------------

    private static ArscPackage parsePackage(ArscFile file, int base, int size) {
        BinReader in = file.in;
        if (size < 288) {
            throw ApkException.badChunk("package too small", base);
        }
        long id = in.u32(base + 8);
        String name = in.utf16String(base + 12, 128);
        long typeStrings = in.u32(base + 268);
        long keyStrings = in.u32(base + 276);
        long typeIdOffset = in.u32(base + 284);
        if (typeStrings >= size || keyStrings >= size) {
            throw new ApkException(ApkException.Code.ARSC,
                    "package pool offset out of range", base);
        }
        StringPool typePool =
                StringPool.parse(in, base + (int) typeStrings);
        StringPool keyPool =
                StringPool.parse(in, base + (int) keyStrings);
        ArscPackage pkg = new ArscPackage(file, (int) id, name,
                typePool, keyPool, (int) typeIdOffset);

        int headerSize = in.u16(base + 2);
        int pos = base + headerSize;
        int end = base + size;
        while (pos < end) {
            in.check(pos, 8);
            int ct = in.u16(pos);
            int ch = in.u16(pos + 2);
            long cs = in.u32(pos + 4);
            if (ch < 8 || cs < ch || cs > Integer.MAX_VALUE
                    || pos + cs > end || cs == 0) {
                throw ApkException.badChunk(
                        "package chunk 0x" + Integer.toHexString(ct), pos);
            }
            ChunkToc toc = new ChunkToc();
            toc.kind = ct;
            toc.offset = pos;
            toc.size = (int) cs;
            if (ct == 0x0001) {
                // Type/key pools are re-emitted from the model by the
                // builder; leaving them out of the TOC avoids emitting
                // them twice.
                pos += (int) cs;
                continue;
            }
            if (ct == CHUNK_TYPE_SPEC) {
                toc.specId = parseSpec(pkg, pos);
            } else if (ct == CHUNK_TYPE) {
                toc.type = parseType(pkg, pos, (int) cs);
                pkg.addType(toc.type);
            } else if (ct == CHUNK_LIBRARY) {
                parseLibrary(file, pos, (int) cs);
            } else if (ct == CHUNK_STAGED_ALIAS) {
                parseStagedAlias(file, pos, (int) cs);
            }
            // Overlayable/policy chunks have no lookup role; like the
            // pools-skip above they stay in the TOC for verbatim rebuild.
            pkg.chunks.add(toc);
            pos += (int) cs;
        }
        return pkg;
    }

    private static int parseSpec(ArscPackage pkg, int pos) {
        BinReader in = pkg.file().reader();
        int id = in.u8(pos + 8);
        long entryCount = in.u32(pos + 12);
        if (entryCount > 10_000_000) {
            throw new ApkException(ApkException.Code.ARSC,
                    "implausible spec entryCount " + entryCount, pos);
        }
        int n = (int) entryCount;
        int[] flags = new int[n];
        for (int i = 0; i < n; i++) {
            flags[i] = (int) in.u32(pos + 16 + i * 4);
        }
        pkg.setSpecFlags(id, flags);
        return id;
    }

    private static void parseLibrary(ArscFile file, int pos, int size) {
        BinReader in = file.in;
        long count = in.u32(pos + 8);
        if (count > 1000) {
            throw new ApkException(ApkException.Code.ARSC,
                    "implausible library count " + count, pos);
        }
        for (long i = 0; i < count; i++) {
            int e = pos + 12 + (int) i * 260;
            if (e + 260 > pos + size) {
                throw new ApkException(ApkException.Code.ARSC,
                        "library entry out of chunk", e);
            }
            int pkgId = (int) in.u32(e);
            file.libNames.put(pkgId, in.utf16String(e + 4, 128));
        }
    }

    private static void parseStagedAlias(ArscFile file, int pos, int size) {
        BinReader in = file.in;
        long count = in.u32(pos + 8);
        if (count > 10_000_000) {
            throw new ApkException(ApkException.Code.ARSC,
                    "implausible staged-alias count " + count, pos);
        }
        for (long i = 0; i < count; i++) {
            int e = pos + 12 + (int) i * 8;
            if (e + 8 > pos + size) {
                throw new ApkException(ApkException.Code.ARSC,
                        "staged-alias entry out of chunk", e);
            }
            file.addStagedAlias((int) in.u32(e), (int) in.u32(e + 4));
        }
    }

    private static ArscType parseType(ArscPackage pkg, int pos, int size) {
        BinReader in = pkg.file().reader();
        int headerSize = in.u16(pos + 2);
        int id = in.u8(pos + 8);
        int offsetFormat = in.u8(pos + 9);
        long entryCount = in.u32(pos + 12);
        long entriesStart = in.u32(pos + 16);
        if (entryCount > 10_000_000 || headerSize < 20
                || headerSize > size || entriesStart > size) {
            throw new ApkException(ApkException.Code.ARSC,
                    "bad type header", pos);
        }
        if (offsetFormat != ArscType.OFFSET_32
                && offsetFormat != ArscType.OFFSET_16
                && offsetFormat != ArscType.OFFSET_SPARSE) {
            throw new ApkException(ApkException.Code.ARSC,
                    "unknown offset format " + offsetFormat, pos);
        }
        long arrayBytes = offsetFormat == ArscType.OFFSET_16
                ? entryCount * 2L : entryCount * 4L;
        if (arrayBytes > size - headerSize) {
            throw new ApkException(ApkException.Code.ARSC,
                    "entries array out of chunk", pos);
        }
        int configOff = pos + 20;
        ResConfig config = ResConfig.parse(in, configOff);
        // Offsets array follows the config (at headerSize); per-entry
        // offsets are relative to the values base (entriesStart).
        return new ArscType(pkg, id, config, (int) entryCount,
                pos, size, pos + headerSize, pos + (int) entriesStart,
                offsetFormat);
    }
}
