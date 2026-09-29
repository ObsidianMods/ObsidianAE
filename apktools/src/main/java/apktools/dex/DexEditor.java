// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex;
// 
// import apktools.ApkException;
// import apktools.apk.ApkReader;
// import apktools.dex.internal.DexLoader;
// import apktools.dex.internal.DexNames;
// 
// import java.io.File;
// import java.util.ArrayList;
// import java.util.Collections;
// import java.util.List;
// 
// /**
//  * Entry point for the DEX subsystem. No Android Context needed — usable
//  * from the file manager, background workers and plain unit tests.
//  *
//  * <p>Loading never writes anything; inspect and edit the returned
//  * {@link DexWorkspace}, then call {@link DexWorkspace#write} explicitly.
//  * Single-DEX loading shares the multi-DEX pipeline.
//  */
// public final class DexEditor {
// 
//     public DexEditor() {}
// 
//     /** Loads one DEX file (same pipeline as {@link #loadDexFiles}). */
//     public DexWorkspace loadDexFile(String path) {
//         if (path == null) throw new NullPointerException("path == null");
//         return loadDexFiles(Collections.singletonList(new File(path)));
//     }
// 
//     /** Loads one DEX file (same pipeline as {@link #loadDexFiles}). */
//     public DexWorkspace loadDexFile(File file) {
//         if (file == null) throw new NullPointerException("file == null");
//         return loadDexFiles(Collections.singletonList(file));
//     }
// 
//     /** Loads several DEX files into one workspace, in list order. */
//     public DexWorkspace loadDexFiles(List<File> files) {
//         if (files == null) throw new NullPointerException("files == null");
//         if (files.isEmpty()) {
//             throw ApkException.notFound("no dex files given");
//         }
//         List<DexLoader.LoadedDex> loaded = new ArrayList<>(files.size());
//         for (int i = 0; i < files.size(); i++) {
//             File f = files.get(i);
//             if (f == null) throw new NullPointerException("files[" + i + "] == null");
//             loaded.add(DexLoader.loadOne(f, null, i));
//         }
//         return new DexWorkspace(loaded);
//     }
// 
//     /** Same as {@link #loadDexFiles(List)} with path strings. */
//     public DexWorkspace loadDexFilesByPath(List<String> paths) {
//         if (paths == null) throw new NullPointerException("paths == null");
//         List<File> files = new ArrayList<>(paths.size());
//         for (String p : paths) {
//             if (p == null) throw new NullPointerException("path == null");
//             files.add(new File(p));
//         }
//         return loadDexFiles(files);
//     }
// 
//     /** Loads raw DEX bytes under an explicit entry name. */
//     public DexWorkspace loadDexBytes(String dexName, byte[] data) {
//         if (dexName == null) throw new NullPointerException("dexName == null");
//         if (data == null) throw new NullPointerException("data == null");
//         return new DexWorkspace(Collections.singletonList(
//                 DexLoader.loadBytes(dexName, data, dexName)));
//     }
// 
//     /**
//      * Integration point with the APK layer: extracts every
//      * {@code classes*.dex} via {@link ApkReader} and loads them as one
//      * multidex workspace (APK order, names preserved).
//      */
//     public DexWorkspace loadFromApk(ApkReader zip) {
//         if (zip == null) throw new NullPointerException("zip == null");
//         List<String> names = zip.dexNames();
//         if (names.isEmpty()) {
//             throw ApkException.notFound("no classes*.dex in apk");
//         }
//         List<DexLoader.LoadedDex> loaded = new ArrayList<>(names.size());
//         for (String n : names) {
//             byte[] data = zip.readBytes(n);
//             loaded.add(DexLoader.loadBytes(n, data, DexNames.fileNameOf(n)));
//         }
//         return new DexWorkspace(loaded);
//     }
// }
