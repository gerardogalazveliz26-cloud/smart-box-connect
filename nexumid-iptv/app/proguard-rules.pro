# El proyecto no usa reflexión propia; Media3, OkHttp y Coil traen sus reglas.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Decodificador FFmpeg: clases con métodos nativos (JNI).
-keep class androidx.media3.decoder.ffmpeg.** { *; }
