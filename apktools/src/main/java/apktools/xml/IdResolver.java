package apktools.xml;

/**
 * Maps a numeric resource id to a qualified name
 * ({@code android:versionCode}, {@code com.example:string/app_name}).
 *
 * <p>Resolution is optional everywhere: decoders take a resolver (or
 * null) and fall back to {@code @0x...} hex when it is absent or the
 * id is unknown. See {@link FrameworkIds} (built-in framework attrs)
 * and {@code apktools.apk} for table-backed resolvers.
 */
public interface IdResolver {

    /** Qualified name for {@code resId}, or null when unknown. */
    String resolve(int resId);

    /**
     * Symbolic name for an integer attribute value (enum/flags entries
     * such as {@code center} or {@code singleTop}), or null. Only used
     * for display; numeric output stays the default.
     */
    default String enumName(int attrId, int value) {
        return null;
    }

    /** Resolver that knows nothing (hex fallback everywhere). */
    IdResolver NONE = new IdResolver() {
        @Override
        public String resolve(int resId) {
            return null;
        }
    };
}
