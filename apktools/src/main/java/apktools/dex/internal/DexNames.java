// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex.internal;
// 
// /**
//  * Descriptor / Java-name conversions and multidex naming helpers.
//  *
//  * <p>DEX type descriptors look like {@code Lcom/example/Main;} while Java
//  * code uses {@code com.example.Main}. Lookup accepts either form.
//  */
// public final class DexNames {
// 
//     private DexNames() {}
// 
//     /** {@code Lcom/example/Main;} → {@code com.example.Main}. */
//     public static String descriptorToJavaName(String descriptor) {
//         if (descriptor == null) return null;
//         if (descriptor.length() >= 2
//                 && descriptor.charAt(0) == 'L'
//                 && descriptor.charAt(descriptor.length() - 1) == ';') {
//             return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
//         }
//         return descriptor;
//     }
// 
//     /**
//      * Accepts a descriptor (returned as-is) or a Java name
//      * ({@code com.example.Main} → {@code Lcom/example/Main;}).
//      */
//     public static String normalizeToDescriptor(String name) {
//         if (name == null) return null;
//         String t = name.trim();
//         if (t.length() >= 2 && t.charAt(0) == 'L' && t.charAt(t.length() - 1) == ';') {
//             return t;
//         }
//         if (t.isEmpty()) return t;
//         return "L" + t.replace('.', '/') + ";";
//     }
// 
//     public static boolean isDescriptor(String name) {
//         return name != null && name.length() >= 2
//                 && name.charAt(0) == 'L' && name.charAt(name.length() - 1) == ';';
//     }
// 
//     /** File name without directories, or {@code "classes.dex"} fallback. */
//     public static String fileNameOf(String path) {
//         if (path == null) return "classes.dex";
//         int s = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
//         String base = s >= 0 ? path.substring(s + 1) : path;
//         return base.isEmpty() ? "classes.dex" : base;
//     }
// 
//     /**
//      * Suggested DEX entry name for the n-th input (0-based):
//      * {@code classes.dex}, {@code classes2.dex}, ...
//      */
//     public static String dexNameForIndex(int index) {
//         if (index <= 0) return "classes.dex";
//         return "classes" + (index + 1) + ".dex";
//     }
// }
