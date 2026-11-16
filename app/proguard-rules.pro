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

# Firebase / Play Services 不需要手动 keep：
# BoM 34.x 起，各 play-services / firebase AAR 自带 consumer 混淆规则（proguard.txt），
# R8 会自动合并，官方文档也明确 R8 下无需额外 keep。
# 旧的 -keep class com.google.android.gms.** { *; } 已删除，避免全量禁用混淆与优化。
-keep class androidx.appcompat.view.** { *; }
-keep class androidx.window.extensions.embedding.** { *; }
-keep class is.xyz.mpv.** { *; }
-keep class lis.xyz.mpv.** { *; }
# ECH: Conscrypt 反射调用 getNetworkSecurityPolicy()，R8 会误杀，必须保留
-keep class com.yenaly.han1meviewer.logic.network.ech.ConscryptEch$PolicyTrustManager { *; }
-keep class org.conscrypt.** { *; }