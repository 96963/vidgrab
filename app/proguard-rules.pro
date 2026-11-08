# VidGrab ProGuard 规则

# 忽略可选依赖缺失警告
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn java.lang.invoke.MethodHandleProxies

# 保留 Compose 相关
-keep class androidx.compose.** { *; }
-keep class org.jetbrains.compose.** { *; }

# 保留 Kotlinx Coroutines
-keep class kotlinx.coroutines.** { *; }

# 保留 Coil 图片加载
-keep class coil.** { *; }

# 保留 Media3 ExoPlayer
-keep class androidx.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }

# 保留 WebView 相关
-keep class android.webkit.** { *; }
-keep class androidx.webkit.** { *; }

# 保留 Jackson (yt-dlp 解析用)
-keep class com.fasterxml.jackson.** { *; }
-keep class com.fasterxml.jackson.databind.** { *; }

# 保留 Apache Commons
-keep class org.apache.commons.** { *; }
-keep class commons.io.** { *; }

# 保留 youtubedl-android 运行时类
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.youtubedl_common.** { *; }
-keep class com.yausername.aria2c.** { *; }
-keep class com.yausername.ffmpeg.** { *; }

# 保留应用核心类（防止重命名导致反射/序列化失败）
-keep class com.vidgrab.app.core.** { *; }
-keep class com.vidgrab.app.ui.** { *; }

# 保留枚举和数据类
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留带 @Serializable 注解的类（如果使用 kotlinx.serialization）
-keep class kotlinx.serialization.** { *; }

# 保留 JNI 本地方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 移除日志（release 构建）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}