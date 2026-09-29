package apktools.arsc;

/** One item of a complex (bag) value: attribute-name ref + typed value. */
public final class BagItem {
    /** Attribute resource id (name). */
    public final int name;
    public final int valueType;
    public final int valueData;

    public BagItem(int name, int valueType, int valueData) {
        this.name = name;
        this.valueType = valueType;
        this.valueData = valueData;
    }
}
