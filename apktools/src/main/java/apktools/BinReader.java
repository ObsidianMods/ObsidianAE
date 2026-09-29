package apktools;

/**
 * Bounds-checked little-endian reader over a byte array.
 *
 * <p>All multi-byte values in APK internals (AXML, ARSC, ZIP) are
 * little-endian. Reads are absolute (offset-based, no cursor) so parsers
 * can jump around chunk tables without copying. Every access is
 * bounds-checked and throws {@link ApkException} instead of
 * {@link ArrayIndexOutOfBoundsException}.
 */
public final class BinReader {

    private final byte[] buf;
    private final int base;
    private final int length;

    public BinReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public BinReader(byte[] buf, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > buf.length) {
            throw ApkException.truncated(offset, length, buf.length - offset);
        }
        this.buf = buf;
        this.base = offset;
        this.length = length;
    }

    public int length() {
        return length;
    }

    public byte[] array() {
        return buf;
    }

    public int baseOffset() {
        return base;
    }

    /** Absolute offset of {@code off} in the backing array. */
    public int abs(int off) {
        check(off, 1);
        return base + off;
    }

    public void check(int off, int need) {
        if (off < 0 || need < 0 || off + need > length) {
            throw ApkException.truncated(base + off, need, length - off);
        }
    }

    public int u8(int off) {
        check(off, 1);
        return buf[base + off] & 0xFF;
    }

    public int u16(int off) {
        check(off, 2);
        int p = base + off;
        return (buf[p] & 0xFF) | ((buf[p + 1] & 0xFF) << 8);
    }

    /** Unsigned 32-bit value as long. */
    public long u32(int off) {
        check(off, 4);
        int p = base + off;
        return ((buf[p] & 0xFFL)
                | ((buf[p + 1] & 0xFFL) << 8)
                | ((buf[p + 2] & 0xFFL) << 16)
                | ((buf[p + 3] & 0xFFL) << 24));
    }

    public int i32(int off) {
        return (int) u32(off);
    }

    public byte[] slice(int off, int len) {
        check(off, len);
        byte[] out = new byte[len];
        System.arraycopy(buf, base + off, out, 0, len);
        return out;
    }

    /** Raw copy without allocation when the caller only reads. */
    public void copyTo(int off, byte[] dst, int dstOff, int len) {
        check(off, len);
        System.arraycopy(buf, base + off, dst, dstOff, len);
    }

    /** UTF-16LE chars, used for package names and fixed-size fields. */
    public String utf16String(int off, int charCount) {
        check(off, charCount * 2);
        int end = off;
        int max = off + charCount * 2;
        int p = base + off;
        while (end + 1 < max) {
            if (buf[p + (end - off)] == 0 && buf[p + (end - off) + 1] == 0) break;
            end += 2;
        }
        char[] chars = new char[(end - off) / 2];
        for (int i = 0; i < chars.length; i++) {
            int q = base + off + i * 2;
            chars[i] = (char) ((buf[q] & 0xFF) | ((buf[q + 1] & 0xFF) << 8));
        }
        return new String(chars);
    }
}
