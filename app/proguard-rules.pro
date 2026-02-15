# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ===== GSON serialized data classes =====
# R8 renames fields, but GSON uses reflection to match JSON keys to field names.
# Without these rules, deserialization returns null for non-null Kotlin fields → NPE.

# Locked folder system
-keep class com.nkls.nekovideo.components.helpers.LockedFileEntry { *; }
-keep class com.nkls.nekovideo.components.helpers.LockedFolderManifest { *; }
-keep class com.nkls.nekovideo.components.helpers.LockedFolderRegistryEntry { *; }
-keep class com.nkls.nekovideo.components.helpers.LockedFoldersRegistry { *; }

# Folder scanner cache
-keep class com.nkls.nekovideo.services.SerializableFolderInfo { *; }
-keep class com.nkls.nekovideo.services.SerializableVideoInfo { *; }
-keep class com.nkls.nekovideo.services.FolderInfo { *; }
-keep class com.nkls.nekovideo.services.VideoInfo { *; }