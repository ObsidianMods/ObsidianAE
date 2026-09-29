// SHELVED (dexlib2/smali unused): whole file line-commented - strip the leading "// " to restore.
// package apktools.dex.internal;
// 
// import apktools.ApkException;
// 
// import com.android.tools.smali.dexlib2.Opcodes;
// import com.android.tools.smali.dexlib2.iface.ClassDef;
// import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
// import com.android.tools.smali.dexlib2.writer.pool.DexPool;
// 
// import java.io.File;
// import java.util.Collection;
// 
// /**
//  * Writes one DEX file through {@link DexPool}.
//  *
//  * <p>Each workspace DEX is written independently, so multidex separation
//  * ({@code classes.dex}, {@code classes2.dex}, ...) is preserved and no
//  * class ever silently moves between files.
//  */
// public final class DexOutput {
// 
//     private DexOutput() {}
// 
//     public static void writeSingle(File out, Opcodes opcodes,
//                                    Collection<? extends ClassDef> classes) {
//         if (out == null) throw new NullPointerException("out == null");
//         try {
//             File parent = out.getParentFile();
//             if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
//                 throw ApkException.io("creating output dir " + parent, null);
//             }
//             DexPool pool = new DexPool(opcodes);
//             for (ClassDef c : classes) {
//                 pool.internClass(c);
//             }
//             pool.writeTo(new FileDataStore(out));
//         } catch (ApkException e) {
//             throw e;
//         } catch (Exception e) {
//             throw ApkException.io("writing dex " + out, e);
//         }
//     }
// }
