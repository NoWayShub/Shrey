# Shrey v0.0.1 ProGuard / R8 Rules

# Keep youtubedl-android and Jackson/Python bridge classes
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.youtubedl_common.** { *; }

# Keep kotlinx.serialization Navigation routes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.shrey.downloader.navigation.**$$serializer { *; }
-keepclassmembers class com.shrey.downloader.navigation.** {
    *** Companion;
}
-keepclasseswithmembers class com.shrey.downloader.navigation.** {
    kotlinx.serialization.KSerializer serializer(...);
}
