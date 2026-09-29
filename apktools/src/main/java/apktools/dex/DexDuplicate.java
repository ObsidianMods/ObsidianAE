// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex;
// 
// import apktools.dex.internal.DexNames;
// 
// import java.util.ArrayList;
// import java.util.Collections;
// import java.util.List;
// 
// /**
//  * One class defined in more than one DEX file of the workspace.
//  *
//  * <p>Mirrors the MT Manager behavior shown when opening multidex apps:
//  * the first DEX keeps the class, later occurrences are reported as
//  * excluded (lookup resolves to the kept owner, but every owner still
//  * lists the class via {@link DexFile#findClass}). A future class-browser
//  * UI can group these by excluded owner, e.g.:
//  *
//  * <pre>
//  * &gt;&gt; classes2.dex
//  *   com.example.Dup
//  * </pre>
//  */
// public final class DexDuplicate {
// 
//     private final String descriptor;
//     private final DexFile keptOwner;
//     private final List<DexFile> excludedOwners;
// 
//     DexDuplicate(String descriptor, DexFile keptOwner, List<DexFile> excludedOwners) {
//         this.descriptor = descriptor;
//         this.keptOwner = keptOwner;
//         this.excludedOwners = Collections.unmodifiableList(
//                 new ArrayList<>(excludedOwners));
//     }
// 
//     /** DEX descriptor, e.g. {@code Lcom/example/Dup;}. */
//     public String getDescriptor() {
//         return descriptor;
//     }
// 
//     /** Java-style name, e.g. {@code com.example.Dup}. */
//     public String getJavaName() {
//         return DexNames.descriptorToJavaName(descriptor);
//     }
// 
//     /** First DEX defining the class — the one lookup resolves to. */
//     public DexFile getKeptOwner() {
//         return keptOwner;
//     }
// 
//     /** Later DEX files also defining it, in load order. */
//     public List<DexFile> getExcludedOwners() {
//         return excludedOwners;
//     }
// 
//     /** Excluded owner entry names ({@code classes2.dex}, ...). */
//     public List<String> getExcludedNames() {
//         List<String> out = new ArrayList<>(excludedOwners.size());
//         for (DexFile f : excludedOwners) {
//             out.add(f.getName());
//         }
//         return out;
//     }
// 
//     @Override
//     public String toString() {
//         return descriptor + " kept=" + keptOwner.getName()
//                 + " excluded=" + getExcludedNames();
//     }
// }
