# Keep kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class nu.bacher.memos.**$$serializer { *; }
-keepclassmembers class nu.bacher.memos.** {
    *** Companion;
}
-keepclasseswithmembers class nu.bacher.memos.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp (Ktor engine)
-dontwarn okhttp3.**
-dontwarn okio.**

# Ktor
-dontwarn io.ktor.**
-keep class io.ktor.client.engine.okhttp.** { *; }

# SQLDelight Android driver
-keep class app.cash.sqldelight.driver.android.** { *; }
