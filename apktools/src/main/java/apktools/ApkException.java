package apktools;

/**
 * Single exception type for the whole apktools module.
 *
 * <p>Every public entry point ({@code apktools}, {@code apktools.xml},
 * {@code apktools.arsc}, {@code apktools.apk}) throws this instead of
 * crashing on malformed input. Callers inspect {@link #code()} to decide
 * what to do (skip file, show error, fall back) and {@link #offset()} to
 * locate the offending bytes while debugging.
 *
 * <p>It is a {@link RuntimeException} on purpose: file-manager call sites
 * stay clean, and a single {@code catch (ApkException e)} around an
 * inspect/decode/edit pass is enough.
 */
public class ApkException extends RuntimeException {

    /** Machine-readable failure reason. Stable across releases. */
    public enum Code {
        /** Read past the end of the buffer. */
        TRUNCATED,
        /** Wrong magic / not the expected binary format. */
        BAD_MAGIC,
        /** Chunk header claims an impossible size or type. */
        BAD_CHUNK,
        /** Structurally valid but needs an unsupported feature/version. */
        UNSUPPORTED,
        /** Corrupt string pool. */
        STRING_POOL,
        /** Malformed binary XML (tag mismatch, bad attribute, ...). */
        XML,
        /** Malformed resources table. */
        ARSC,
        /** Bad ZIP / EOCD / entry data. */
        ZIP,
        /** Requested entry / resource / chunk not present. */
        NOT_FOUND,
        /** Underlying I/O failure (message + cause carry the detail). */
        IO,
        /** Recompilation failed (model holds values we cannot emit). */
        ENCODE
    }

    private final Code code;
    /** Byte offset related to the failure, or -1 if not applicable. */
    private final long offset;

    public ApkException(Code code, String message) {
        this(code, message, -1, null);
    }

    public ApkException(Code code, String message, long offset) {
        this(code, message, offset, null);
    }

    public ApkException(Code code, String message, Throwable cause) {
        this(code, message, -1, cause);
    }

    public ApkException(Code code, String message, long offset, Throwable cause) {
        super(message + (offset >= 0 ? " (offset " + offset + ")" : ""), cause);
        this.code = code;
        this.offset = offset;
    }

    public Code code() {
        return code;
    }

    public long offset() {
        return offset;
    }

    // -- factories: keep call sites one-liners ---------------------------

    public static ApkException truncated(long offset, int need, int have) {
        return new ApkException(Code.TRUNCATED,
                "need " + need + " bytes, have " + have, offset);
    }

    public static ApkException badMagic(String what, long got, long offset) {
        return new ApkException(Code.BAD_MAGIC,
                what + " bad magic 0x" + Long.toHexString(got), offset);
    }

    public static ApkException badChunk(String what, long offset) {
        return new ApkException(Code.BAD_CHUNK, what, offset);
    }

    public static ApkException unsupported(String what) {
        return new ApkException(Code.UNSUPPORTED, what);
    }

    public static ApkException notFound(String what) {
        return new ApkException(Code.NOT_FOUND, what);
    }

    public static ApkException io(String what, Throwable cause) {
        return new ApkException(Code.IO, what, cause);
    }
}
