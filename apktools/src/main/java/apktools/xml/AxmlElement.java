package apktools.xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One decoded element: namespace URI, tag name, attributes, namespace
 * declarations that start at this element, and ordered mixed children.
 */
public final class AxmlElement extends AxmlNode {

    public final String namespace;
    public final String name;
    public final List<AxmlAttribute> attributes = new ArrayList<>();
    public final List<NsDecl> namespaces = new ArrayList<>();
    public final List<AxmlNode> children = new ArrayList<>();

    public AxmlElement(String namespace, String name) {
        this.namespace = namespace == null ? "" : namespace;
        this.name = name;
    }

    /** First direct child element with this tag name, or null. */
    public AxmlElement child(String tag) {
        for (AxmlNode n : children) {
            if (n instanceof AxmlElement) {
                AxmlElement e = (AxmlElement) n;
                if (e.name.equals(tag)) return e;
            }
        }
        return null;
    }

    /** All direct child elements with this tag name. */
    public List<AxmlElement> children(String tag) {
        List<AxmlElement> out = new ArrayList<>();
        for (AxmlNode n : children) {
            if (n instanceof AxmlElement) {
                AxmlElement e = (AxmlElement) n;
                if (e.name.equals(tag)) out.add(e);
            }
        }
        return out;
    }

    /** All direct child elements regardless of tag. */
    public List<AxmlElement> elements() {
        List<AxmlElement> out = new ArrayList<>();
        for (AxmlNode n : children) {
            if (n instanceof AxmlElement) out.add((AxmlElement) n);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Attribute lookup. {@code ns} is the namespace URI (pass "" or null
     * for unqualified); {@code name} the local name.
     */
    public AxmlAttribute attr(String ns, String name) {
        String want = ns == null ? "" : ns;
        for (AxmlAttribute a : attributes) {
            if (a.name.equals(name) && a.namespace.equals(want)) return a;
        }
        return null;
    }

    /** Concatenated direct text children. */
    public String text() {
        StringBuilder sb = null;
        for (AxmlNode n : children) {
            if (n instanceof AxmlText) {
                if (sb == null) sb = new StringBuilder();
                sb.append(((AxmlText) n).text);
            }
        }
        return sb == null ? "" : sb.toString();
    }
}
