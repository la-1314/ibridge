# Keep the app entry points and reflection-visible model classes.
-keep class com.ibridge.otg.IBridgeApp { *; }
-keep class com.ibridge.otg.MainActivity { *; }

# Miuix / Compose already ship their own consumer rules; keep annotations for safety.
-keepattributes *Annotation*, InnerClasses, Signature

# Do not warn about optional desktop-only classes pulled in by Compose Multiplatform metadata.
-dontwarn org.jetbrains.skia.**
-dontwarn org.jetbrains.compose.**
