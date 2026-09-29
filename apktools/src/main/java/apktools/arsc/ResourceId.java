package apktools.arsc;

/**
 * Packed Android resource id ({@code 0xPPTTEEEE}): package, type, entry.
 */
public final class ResourceId {

    private ResourceId() {}

    public static int make(int pkg, int type, int entry) {
        return ((pkg & 0xFF) << 24) | ((type & 0xFF) << 16) | (entry & 0xFFFF);
    }

    public static int pkg(int id) {
        return (id >>> 24) & 0xFF;
    }

    public static int type(int id) {
        return (id >>> 16) & 0xFF;
    }

    public static int entry(int id) {
        return id & 0xFFFF;
    }

    public static boolean isNull(int id) {
        return id == 0;
    }

    public static String hex(int id) {
        return "0x" + Integer.toHexString(id);
    }
}
