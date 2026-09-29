package apktools;

/**
 * Growable little-endian writer. Used by the recompilers (AXML/ARSC) to
 * emit chunks. Supports patching sizes after the fact via {@link #patchU32}.
 */
public final class BinWriter {

    private byte[] buf = new byte[256];
    private int pos;

    public int position() {
        return pos;
    }

    private void need(int n) {
        if (pos + n > buf.length) {
            int cap = Math.max(buf.length * 2, pos + n + 64);
            byte[] next = new byte[cap];
            System.arraycopy(buf, 0, next, 0, pos);
            buf = next;
        }
    }

    public void u8(int v) {
        need(1);
        buf[pos++] = (byte) v;
    }

    public void u16(int v) {
        need(2);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >>> 8);
    }

    public void u32(long v) {
        need(4);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 24);
    }

    public void i32(int v) {
        u32(v & 0xFFFFFFFFL);
    }

    public void bytes(byte[] src) {
        bytes(src, 0, src.length);
    }

    public void bytes(byte[] src, int off, int len) {
        need(len);
        System.arraycopy(src, off, buf, pos, len);
        pos += len;
    }

    /** Overwrite a previously written u32 (for chunk sizes). */
    public void patchU32(int at, long v) {
        buf[at] = (byte) v;
        buf[at + 1] = (byte) (v >>> 8);
        buf[at + 2] = (byte) (v >>> 16);
        buf[at + 3] = (byte) (v >>> 24);
    }

    /** Pad with zeroes up to the given alignment. */
    public void align(int alignment) {
        while ((pos & (alignment - 1)) != 0) u8(0);
    }

    public void skip(int n) {
        need(n);
        pos += n; // caller patches later; buffer is zero-filled on growth only
        for (int i = pos - n; i < pos; i++) buf[i] = 0;
    }

    public byte[] toByteArray() {
        byte[] out = new byte[pos];
        System.arraycopy(buf, 0, out, 0, pos);
        return out;
    }
}
