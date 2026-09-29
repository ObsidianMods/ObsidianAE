package apktools.arsc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One resource type in a {@link ResTreePackage}: one block per config. */
public final class ResTreeType {

    private final ResTreePackage pkg;
    private final int id;
    private final String name;
    private final List<ResTreeConfig> configs = new ArrayList<>();

    ResTreeType(ResTree tree, ResTreePackage pkg, ArscPackage raw,
                int typeId, List<ArscType> chunks) {
        this.pkg = pkg;
        this.id = typeId;
        this.name = raw.typeName(typeId);
        // Canonical order: default config first, then qualifiers.
        List<ArscType> sorted = new ArrayList<>(chunks);
        Collections.sort(sorted, (a, b) -> a.config().qualifierString()
                .compareTo(b.config().qualifierString()));
        for (ArscType t : sorted) {
            configs.add(new ResTreeConfig(tree, this, t));
        }
    }

    /** Detached constructor used by the reader. */
    ResTreeType(ResTreePackage pkg, int id, String name) {
        this.pkg = pkg;
        this.id = id;
        this.name = name;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<ResTreeConfig> configs() {
        return Collections.unmodifiableList(configs);
    }

    ResTreePackage pkg() {
        return pkg;
    }

    void addConfig(ResTreeConfig c) {
        configs.add(c);
    }
}
