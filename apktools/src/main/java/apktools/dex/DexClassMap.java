// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex;
// 
// import apktools.dex.internal.DexNames;
// 
// import java.util.ArrayList;
// import java.util.Collections;
// import java.util.LinkedHashMap;
// import java.util.List;
// import java.util.Map;
// import java.util.Set;
// 
// /**
//  * Workspace-wide class index. Callers look classes up by Java name or DEX
//  * descriptor without caring which {@code classesN.dex} owns them; each
//  * {@link DexClass} still reports its owner via {@link DexClass#getDexFile}.
//  *
//  * <p>Duplicate classes (same type in several DEX files) resolve to the
//  * first DEX that defines them; the rest are recorded as
//  * {@link DexDuplicate} so a UI can warn like MT Manager does.
//  */
// public final class DexClassMap {
// 
//     private final DexWorkspace workspace;
//     private final Map<String, DexFile> ownerByType = new LinkedHashMap<>();
//     private final Map<String, DexDuplicate> duplicates = new LinkedHashMap<>();
// 
//     DexClassMap(DexWorkspace workspace) {
//         this.workspace = workspace;
//     }
// 
//     void index(DexFile file) {
//         for (String desc : file.getClassDescriptors()) {
//             DexFile kept = ownerByType.get(desc);
//             if (kept == null) {
//                 ownerByType.put(desc, file);
//             } else if (kept != file) {
//                 DexDuplicate existing = duplicates.get(desc);
//                 if (existing == null) {
//                     List<DexFile> excluded = new ArrayList<>();
//                     excluded.add(file);
//                     duplicates.put(desc, new DexDuplicate(desc, kept, excluded));
//                 } else {
//                     List<DexFile> excluded = new ArrayList<>(existing.getExcludedOwners());
//                     if (!excluded.contains(file)) {
//                         excluded.add(file);
//                         duplicates.put(desc, new DexDuplicate(desc, kept, excluded));
//                     }
//                 }
//             }
//         }
//     }
// 
//     /** By descriptor ({@code Lcom/a/B;}) or Java name ({@code com.a.B}). */
//     public DexClass findClass(String name) {
//         String desc = DexNames.normalizeToDescriptor(name);
//         DexFile owner = ownerByType.get(desc);
//         return owner != null ? new DexClass(owner, desc) : null;
//     }
// 
//     public boolean containsClass(String name) {
//         return ownerByType.containsKey(DexNames.normalizeToDescriptor(name));
//     }
// 
//     public int size() {
//         return ownerByType.size();
//     }
// 
//     public Set<String> descriptors() {
//         return Collections.unmodifiableSet(ownerByType.keySet());
//     }
// 
//     /** Owning file for a descriptor, or null. */
//     public DexFile ownerOf(String descriptor) {
//         return ownerByType.get(DexNames.normalizeToDescriptor(descriptor));
//     }
// 
//     /** True when some type is defined in more than one DEX file. */
//     public boolean hasDuplicates() {
//         return !duplicates.isEmpty();
//     }
// 
//     /** One entry per duplicated type, in first-seen order. */
//     public List<DexDuplicate> getDuplicates() {
//         return Collections.unmodifiableList(
//                 new ArrayList<>(duplicates.values()));
//     }
// 
//     /** Owning workspace. */
//     public DexWorkspace getWorkspace() {
//         return workspace;
//     }
// }
