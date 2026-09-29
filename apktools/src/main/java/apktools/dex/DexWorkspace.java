// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex;
// 
// import apktools.ApkException;
// import apktools.dex.internal.DexLoader;
// import apktools.dex.internal.DexOutput;
// 
// import java.io.File;
// import java.util.ArrayList;
// import java.util.Collections;
// import java.util.List;
// 
// /**
//  * A loaded DEX set: one editing session over one or more DEX files.
//  *
//  * <p>Multidex files stay distinguishable ({@code classes.dex},
//  * {@code classes2.dex}, ...) and writing preserves that separation —
//  * classes are never merged or repartitioned.
//  *
//  * <p>Not thread-safe.
//  */
// public final class DexWorkspace {
// 
//     private final List<DexFile> dexFiles = new ArrayList<>();
//     private final DexClassMap classMap = new DexClassMap(this);
// 
//     DexWorkspace(List<DexLoader.LoadedDex> loaded) {
//         for (int i = 0; i < loaded.size(); i++) {
//             DexFile f = new DexFile(this, loaded.get(i), i);
//             dexFiles.add(f);
//             classMap.index(f);
//         }
//     }
// 
//     /** Loaded DEX files in load order. */
//     public List<DexFile> getDexFiles() {
//         return Collections.unmodifiableList(dexFiles);
//     }
// 
//     public int getDexCount() {
//         return dexFiles.size();
//     }
// 
//     /** DEX file by entry name ({@code classes2.dex}), or null. */
//     public DexFile getDexFile(String name) {
//         for (DexFile f : dexFiles) {
//             if (f.getName().equals(name)) return f;
//         }
//         return null;
//     }
// 
//     /** Workspace-wide class index. */
//     public DexClassMap getClassMap() {
//         return classMap;
//     }
// 
//     /** By descriptor or Java name, across all DEX files, or null. */
//     public DexClass findClass(String name) {
//         return classMap.findClass(name);
//     }
// 
//     public int getClassCount() {
//         return classMap.size();
//     }
// 
//     /**
//      * True when some type is defined in more than one DEX file.
//      * See {@link #getDuplicates} — a future class-browser UI can warn
//      * exactly like MT Manager ("duplicate classes have been excluded").
//      */
//     public boolean hasDuplicates() {
//         return classMap.hasDuplicates();
//     }
// 
//     /** One entry per duplicated type; empty when {@link #hasDuplicates} is false. */
//     public List<DexDuplicate> getDuplicates() {
//         return classMap.getDuplicates();
//     }
// 
//     /**
//      * Writes every DEX back to {@code outputDirectory}, one file per
//      * loaded DEX ({@code classes.dex}, {@code classes2.dex}, ...),
//      * preserving the input separation. Returns the files written.
//      */
//     public List<File> write(File outputDirectory) {
//         if (outputDirectory == null) throw new NullPointerException("outputDirectory == null");
//         if (outputDirectory.isFile()) {
//             throw ApkException.io("not a directory: " + outputDirectory, null);
//         }
//         if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
//             throw ApkException.io("creating output dir " + outputDirectory, null);
//         }
//         List<File> out = new ArrayList<>(dexFiles.size());
//         for (DexFile f : dexFiles) {
//             File dest = new File(outputDirectory, f.getName());
//             DexOutput.writeSingle(dest, f.opcodes(), f.currentClasses());
//             out.add(dest);
//         }
//         return out;
//     }
// }
