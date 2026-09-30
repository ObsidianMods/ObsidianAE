# Add project specific ProGuard rules here.

-obfuscationdictionary proguard-dictionary.txt
-classobfuscationdictionary proguard-dictionary.txt


-repackageclasses I

# Keep app frames readable in crash stacks (diagnosability over bytes).
# Shrinking/optimization still apply; only app naming is preserved so the
# recovery screen's copy-paste reports point at real classes/methods.
-keep class com.obsidian.apkeditor.** { *; }

# smali/dexlib2/baksmali + apksig are exercised on-device by the edit/build
# tools (class merge, assemble, sign). Keep them intact and silence the
# optional-dependency warnings their jars carry.
-keep class com.android.tools.smali.** { *; }
-dontwarn com.android.tools.smali.**
-dontwarn org.antlr.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
