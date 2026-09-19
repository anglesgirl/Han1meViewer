# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepattributes SourceFile, LineNumberTable

-keepnames class * extends android.app.Activity
-keepnames class * extends androidx.fragment.app.Fragment

-keep class * extends cn.jzvd.** { *; }

-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }
-keep class androidx.appcompat.view.** { *; }
-keep class androidx.window.extensions.embedding.** { *; }
-keep class is.xyz.mpv.** { *; }
-keep class lis.xyz.mpv.** { *; }
-keep class cn.jzvd.** { *; }

# ⚠️ ECH 总开关：Conscrypt 用反射调用 PolicyTrustManager.getNetworkSecurityPolicy()，
# 被 R8 改名/裁掉的话，release 包会「日志全绿但一个字节 ECH 都不发」（ECH 静默失效）。
-keep class com.yenaly.han1meviewer.logic.network.ech.ConscryptEch$PolicyTrustManager { *; }

# WebView 传输桥：`@JavascriptInterface` 的方法由 JS **按名字**调用（send/postForm/log），
# 被混淆就变成"桥静默失效"——签名里核过 class 名可混淆，但方法名必须留
-keepclassmembers class com.yenaly.han1meviewer.logic.network.ech.EchWebBridge {
    public *;
}

# ⚠️ H3（QUIC+ECH）native 入口：Rust 侧按「全限定类名_方法名」导出符号，运行时 dlsym 按名解析。
# 类被改名 / h3Fetch 被裁掉 = H3 静默失效（永远回落 H2，日志上看不出错）——名字必须原样保留。
-keep class com.yenaly.han1meviewer.logic.network.ech.HyEchH3 { *; }
-keepclasseswithmembernames class com.yenaly.han1meviewer.logic.network.ech.HyEchH3 {
    native <methods>;
}