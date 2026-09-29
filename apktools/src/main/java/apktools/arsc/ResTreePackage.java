package apktools.arsc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One package in a {@link ResTree}: types grouped with id maps. */
public final class ResTreePackage {

    private final ResTree tree;
    private final String name;
    private final int id;
    private final List<ResTreeType> types = new ArrayList<>();
    private final ResTree.IdResolverView view;

    ResTreePackage(ResTree tree, ArscPackage pkg) {
        this.tree = tree;
        this.name = pkg.displayName();
        this.id = pkg.id();
        this.view = new ResTree.IdResolverView(tree.table(), name);
        // Group type chunks by type id, sorted.
        Map<Integer, List<ArscType>> grouped = new LinkedHashMap<>();
        for (ArscType t : pkg.allTypes()) {
            List<ArscType> l = grouped.get(t.id());
            if (l == null) {
                l = new ArrayList<>();
                grouped.put(t.id(), l);
            }
            l.add(t);
        }
        List<Integer> ids = new ArrayList<>(grouped.keySet());
        Collections.sort(ids);
        for (int typeId : ids) {
            types.add(new ResTreeType(tree, this, pkg, typeId,
                    grouped.get(typeId)));
        }
    }

    /** Detached constructor used by the reader. */
    ResTreePackage(ResTree tree, String name, int id) {
        this.tree = tree;
        this.name = name;
        this.id = id;
        this.view = new ResTree.IdResolverView(tree.table(), name);
    }

    ResTree.IdResolverView view() {
        return view;
    }

    public String name() {
        return name;
    }

    public int id() {
        return id;
    }

    public List<ResTreeType> types() {
        return Collections.unmodifiableList(types);
    }

    public ResTreeType findType(String typeName) {
        for (ResTreeType t : types) {
            if (t.name().equals(typeName)) return t;
        }
        return null;
    }

    void addType(ResTreeType t) {
        types.add(t);
    }

    ResTree tree() {
        return tree;
    }
}
