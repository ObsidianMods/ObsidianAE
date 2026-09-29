// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex.internal;
// 
// import apktools.ApkException;
// 
// import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
// import com.android.tools.smali.baksmali.BaksmaliOptions;
// import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
// import com.android.tools.smali.dexlib2.Opcodes;
// import com.android.tools.smali.dexlib2.iface.ClassDef;
// import com.android.tools.smali.smali.Smali;
// import com.android.tools.smali.smali.SmaliOptions;
// 
// import java.io.File;
// import java.io.StringWriter;
// import java.nio.charset.StandardCharsets;
// import java.nio.file.Files;
// import java.util.Collections;
// 
// /**
//  * Smali disassembly / assembly through Google's baksmali/smali.
//  *
//  * <p>Uses only the libraries' public entry points
//  * ({@link ClassDefinition}, {@link Smali}) so minor upstream refactors of
//  * generated parser code do not break the wrapper. Single-class assembly
//  * goes through a temp file because {@link Smali#assemble} is file-oriented;
//  * nothing of that leaks into the public API.
//  */
// public final class DexSmali {
// 
//     private DexSmali() {}
// 
//     /** Disassembles one class. Generated lazily — never called bulk. */
//     public static String toSmali(ClassDef def, int apiLevel) {
//         if (def == null) throw new NullPointerException("def == null");
//         try {
//             BaksmaliOptions options = new BaksmaliOptions();
//             options.apiLevel = apiLevel;
//             ClassDefinition classDef = new ClassDefinition(options, def);
//             StringWriter out = new StringWriter(4096);
//             BaksmaliWriter writer = new BaksmaliWriter(out);
//             try {
//                 classDef.writeTo(writer);
//             } finally {
//                 try {
//                     writer.close();
//                 } catch (Exception ignored) {
//                 }
//             }
//             return out.toString();
//         } catch (ApkException e) {
//             throw e;
//         } catch (Exception e) {
//             throw new ApkException(ApkException.Code.ENCODE,
//                     "disassembling " + def.getType(), e);
//         }
//     }
// 
//     /**
//      * Assembles one Smali unit and returns the replacement class.
//      *
//      * @param expectedDescriptor the class being edited; the assembled
//      *        output must define exactly this type (no silent moves).
//      */
//     public static ClassDef assembleSingle(String smali, int apiLevel,
//                                           String expectedDescriptor) {
//         if (smali == null) throw new NullPointerException("smali == null");
//         File tmpDir = null;
//         try {
//             tmpDir = Files.createTempDirectory("apktools-dex").toFile();
//             File smaliFile = new File(tmpDir, "edit.smali");
//             Files.write(smaliFile.toPath(), smali.getBytes(StandardCharsets.UTF_8));
//             File outDex = new File(tmpDir, "out.dex");
// 
//             SmaliOptions options = new SmaliOptions();
//             options.apiLevel = apiLevel;
//             options.outputDexFile = outDex.getPath();
//             boolean ok = Smali.assemble(options,
//                     Collections.singletonList(smaliFile.getPath()));
//             if (!ok) {
//                 throw new ApkException(ApkException.Code.ENCODE,
//                         "smali assembly failed for " + expectedDescriptor
//                                 + " (syntax errors)");
//             }
//             DexLoader.LoadedDex loaded = DexLoader.loadOne(outDex, "out.dex", 0);
//             for (ClassDef c : loaded.classes) {
//                 if (c.getType().equals(expectedDescriptor)) {
//                     return c;
//                 }
//             }
//             throw new ApkException(ApkException.Code.ENCODE,
//                     "assembled smali defines no " + expectedDescriptor);
//         } catch (ApkException e) {
//             throw e;
//         } catch (Exception e) {
//             throw new ApkException(ApkException.Code.ENCODE,
//                     "assembling " + expectedDescriptor, e);
//         } finally {
//             deleteQuietly(tmpDir);
//         }
//     }
// 
//     private static void deleteQuietly(File f) {
//         if (f == null) return;
//         if (f.isDirectory()) {
//             File[] kids = f.listFiles();
//             if (kids != null) {
//                 for (File k : kids) deleteQuietly(k);
//             }
//         }
//         try {
//             f.delete();
//         } catch (Exception ignored) {
//         }
//     }
// 
//     /** Opcodes matching the api level used for this DEX. */
//     public static Opcodes opcodesForApi(int apiLevel) {
//         try {
//             return Opcodes.forApi(apiLevel);
//         } catch (Exception ignored) {
//             return Opcodes.getDefault();
//         }
//     }
// }
