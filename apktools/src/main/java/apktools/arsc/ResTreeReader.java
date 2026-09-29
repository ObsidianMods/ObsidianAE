package apktools.arsc;

import apktools.ApkException;
import apktools.ResValue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads MT-style resource trees (see {@link ResTreeWriter}) back into
 * a {@link ResTree}. Uses only JDK DOM parsing — still pure Java with
 * no Android dependencies.
 *
 * <p>With a live table ({@link #read(File, ArscFile)}) rows bind to
 * their entries: values parse immediately (refs, enums, formats) and
 * {@link ResTreeRow#setText} keeps working. Without one the tree is
 * detached: literals parse, references stay raw text.
 */
public final class ResTreeReader {

    private ResTreeReader() {}

    /** Reads a detached tree (inspection, interchange). */
    public static ResTree read(File dir) {
        return read(dir, null);
    }

    /** Reads a tree bound to a live table (editing). */
    public static ResTree read(File dir, ArscFile table) {
        try {
            return readTree(dir, table);
        } catch (ApkException e) {
            throw e;
        } catch (Exception e) {
            throw ApkException.io("reading resource tree " + dir, e);
        }
    }

    // -- structure ---------------------------------------------------------------

    private static ResTree readTree(File dir, ArscFile table)
            throws Exception {
        ResTree tree = ResTree.detached(table);
        File[] pkgs = dir.listFiles();
        if (pkgs == null) {
            throw ApkException.notFound("resource tree " + dir);
        }
        java.util.Arrays.sort(pkgs);
        for (File pkgDir : pkgs) {
            if (!pkgDir.isDirectory()) continue;
            readPackage(tree, table, pkgDir);
        }
        if (tree.packages().isEmpty()) {
            throw ApkException.notFound(
                    "no packages in resource tree " + dir);
        }
        return tree;
    }

    private static void readPackage(ResTree tree, ArscFile table,
                                    File pkgDir) throws Exception {
        File info = new File(pkgDir, "package-info.xml");
        Element root = parse(info).getDocumentElement();
        int pkgId = parseHex(reqAttr(root, "id"), info);
        String pkgName = reqAttr(root, "name");
        ResTreePackage pkg = new ResTreePackage(tree, pkgName, pkgId);
        tree.addPackage(pkg);

        File[] types = pkgDir.listFiles();
        if (types == null) return;
        java.util.Arrays.sort(types);
        for (File typeDir : types) {
            if (!typeDir.isDirectory()) continue;
            readType(tree, table, pkg, typeDir);
        }
    }

    private static void readType(ResTree tree, ArscFile table,
                                 ResTreePackage pkg, File typeDir)
            throws Exception {
        File info = new File(typeDir, "type-info.xml");
        Element root = parse(info).getDocumentElement();
        int typeId = parseHex(reqAttr(root, "id"), info);
        String typeName = typeDir.getName();
        ResTreeType type = new ResTreeType(pkg, typeId, typeName);
        pkg.addType(type);

        // id -> key for this type.
        java.util.Map<Integer, String> keys =
                new java.util.LinkedHashMap<>();
        for (Element e : children(root, "entry")) {
            keys.put(parseHex(reqAttr(e, "id"), info),
                    reqAttr(e, "name"));
        }

        File[] files = typeDir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            String n = f.getName();
            if (!f.isFile() || n.equals("type-info.xml")
                    || !n.startsWith(typeName) || !n.endsWith(".xml")) {
                continue;
            }
            String suffix = n.substring(typeName.length(),
                    n.length() - ".xml".length());
            if (suffix.startsWith("-")) suffix = suffix.substring(1);
            else if (!suffix.isEmpty()) continue; // dedup foo.xml.2
            readValues(tree, table, pkg, type, keys, f, suffix);
        }
    }

    private static void readValues(ResTree tree, ArscFile table,
                                   ResTreePackage pkg, ResTreeType type,
                                   java.util.Map<Integer, String> keys,
                                   File file, String qualifier)
            throws Exception {
        ResConfig config = ResConfig.fromPathSuffix(
                qualifier.isEmpty() ? "" : "-" + qualifier);
        ResTreeConfig cfg = new ResTreeConfig(type, config);
        type.addConfig(cfg);
        ResValueParser.Ctx ctx = table != null
                ? new ResValueParser.Ctx(table, pkg.name()) : null;

        Element root = parse(file).getDocumentElement();
        if (!root.getTagName().equals("resources")) {
            throw new ApkException(ApkException.Code.XML,
                    "root is not <resources> in " + file);
        }
        NodeList nodes = root.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element)) continue;
            readRow(tree, table, ctx, pkg, type, keys, cfg, file,
                    (Element) node, qualifier);
        }
    }

    // -- rows ----------------------------------------------------------------------

    private static void readRow(ResTree tree, ArscFile table,
                                ResValueParser.Ctx ctx, ResTreePackage pkg,
                                ResTreeType type,
                                java.util.Map<Integer, String> keys,
                                ResTreeConfig cfg, File file, Element e,
                                String qualifier) throws Exception {
        String tag = e.getTagName();
        String key = e.hasAttribute("name")
                ? e.getAttribute("name") : null;
        int resId = 0;
        if (key != null) {
            resId = findId(type, keys, key, file);
        }
        ResTree.IdResolverView view = pkg.view();
        if (tag.equals("attr")) {
            readAttr(tree, table, ctx, pkg, type, cfg, file, e, key, resId, qualifier,
                    view);
            return;
        }
        if (tag.equals("style")) {
            readStyle(tree, table, ctx, pkg, type, cfg, file, e, key, resId, qualifier,
                    view);
            return;
        }
        if (tag.equals("plurals")) {
            readPlurals(tree, table, ctx, pkg, type, cfg, file, e, key,
                    resId, qualifier, view);
            return;
        }
        if (tag.equals("array")) {
            readArray(tree, table, ctx, pkg, type, cfg, file, e, key, resId, qualifier,
                    view);
            return;
        }
        // Simple rows: path/scalars/generic item/id.
        if (key == null) {
            throw new ApkException(ApkException.Code.XML,
                    "<" + tag + "> without name in " + file);
        }
        ArscEntry live = bindRow(table, pkg.name(), type.id(),
                ResourceId.entry(resId), qualifier);
        ResTreeRow row = new ResTreeRow(tree, cfg, resId, key, view);
        if (live != null) {
            row = new ResTreeRow(tree, cfg, live, view);
        } else {
            storeSimple(row, ctx, textOf(e), file);
        }
        cfg.addRow(row);
        tree.mapName(type.name(), key, resId);
    }

    /**
     * Binds a file row to its exact table entry: same package, type,
     * entry index AND config qualifier. Binding by id alone would land
     * non-default rows on the default config's (different) value.
     */
    private static ArscEntry bindRow(ArscFile table, String pkgName,
                                     int typeId, int entryIdx,
                                     String qualifier) {
        if (table == null) return null;
        String want = qualifier.isEmpty() ? "" : "-" + qualifier;
        for (ArscPackage p : table.packages()) {
            if (!p.displayName().equals(pkgName)) continue;
            for (ArscType t : p.types(typeId)) {
                if (t.config().qualifierString().equals(want)) {
                    return t.get(entryIdx);
                }
            }
            return null;
        }
        return null;
    }

    private static int findId(ResTreeType type,
                              java.util.Map<Integer, String> keys,
                              String key, File file) {
        for (java.util.Map.Entry<Integer, String> e : keys.entrySet()) {
            if (e.getValue().equals(key)) return e.getKey();
        }
        throw ApkException.notFound(
                "id for " + type.name() + "/" + key + " in " + file);
    }

    private static void storeSimple(ResTreeRow row,
                                    ResValueParser.Ctx ctx, String text,
                                    File file) {
        if (ctx != null) {
            try {
                ResValueParser.Result r =
                        ResValueParser.parse(text, ctx, 0);
                if (r.isString()) {
                    row.setDetachedSimple(r.type, r.data, r.string);
                } else {
                    row.setDetachedSimple(r.type, r.data, null);
                }
                return;
            } catch (ApkException ex) {
                throw new ApkException(ApkException.Code.XML,
                        file + ": cannot parse \"" + text + "\"", ex);
            }
        }
        // Detached without a table: literals only, refs stay raw.
        try {
            ResValueParser.Result r = ResValueParser.parse(text,
                    new ResValueParser.Ctx(null, ""), 0);
            if (r.isString()) {
                row.setDetachedSimple(r.type, r.data, r.string);
            } else {
                row.setDetachedSimple(r.type, r.data, null);
            }
        } catch (ApkException ex) {
            row.setDetachedRawText(text);
        }
    }

    // -- bags ------------------------------------------------------------------------

    private static void readAttr(ResTree tree, ArscFile table,
                                 ResValueParser.Ctx ctx, ResTreePackage pkg,
                                 ResTreeType type, ResTreeConfig cfg,
                                 File file, Element e, String key, int resId,
                                 String qualifier,
                                 ResTree.IdResolverView view)
            throws Exception {
        String format = e.hasAttribute("format")
                ? e.getAttribute("format") : null;
        List<ResTreeRow.Item> items = new ArrayList<>();
        for (Element item : children(e, null)) {
            String kind = item.getTagName();
            if (kind.equals("enum") || kind.equals("flag")) {
                String nm = reqAttr(item, "name", file);
                String val = reqAttr(item, "value", file);
                items.add(parseItem(ctx, pkg, nm, val, resId, file));
            } else if (kind.equals("integer") && item.hasAttribute("id")
                    && !item.hasAttribute("name")) {
                // Pseudo-id meta-item (min/max/l10n, MT form).
                int nameId = parseHex(reqAttr(item, "id", file), file);
                String val = textOf(item);
                SoftValue v = softParse(ctx, val, 0, file);
                items.add(new ResTreeRow.Item("", val, nameId, v.type,
                        v.data, v.string));
            } else {
                throw new ApkException(ApkException.Code.XML,
                        "bad <attr> child <" + kind + "> in " + file);
            }
        }
        if (format != null) {
            int mask = ResValueParser.parseFormatMask(format);
            finishBag(tree, table, pkg, type, cfg, file, key, resId, view, items,
                    null, null, mask, qualifier);
        } else {
            finishBag(tree, table, pkg, type, cfg, file, key, resId, view, items,
                    null, null, null, qualifier);
        }
    }

    private static void readStyle(ResTree tree, ArscFile table,
                                  ResValueParser.Ctx ctx, ResTreePackage pkg,
                                  ResTreeType type, ResTreeConfig cfg,
                                  File file, Element e, String key, int resId,
                                  String qualifier,
                                  ResTree.IdResolverView view)
            throws Exception {
        Integer parent = null;
        String parentText = null;
        if (e.hasAttribute("parent")) {
            String p = e.getAttribute("parent");
            if (ctx != null && ctx.table != null) {
                ResValueParser.Result r = parseValueText(ctx, p, 0, file);
                if (r.type != ResValue.TYPE_REFERENCE) {
                    throw new ApkException(ApkException.Code.XML,
                            "style parent must be a reference in " + file);
                }
                parent = r.data;
            } else {
                parentText = p;
            }
        }
        List<ResTreeRow.Item> items = new ArrayList<>();
        for (Element item : children(e, "item")) {
            String nm = reqAttr(item, "name", file);
            int nameId = styleItemId(ctx, pkg, nm, file);
            String val = textOf(item);
            SoftValue v = softParse(ctx, val, nameId, file);
            items.add(new ResTreeRow.Item(nm, val, nameId, v.type,
                    v.data, v.string));
        }
        finishBag(tree, table, pkg, type, cfg, file, key, resId, view,
                items, parent, parentText, null, qualifier);
    }

    private static void readPlurals(ResTree tree, ArscFile table,
                                    ResValueParser.Ctx ctx,
                                    ResTreePackage pkg, ResTreeType type,
                                    ResTreeConfig cfg, File file, Element e,
                                    String key, int resId, String qualifier,
                                    ResTree.IdResolverView view)
            throws Exception {
        List<ResTreeRow.Item> items = new ArrayList<>();
        for (Element item : children(e, "item")) {
            String q = reqAttr(item, "quantity", file);
            int nameId = pluralId(q, file);
            String val = textOf(item);
            SoftValue v = softParse(ctx, val, 0, file);
            items.add(new ResTreeRow.Item(q, val, nameId, v.type,
                    v.data, v.string));
        }
        finishBag(tree, table, pkg, type, cfg, file, key, resId, view, items,
                null, null, null, qualifier);
    }

    private static void readArray(ResTree tree, ArscFile table,
                                  ResValueParser.Ctx ctx, ResTreePackage pkg,
                                  ResTreeType type, ResTreeConfig cfg,
                                  File file, Element e, String key, int resId,
                                  String qualifier,
                                  ResTree.IdResolverView view)
            throws Exception {
        List<ResTreeRow.Item> items = new ArrayList<>();
        int index = 0;
        for (Element item : children(e, "item")) {
            String val = textOf(item);
            SoftValue v = softParse(ctx, val, 0, file);
            items.add(new ResTreeRow.Item("", val,
                    ResValue.BAG_ATTR_MIN + index, v.type, v.data,
                    v.string));
            index++;
        }
        finishBag(tree, table, pkg, type, cfg, file, key, resId, view, items,
                null, null, null, qualifier);
    }

    private static void finishBag(ResTree tree, ArscFile table,
                                  ResTreePackage pkg, ResTreeType type,
                                  ResTreeConfig cfg, File file, String key,
                                  int resId, ResTree.IdResolverView view,
                                  List<ResTreeRow.Item> items,
                                  Integer parent, String parentText,
                                  Integer format, String qualifier) {
        ArscEntry live = bindRow(table, pkg.name(), type.id(),
                ResourceId.entry(resId), qualifier);
        ResTreeRow row;
        if (live != null && live.isComplex()) {
            row = new ResTreeRow(tree, cfg, live, view);
        } else {
            row = new ResTreeRow(tree, cfg, resId, key, view);
            row.setDetachedBag(parent, parentText, format, items);
        }
        cfg.addRow(row);
    }

    /** Parse result that tolerates unresolvable refs (raw text kept). */
    private static final class SoftValue {
        final int type;
        final int data;
        final String string;

        SoftValue(int type, int data, String string) {
            this.type = type;
            this.data = data;
            this.string = string;
        }
    }

    /**
     * Parses a bag-item value, keeping raw text when references cannot
     * resolve (detached trees without a table). Strict when a table is
     * bound: bad input fails loudly.
     */
    private static SoftValue softParse(ResValueParser.Ctx ctx, String val,
                                       int attrId, File file) {
        ResValueParser.Ctx use = ctx != null ? ctx
                : new ResValueParser.Ctx(null, "");
        try {
            ResValueParser.Result r =
                    ResValueParser.parse(val, use, attrId);
            return new SoftValue(r.type, r.data, r.string);
        } catch (ApkException e) {
            if (use.table != null) {
                throw new ApkException(ApkException.Code.XML,
                        file + ": cannot parse \"" + val + "\"", e);
            }
            return new SoftValue(-1, 0, val);
        }
    }

    // -- values --------------------------------------------------------------------------

    private static ResTreeRow.Item parseItem(ResValueParser.Ctx ctx,
                                             ResTreePackage pkg, String nm,
                                             String val, int attrId,
                                             File file) throws Exception {
        int nameId = enumNameId(ctx, pkg, nm, file);
        SoftValue v = softParse(ctx, val, attrId, file);
        return new ResTreeRow.Item(nm, val, nameId, v.type, v.data,
                v.string);
    }

    private static ResValueParser.Result parseValueText(
            ResValueParser.Ctx ctx, String val, int attrId, File file)
            throws Exception {
        ResValueParser.Ctx use = ctx != null ? ctx
                : new ResValueParser.Ctx(null, "");
        try {
            return ResValueParser.parse(val, use, attrId);
        } catch (ApkException e) {
            if (ctx != null) throw e;
            // Detached without table: keep literals, raw-keep refs.
            ResTreeRow.Item marker = null;
            throw new ApkException(ApkException.Code.XML,
                    file + ": cannot parse \"" + val + "\"", e);
        }
    }

    private static int enumNameId(ResValueParser.Ctx ctx,
                                  ResTreePackage pkg, String nm, File file)
            throws Exception {
        if (ctx == null || ctx.table == null) return 0;
        // Enum symbols are id-type entries in the same package.
        ArscPackage raw = null;
        for (ArscPackage p : ctx.table.packages()) {
            if (p.displayName().equals(pkg.name())) {
                raw = p;
                break;
            }
        }
        if (raw == null) return 0;
        for (ArscType t : raw.allTypes()) {
            for (int i = 0; i < t.entryCount(); i++) {
                ArscEntry e = t.get(i);
                if (e == null) continue;
                try {
                    if (e.key().equals(nm)) {
                        return e.resourceId();
                    }
                } catch (Exception ignored) {
                    continue;
                }
            }
        }
        return 0;
    }

    private static int styleItemId(ResValueParser.Ctx ctx,
                                   ResTreePackage pkg, String nm, File file)
            throws Exception {
        String ref;
        if (nm.startsWith("android:")) {
            ref = "android:attr/" + nm.substring("android:".length());
        } else if (nm.contains(":")) {
            String[] parts = nm.split(":", 2);
            ref = parts[0] + ":attr/" + parts[1];
        } else {
            ref = "attr/" + nm;
        }
        if (ctx == null) return 0;
        int id = ctx.idFor(ref);
        if (id == 0) {
            throw ApkException.notFound(
                    "style item " + nm + " in " + file);
        }
        return id;
    }

    private static int pluralId(String q, File file) throws Exception {
        String[] names = {"other", "zero", "one", "two", "few", "many"};
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(q)) {
                return ResValue.BAG_PLURALS_BASE + i;
            }
        }
        throw new ApkException(ApkException.Code.XML,
                "bad quantity \"" + q + "\" in " + file);
    }

    // -- dom -------------------------------------------------------------------------------

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(file);
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            if (tag == null || e.getTagName().equals(tag)) out.add(e);
        }
        return out;
    }

    private static String reqAttr(Element e, String name, File file)
            throws Exception {
        if (!e.hasAttribute(name)) {
            throw new ApkException(ApkException.Code.XML,
                    "<" + e.getTagName() + "> without " + name + " in "
                    + file);
        }
        return e.getAttribute(name);
    }

    private static String reqAttr(Element e, String name)
            throws Exception {
        if (!e.hasAttribute(name)) {
            throw new ApkException(ApkException.Code.XML,
                    "<" + e.getTagName() + "> without " + name);
        }
        return e.getAttribute(name);
    }

    private static String textOf(Element e) {
        return e.getTextContent();
    }

    private static int parseHex(String s, File file) throws Exception {
        try {
            long v = Long.decode(s);
            if ((v & 0xFFFFFFFF00000000L) != 0) throw new Exception();
            return (int) v;
        } catch (Exception e) {
            throw new ApkException(ApkException.Code.XML,
                    "bad id \"" + s + "\" in " + file);
        }
    }

    private static String valueText(ResValueParser.Result r) {
        return r.isString() ? r.string : "";
    }
}
