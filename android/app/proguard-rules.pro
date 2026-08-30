# Ktor's CIO engine and kotlinx.serialization resolve implementations reflectively.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.debug.**
-keepclassmembers class io.ktor.** { volatile <fields>; }

# kotlinx.serialization keeps generated serializers reachable through companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.airclip.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.airclip.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Shizuku instantiates the user service by name in a separate process.
-keep class com.airclip.platform.shizuku.AirClipShizukuService { *; }
-keep interface com.airclip.platform.shizuku.IShizukuClipboard { *; }
-keep class com.airclip.platform.shizuku.IShizukuClipboard$* { *; }
-keep class rikka.shizuku.** { *; }
