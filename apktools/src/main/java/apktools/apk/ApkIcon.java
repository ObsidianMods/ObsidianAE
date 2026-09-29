package apktools.apk;

import apktools.xml.AxmlDocument;

import java.util.Collections;
import java.util.List;

/**
 * A resolved launcher icon in whichever form the APK defines it.
 *
 * <p>APKs define icons many ways — adaptive XML, vector XML, plain
 * bitmaps per density, or bare colors — so resolution never throws for a
 * missing/odd icon: it returns {@link Kind#MISSING} instead. Raw file
 * bytes are included for bitmap/XML kinds so the file manager can hand
 * them straight to an image pipeline.
 */
public final class ApkIcon {

    public enum Kind {
        /** PNG/WebP/JPEG bytes ({@link #bytes}, {@link #densityDpi}). */
        BITMAP,
        /** {@code <vector>} XML ({@link #document} + {@link #bytes}). */
        XML_VECTOR,
        /** {@code <adaptive-icon>} XML with {@link #layers}. */
        XML_ADAPTIVE,
        /** Other XML drawable (layer-list, shape, ...) + raw bytes. */
        XML_DRAWABLE,
        /** Bare {@code #argb} color (adaptive layers, monochrome). */
        COLOR,
        /** Nothing resolvable (id 0, dangling ref, unknown file). */
        MISSING
    }

    /** One adaptive-icon layer: either a sub-icon or a flat color. */
    public static final class Layer {
        /** Role: background, foreground, monochrome. */
        public final String role;
        public final ApkIcon icon; // null when isColor
        public final int color; // valid when isColor
        public final boolean isColor;

        Layer(String role, ApkIcon icon) {
            this.role = role;
            this.icon = icon;
            this.color = 0;
            this.isColor = false;
        }

        Layer(String role, int color) {
            this.role = role;
            this.icon = null;
            this.color = color;
            this.isColor = true;
        }
    }

    public final Kind kind;
    /** Resource id it was resolved from (0 when synthesised). */
    public final int resId;
    /** ZIP path of the source file ("" when none). */
    public final String path;
    /** Raw file bytes for BITMAP/XML kinds (null otherwise). */
    public final byte[] bytes;
    /** Decoded document for XML kinds (null otherwise). */
    public final AxmlDocument document;
    /** Density bucket of the chosen bitmap (0 = unspecified). */
    public final int densityDpi;
    /** Adaptive layers (empty unless XML_ADAPTIVE). */
    public final List<Layer> layers;
    /** ARGB color for COLOR kind. */
    public final int color;
    /** File extension hint (png, webp, jpg, xml, ...). */
    public final String extension;

    private ApkIcon(Kind kind, int resId, String path, byte[] bytes,
                    AxmlDocument document, int densityDpi,
                    List<Layer> layers, int color, String extension) {
        this.kind = kind;
        this.resId = resId;
        this.path = path;
        this.bytes = bytes;
        this.document = document;
        this.densityDpi = densityDpi;
        this.layers = layers;
        this.color = color;
        this.extension = extension;
    }

    public static ApkIcon bitmap(int resId, String path, byte[] bytes,
                                 int densityDpi, String ext) {
        return new ApkIcon(Kind.BITMAP, resId, path, bytes, null,
                densityDpi, Collections.<Layer>emptyList(), 0, ext);
    }

    public static ApkIcon xml(Kind kind, int resId, String path, byte[] bytes,
                              AxmlDocument doc) {
        return new ApkIcon(kind, resId, path, bytes, doc, 0,
                Collections.<Layer>emptyList(), 0, "xml");
    }

    public static ApkIcon adaptive(int resId, String path, byte[] bytes,
                                   AxmlDocument doc, List<Layer> layers) {
        return new ApkIcon(Kind.XML_ADAPTIVE, resId, path, bytes, doc, 0,
                Collections.unmodifiableList(layers), 0, "xml");
    }

    public static ApkIcon color(int argb) {
        return new ApkIcon(Kind.COLOR, 0, "", null, null, 0,
                Collections.<Layer>emptyList(), argb, "");
    }

    public static ApkIcon missing(int resId) {
        return new ApkIcon(Kind.MISSING, resId, "", null, null, 0,
                Collections.<Layer>emptyList(), 0, "");
    }

    /** Density with 0 (unspecified) reported as ~mdpi. */
    public int effectiveDensity() {
        return densityDpi == 0 ? 160 : densityDpi;
    }

    public boolean isUsable() {
        return kind != Kind.MISSING;
    }

    @Override
    public String toString() {
        return kind + (path.isEmpty() ? "" : " " + path)
                + (densityDpi != 0 ? " " + densityDpi + "dpi" : "")
                + (kind == Kind.COLOR
                ? " #" + Integer.toHexString(color) : "");
    }
}
