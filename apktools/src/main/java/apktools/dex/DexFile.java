// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex;
// 
// import apktools.ApkException;
// import apktools.dex.internal.DexLoader;
// import apktools.dex.internal.DexNames;
// 
// import com.android.tools.smali.dexlib2.Opcodes;
// import com.android.tools.smali.dexlib2.iface.ClassDef;
// 
// import java.util.ArrayList;
// import java.util.Collection;
// import java.util.LinkedHashMap;
// import java.util.List;
// import java.util.Map;
// 
// /**
//  * One DEX file inside a {@link DexWorkspace}.
//  *
//  * <p>Knows its source path, entry name ({@code classes.dex},
//  * {@code classes2.dex}, ...), 0-based index and classes. Not thread-safe:
//  * one workspace is one editing session.
//  */
// public final class DexFile {
// 
//     private final DexWorkspace workspace;
//     private final String name;
//     private final String sourcePath;
//     private final int index;
//     private final Opcodes opcodes;
//     private final int apiLevel;
//     private final List<ClassDef> classes = new ArrayList<>();
//     private final Map<String, ClassDef> byType = new LinkedHashMap<>();
// 
//     DexFile(DexWorkspace workspace, DexLoader.LoadedDex loaded, int index) {
//         this.workspace = workspace;
//         this.name = loaded.dexName;
//         this.sourcePath = loaded.sourcePath;
//         this.index = index;
//         this.opcodes = loaded.opcodes;
//         this.apiLevel = loaded.apiLevel;
//         for (ClassDef c : loaded.classes) {
//             classes.add(c);
//             byType.put(c.getType(), c);
//         }
//     }
// 
//     /** Entry name, e.g. {@code classes.dex} or {@code classes2.dex}. */
//     public String getName() {
//         return name;
//     }
// 
//     /** Where this DEX was loaded from (file path or APK entry label). */
//     public String getSourcePath() {
//         return sourcePath;
//     }
// 
//     /** 0-based position in the workspace load order. */
//     public int getIndex() {
//         return index;
//     }
// 
//     /** API level derived from the DEX version (drives baksmali/smali). */
//     public int getApiLevel() {
//         return apiLevel;
//     }
// 
//     /** Owning workspace. */
//     public DexWorkspace getWorkspace() {
//         return workspace;
//     }
// 
//     public int getClassCount() {
//         return classes.size();
//     }
// 
//     /** Live handles for every class, in DEX order. */
//     public List<DexClass> getClasses() {
//         List<DexClass> out = new ArrayList<>(classes.size());
//         for (ClassDef c : classes) {
//             out.add(new DexClass(this, c.getType()));
//         }
//         return out;
//     }
// 
//     /** Class descriptors contained here. */
//     public List<String> getClassDescriptors() {
//         List<String> out = new ArrayList<>(classes.size());
//         for (ClassDef c : classes) {
//             out.add(c.getType());
//         }
//         return out;
//     }
// 
//     /** Finds a class by descriptor or Java name, or null. */
//     public DexClass findClass(String name) {
//         String desc = DexNames.normalizeToDescriptor(name);
//         return byType.containsKey(desc) ? new DexClass(this, desc) : null;
//     }
// 
//     public boolean containsClass(String name) {
//         return byType.containsKey(DexNames.normalizeToDescriptor(name));
//     }
// 
//     // -- internal ----------------------------------------------------------
// 
//     ClassDef findUpstream(String descriptor) {
//         return byType.get(descriptor);
//     }
// 
//     Opcodes opcodes() {
//         return opcodes;
//     }
// 
//     Collection<ClassDef> currentClasses() {
//         return classes;
//     }
// 
//     void replaceClass(String descriptor, ClassDef replacement) {
//         if (!byType.containsKey(descriptor)) {
//             throw ApkException.notFound("class " + descriptor);
//         }
//         byType.put(descriptor, replacement);
//         for (int i = 0; i < classes.size(); i++) {
//             if (classes.get(i).getType().equals(descriptor)) {
//                 classes.set(i, replacement);
//                 return;
//             }
//         }
//     }
// }
