package apktools.arsc;

import apktools.ResValue;
import apktools.StringPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One decoded resource entry: key name, owning config, and either a
 * simple typed value or a bag (style/theme/array).
 *
 * <p>Instances are views over the table bytes — cheap to hold, values
 * decoded once on first access. {@link #setValue} / {@link #setString}
 * mutate the in-memory model; call {@code ArscFile.toBytes()} to rebuild.
 */
public final class ArscEntry {

    /** Entry was {@code NO_ENTRY} (sparse slot). */
    public static final int NO_ENTRY = -1;

    final ArscPackage pkg;
    final int typeId;
    final int entryIndex;
    final int keyIndex;
    final int specFlags;
    final ResConfig config;

    // Value model.
    boolean complex;
    int parent; // bag parent ref when complex
    int valueType;
    int valueData;
    String pendingString; // setString() not yet flushed to the pool
    List<BagItem> bag;

    // Rebuild bookkeeping.
    final int rawOffset;
    final int rawLength;
    /** Raw entry flags word (complex/compact recomputed on edit). */
    final int entryFlags;
    boolean dirty;

    ArscEntry(ArscPackage pkg, int typeId, int entryIndex, int keyIndex,
              int specFlags, ResConfig config,
              boolean complex, int parent,
              int valueType, int valueData, List<BagItem> bag,
              int rawOffset, int rawLength, int entryFlags) {
        this.pkg = pkg;
        this.typeId = typeId;
        this.entryIndex = entryIndex;
        this.keyIndex = keyIndex;
        this.specFlags = specFlags;
        this.config = config;
        this.complex = complex;
        this.parent = parent;
        this.valueType = valueType;
        this.valueData = valueData;
        this.bag = bag;
        this.rawOffset = rawOffset;
        this.rawLength = rawLength;
        this.entryFlags = entryFlags;
    }

    public int resourceId() {
        return ResourceId.make(pkg.id(), typeId, entryIndex);
    }

    /** Owning package (for pool access and mutations). */
    public ArscPackage pkg() {
        return pkg;
    }

    /** Key (name) string. */
    public String key() {
        return pkg.keys().get(keyIndex);
    }

    public boolean isComplex() {
        return complex;
    }

    public boolean isPublic() {
        return (specFlags & 0x02) != 0;
    }

    public int valueType() {
        return valueType;
    }

    public int valueData() {
        return valueData;
    }

    public List<BagItem> bag() {
        return bag == null
                ? Collections.<BagItem>emptyList()
                : Collections.unmodifiableList(bag);
    }

    public int bagParent() {
        return parent;
    }

    /**
     * Display string: pool string for {@code TYPE_STRING}, aapt-style
     * formatting otherwise, {@code @(bag N)} for complex values.
     */
    public String stringValue() {
        if (complex) return "@(bag " + (bag == null ? 0 : bag.size()) + ")";
        if (pendingString != null) return pendingString;
        if (valueType == ResValue.TYPE_STRING) {
            return pkg.file().strings().get(valueData);
        }
        return ResValue.toString(valueType, valueData, pkg.file().strings());
    }

    /** Replace with a simple typed value. */
    public void setValue(int type, int data) {
        this.complex = false;
        this.bag = null;
        this.pendingString = null;
        this.valueType = type;
        this.valueData = data;
        this.dirty = true;
    }

    /**
     * Replace with a string. The text is appended to the global pool on
     * rebuild (existing indices never shift), or reuses an identical one.
     */
    public void setString(String s) {
        this.complex = false;
        this.bag = null;
        this.valueType = ResValue.TYPE_STRING;
        this.pendingString = s;
        // Intern now so the index is stable no matter when toBytes runs.
        this.valueData = pkg.file().internString(s);
        this.dirty = true;
    }

    /** Replace with a bag (style/array). Items are copied defensively. */
    public void setBag(int parent, List<BagItem> items) {
        this.complex = true;
        this.parent = parent;
        this.bag = new ArrayList<>(items);
        this.pendingString = null;
        this.dirty = true;
    }

    @Override
    public String toString() {
        return ResourceId.hex(resourceId()) + " " + key() + "=" + stringValue();
    }
}
