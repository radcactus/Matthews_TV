# libVLC is driven from native code (JNI) and looks up its Java classes by name.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# Our own classes are small; keep them whole so kotlinx.serialization, the
# activities and the TV-provider integration can't be broken by shrinking.
-keep class app.marquee.tv.** { *; }
-keepclassmembers class app.marquee.tv.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# TV home-screen (Watch Next) provider helpers.
-keep class androidx.tvprovider.** { *; }

# Optional TLS providers OkHttp looks for but doesn't need.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
