// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex.internal;
// 
// import apktools.ApkException;
// 
// import com.android.tools.smali.dexlib2.Opcodes;
// import com.android.tools.smali.dexlib2.VersionMap;
// import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
// import com.android.tools.smali.dexlib2.iface.ClassDef;
// 
// import java.io.ByteArrayInputStream;
// import java.io.File;
// import java.io.IOException;
// import java.util.ArrayList;
// import java.util.List;
// 
// /**
//  * Loads raw DEX files/bytes into snapshots the wrapper owns.
//  *
//  * <p>Only this class (plus {@link DexSmali}/{@link DexOutput}) touches
//  * {@code com.android.tools.smali} types. Everything leaving here is either
//  * a plain holder ({@link LoadedDex}) or wrapped by the public API.
//  */
// public final class DexLoader {
// 
//     /** One loaded DEX: opcode set plus an owned snapshot of its classes. */
//     public static final class LoadedDex {
//         public final String dexName;
//         public final String sourcePath;
//         public final Opcodes opcodes;
//         public final int apiLevel;
//         public final List<ClassDef> classes;
// 
//         LoadedDex(String dexName, String sourcePath, Opcodes opcodes,
//                   int apiLevel, List<ClassDef> classes) {
//             this.dexName = dexName;
//             this.sourcePath = sourcePath;
//             this.opcodes = opcodes;
//             this.apiLevel = apiLevel;
//             this.classes = classes;
//         }
//     }
// 
//     private DexLoader() {}
// 
//     public static LoadedDex loadOne(File file, String dexName, int index) {
//         if (file == null) throw new NullPointerException("file == null");
//         if (!file.isFile()) {
//             throw ApkException.notFound("dex file " + file);
//         }
//         String name = dexName != null ? dexName : file.getName();
//         try {
//             Opcodes opcodes = opcodesForFile(file);
//             DexBackedDexFile dex = com.android.tools.smali.dexlib2.DexFileFactory
//                     .loadDexFile(file, opcodes);
//             return new LoadedDex(name, file.getPath(), dex.getOpcodes(),
//                     dex.getOpcodes().api, snapshot(dex.getClasses()));
//         } catch (ApkException e) {
//             throw e;
//         } catch (IOException e) {
//             throw ApkException.io("loading dex " + file, e);
//         } catch (Exception e) {
//             throw new ApkException(ApkException.Code.BAD_MAGIC,
//                     "not a dex file: " + file, e);
//         }
//     }
// 
//     public static LoadedDex loadBytes(String dexName, byte[] data, String sourcePath) {
//         if (dexName == null) throw new NullPointerException("dexName == null");
//         if (data == null) throw new NullPointerException("data == null");
//         if (data.length < 112) {
//             throw new ApkException(ApkException.Code.BAD_MAGIC,
//                     "not a dex file: " + dexName + " (" + data.length + " bytes)");
//         }
//         try {
//             Opcodes opcodes = opcodesForBytes(data);
//             DexBackedDexFile dex = DexBackedDexFile.fromInputStream(
//                     opcodes, new ByteArrayInputStream(data));
//             return new LoadedDex(dexName, sourcePath != null ? sourcePath : dexName,
//                     dex.getOpcodes(), dex.getOpcodes().api, snapshot(dex.getClasses()));
//         } catch (ApkException e) {
//             throw e;
//         } catch (IOException e) {
//             throw ApkException.io("loading dex " + dexName, e);
//         } catch (Exception e) {
//             throw new ApkException(ApkException.Code.BAD_MAGIC,
//                     "not a dex file: " + dexName, e);
//         }
//     }
// 
//     private static List<ClassDef> snapshot(Iterable<? extends ClassDef> in) {
//         List<ClassDef> out = new ArrayList<>();
//         for (ClassDef c : in) out.add(c);
//         return out;
//     }
// 
//     private static Opcodes opcodesForFile(File file) {
//         byte[] head = new byte[8];
//         java.io.FileInputStream fis = null;
//         try {
//             fis = new java.io.FileInputStream(file);
//             int n = 0;
//             while (n < 8) {
//                 int r = fis.read(head, n, 8 - n);
//                 if (r < 0) break;
//                 n += r;
//             }
//             if (n < 8) return Opcodes.getDefault();
//             return opcodesForBytes(head);
//         } catch (IOException ignored) {
//             return Opcodes.getDefault();
//         } finally {
//             if (fis != null) {
//                 try {
//                     fis.close();
//                 } catch (IOException ignored) {
//                 }
//             }
//         }
//     }
// 
//     static Opcodes opcodesForBytes(byte[] head) {
//         try {
//             if (head.length >= 7 && head[0] == 'd' && head[1] == 'e'
//                     && head[2] == 'x' && head[3] == '\n') {
//                 int v = (head[4] - '0') * 100 + (head[5] - '0') * 10 + (head[6] - '0');
//                 int api = VersionMap.mapDexVersionToApi(v);
//                 if (api != VersionMap.NO_VERSION) {
//                     return Opcodes.forApi(api);
//                 }
//             }
//         } catch (Exception ignored) {
//             // Fall through to default.
//         }
//         return Opcodes.getDefault();
//     }
// }
