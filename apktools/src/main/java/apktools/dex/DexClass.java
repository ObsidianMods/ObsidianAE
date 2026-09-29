// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex;
// 
// import apktools.ApkException;
// import apktools.dex.internal.DexNames;
// import apktools.dex.internal.DexSmali;
// 
// import com.android.tools.smali.dexlib2.iface.ClassDef;
// import com.android.tools.smali.dexlib2.iface.Field;
// import com.android.tools.smali.dexlib2.iface.Method;
// import com.android.tools.smali.dexlib2.iface.MethodParameter;
// 
// import java.util.ArrayList;
// import java.util.List;
// 
// /**
//  * One DEX class. A live handle: it resolves the current representation
//  * from its owning {@link DexFile} on every call, so
//  * {@link #updateFromSmali} is immediately visible through handles handed
//  * out earlier.
//  *
//  * <p>Never exposes dexlib types; upstream {@code ClassDef} stays inside
//  * {@code apktools.dex.internal}.
//  */
// public final class DexClass {
// 
//     private final DexFile owner;
//     private final String descriptor;
// 
//     DexClass(DexFile owner, String descriptor) {
//         this.owner = owner;
//         this.descriptor = descriptor;
//     }
// 
//     /** DEX descriptor, e.g. {@code Lcom/example/Main;}. */
//     public String getDescriptor() {
//         return descriptor;
//     }
// 
//     /** Java-style name, e.g. {@code com.example.Main}. */
//     public String getName() {
//         return DexNames.descriptorToJavaName(descriptor);
//     }
// 
//     /** Owning DEX file (never silently moved by edits). */
//     public DexFile getDexFile() {
//         return owner;
//     }
// 
//     private ClassDef current() {
//         ClassDef def = owner.findUpstream(descriptor);
//         if (def == null) {
//             throw ApkException.notFound("class " + descriptor);
//         }
//         return def;
//     }
// 
//     public String getSuperclassDescriptor() {
//         return current().getSuperclass();
//     }
// 
//     public String getSuperclassName() {
//         return DexNames.descriptorToJavaName(current().getSuperclass());
//     }
// 
//     /** Interface descriptors. */
//     public List<String> getInterfaceDescriptors() {
//         return new ArrayList<>(current().getInterfaces());
//     }
// 
//     /** Interface names in Java style. */
//     public List<String> getInterfaces() {
//         List<String> out = new ArrayList<>();
//         for (String i : current().getInterfaces()) {
//             out.add(DexNames.descriptorToJavaName(i));
//         }
//         return out;
//     }
// 
//     public int getAccessFlags() {
//         return current().getAccessFlags();
//     }
// 
//     public String getSourceFile() {
//         return current().getSourceFile();
//     }
// 
//     public int getAnnotationCount() {
//         return current().getAnnotations().size();
//     }
// 
//     /** {@code "name : type"} per field, static and instance. */
//     public List<String> getFields() {
//         List<String> out = new ArrayList<>();
//         for (Field f : current().getFields()) {
//             out.add(f.getName() + " : " + f.getType());
//         }
//         return out;
//     }
// 
//     /** {@code "name(params)return"} per method, direct and virtual. */
//     public List<String> getMethods() {
//         List<String> out = new ArrayList<>();
//         for (Method m : current().getMethods()) {
//             StringBuilder sb = new StringBuilder();
//             sb.append(m.getName()).append('(');
//             boolean first = true;
//             for (MethodParameter p : m.getParameters()) {
//                 if (!first) sb.append(", ");
//                 first = false;
//                 sb.append(p.getType());
//             }
//             sb.append(')').append(m.getReturnType());
//             out.add(sb.toString());
//         }
//         return out;
//     }
// 
//     /**
//      * Disassembles this class with baksmali. Generated on demand —
//      * loading never pre-computes Smali.
//      */
//     public String toSmali() {
//         return DexSmali.toSmali(current(), owner.getApiLevel());
//     }
// 
//     /**
//      * Reassembles edited Smali and swaps it into the owning DEX.
//      * The Smali must define this same class; anything else is rejected
//      * instead of moving classes between DEX files.
//      */
//     public void updateFromSmali(String smali) {
//         if (smali == null) throw new NullPointerException("smali == null");
//         ClassDef replacement = DexSmali.assembleSingle(smali,
//                 owner.getApiLevel(), descriptor);
//         owner.replaceClass(descriptor, replacement);
//     }
// 
//     @Override
//     public String toString() {
//         return getName();
//     }
// }
