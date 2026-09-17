# Keep libVLC native bindings.
-keep class org.videolan.libvlc.** { *; }

# JLayer loads its synthesis coefficient .ser resource relative to this package.
# Preserve decoder names/resources in the minified APK, not just debug builds.
-keep class javazoom.jl.decoder.** { *; }
# Desktop player classes are unused/removed; only the decoder package is called.
-dontwarn javax.sound.sampled.**

# Cast: the OptionsProvider is referenced only by name in the manifest meta-data,
# so R8 must not rename or strip it (would break Cast init in release builds).
-keep class com.iptv.player.cast.CastOptionsProvider { *; }

# Retrofit / OkHttp / Gson models accessed via reflection.
-keep class com.iptv.player.data.remote.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# Media3 FFmpeg audio extension (CI-built AAR in app/libs; absent locally).
# libffmpegJNI.so resolves Java methods by name; the AAR's consumer rules only
# cover growOutputBuffer, so keep the whole package explicitly. Absent AAR ->
# no such classes, and R8 must not warn about the missing package.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
