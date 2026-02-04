# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android SDK tools.

# Keep ONVIF model classes
-keep class com.example.onvifcameraviewer.domain.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
