package apktools.apk;

import apktools.ApkException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Minimal fast ZIP reader for APKs.
 *
 * <p>Reads only the end-of-central-directory + central directory up
 * front (kilobytes, not megabytes), then inflates individual entries on
 * demand. Data offsets come from local headers; sizes always come from
 * the central directory, so data-descriptor entries work. ZIP64 extra
 * fields are honored for large archives.
 *
 * <p>Not a general ZIP replacement — no encryption, no multi-disk, no
 * archives that hide the EOCD — but strict where APKs need it, with
 * {@link ApkException} (not raw IO crashes) on corrupt input.
 */
public final class ApkReader {

    public static final int METHOD_STORED = 0;
    public static final int METHOD_DEFLATED = 8;

    /** Central-directory metadata for one entry. */
    public static final class Entry {
        public final String name;
        public final int method;
        public final long compSize;
        public final long uncompSize;
        public final long localHeaderOffset;
        public final long crc;

        Entry(String name, int method, long compSize, long uncompSize,
              long localHeaderOffset, long crc) {
            this.name = name;
            this.method = method;
            this.compSize = compSize;
            this.uncompSize = uncompSize;
            this.localHeaderOffset = localHeaderOffset;
            this.crc = crc;
        }

        public boolean isDirectory() {
            return name.endsWith("/");
        }
    }

    private final File file; // null when memory-backed
    private final byte[] memory; // null when file-backed
    private final List<Entry> entries;
    private final Map<String, Entry> byName;
    private final long centralDirOffset;

    private ApkReader(File file, byte[] memory, List<Entry> entries,
                      long centralDirOffset) {
        this.file = file;
        this.memory = memory;
        this.entries = entries;
        this.byName = new LinkedHashMap<>();
        for (Entry e : entries) byName.put(e.name, e);
        this.centralDirOffset = centralDirOffset;
    }

    // -- open --------------------------------------------------------------

    public static ApkReader open(File file) {
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                long len = raf.length();
                long eocd = findEocd(new Raf(raf), len);
                raf.seek(eocd + 10);
                long totalEntries = readU16(raf);
                long cdSize = readU32(raf);
                long cdOffset = readU32(raf);
                List<Entry> entries = readCentralDir(raf, cdOffset, totalEntries);
                return new ApkReader(file, null, entries, cdOffset);
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            throw ApkException.io("reading zip " + file, e);
        }
    }

    public static ApkReader open(byte[] data, String name) {
        try {
            Mem mem = new Mem(data);
            long eocd = findEocd(mem, data.length);
            mem.pos(eocd + 10);
            long totalEntries = mem.u16();
            long cdSize = mem.u32();
            long cdOffset = mem.u32();
            List<Entry> entries = readCentralDir(mem, cdOffset, totalEntries);
            return new ApkReader(null, data, entries, cdOffset);
        } catch (IOException e) {
            throw ApkException.io("reading zip " + name, e);
        }
    }

    // -- access --------------------------------------------------------------

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public Entry find(String name) {
        return byName.get(name);
    }

    public boolean has(String name) {
        return byName.containsKey(name);
    }

    /** Names of {@code classes*.dex} in order (classes.dex first). */
    public List<String> dexNames() {
        List<String> out = new ArrayList<>();
        Entry base = byName.get("classes.dex");
        if (base != null) out.add(base.name);
        for (int i = 2; ; i++) {
            Entry e = byName.get("classes" + i + ".dex");
            if (e == null) break;
            out.add(e.name);
        }
        return out;
    }

    /** Raw (possibly compressed) bytes of an entry. */
    public byte[] readBytes(String name) {
        Entry e = byName.get(name);
        if (e == null) throw ApkException.notFound("zip entry " + name);
        if (e.uncompSize > Integer.MAX_VALUE) {
            throw ApkException.unsupported("entry too large: " + name);
        }
        try {
            if (e.method == METHOD_STORED) {
                return readRaw(e.localHeaderOffset, name, e.compSize);
            } else if (e.method == METHOD_DEFLATED) {
                byte[] comp = readRaw(e.localHeaderOffset, name, e.compSize);
                Inflater inflater = new Inflater(true);
                try {
                    inflater.setInput(comp);
                    byte[] out = new byte[(int) e.uncompSize];
                    int done = 0;
                    while (!inflater.finished() && done < out.length) {
                        int n = inflater.inflate(out, done, out.length - done);
                        if (n == 0) {
                            if (inflater.needsInput()) break;
                            throw ApkException.badChunk("deflate stall " + name,
                                    e.localHeaderOffset);
                        }
                        done += n;
                    }
                    if (done != out.length) {
                        throw new ApkException(ApkException.Code.ZIP,
                                "size mismatch inflating " + name
                                        + " (" + done + "/" + out.length + ")");
                    }
                    return out;
                } catch (java.util.zip.DataFormatException ex) {
                    throw new ApkException(ApkException.Code.ZIP,
                            "deflate error " + name, ex);
                } finally {
                    inflater.end();
                }
            }
            throw ApkException.unsupported(
                    "compression method " + e.method + " for " + name);
        } catch (IOException ex) {
            throw ApkException.io("reading entry " + name, ex);
        }
    }

    /** Decompressed stream for an entry. */
    public InputStream openStream(String name) {
        Entry e = byName.get(name);
        if (e == null) throw ApkException.notFound("zip entry " + name);
        try {
            if (e.method == METHOD_STORED) {
                long dataOff = dataOffset(e.localHeaderOffset, name);
                if (file != null) {
                    RandomAccessFile raf = new RandomAccessFile(file, "r");
                    raf.seek(dataOff);
                    return new RafStream(raf, e.compSize);
                }
                return new ByteArrayInputStream(memory, (int) dataOff,
                        (int) e.compSize);
            } else if (e.method == METHOD_DEFLATED) {
                return new InflaterInputStream(
                        new ByteArrayInputStream(readRaw(
                                e.localHeaderOffset, name, e.compSize)),
                        new Inflater(true));
            }
            throw ApkException.unsupported(
                    "compression method " + e.method + " for " + name);
        } catch (IOException ex) {
            throw ApkException.io("opening entry " + name, ex);
        }
    }

    /**
     * Raw entry stream that never buffers the whole entry: STORED data
     * is sliced straight off the source, DEFLATED data inflates on the
     * fly. Closing abandons the read cheaply (no skip), which makes it
     * ideal for bounded scans of nested archives (split APK bundles).
     * Caller must close.
     */
    public InputStream openRawStream(String name) {
        Entry e = byName.get(name);
        if (e == null) throw ApkException.notFound("zip entry " + name);
        if (e.method != METHOD_STORED && e.method != METHOD_DEFLATED) {
            throw ApkException.unsupported(
                    "compression method " + e.method + " for " + name);
        }
        try {
            InputStream raw;
            if (file != null) {
                long dataOff = dataOffset(e.localHeaderOffset, name);
                RandomAccessFile raf = new RandomAccessFile(file, "r");
                try {
                    raf.seek(dataOff);
                    raw = new RafStream(raf, e.compSize);
                } catch (Throwable t) {
                    try {
                        raf.close();
                    } catch (IOException ignored) {
                    }
                    throw t;
                }
            } else {
                long dataOff = dataOffset(e.localHeaderOffset, name);
                raw = new ByteArrayInputStream(memory, (int) dataOff,
                        (int) Math.min(e.compSize, Integer.MAX_VALUE));
            }
            if (e.method == METHOD_DEFLATED) {
                return new InflaterInputStream(raw, new Inflater(true));
            }
            return raw;
        } catch (IOException ex) {
            throw ApkException.io("opening entry " + name, ex);
        }
    }

    public long centralDirOffset() {
        return centralDirOffset;
    }

    /** Raw byte range from the underlying source (signing-block scans). */
    public byte[] readRawRange(long offset, int length) {
        try {
            byte[] out = new byte[length];
            if (file != null) {
                RandomAccessFile raf = new RandomAccessFile(file, "r");
                try {
                    raf.seek(offset);
                    raf.readFully(out);
                } finally {
                    raf.close();
                }
            } else {
                System.arraycopy(memory, (int) offset, out, 0, length);
            }
            return out;
        } catch (IOException e) {
            throw ApkException.io("reading raw range", e);
        }
    }

    public File file() {
        return file;
    }

    // -- internals ---------------------------------------------------------------

    private interface Src {
        void pos(long p) throws IOException;

        long pos() throws IOException;

        long length() throws IOException;

        int u8() throws IOException;

        int u16() throws IOException;

        long u32() throws IOException;

        void readFully(byte[] b, int o, int l) throws IOException;
    }

    private static final class Raf implements Src {
        final RandomAccessFile raf;

        Raf(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override public void pos(long p) throws IOException {
            raf.seek(p);
        }

        @Override public long pos() throws IOException {
            return raf.getFilePointer();
        }

        @Override public long length() throws IOException {
            return raf.length();
        }

        @Override public int u8() throws IOException {
            return raf.readUnsignedByte();
        }

        @Override public int u16() throws IOException {
            int a = raf.readUnsignedByte();
            int b = raf.readUnsignedByte();
            return a | (b << 8);
        }

        @Override public long u32() throws IOException {
            long a = raf.readUnsignedByte();
            long b = raf.readUnsignedByte();
            long c = raf.readUnsignedByte();
            long d = raf.readUnsignedByte();
            return a | (b << 8) | (c << 16) | (d << 24);
        }

        @Override public void readFully(byte[] b, int o, int l) throws IOException {
            raf.readFully(b, o, l);
        }
    }

    private static final class Mem implements Src {
        final byte[] b;
        int p;

        Mem(byte[] b) {
            this.b = b;
        }

        @Override public void pos(long p) {
            this.p = (int) p;
        }

        @Override public long pos() {
            return p;
        }

        @Override public long length() {
            return b.length;
        }

        @Override public int u8() {
            return b[p++] & 0xFF;
        }

        @Override public int u16() {
            int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
            p += 2;
            return v;
        }

        @Override public long u32() {
            long v = (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8)
                    | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
            p += 4;
            return v;
        }

        @Override public void readFully(byte[] dst, int o, int l) {
            System.arraycopy(b, p, dst, o, l);
            p += l;
        }
    }

    private static long readU16(RandomAccessFile raf) throws IOException {
        int a = raf.readUnsignedByte();
        int b = raf.readUnsignedByte();
        return a | (b << 8);
    }

    private static long readU32(RandomAccessFile raf) throws IOException {
        long a = raf.readUnsignedByte();
        long b = raf.readUnsignedByte();
        long c = raf.readUnsignedByte();
        long d = raf.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    private static long findEocd(Raf raf, long len) throws IOException {
        return findEocd((Src) raf, len);
    }

    private static long findEocd(Src src, long len) throws IOException {
        // EOCD is at least 22 bytes; comment can push it back up to 64K.
        long scanSize = Math.min(len, 65557L + 22);
        long scanStart = len - scanSize;
        // Read the tail once for speed.
        int n = (int) scanSize;
        byte[] tail = new byte[n];
        src.pos(scanStart);
        src.readFully(tail, 0, n);
        for (int i = n - 22; i >= 0; i--) {
            if (tail[i] == 0x50 && tail[i + 1] == 0x4B
                    && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                return scanStart + i;
            }
        }
        throw new ApkException(ApkException.Code.ZIP, "EOCD not found");
    }

    private static List<Entry> readCentralDir(RandomAccessFile raf,
                                             long cdOffset,
                                             long totalEntries) throws IOException {
        return readCentralDir((Src) new Raf(raf), cdOffset, totalEntries);
    }

    private static List<Entry> readCentralDir(Src src, long cdOffset,
                                             long totalEntries) throws IOException {
        if (totalEntries > 500000) {
            throw new ApkException(ApkException.Code.ZIP,
                    "implausible entry count " + totalEntries);
        }
        List<Entry> out = new ArrayList<>((int) Math.min(totalEntries, 65536));
        src.pos(cdOffset);
        byte[] nameBuf = new byte[512];
        for (long i = 0; i < totalEntries; i++) {
            long sig = src.u32();
            if (sig != 0x02014b50L) {
                throw new ApkException(ApkException.Code.ZIP,
                        "bad central dir signature 0x" + Long.toHexString(sig),
                        cdOffset);
            }
            src.pos(src.pos() + 6); // version made/by + flags
            int method = src.u16();
            src.pos(src.pos() + 4); // time + date
            long crc = src.u32();
            long compSize = src.u32();
            long uncompSize = src.u32();
            int nameLen = src.u16();
            int extraLen = src.u16();
            int commentLen = src.u16();
            src.pos(src.pos() + 8); // disk + attrs
            long localOffset = src.u32();
            if (nameLen > nameBuf.length) nameBuf = new byte[nameLen];
            src.readFully(nameBuf, 0, nameLen);
            String name;
            try {
                name = new String(nameBuf, 0, nameLen, "UTF-8");
            } catch (Exception e) {
                name = new String(nameBuf, 0, nameLen);
            }
            // ZIP64: 0xFFFFFFFF placeholders resolved from extra field.
            if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL
                    || localOffset == 0xFFFFFFFFL) {
                long[] z64 = readZip64Extra(src, extraLen);
                if (uncompSize == 0xFFFFFFFFL) uncompSize = z64[0];
                if (compSize == 0xFFFFFFFFL) compSize = z64[1];
                if (localOffset == 0xFFFFFFFFL) localOffset = z64[2];
            } else {
                src.pos(src.pos() + extraLen);
            }
            src.pos(src.pos() + commentLen);
            out.add(new Entry(name, method, compSize, uncompSize,
                    localOffset, crc));
        }
        return out;
    }

    /** Returns {uncomp, comp, localOffset} from the ZIP64 extra field. */
    private static long[] readZip64Extra(Src src, int extraLen) throws IOException {
        long[] out = new long[]{-1, -1, -1};
        long end = src.pos() + extraLen;
        while (src.pos() + 4 <= end) {
            int id = src.u16();
            int size = src.u16();
            if (id == 0x0001) {
                long p = src.pos();
                // Order: uncomp, comp, local offset, disk (only the
                // fields holding 0xFFFF placeholders exist, in order).
                // We cannot know which were placeholders here; the caller
                // passes them positionally — instead read greedily: the
                // standard order fills uncomp, comp, offset, disk.
                if (size >= 8) {
                    out[0] = src.u32() | (src.u32() << 32);
                }
                if (size >= 16) {
                    out[1] = src.u32() | (src.u32() << 32);
                }
                if (size >= 24) {
                    out[2] = src.u32() | (src.u32() << 32);
                }
                src.pos(p + size);
            } else {
                src.pos(src.pos() + size);
            }
        }
        src.pos(end);
        return out;
    }

    private byte[] readRaw(long localOffset, String name, long size) throws IOException {
        if (size > Integer.MAX_VALUE) {
            throw ApkException.unsupported("entry too large: " + name);
        }
        long dataOff = dataOffset(localOffset, name);
        byte[] out = new byte[(int) size];
        if (file != null) {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                raf.seek(dataOff);
                raf.readFully(out);
            } finally {
                raf.close();
            }
        } else {
            System.arraycopy(memory, (int) dataOff, out, 0, out.length);
        }
        return out;
    }

    private long dataOffset(long localOffset, String name) throws IOException {
        if (file != null) {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                raf.seek(localOffset);
                long sig = readU32(raf);
                if (sig != 0x04034b50L) {
                    throw new ApkException(ApkException.Code.ZIP,
                            "bad local header for " + name, localOffset);
                }
                raf.seek(localOffset + 26);
                long nameLen = readU16(raf);
                long extraLen = readU16(raf);
                return localOffset + 30 + nameLen + extraLen;
            } finally {
                raf.close();
            }
        }
        int p = (int) localOffset;
        long sig = (memory[p] & 0xFFL) | ((memory[p + 1] & 0xFFL) << 8)
                | ((memory[p + 2] & 0xFFL) << 16) | ((memory[p + 3] & 0xFFL) << 24);
        if (sig != 0x04034b50L) {
            throw new ApkException(ApkException.Code.ZIP,
                    "bad local header for " + name, localOffset);
        }
        int nameLen = (memory[p + 26] & 0xFF) | ((memory[p + 27] & 0xFF) << 8);
        int extraLen = (memory[p + 28] & 0xFF) | ((memory[p + 29] & 0xFF) << 8);
        return localOffset + 30 + nameLen + extraLen;
    }

    /** Bounded stream over a RandomAccessFile region. */
    private static final class RafStream extends InputStream {
        private final RandomAccessFile raf;
        private long left;

        RafStream(RandomAccessFile raf, long size) {
            this.raf = raf;
            this.left = size;
        }

        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int v = raf.read();
            if (v >= 0) left--;
            return v;
        }

        @Override public int read(byte[] b, int o, int l) throws IOException {
            if (left <= 0) return -1;
            int n = raf.read(b, o, (int) Math.min(l, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override public void close() throws IOException {
            raf.close();
        }
    }
}
