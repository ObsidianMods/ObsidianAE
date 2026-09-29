package apktools;

import apktools.apk.ApkInfo;
import apktools.apk.ApkReader;
import apktools.arsc.ArscFile;
import apktools.xml.AxmlDecoder;
import apktools.xml.AxmlDocument;
import apktools.xml.AxmlEncoder;

import java.io.File;

/**
 * Entry point coordinating the apktools systems.
 *
 * <ul>
 *   <li>{@code apktools.xml} — binary XML decode / recompile</li>
 *   <li>{@code apktools.arsc} — resources table parse / rebuild</li>
 *   <li>{@code apktools.apk} — APK metadata, icons, ZIP access</li>
 *   <li>{@code apktools.dex} — DEX load / Smali edit / write (smali fork)</li>
 * </ul>
 */
public final class ApkTools {

    /** Library version. */
    public static final String VERSION = "1.0.0";

    private ApkTools() {}

    // -- xml ---------------------------------------------------------------

    /** Decodes binary XML (manifest, layout, drawable, ...). */
    public static AxmlDocument decodeXml(byte[] data) {
        return AxmlDecoder.decode(data);
    }

    /**
     * Decodes binary XML with id resolution: rendered output shows
     * resource names instead of hex ids (see {@code IdResolver}).
     */
    public static AxmlDocument decodeXml(byte[] data,
                                         apktools.xml.IdResolver resolver) {
        return AxmlDecoder.decode(data, resolver);
    }

    /** Recompiles a (possibly edited) document to binary XML. */
    public static byte[] encodeXml(AxmlDocument doc) {
        return AxmlEncoder.encode(doc);
    }

    /** Decodes + renders a human-readable approximation. */
    public static String xmlToString(byte[] data) {
        return AxmlDecoder.decodeToString(data);
    }

    // -- arsc ---------------------------------------------------------------

    /** Opens a resources table (lazy: header + table of contents). */
    public static ArscFile openArsc(byte[] data) {
        return ArscFile.open(data);
    }

    /** Rebuilds a (possibly edited) table. */
    public static byte[] rebuildArsc(ArscFile table) {
        return table.toBytes();
    }

    /**
     * Decodes a table into the MT-style resource tree model
     * (package → type → config → entries) for browsing and editing.
     */
    public static apktools.arsc.ResTree decodeTree(ArscFile table) {
        return apktools.arsc.ResTree.decode(table);
    }

    /** Exports a resource tree to the MT-compatible directory layout. */
    public static void writeTree(apktools.arsc.ResTree tree,
                                 java.io.File dir) {
        apktools.arsc.ResTreeWriter.write(tree, dir);
    }

    /** Reads a resource tree back (detached, inspection only). */
    public static apktools.arsc.ResTree readTree(java.io.File dir) {
        return apktools.arsc.ResTreeReader.read(dir);
    }

    /** Reads a tree bound to a live table (editing). */
    public static apktools.arsc.ResTree readTree(java.io.File dir,
                                                 ArscFile table) {
        return apktools.arsc.ResTreeReader.read(dir, table);
    }

    // -- apk ------------------------------------------------------------------

    /** Raw ZIP access to an APK. */
    public static ApkReader openApk(File apk) {
        return ApkReader.open(apk);
    }

    /** Full metadata inspection (manifest + lazy resources/icons). */
    public static ApkInfo inspectApk(File apk) {
        return ApkInfo.load(apk);
    }

    public static ApkInfo inspectApk(String path) {
        return ApkInfo.load(path);
    }

    public static ApkInfo inspectApk(byte[] apk, String name) {
        return ApkInfo.load(apk, name);
    }

//     // -- dex ------------------------------------------------------------------
// 
//     /** Loads one DEX file (see {@code apktools.dex.DexEditor}). */
//     public static apktools.dex.DexWorkspace openDex(File dex) {
//         return new apktools.dex.DexEditor().loadDexFile(dex);
//     }
// 
//     public static apktools.dex.DexWorkspace openDex(String path) {
//         return new apktools.dex.DexEditor().loadDexFile(path);
//     }
// 
//     /** Loads several DEX files into one multidex workspace. */
//     public static apktools.dex.DexWorkspace openDexFiles(java.util.List<File> files) {
//         return new apktools.dex.DexEditor().loadDexFiles(files);
//     }
// 
//     /** Loads every {@code classes*.dex} of an APK into one workspace. */
//     public static apktools.dex.DexWorkspace openDexFromApk(ApkReader apk) {
//         return new apktools.dex.DexEditor().loadFromApk(apk);
//     }
}
