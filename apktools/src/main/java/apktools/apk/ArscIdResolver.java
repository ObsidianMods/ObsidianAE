package apktools.apk;

import apktools.arsc.ArscEntry;
import apktools.arsc.ArscFile;
import apktools.arsc.ArscPackage;
import apktools.arsc.BagItem;
import apktools.arsc.ResourceId;
import apktools.xml.FrameworkIds;
import apktools.xml.IdResolver;

import java.util.List;

/**
 * Table-backed {@link IdResolver}: app (and shared-library) ids resolve
 * through the resources table, framework ids ({@code 0x01...}) through
 * {@link FrameworkIds}.
 *
 * <p>Naming follows aapt/apktool conventions: the context package is
 * omitted ({@code @string/app_name}), framework keeps its prefix
 * ({@code @android:drawable/ic_menu_help},
 * {@code ?android:attr/textColor}), other packages are qualified
 * ({@code @lib:string/x}). Anything unknown resolves to null so
 * callers fall back to hex.
 *
 * <p>Public so the resource-tree layer ({@code apktools.arsc}) and the
 * file manager can share one naming scheme.
 */
public final class ArscIdResolver implements IdResolver {

    private final ArscFile table;
    private final String contextPackage;

    public ArscIdResolver(ArscFile table, String contextPackage) {
        this.table = table;
        this.contextPackage =
                contextPackage == null ? "" : contextPackage;
    }

    @Override
    public String resolve(int resId) {
        if (resId == 0) return null;
        if (ResourceId.pkg(resId) == 0x01) {
            return FrameworkIds.get(resId);
        }
        if (table == null) return null;
        try {
            resId = table.finalizeId(resId);
            ArscPackage pkg = table.findPackage(ResourceId.pkg(resId));
            if (pkg == null) return null;
            int typeId = ResourceId.type(resId);
            ArscEntry e = pkg.entry(typeId, ResourceId.entry(resId));
            if (e == null) return null;
            String name = pkg.typeName(typeId) + "/" + e.key();
            String owner = pkg.displayName();
            if (owner.equals(contextPackage)) return name;
            return owner + ":" + name;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Enum/flags symbol for an integer attribute value: scans the
     * attribute's bag for an item holding exactly {@code value} and
     * returns its bare name ({@code center}). Framework attributes
     * resolve through the built-in enum table. Combinations without an
     * exact row stay numeric.
     */
    @Override
    public String enumName(int attrId, int value) {
        String fromBag = enumFromBag(attrId, value);
        if (fromBag != null) return fromBag;
        if (ResourceId.pkg(attrId) == 0x01) {
            return apktools.xml.FrameworkEnums.symbol(attrId, value);
        }
        return null;
    }

    private String enumFromBag(int attrId, int value) {
        if (table == null) return null;
        try {
            ArscPackage pkg =
                    table.findPackage(ResourceId.pkg(attrId));
            if (pkg == null) return null;
            ArscEntry attr = pkg.entry(ResourceId.type(attrId),
                    ResourceId.entry(attrId));
            if (attr == null || !attr.isComplex()) return null;
            List<BagItem> items = attr.bag();
            for (BagItem b : items) {
                if (b.valueData == value) {
                    String name = resolve(b.name);
                    if (name == null) {
                        return "0x" + Integer.toHexString(b.name);
                    }
                    int slash = name.lastIndexOf('/');
                    int colon = name.lastIndexOf(':');
                    int cut = Math.max(slash, colon);
                    return cut >= 0 ? name.substring(cut + 1) : name;
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }
}
