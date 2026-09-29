package apktools.arsc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One config variant of a type: the rows that share a
 * {@link ResConfig}. Renders as one {@code <type>[-qualifiers].xml}
 * file in a resource tree.
 */
public final class ResTreeConfig {

    private final ResTreeType type;
    private final ResConfig config;
    private final List<ResTreeRow> rows = new ArrayList<>();

    ResTreeConfig(ResTree tree, ResTreeType type, ArscType chunk) {
        this.type = type;
        this.config = chunk.config();
        ResTree.IdResolverView view = type.pkg().view();
        for (int i = 0; i < chunk.entryCount(); i++) {
            ArscEntry e = chunk.get(i);
            if (e == null) continue; // sparse slot: no value to show
            rows.add(new ResTreeRow(tree, this, e, view));
        }
    }

    /** Detached constructor used by the reader. */
    ResTreeConfig(ResTreeType type, ResConfig config) {
        this.type = type;
        this.config = config;
    }

    public ResConfig config() {
        return config;
    }

    /** aapt-style qualifier without the leading dash ("" = default). */
    public String qualifier() {
        String q = config.qualifierString();
        return q.startsWith("-") ? q.substring(1) : q;
    }

    public List<ResTreeRow> rows() {
        return Collections.unmodifiableList(rows);
    }

    void addRow(ResTreeRow r) {
        rows.add(r);
    }

    ResTreeType type() {
        return type;
    }
}
