# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keep class com.crabcode.ide.**$$serializer {
    *** INSTANCE;
    *** Companion;
}
-keepclassmembers class com.crabcode.ide.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Apache commons-compress (rootfs extraction) — keep entry points
-keep class org.apache.commons.compress.archivers.tar.** { *; }
