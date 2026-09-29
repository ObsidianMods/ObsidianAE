package apktools.apk;

import apktools.ResValue;
import apktools.arsc.ArscEntry;
import apktools.arsc.ArscFile;
import apktools.arsc.ArscPackage;
import apktools.arsc.ArscType;
import apktools.arsc.ResConfig;
import apktools.arsc.ResourceId;
import apktools.xml.AxmlAttribute;
import apktools.xml.AxmlDecoder;
import apktools.xml.AxmlDocument;
import apktools.xml.AxmlElement;
import apktools.xml.AxmlNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps an {@code android:icon} resource id to file bytes.
 *
 * <p>File-backed resources (drawables, mipmaps, layouts, ...) carry
 * their ZIP path as the entry's string value — including when a
 * shrinker obfuscates file names ({@code res/S5.png}) — and every type
 * chunk carries its exact {@link ResConfig}. Candidates are therefore
 * collected from the table itself, never guessed, and ranked with
 * {@link ResConfig#betterThan}: scalable XML first, then highest
 * density bitmap. A filename-pattern fallback covers tables that point
 * at files indirectly (aliases without path values).
 *
 * <p>XML drawables recurse (adaptive layers, nested refs) with cycle
 * protection and a depth cap. Anything unresolvable yields
 * {@link ApkIcon.Kind#MISSING}, never an exception.
 */
final class IconResolver {

    private static final int MAX_DEPTH = 8;

    private final ApkReader zip;
    private final ArscFile arsc;

    IconResolver(ApkReader zip, ArscFile arsc) {
        this.zip = zip;
        this.arsc = arsc;
    }

    ApkIcon resolve(int resId) {
        return resolve(resId, new HashSet<Integer>(), 0);
    }

    private ApkIcon resolve(int resId, Set<Integer> seen, int depth) {
        if (resId == 0 || arsc == null || depth > MAX_DEPTH
                || !seen.add(resId)) {
            return ApkIcon.missing(resId);
        }
        resId = arsc.finalizeId(resId);
        ArscPackage pkg = arsc.findPackage(ResourceId.pkg(resId));
        if (pkg == null) return ApkIcon.missing(resId);
        int typeId = ResourceId.type(resId);
        int entryIdx = ResourceId.entry(resId);

        // Direct color value (adaptive layers point at colors).
        ArscEntry def = pkg.entry(typeId, entryIdx);
        if (def != null && !def.isComplex()
                && ResValue.isColor(def.valueType())) {
            return ApkIcon.color(def.valueData());
        }

        List<Candidate> cands = directCandidates(pkg, typeId, entryIdx,
                seen, depth);
        if (cands.isEmpty()) {
            cands = guessedCandidates(pkg.typeName(typeId), safeKey(def));
        }
        if (cands.isEmpty()) return ApkIcon.missing(resId);

        // XML (scalable) before bitmaps; a broken XML falls back to
        // the best bitmap instead of MISSING.
        Candidate bestXml = null;
        Candidate bestBitmap = null;
        for (Candidate c : cands) {
            if (c.isXml) {
                if (bestXml == null || ResConfig.betterThan(c.config,
                        bestXml.config, 0)) bestXml = c;
            } else if (bestBitmap == null || ResConfig.betterThan(c.config,
                    bestBitmap.config, 0)) {
                bestBitmap = c;
            }
        }
        if (bestXml != null) {
            ApkIcon xml = loadXml(bestXml, resId, seen, depth);
            if (xml.isUsable()) return xml;
        }
        if (bestBitmap != null) return loadBitmap(bestBitmap, resId);
        // Last resort: first decodable XML of any config.
        for (Candidate c : cands) {
            if (c.isXml) {
                ApkIcon xml = loadXml(c, resId, seen, depth);
                if (xml.isUsable()) return xml;
            }
        }
        return ApkIcon.missing(resId);
    }

    // -- candidates ----------------------------------------------------------

    private static final class Candidate {
        String path;
        ResConfig config;
        boolean isXml;
        String ext;
    }

    /**
     * Exact candidates: every config variant of the entry contributes
     * its own value-path and exact config. Reference values (aliases)
     * are followed.
     */
    private List<Candidate> directCandidates(ArscPackage pkg, int typeId,
                                             int entryIdx, Set<Integer> seen,
                                             int depth) {
        List<Candidate> out = new ArrayList<>();
        for (ArscType t : pkg.types(typeId)) {
            ArscEntry e = t.get(entryIdx);
            if (e == null || e.isComplex()) continue;
            if (e.valueType() == ResValue.TYPE_STRING) {
                String path;
                try {
                    path = e.stringValue();
                } catch (Exception ex) {
                    continue;
                }
                if (path == null || path.isEmpty() || !zip.has(path)) {
                    continue;
                }
                Candidate c = new Candidate();
                c.path = path;
                c.config = t.config();
                c.ext = extension(path);
                c.isXml = c.ext.equals("xml");
                out.add(c);
            } else if (ResValue.isReference(e.valueType())) {
                // Alias (e.g. drawable -> mipmap): follow once. The
                // aliased id is marked seen by the recursive call.
                ApkIcon sub = resolve(e.valueData(), seen, depth + 1);
                if (sub.isUsable() && sub.path != null
                        && !sub.path.isEmpty()) {
                    Candidate c = new Candidate();
                    c.path = sub.path;
                    c.config = t.config();
                    c.ext = sub.extension;
                    c.isXml = sub.kind != ApkIcon.Kind.BITMAP;
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** Filename-pattern fallback for tables without value paths. */
    private List<Candidate> guessedCandidates(String typeName, String key) {
        List<Candidate> out = new ArrayList<>();
        if (key == null || key.isEmpty()) return out;
        // Any res/ folder for this type (drawable, mipmap, ...) with
        // any config qualifiers.
        Pattern pattern = Pattern.compile("^res/"
                + Pattern.quote(typeName) + "((?:-[^/]+)?)/([^/]+)"
                + "\\.([^/.]+)$");
        for (ApkReader.Entry e : zip.entries()) {
            String n = e.name;
            Matcher m = pattern.matcher(n);
            if (!m.matches()) continue;
            if (!m.group(2).equals(key)) continue;
            Candidate c = new Candidate();
            c.path = n;
            c.config = ResConfig.fromPathSuffix(m.group(1));
            c.ext = m.group(3).toLowerCase();
            c.isXml = c.ext.equals("xml");
            out.add(c);
        }
        return out;
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash) return "";
        return path.substring(dot + 1).toLowerCase();
    }

    private String safeKey(ArscEntry e) {
        if (e == null) return "";
        try {
            return e.key();
        } catch (Exception ex) {
            return "";
        }
    }

    // -- loaders ---------------------------------------------------------------

    private ApkIcon loadBitmap(Candidate c, int resId) {
        byte[] bytes;
        try {
            bytes = zip.readBytes(c.path);
        } catch (Exception e) {
            return ApkIcon.missing(resId);
        }
        return ApkIcon.bitmap(resId, c.path, bytes,
                c.config.density, c.ext);
    }

    private ApkIcon loadXml(Candidate c, int resId,
                            Set<Integer> seen, int depth) {
        byte[] bytes;
        try {
            bytes = zip.readBytes(c.path);
        } catch (Exception e) {
            return ApkIcon.missing(resId);
        }
        AxmlDocument doc;
        try {
            doc = AxmlDecoder.decode(bytes);
        } catch (Exception e) {
            return ApkIcon.missing(resId);
        }
        if (doc.root == null) return ApkIcon.missing(resId);
        String root = doc.root.name;
        if (root.equals("adaptive-icon")) {
            List<ApkIcon.Layer> layers = new ArrayList<>();
            addLayer(layers, doc, "background", seen, depth);
            addLayer(layers, doc, "foreground", seen, depth);
            addLayer(layers, doc, "monochrome", seen, depth);
            return ApkIcon.adaptive(resId, c.path, bytes, doc, layers);
        }
        if (root.equals("vector")) {
            flattenVectorRefs(doc, seen, depth);
            return ApkIcon.xml(ApkIcon.Kind.XML_VECTOR, resId, c.path,
                    bytes, doc);
        }
        return ApkIcon.xml(ApkIcon.Kind.XML_DRAWABLE, resId, c.path,
                bytes, doc);
    }

    private void addLayer(List<ApkIcon.Layer> out, AxmlDocument doc,
                          String role, Set<Integer> seen, int depth) {        AxmlElement e = doc.root.child(role);
        if (e == null) return;
        AxmlAttribute drawable = null;
        for (AxmlAttribute a : e.attributes) {
            if (a.name.equals("drawable")) {
                drawable = a;
                break;
            }
        }
        if (drawable == null) return;
        if (ResValue.isColor(drawable.valueType)) {
            out.add(new ApkIcon.Layer(role, drawable.valueData));
        } else if (drawable.valueType == ResValue.TYPE_REFERENCE) {
            ApkIcon sub = resolve(drawable.valueData, seen, depth + 1);
            out.add(new ApkIcon.Layer(role, sub));
        }
        // Anything else (theme attrs, floats) has no static layer.
    }

    // -- render flattening ---------------------------------------------------

    /**
     * Namespaces as decoded documents carry them.
     */
    private static final String NS_AAPT =
            "http://schemas.android.com/aapt";

    /**
     * Standalone renderers (no resource table) choke on two things real
     * vectors use: color attributes given as {@code @ref} instead of a
     * literal, and fills that point at a separate {@code <gradient>}
     * file (both render as transparent — a blank icon). Both are
     * rewritten here, where the table is at hand: plain-color refs
     * become literal {@code #aarrggbb} attributes and gradient refs
     * become an inline {@code aapt:attr} subtree — the same shape aapt
     * itself emits for inline gradients. Only icon documents are
     * touched; decode/encode round-trips stay byte-faithful.
     */
    private void flattenVectorRefs(AxmlDocument doc, Set<Integer> seen,
                                   int depth) {
        if (doc == null || doc.root == null) return;
        flattenElement(doc.root, seen, depth);
    }

    private void flattenElement(AxmlElement e, Set<Integer> seen,
                                int depth) {
        for (int i = 0; i < e.attributes.size(); i++) {
            AxmlAttribute a = e.attributes.get(i);
            if (!isFlattenableColorAttr(e.name, a.name)) continue;
            if (a.valueType != ResValue.TYPE_REFERENCE
                    && a.valueType != ResValue.TYPE_DYNAMIC_REFERENCE) {
                continue;
            }
            FillTarget t = lookupFill(a.valueData, seen, depth);
            if (t == null) continue;
            if (t.isColor) {
                e.attributes.set(i, new AxmlAttribute(a.namespace,
                        a.name, a.resourceId,
                        "#" + String.format("%08x", t.color),
                        ResValue.TYPE_INT_COLOR_ARGB8, t.color));
            } else if (e.name.equals("path")
                    && a.name.equals("fillColor")
                    && t.gradient != null) {
                // Gradient fills only make sense on paths: drop the
                // reference and graft the gradient inline.
                e.attributes.remove(i--);
                e.children.add(gradientAttrWrap(t.gradient));
            }
        }
        for (AxmlNode n : e.children) {
            if (n instanceof AxmlElement) {
                flattenElement((AxmlElement) n, seen, depth);
            }
        }
    }

    private static boolean isFlattenableColorAttr(String tag,
                                                  String attr) {
        if (tag.equals("path")) {
            return attr.equals("fillColor") || attr.equals("strokeColor");
        }
        if (tag.equals("item")) return attr.equals("color");
        if (tag.equals("gradient")) {
            return attr.equals("startColor") || attr.equals("centerColor")
                    || attr.equals("endColor");
        }
        return false;
    }

    private static final class FillTarget {
        boolean isColor;
        int color;
        /** Root of a decoded {@code <gradient>} doc (else null). */
        AxmlElement gradient;
    }

    private FillTarget lookupFill(int refId, Set<Integer> seen,
                                  int depth) {
        if (arsc == null || depth > MAX_DEPTH) return null;
        int id = arsc.finalizeId(refId);
        ArscPackage pkg = arsc.findPackage(ResourceId.pkg(id));
        if (pkg == null) return null;
        ArscEntry e = pkg.entry(ResourceId.type(id),
                ResourceId.entry(id));
        if (e == null || e.isComplex()) return null;
        if (ResValue.isColor(e.valueType())) {
            FillTarget t = new FillTarget();
            t.isColor = true;
            t.color = e.valueData();
            return t;
        }
        if (e.valueType() != ResValue.TYPE_STRING) return null;
        String path;
        try {
            path = e.stringValue();
        } catch (Exception ex) {
            return null;
        }
        if (path == null || path.isEmpty() || !zip.has(path)) return null;
        byte[] bytes;
        try {
            bytes = zip.readBytes(path);
        } catch (Exception ex) {
            return null;
        }
        AxmlDocument g;
        try {
            g = AxmlDecoder.decode(bytes);
        } catch (Exception ex) {
            return null;
        }
        if (g.root == null || !g.root.name.equals("gradient")
                || !gradientUsable(g.root)) {
            return null;
        }
        // Flatten color refs inside the gradient itself (@color stops).
        flattenElement(g.root, seen, depth + 1);
        FillTarget t = new FillTarget();
        t.gradient = g.root;
        return t;
    }

    /**
     * A gradient is only grafted when the renderer could actually draw
     * it (stops, or legacy start/end colors); otherwise the reference
     * is left alone — same blank as before, never a regression.
     */
    private static boolean gradientUsable(AxmlElement gradient) {
        for (AxmlNode n : gradient.children) {
            if (n instanceof AxmlElement
                    && ((AxmlElement) n).name.equals("item")) {
                return true;
            }
        }
        return hasAttr(gradient, "startColor")
                || hasAttr(gradient, "endColor");
    }

    private static boolean hasAttr(AxmlElement e, String name) {
        for (AxmlAttribute a : e.attributes) {
            if (a.name.equals(name)) return true;
        }
        return false;
    }

    private static AxmlElement gradientAttrWrap(AxmlElement gradient) {
        AxmlElement wrap = new AxmlElement(NS_AAPT, "attr");
        wrap.attributes.add(new AxmlAttribute("", "name", 0,
                "android:fillColor", ResValue.TYPE_STRING, 0));
        wrap.children.add(gradient);
        return wrap;
    }
}
