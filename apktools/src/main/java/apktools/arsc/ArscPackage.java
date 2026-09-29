package apktools.arsc;

import apktools.StringPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code RES_TABLE_PACKAGE} chunk: type/key string pools plus the
 * type-spec and type chunks belonging to one package id.
 */
public final class ArscPackage {

    private final ArscFile file;
    private final int id;
    private final String name;
    private final StringPool types;
    private final StringPool keys;
    final int typeIdOffset;

    // File order of spec/type chunks (rebuild preserves it).
    final List<ArscFile.ChunkToc> chunks = new ArrayList<>();
    private final Map<Integer, List<ArscType>> typesById = new LinkedHashMap<>();
    private final Map<Integer, int[]> specFlagsById = new LinkedHashMap<>();

    ArscPackage(ArscFile file, int id, String name,
                StringPool types, StringPool keys, int typeIdOffset) {
        this.file = file;
        this.id = id;
        this.name = name;
        this.types = types;
        this.keys = keys;
        this.typeIdOffset = typeIdOffset;
    }

    public ArscFile file() {
        return file;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    /**
     * Display name for references: the shared-library name from the
     * table's library chunk when present, else the package name.
     */
    public String displayName() {
        String lib = file.libName(id);
        return lib != null ? lib : name;
    }

    /** Type-name pool (index = typeId - typeIdOffset - 1). */
    public StringPool typeNames() {
        return types;
    }

    /** Key (entry-name) pool. */
    public StringPool keys() {
        return keys;
    }

    /** Type name for a type id, or {@code "type-XX"} when unknown. */
    public String typeName(int typeId) {
        int idx = typeId - typeIdOffset - 1;
        if (idx >= 0 && idx < types.count()) return types.get(idx);
        return "type-" + typeId;
    }

    /** Type id for a type name, or -1. Linear scan — cache if hot. */
    public int typeId(String typeName) {
        int idx = types.indexOf(typeName);
        return idx < 0 ? -1 : idx + typeIdOffset + 1;
    }

    /** All type chunks of one type id (one per config). */
    public List<ArscType> types(int typeId) {
        List<ArscType> l = typesById.get(typeId);
        return l == null
                ? Collections.<ArscType>emptyList()
                : Collections.unmodifiableList(l);
    }

    /** Every type chunk in the package. */
    public List<ArscType> allTypes() {
        List<ArscType> out = new ArrayList<>();
        for (List<ArscType> l : typesById.values()) out.addAll(l);
        return out;
    }

    /** Best config match for a type id (null config = first available). */
    public ArscType findType(int typeId, ResConfig want) {
        List<ArscType> l = typesById.get(typeId);
        if (l == null || l.isEmpty()) return null;
        ArscType best = null;
        for (ArscType t : l) {
            if (want == null) return t;
            if (ResConfig.betterThan(t.config(), best == null ? null : best.config(), 0)) {
                best = t;
            }
        }
        return best;
    }

    public ArscType findType(int typeId) {
        return findType(typeId, null);
    }

    /** Entry lookup without config preference (scans all configs). */
    public ArscEntry entry(int typeId, int entryIndex) {
        return entry(typeId, entryIndex, null);
    }

    /** Entry lookup with config preference (density-aware callers). */
    public ArscEntry entry(int typeId, int entryIndex, ResConfig want) {
        List<ArscType> l = typesById.get(typeId);
        if (l == null) return null;
        if (want == null) {
            for (ArscType t : l) {
                ArscEntry e = t.get(entryIndex);
                if (e != null) return e;
            }
            return null;
        }
        // Ranked walk: try configs best-first.
        List<ArscType> sorted = new ArrayList<>(l);
        Collections.sort(sorted, (a, b) -> {
            if (a == b) return 0;
            return ResConfig.betterThan(a.config(), b.config(), 0) ? -1 : 1;
        });
        for (ArscType t : sorted) {
            ArscEntry e = t.get(entryIndex);
            if (e != null) return e;
        }
        return null;
    }

    /** Spec (PUBLIC etc.) flags for an entry, or 0. */
    int specFlags(int typeId, int entryIndex) {
        int[] flags = specFlagsById.get(typeId);
        if (flags == null || entryIndex < 0 || entryIndex >= flags.length) return 0;
        return flags[entryIndex];
    }

    void addType(ArscType t) {
        List<ArscType> l = typesById.get(t.id());
        if (l == null) {
            l = new ArrayList<>();
            typesById.put(t.id(), l);
        }
        l.add(t);
    }

    void setSpecFlags(int typeId, int[] flags) {
        specFlagsById.put(typeId, flags);
    }

    /** Live entries across all types (reads offset arrays, not values). */
    public int liveEntryCount() {
        int n = 0;
        for (List<ArscType> l : typesById.values()) {
            for (ArscType t : l) n += t.liveCount();
        }
        return n;
    }
}
