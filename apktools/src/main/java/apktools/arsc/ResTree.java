package apktools.arsc;

import apktools.apk.ArscIdResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoded, config-major view of a resources table — the internal model
 * behind resource trees and the future ARSC editor.
 *
 * <p>While {@link ArscFile} mirrors file chunks (package → type chunks
 * per config), this mirrors what editors show: package → type → config
 * → named entries with display text. Every row keeps its live
 * {@link ArscEntry} handle, so {@link ResTreeRow#setText(String)}
 * edits the table in place and {@link ArscFile#toBytes()} saves it.
 *
 * <p>Detached trees (see {@link ResTreeReader}) carry parsed values
 * without a live table for inspection and interchange.
 */
public final class ResTree {

    private final ArscFile table;
    private final List<ResTreePackage> packages = new ArrayList<>();
    private final Map<String, Integer> idByName = new LinkedHashMap<>();

    private ResTree(ArscFile table) {
        this.table = table;
    }

    /**
     * Decodes a table into the tree model. Values stay lazy inside the
     * table; building the tree itself only walks chunk headers plus
     * offset arrays.
     */
    public static ResTree decode(ArscFile table) {
        ResTree tree = new ResTree(table);
        for (ArscPackage pkg : table.packages()) {
            ResTreePackage tp = new ResTreePackage(tree, pkg);
            tree.packages.add(tp);
            for (ResTreeType type : tp.types()) {
                for (ResTreeConfig cfg : type.configs()) {
                    for (ResTreeRow row : cfg.rows()) {
                        tree.idByName.put(
                                type.name() + "/" + row.key(), row.resId());
                    }
                }
            }
        }
        // Reverse map hits framework + foreign packages as qualified ids.
        return tree;
    }

    /** Live table backbone (null for detached trees). */
    public ArscFile table() {
        return table;
    }

    public List<ResTreePackage> packages() {
        return Collections.unmodifiableList(packages);
    }

    public ResTreePackage findPackage(String name) {
        for (ResTreePackage p : packages) {
            if (p.name().equals(name)) return p;
        }
        return null;
    }

    /**
     * Name → id for {@code type/name} within one package (built from
     * live keys, so obfuscated file names don't matter).
     */
    Map<String, Integer> idByName() {
        return idByName;
    }

    /** Detached-tree factory used by the reader (table may be null). */
    static ResTree detached(ArscFile table) {
        return new ResTree(table);
    }

    void addPackage(ResTreePackage p) {
        packages.add(p);
    }

    void mapName(String type, String key, int resId) {
        idByName.put(type + "/" + key, resId);
    }

    /** Display + parse context scoped to one package. */
    static final class IdResolverView {
        final ArscIdResolver inner;
        private final ArscFile table;
        private final String contextPackage;
        private ResValueParser.Ctx parserCtx;

        IdResolverView(ArscFile table, String contextPackage) {
            this.table = table;
            this.contextPackage =
                    contextPackage == null ? "" : contextPackage;
            inner = new ArscIdResolver(table, this.contextPackage);
        }

        /** Parser context (name → id needs the live table). */
        synchronized ResValueParser.Ctx ctx() {
            if (parserCtx == null) {
                parserCtx =
                        new ResValueParser.Ctx(table, contextPackage);
            }
            return parserCtx;
        }
    }
}
