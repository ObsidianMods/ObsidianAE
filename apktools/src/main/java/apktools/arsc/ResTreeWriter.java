package apktools.arsc;

import apktools.ApkException;
import apktools.ResValue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exports a {@link ResTree} to the resource-tree layout:
 *
 * <pre>
 *   &lt;pkg&gt;/package-info.xml
 *   &lt;pkg&gt;/&lt;type&gt;/type-info.xml
 *   &lt;pkg&gt;/&lt;type&gt;/&lt;type&gt;[-qualifiers].xml
 * </pre>
 *
 * Value files use source-like tags ({@code <string>},
 * {@code <color>}, {@code <attr format>}, {@code <style>},
 * {@code <plurals>}, {@code <array>}, {@code <path>} for file-backed
 * types). Interchangeable in structure with asrc exports, so
 * trees can be compared across tools; {@link ResTreeReader} reads them
 * back.
 */
public final class ResTreeWriter {

    /** Types whose values are file paths ({@code <path>}). */
    private static final Set<String> PATH_TYPES = new HashSet<>();
    /** Types with scalar text tags. */
    private static final Set<String> SCALAR_TAGS = new HashSet<>();

    static {
        // Standard res/ file folders (developer.android.com) whose
        // entries are file paths, plus aapt's interpolator type.
        String[] paths = {"anim", "animator", "drawable", "mipmap",
            "layout", "menu", "navigation", "xml", "interpolator",
            "transition", "raw", "font"};
        for (String t : paths) PATH_TYPES.add(t);
        String[] scalars = {"string", "color", "bool", "integer",
            "dimen", "fraction"};
        for (String t : scalars) SCALAR_TAGS.add(t);
    }

    private ResTreeWriter() {}

    /** Writes the whole tree under {@code dir}. */
    public static void write(ResTree tree, File dir) {
        try {
            for (ResTreePackage pkg : tree.packages()) {
                writePackage(tree, pkg, dir.toPath().resolve(pkg.name()));
            }
        } catch (Exception e) {
            throw ApkException.io("writing resource tree", e);
        }
    }

    private static void writePackage(ResTree tree, ResTreePackage pkg,
                                     Path dir) throws Exception {
        Files.createDirectories(dir);
        writeFile(dir.resolve("package-info.xml"),
                "<?xml version='1.0' encoding='utf-8' ?>\n"
                + "<package id=\"" + hexMin(pkg.id()) + "\" name=\""
                + esc(pkg.name()) + "\" />");
        for (ResTreeType type : pkg.types()) {
            writeType(tree, pkg, type, dir.resolve(type.name()));
        }
    }

    /**
     * True when every live row of the type carries the WEAK entry bit
     * (aapt marks generated id entries weak). Display-only; the flag
     * itself survives through verbatim rebuilds.
     */
    private static boolean allWeak(ResTreeType type) {
        boolean any = false;
        for (ResTreeConfig cfg : type.configs()) {
            for (ResTreeRow row : cfg.rows()) {
                ArscEntry live = row.live();
                if (live == null) return false;
                any = true;
                if ((live.entryFlags & 0x04) == 0) return false;
            }
        }
        return any;
    }

    private static void writeType(ResTree tree, ResTreePackage pkg,
                                  ResTreeType type, Path dir)
            throws Exception {
        Files.createDirectories(dir);
        StringBuilder info = new StringBuilder(
                "<?xml version='1.0' encoding='utf-8' ?>\n");
        info.append("<type id=\"").append(hexMin(type.id())).append("\"");
        if (allWeak(type)) {
            info.append(" defaultWeak=\"true\"");
        }
        info.append(">\n");
        // Live entries across all configs, by id (sparse slots have no
        // key and cannot be named). Sanitized keys keep their real
        // form in realName, like MT Manager.
        java.util.TreeMap<Integer, String> names =
                new java.util.TreeMap<>();
        java.util.TreeMap<Integer, String> reals = new java.util.TreeMap<>();
        for (ResTreeConfig cfg : type.configs()) {
            for (ResTreeRow row : cfg.rows()) {
                names.put(row.resId(), row.key());
                if (row.realKey() != null) {
                    reals.put(row.resId(), row.realKey());
                }
            }
        }
        for (java.util.Map.Entry<Integer, String> e : names.entrySet()) {
            info.append("    <entry id=\"").append(hex8(e.getKey()))
                    .append("\" name=\"")
                    .append(esc(e.getValue()));
            String real = reals.get(e.getKey());
            if (real != null) {
                info.append("\" realName=\"").append(esc(real));
            }
            info.append("\" />\n");
        }
        info.append("</type>");
        writeFile(dir.resolve("type-info.xml"), info.toString());

        Set<String> used = new HashSet<>();
        for (ResTreeConfig cfg : type.configs()) {
            if (cfg.rows().isEmpty()) continue;
            String file = type.name();
            String q = cfg.qualifier();
            if (!q.isEmpty()) file += "-" + q;
            file += ".xml";
            if (!used.add(file)) {
                int n = 2;
                while (!used.add(file + "." + n)) n++;
                file = file + "." + n;
            }
            writeValues(tree, pkg, type, cfg, dir.resolve(file));
        }
    }

    private static void writeValues(ResTree tree, ResTreePackage pkg,
                                    ResTreeType type, ResTreeConfig cfg,
                                    Path file) throws Exception {
        StringBuilder sb = new StringBuilder(
                "<?xml version='1.0' encoding='utf-8' ?>\n<resources>\n");
        for (ResTreeRow row : cfg.rows()) {
            writeRow(tree, pkg, type, cfg, row, sb);
        }
        sb.append("</resources>");
        writeFile(file, sb.toString());
    }

    private static void writeRow(ResTree tree, ResTreePackage pkg,
                                 ResTreeType type, ResTreeConfig cfg,
                                 ResTreeRow row, StringBuilder sb) {
        String key = row.key();
        String tag = type.name();
        if (row.isComplex()) {
            writeBag(row, sb);
            return;
        }
        // Scalar tags follow the value type across directories (MT):
        // colors, floats and fractions keep their own tag anywhere.
        int vt = row.simpleType();
        if (vt == ResValue.TYPE_FRACTION) {
            tag = "fraction";
        } else if (vt == ResValue.TYPE_FLOAT) {
            tag = "float";
        } else if (vt == ResValue.TYPE_INT_COLOR_ARGB8
                || vt == ResValue.TYPE_INT_COLOR_RGB8
                || vt == ResValue.TYPE_INT_COLOR_ARGB4
                || vt == ResValue.TYPE_INT_COLOR_RGB4) {
            tag = "color";
        }
        String text = row.text();
        if (text == null) text = "";
        if (tag.equals("id")) {
            sb.append("    <id name=\"").append(esc(key)).append("\" />\n");
        } else if (PATH_TYPES.contains(tag)) {
            sb.append("    <path name=\"").append(esc(key)).append("\">")
                    .append(escText(text)).append("</path>\n");
        } else if (SCALAR_TAGS.contains(tag) || tag.equals("float")
                || tag.equals("fraction") || tag.equals("color")) {
            sb.append("    <").append(tag).append(" name=\"")
                    .append(esc(key)).append("\">").append(escText(text))
                    .append("</").append(tag).append(">\n");
        } else {
            // Unknown simple type: qualified generic item.
            sb.append("    <item name=\"").append(esc(key))
                    .append("\" type=\"").append(esc(tag)).append("\">")
                    .append(escText(text)).append("</item>\n");
        }
    }

    private static void writeBag(ResTreeRow row, StringBuilder sb) {
        String kind = row.bagKind();
        if (kind.equals("attr")) {
            writeAttr(row, sb);
        } else if (kind.equals("style")) {
            writeStyle(row, sb);
        } else if (kind.equals("plurals")) {
            writePlurals(row, sb);
        } else {
            // Arrays and any other bag shape.
            writeArray(row, sb);
        }
    }

    private static void writeAttr(ResTreeRow row, StringBuilder sb) {
        sb.append("    <attr name=\"").append(esc(row.key())).append("\"");
        String format = row.attrFormat();
        if (format != null && !format.equals("0")) {
            sb.append(" format=\"").append(esc(format)).append("\"");
        }
        List<ResTreeRow.Item> items = row.items();
        if (items.isEmpty()) {
            sb.append(" />\n");
            return;
        }
        boolean flags =
                (row.formatBits() & ResValue.FORMAT_FLAGS) != 0;
        sb.append(">\n");
        for (ResTreeRow.Item it : row.items()) {
            if (isPseudoId(it.nameId)) {
                // min/max/l10n (and future pseudo-ids): MT renders them
                // as integer items carrying the raw pseudo-id.
                sb.append("        <integer id=\"0x")
                        .append(Integer.toHexString(it.nameId))
                        .append("\">").append(escText(it.value))
                        .append("</integer>\n");
                continue;
            }
            String kind = flags ? "flag" : "enum";
            sb.append("        <").append(kind);
            sb.append(" name=\"").append(esc(it.name)).append("\"");
            sb.append(" value=\"").append(esc(it.value))
                    .append("\" />\n");
        }
        sb.append("    </attr>\n");
    }

    /** Bag pseudo-ids (attr meta-items) render as {@code <integer id>}. */
    static boolean isPseudoId(int nameId) {
        return (nameId & 0xFFFF0000) == 0x01000000;
    }

    private static void writeStyle(ResTreeRow row, StringBuilder sb) {
        sb.append("    <style name=\"").append(esc(row.key())).append("\"");
        String parent = row.parentRefText();
        if (parent != null) {
            sb.append(" parent=\"").append(esc(parent)).append("\"");
        }
        List<ResTreeRow.Item> items = row.items();
        if (items.isEmpty()) {
            sb.append(" />\n");
            return;
        }
        sb.append(">\n");
        for (ResTreeRow.Item it : items) {
            sb.append("        <item name=\"").append(esc(it.name))
                    .append("\">").append(escText(it.value))
                    .append("</item>\n");
        }
        sb.append("    </style>\n");
    }

    private static void writePlurals(ResTreeRow row, StringBuilder sb) {
        sb.append("    <plurals name=\"").append(esc(row.key()))
                .append("\">\n");
        for (ResTreeRow.Item it : row.items()) {
            sb.append("        <item quantity=\"").append(esc(it.name))
                    .append("\">").append(escText(it.value))
                    .append("</item>\n");
        }
        sb.append("    </plurals>\n");
    }

    private static void writeArray(ResTreeRow row, StringBuilder sb) {
        sb.append("    <array name=\"").append(esc(row.key()))
                .append("\">\n");
        for (ResTreeRow.Item it : row.items()) {
            sb.append("        <item>").append(escText(it.value))
                    .append("</item>\n");
        }
        sb.append("    </array>\n");
    }

    // -- names + values --------------------------------------------------------

    /** Bare enum/flag name (after the last / or :). */
    static String bareName(ResTree tree, ResTreePackage pkg, int id) {
        String name = pkg.view().inner.resolve(id);
        if (name == null) return "0x" + Integer.toHexString(id);
        int slash = name.lastIndexOf('/');
        int colon = name.lastIndexOf(':');
        int cut = Math.max(slash, colon);
        return cut >= 0 ? name.substring(cut + 1) : name;
    }

    /** Style item name: attrs render bare (android: for framework). */
    static String styleItemName(ResTree tree, ResTreePackage pkg, int id) {
        String name = pkg.view().inner.resolve(id);
        if (name == null) return "0x" + Integer.toHexString(id);
        if (name.startsWith("android:attr/")) {
            return "android:" + name.substring("android:attr/".length());
        }
        int slash = name.lastIndexOf('/');
        String head = slash >= 0 ? name.substring(0, slash) : "";
        String bare = slash >= 0 ? name.substring(slash + 1) : name;
        if (head.equals("attr")
                || head.equals(pkg.name() + ":attr")) {
            return bare;
        }
        return name;
    }

    /** Reference text for parent refs ({@code @style/X}). */
    static String refText(ResTree tree, ResTreePackage pkg, int id) {
        String name = pkg.view().inner.resolve(id);
        if (name == null) return "@0x" + Integer.toHexString(id);
        return "@" + name;
    }

    /** Raw pool text for string bag items (null when unavailable). */
    static String itemString(ResTree tree, BagItem b) {
        if (b.valueType != ResValue.TYPE_STRING || tree.table() == null) {
            return null;
        }
        try {
            return tree.table().strings().get(b.valueData);
        } catch (Exception e) {
            return null;
        }
    }

    /** Bag-item value text (refs, colors, dims, strings). */
    static String itemValueText(ResTree tree, ResTreePackage pkg,
                                BagItem b) {
        if (b.valueType == ResValue.TYPE_STRING) {
            String s = itemString(tree, b);
            return s != null ? s : "@string/" + b.valueData;
        }
        return ResValue.toString(b.valueType, b.valueData,
                tree.table() != null ? tree.table().strings() : null,
                pkg.view().inner);
    }

    // -- io ----------------------------------------------------------------------

    private static void writeFile(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexMin(int v) {
        String h = Integer.toHexString(v);
        while (h.length() < 2) h = "0" + h;
        return "0x" + h;
    }

    private static String hex8(int v) {
        String h = Integer.toHexString(v);
        while (h.length() < 8) h = "0" + h;
        return "0x" + h;
    }

    static String esc(String s) {
        return escAttr(s);
    }

    /** Full escaping for attribute values. */
    static String escAttr(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Minimal escaping for text content (MT keeps quotes raw). */
    static String escText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
