package apktools.arsc;

import apktools.ApkException;
import apktools.BinReader;
import apktools.BinWriter;
import apktools.ResValue;

import java.util.List;

/**
 * Rebuilds an {@link ArscFile} back to bytes.
 *
 * <p>Strategy is index-preserving: the global, type and key pools keep
 * every original string in order and only <i>append</i> new ones, specs
 * and unknown chunks are copied verbatim, and unmutated entry values are
 * sliced straight from the source buffer. A rebuild with no edits is
 * therefore semantically identical; edits only shift what they must.
 */
public final class ArscBuilder {

    private ArscBuilder() {}

    public static byte[] build(ArscFile file) {
        BinReader in = file.reader();
        BinWriter w = new BinWriter();

        // Table header.
        w.u16(ArscFile.CHUNK_TABLE);
        w.u16(12);
        int sizePos = w.position();
        w.u32(0);
        w.u32(file.packages().size());

        // Global pool: verbatim when untouched, else append extras.
        List<String> extras = file.pendingStrings();
        if (extras.isEmpty()) {
            w.bytes(file.strings().rawBytes());
        } else {
            w.bytes(file.strings().toBytes(
                    file.strings().isUtf8(), extras));
        }

        for (ArscPackage pkg : file.packages()) {
            writePackage(file, pkg, w);
        }
        for (ArscFile.RawChunk raw : file.unknownChunks()) {
            w.bytes(in.slice(raw.offset, raw.size));
        }

        w.patchU32(sizePos, w.position());
        return w.toByteArray();
    }

    private static void writePackage(ArscFile file, ArscPackage pkg,
                                     BinWriter w) {
        BinReader in = file.reader();
        int[] orig = findPackageChunk(file, pkg);
        int pkgOff = orig[0];
        if (in.u16(pkgOff + 2) > 288) {
            throw new ApkException(ApkException.Code.ENCODE,
                    "package header extensions not supported", pkgOff);
        }
        long lastPublicType = in.u32(pkgOff + 272);
        long lastPublicKey = in.u32(pkgOff + 280);

        int pkgStart = w.position();
        w.u16(ArscFile.CHUNK_PACKAGE);
        w.u16(288);
        int pkgSizePos = w.position();
        w.u32(0);
        w.u32(pkg.id());
        writeUtf16Name(w, pkg.name());
        int typeStringsPos = w.position();
        w.u32(0); // patched below
        w.u32(lastPublicType);
        int keyStringsPos = w.position();
        w.u32(0); // patched below
        w.u32(lastPublicKey);
        w.u32(pkg.typeIdOffset);
        // Fixed header is exactly 288 bytes (verified by field layout).

        w.patchU32(typeStringsPos, w.position() - pkgStart);
        w.bytes(pkg.typeNames().toBytes(pkg.typeNames().isUtf8()));
        w.patchU32(keyStringsPos, w.position() - pkgStart);
        w.bytes(pkg.keys().toBytes(pkg.keys().isUtf8()));

        for (ArscFile.ChunkToc toc : pkg.chunks) {
            if (toc.kind == ArscFile.CHUNK_TYPE && toc.type != null) {
                writeType(file, toc.type, w);
            } else {
                // Specs (flags never change in v1 edits) and unknown
                // platform chunks are preserved verbatim.
                w.bytes(in.slice(toc.offset, toc.size));
            }
        }
        w.align(4);
        w.patchU32(pkgSizePos, w.position() - pkgStart);
    }

    private static int[] findPackageChunk(ArscFile file, ArscPackage pkg) {
        BinReader in = file.reader();
        int pos = 12;
        int end = (int) in.u32(4);
        // Skip global + optional second pool.
        for (int i = 0; i < 2; i++) {
            if (pos < end && in.u16(pos) == 0x0001) {
                pos += (int) in.u32(pos + 4);
            }
        }
        for (ArscPackage p : file.packages()) {
            int size = (int) in.u32(pos + 4);
            if (p == pkg) return new int[]{pos, size};
            pos += size;
        }
        throw ApkException.notFound("package chunk");
    }

    private static void writeUtf16Name(BinWriter w, String name) {
        for (int i = 0; i < 128; i++) {
            w.u16(i < name.length() ? name.charAt(i) : 0);
        }
    }

    private static void writeType(ArscFile file, ArscType t, BinWriter w) {
        BinReader in = file.reader();
        w.u16(ArscFile.CHUNK_TYPE);
        // headerSize = fixed fields + config; offsets array follows it,
        // values follow the array (entriesStart = values offset).
        int configSize = t.config().raw.length;
        int fixedSize = 20 + configSize;
        w.u16(fixedSize);
        int sizePos = w.position();
        w.u32(0);
        w.u8(t.id());
        w.u8(0);
        w.u16(0);
        w.u32(t.entryCount());
        int entriesStart = fixedSize + t.entryCount() * 4;
        w.u32(entriesStart);
        w.bytes(t.config().raw);
        int chunkStart = w.position() - fixedSize;
        int valuesBase = chunkStart + entriesStart;
        // Reserve the offsets array, then emit values.
        for (int i = 0; i < t.entryCount(); i++) w.u32(0xFFFFFFFFL);
        for (int i = 0; i < t.entryCount(); i++) {
            ArscEntry e = t.get(i);
            if (e == null) continue; // stays NO_ENTRY
            // offsets[] are relative to the values base.
            w.patchU32(chunkStart + fixedSize + i * 4,
                    w.position() - valuesBase);
            writeValue(file, e, w);
        }
        w.patchU32(sizePos, w.position() - chunkStart);
        w.align(4);
        // Size includes alignment padding (chunks are 4-aligned).
        w.patchU32(sizePos, w.position() - chunkStart);
    }

    private static void writeValue(ArscFile file, ArscEntry e, BinWriter w) {
        BinReader in = file.reader();
        if (!e.dirty) {
            w.bytes(in.slice(e.rawOffset, e.rawLength));
            return;
        }
        // Preserve public/weak/feature bits; complex + compact are
        // recomputed for the new shape (compact always expands).
        int kept = e.entryFlags & ArscType.PRESERVED_FLAGS;
        if (e.pendingString != null) {
            int idx = file.internString(e.pendingString);
            w.u16(8);
            w.u16(kept);
            w.u32(e.keyIndex);
            w.u16(8);
            w.u8(0);
            w.u8(ResValue.TYPE_STRING);
            w.i32(idx);
            return;
        }
        if (!e.complex) {
            w.u16(8);
            w.u16(kept);
            w.u32(e.keyIndex);
            w.u16(8);
            w.u8(0);
            w.u8(e.valueType);
            w.i32(e.valueData);
            return;
        }
        List<BagItem> items = e.bag;
        w.u16(16 + items.size() * 12);
        w.u16(1 | kept); // FLAG_COMPLEX
        w.u32(e.keyIndex);
        w.i32(e.parent);
        w.u32(items.size());
        for (BagItem b : items) {
            w.i32(b.name);
            w.u16(8);
            w.u8(0);
            w.u8(b.valueType);
            w.i32(b.valueData);
        }
    }
}
