# Keep Compose runtime metadata used by the Compose compiler.
-dontwarn org.jetbrains.annotations.**

# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class fr.tear36.blus.** {
    *** Companion;
}
-keepclasseswithmembers class fr.tear36.blus.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# osmdroid
-dontwarn org.osmdroid.**
-keep class org.osmdroid.** { *; }

# Play services location (optional at runtime)
-dontwarn com.google.android.gms.**