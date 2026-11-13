# WebRTC нативный код ходит в Java-классы по именам через JNI —Keep критичен
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# kotlinx.serialization: сохраняем сериализаторы @Serializable-моделей Frigate
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ru.mytolm.frgate.**$$serializer { *; }
-keepclassmembers class ru.mytolm.frgate.** {
    *** Companion;
}
-keepclasseswithmembers class ru.mytolm.frgate.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp/okio (официальные правила okhttp поставляются, добавляем запас)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room реализация создаётся рефлексией по имени
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# media3 DefaultMediaSourceFactory грузит фабрики плейлистов рефлексией по имени класса
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.dash.** { *; }
