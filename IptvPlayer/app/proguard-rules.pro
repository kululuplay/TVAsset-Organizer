# Keep libVLC native bindings.
-keep class org.videolan.libvlc.** { *; }

# Retrofit / OkHttp / Gson models accessed via reflection.
-keep class com.iptv.player.data.remote.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
