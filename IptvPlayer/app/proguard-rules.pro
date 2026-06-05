# Keep libVLC native bindings.
-keep class org.videolan.libvlc.** { *; }

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
