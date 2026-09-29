package apktools.xml;

import apktools.ResValue;
import apktools.StringPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A decoded binary-XML document: string pool (kept for typed-value
 * display), top-level namespace declarations, and the root element.
 *
 * <p>Use {@link AxmlDecoder} to parse and {@link AxmlEncoder} to rebuild.
 * {@link #toXmlString()} renders a human-readable approximation (typed
 * values are formatted aapt-style, not re-resolved against resources).
 */
public final class AxmlDocument {

    public StringPool pool;
    /** Resource-map ids parallel to pool indices (may be null). */
    public int[] resourceMap;
    public final List<NsDecl> namespaces = new ArrayList<>();
    public AxmlElement root;
    /**
     * Optional id resolver (null = disabled). When set,
     * {@link #toXmlString()} shows resolved resource names instead of
     * hex ids. See {@link AxmlDecoder#decode(byte[], IdResolver)}.
     */
    public IdResolver idResolver;

    /** URI → prefix over all in-scope declarations (first wins). */
    public Map<String, String> prefixMap() {
        Map<String, String> out = new LinkedHashMap<>();
        collectPrefixes(root, out);
        for (NsDecl d : namespaces) {
            if (!out.containsKey(d.uri)) out.put(d.uri, d.prefix);
        }
        return out;
    }

    private static void collectPrefixes(AxmlElement e, Map<String, String> out) {
        if (e == null) return;
        for (NsDecl d : e.namespaces) {
            if (!out.containsKey(d.uri)) out.put(d.uri, d.prefix);
        }
        for (AxmlNode n : e.children) {
            if (n instanceof AxmlElement) collectPrefixes((AxmlElement) n, out);
        }
    }

    /** Human-readable XML approximation of the document. */
    public String toXmlString() {
        return toXmlString(idResolver);
    }

    /**
     * Same with an explicit resolver (null = hex ids). Overrides
     * {@link #idResolver} for this call only.
     */
    public String toXmlString(IdResolver resolver) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        Map<String, String> prefixes = prefixMap();
        // Declare every known prefix on the root so output stays valid.
        appendElement(sb, root, prefixes, resolver, 0, true);
        return sb.toString();
    }

    private void appendElement(StringBuilder sb, AxmlElement e,
                               Map<String, String> prefixes,
                               IdResolver resolver,
                               int depth, boolean declareAll) {
        indent(sb, depth);
        String tag = qualified(e.namespace, e.name, prefixes);
        sb.append('<').append(tag);

        List<String[]> attrs = new ArrayList<>();
        if (declareAll) {
            for (Map.Entry<String, String> en : prefixes.entrySet()) {
                if (en.getKey().isEmpty()) continue;
                attrs.add(new String[]{"xmlns:" + en.getValue(), en.getKey()});
            }
        } else {
            for (NsDecl d : e.namespaces) {
                attrs.add(new String[]{"xmlns:" + d.prefix, d.uri});
            }
        }
        for (AxmlAttribute a : e.attributes) {
            attrs.add(new String[]{a.qualifiedName(prefixes),
                    attrDisplay(a, resolver)});
        }
        boolean multiline = attrs.size() > 1;
        if (!multiline) {
            for (String[] av : attrs) {
                sb.append(' ').append(av[0]).append("=\"");
                escape(sb, av[1]).append('"');
            }
        } else {
            for (String[] av : attrs) {
                sb.append('\n');
                indent(sb, depth + 1);
                sb.append(av[0]).append("=\"");
                escape(sb, av[1]).append('"');
            }
        }

        boolean hasElements = false;
        StringBuilder text = new StringBuilder();
        for (AxmlNode n : e.children) {
            if (n instanceof AxmlElement) hasElements = true;
            else text.append(((AxmlText) n).text);
        }
        if (!hasElements) {
            if (text.toString().trim().isEmpty()) {
                sb.append(" />\n");
            } else {
                sb.append('>');
                escape(sb, text.toString().trim()).append("</");
                sb.append(tag).append(">\n");
            }
            return;
        }
        sb.append(">\n");
        for (AxmlNode n : e.children) {
            if (n instanceof AxmlElement) {
                appendElement(sb, (AxmlElement) n, prefixes, resolver,
                        depth + 1, false);
            } else {
                String t = ((AxmlText) n).text;
                if (!t.trim().isEmpty()) {
                    indent(sb, depth + 1);
                    escape(sb, t).append('\n');
                }
            }
        }
        indent(sb, depth);
        sb.append("</").append(tag).append(">\n");
    }

    /**
     * Attribute display value: enum/flags symbols for plain integers
     * whose attribute id is known ({@code center}, {@code singleTop}),
     * resolved references otherwise.
     */
    private String attrDisplay(AxmlAttribute a, IdResolver resolver) {
        if (a.rawValue == null && resolver != null && a.resourceId != 0
                && (a.valueType == ResValue.TYPE_INT_DEC
                || a.valueType == ResValue.TYPE_INT_HEX)) {
            String sym = resolver.enumName(a.resourceId, a.valueData);
            if (sym != null) return sym;
        }
        return a.stringValue(pool, resolver);
    }

    private static String qualified(String ns, String name,
                                    Map<String, String> prefixes) {
        if (ns == null || ns.isEmpty()) return name;
        String p = prefixes.get(ns);
        return p == null || p.isEmpty() ? name : p + ":" + name;
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("    ");
    }

    private static StringBuilder escape(StringBuilder sb, String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb;
    }

    // -- structural equality (round-trip tests) --------------------------

    /** Deep structural equality: tags, namespaces, attrs, order, values. */
    public boolean structurallyEquals(AxmlDocument o) {
        if (o == null || root == null || o.root == null) return false;
        return elementsEqual(root, o.root);
    }

    private static boolean elementsEqual(AxmlElement a, AxmlElement b) {
        if (!a.name.equals(b.name) || !a.namespace.equals(b.namespace)) return false;
        if (a.attributes.size() != b.attributes.size()) return false;
        for (int i = 0; i < a.attributes.size(); i++) {
            AxmlAttribute x = a.attributes.get(i), y = b.attributes.get(i);
            if (!x.name.equals(y.name) || !x.namespace.equals(y.namespace)
                    || x.resourceId != y.resourceId
                    || x.valueType != y.valueType || x.valueData != y.valueData
                    || !eq(x.rawValue, y.rawValue)) return false;
        }
        // Whitespace-only text is layout noise; compare the rest in order.
        List<AxmlNode> ca = significant(a.children), cb = significant(b.children);
        if (ca.size() != cb.size()) return false;
        for (int i = 0; i < ca.size(); i++) {
            AxmlNode x = ca.get(i), y = cb.get(i);
            if (x instanceof AxmlElement || y instanceof AxmlElement) {
                if (!(x instanceof AxmlElement) || !(y instanceof AxmlElement)) return false;
                if (!elementsEqual((AxmlElement) x, (AxmlElement) y)) return false;
            } else if (!((AxmlText) x).text.equals(((AxmlText) y).text)) {
                return false;
            }
        }
        return true;
    }

    private static List<AxmlNode> significant(List<AxmlNode> in) {
        List<AxmlNode> out = new ArrayList<>(in.size());
        for (AxmlNode n : in) {
            if (n instanceof AxmlText && ((AxmlText) n).text.trim().isEmpty()) continue;
            out.add(n);
        }
        return out;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
