package apktools.xml;

/** Namespace declaration scoped to one element (prefix → URI). */
public final class NsDecl {
    public final String prefix;
    public final String uri;

    public NsDecl(String prefix, String uri) {
        this.prefix = prefix == null ? "" : prefix;
        this.uri = uri;
    }
}
