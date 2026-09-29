package apktools.arsc;

import apktools.ResValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One named entry in a {@link ResTreeConfig}: display text, bag items,
 * plus an edit path. Live rows read/mutate their {@link ArscEntry};
 * detached rows (from {@link ResTreeReader}) store parsed values.
 */
public final class ResTreeRow {

    /** One bag item in presentation form (name + value texts). */
    public static final class Item {
        public final String name;
        public final String value;
        final int nameId;
        final int valueType;
        final int valueData;
        final String valueString;

        Item(String name, String value, int nameId,
             int valueType, int valueData, String valueString) {
            this.name = name;
            this.value = value;
            this.nameId = nameId;
            this.valueType = valueType;
            this.valueData = valueData;
            this.valueString = valueString;
        }
    }

    private final ResTreeConfig config;
    private final ResTree tree;
    private final int resId;
    private final String key;
    /** Original key when {@link #key} was sanitized, else null. */
    private final String realKey;
    private final ArscEntry live;
    private final ResTree.IdResolverView view;

    // Detached storage.
    private boolean complex;
    private int valueType = -1;
    private int valueData;
    private String pendingString;
    private String rawText; // unparseable text (refs without a table)
    private int parent;
    private boolean hasParent;
    private String parentText; // detached parent ref text (no table)
    private int formatMask;
    private boolean hasFormat;
    private List<Item> items;

    ResTreeRow(ResTree tree, ResTreeConfig config, ArscEntry entry,
               ResTree.IdResolverView view) {
        this.config = config;
        this.tree = tree;
        this.resId = entry.resourceId();
        String raw = safeKey(entry);
        String clean = sanitizeKey(raw, config.type().name(),
                ResourceId.entry(resId));
        this.key = clean;
        this.realKey = clean.equals(raw) ? null : raw;
        this.live = entry;
        this.view = view;
        this.complex = entry.isComplex();
    }

    /** Detached constructor used by the reader. */
    ResTreeRow(ResTree tree, ResTreeConfig config, int resId, String key,
               ResTree.IdResolverView view) {
        this.config = config;
        this.tree = tree;
        this.resId = resId;
        this.key = key;
        this.realKey = null;
        this.live = null;
        this.view = view;
    }

    ResTreePackage pkg() {
        return config.type().pkg();
    }

    ResTree tree() {
        return tree;
    }

    private static String safeKey(ArscEntry e) {
        try {
            return e.key();
        } catch (Exception ex) {
            return "entry-" + ResourceId.entry(e.resourceId());
        }
    }

    /**
     * Sanitizes aapt-internal keys ({@code $foo__0}) to
     * {@code <type><index>} like MT Manager — the raw form is kept as
     * {@link #realKey} and written to {@code realName}.
     */
    static String sanitizeKey(String key, String type, int index) {
        if (key != null && key.startsWith("$")) {
            return String.format("%s%04x", type, index);
        }
        return key;
    }

    /** Unsanitized key, or null when {@link #key} is already real. */
    public String realKey() {
        return realKey;
    }

    public int resId() {
        return resId;
    }

    public String key() {
        return key;
    }

    /** Live table entry, or null for detached rows. */
    public ArscEntry live() {
        return live;
    }

    public boolean isComplex() {
        return complex;
    }

    /** Value type for simple rows (-1 when unknown/raw). */
    int simpleType() {
        if (complex) return -1;
        if (live != null) return live.valueType();
        return valueType;
    }

    /**
     * display text for simple values ({@code @string/x},
     * {@code #ff00ff00}, {@code 16.0dp}, {@code true}). Null for bags.
     */
    public String text() {
        if (complex) return null;
        if (live != null) {
            if (live.valueType() == ResValue.TYPE_STRING) {
                try {
                    return live.stringValue();
                } catch (Exception e) {
                    return "@string/" + live.valueData();
                }
            }
            return ResValue.toString(live.valueType(), live.valueData(),
                    live.pkg().file().strings(), view.inner);
        }
        if (rawText != null) return rawText;
        if (pendingString != null) return pendingString;
        if (valueType < 0) return "";
        return ResValue.toString(valueType, valueData, null, view.inner);
    }

    /**
     * Parses editor text and applies it: live rows mutate the table
     * entry, detached rows store the parsed value. Throws
     * {@link apktools.ApkException} on bad input. Bags cannot be set
     * as text.
     */
    public void setText(String text) {
        if (isComplex()) {
            throw new apktools.ApkException(
                    apktools.ApkException.Code.ENCODE,
                    "bag entries need structural edits: " + key);
        }
        ResValueParser.Result r =
                ResValueParser.parse(text, view.ctx(), 0);
        if (live != null) {
            if (r.isString()) live.setString(r.string);
            else live.setValue(r.type, r.data);
        } else {
            valueType = r.type;
            valueData = r.data;
            pendingString = r.string;
            rawText = null;
        }
    }

    // -- bags --------------------------------------------------------------------

    /**
     * Bag kind for file mapping: {@code attr}, {@code style},
     * {@code plurals} or {@code array} (other complex shapes report
     * {@code array}).
     */
    public String bagKind() {
        String t = config.type().name();
        if (t.equals("attr") || t.equals("style")
                || t.equals("plurals")) {
            return t;
        }
        return "array";
    }

    /** Bag parent ref (styles), 0 when none. */
    public int bagParent() {
        if (live != null) {
            return live.isComplex() ? live.bagParent() : 0;
        }
        return hasParent ? parent : 0;
    }

    /** Parent ref display text (null when no parent). */
    String parentRefText() {
        if (parentText != null) return parentText;
        if (bagParent() == 0) return null;
        return ResTreeWriter.refText(tree(), pkg(), bagParent());
    }

    /** Attr format mask text ({@code dimension|enum}), or null. */
    public String attrFormat() {
        Integer mask = attrFormatMask();
        return mask == null ? null : ResValue.attrFormat(mask);
    }

    private Integer attrFormatMask() {
        if (live != null) {
            if (!live.isComplex()) return null;
            for (BagItem b : live.bag()) {
                if (b.name == ResValue.BAG_ATTR_TYPE) {
                    return b.valueData;
                }
            }
            return null;
        }
        return hasFormat ? formatMask : null;
    }

    /** Presentation items (live computed, detached stored). */
    public List<Item> items() {
        if (live != null && live.isComplex()) {
            return liveItems();
        }
        return items == null
                ? Collections.<Item>emptyList()
                : Collections.unmodifiableList(items);
    }

    private List<Item> liveItems() {
        List<Item> out = new ArrayList<>();
        ResTreePackage pkg = pkg();
        String kind = bagKind();
        for (BagItem b : live.bag()) {
            if (kind.equals("attr") && b.name == ResValue.BAG_ATTR_TYPE) {
                continue; // rendered as the format="..." attribute
            }
            if (kind.equals("attr")
                    && (b.name == ResValue.BAG_ATTR_MIN
                    || b.name == ResValue.BAG_ATTR_MAX
                    || b.name == ResValue.BAG_ATTR_L10N)) {
                out.add(new Item("", Integer.toString(b.valueData),
                        b.name, b.valueType, b.valueData, null));
                continue;
            }
            String name;
            String value = ResTreeWriter.itemValueText(tree(), pkg, b);
            if (kind.equals("style")) {
                name = ResTreeWriter.styleItemName(tree(), pkg, b.name);
                if ((b.valueType == ResValue.TYPE_INT_DEC
                        || b.valueType == ResValue.TYPE_INT_HEX)) {
                    // Enum/flag symbols (fitCenter, no, match_parent)
                    // when the value matches the attr's declaration.
                    String sym = view.inner.enumName(b.name, b.valueData);
                    if (sym != null) value = sym;
                }
            } else if (kind.equals("plurals")) {
                String q = ResValue.pluralQuantity(b.name);
                if (q == null) {
                    throw new apktools.ApkException(
                            apktools.ApkException.Code.ENCODE,
                            "unrepresentable plural quantity "
                            + Integer.toHexString(b.name) + " in " + key);
                }
                name = q;
            } else if (kind.equals("attr")) {
                name = ResTreeWriter.bareName(tree(), pkg, b.name);
            } else {
                name = "";
            }
            out.add(new Item(name, value,
                    b.name, b.valueType, b.valueData,
                    b.valueType == ResValue.TYPE_STRING
                            ? ResTreeWriter.itemString(tree(), b) : null));
        }
        return out;
    }

    /** Format mask bits for attr rows (0 when none). */
    int formatBits() {
        Integer m = attrFormatMask();
        return m == null ? 0 : m;
    }

    // -- detached mutation (reader) --------------------------------------------------

    void setDetachedSimple(int type, int data, String string) {
        this.complex = false;
        this.valueType = type;
        this.valueData = data;
        this.pendingString = string;
        this.rawText = null;
    }

    void setDetachedRawText(String text) {
        this.complex = false;
        this.valueType = -1;
        this.pendingString = null;
        this.rawText = text;
    }

    void setDetachedBag(Integer parent, String parentText,
                        Integer format, List<Item> items) {
        this.complex = true;
        this.hasParent = parent != null || parentText != null;
        this.parent = parent == null ? 0 : parent;
        this.parentText = parentText;
        this.hasFormat = format != null;
        this.formatMask = format == null ? 0 : format;
        this.items = new ArrayList<>(items);
    }

    // -- compare ------------------------------------------------------------------------

    /**
     * Canonical signature for tree comparison (export/import
     * round-trips): key plus value text or structured bag content.
     */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        sb.append(key).append('=');
        if (!complex) {
            sb.append(text());
            return sb.toString();
        }
        sb.append('(').append(bagKind());
        String parentRef = parentRefText();
        if (bagKind().equals("style") && parentRef != null) {
            sb.append('^').append(parentRef);
        }
        if (bagKind().equals("attr") && attrFormat() != null) {
            sb.append('#').append(attrFormat());
        }
        for (Item it : items()) {
            sb.append(';');
            if (it.name.isEmpty()) {
                // Pseudo-ids (min/max/l10n) share empty names; the id
                // keeps them distinct.
                sb.append("#").append(Integer.toHexString(it.nameId));
            } else {
                sb.append(it.name);
            }
            sb.append('=').append(it.value);
        }
        sb.append(')');
        return sb.toString();
    }
}
