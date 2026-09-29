# Add project specific ProGuard rules here.

-obfuscationdictionary proguard-dictionary.txt
-classobfuscationdictionary proguard-dictionary.txt


-repackageclasses I

# Keep app frames readable in crash stacks (diagnosability over bytes).
# Shrinking/optimization still apply; only app naming is preserved so the
# recovery screen's copy-paste reports point at real classes/methods.
-keep class com.obsidian.apkeditor.** { *; }