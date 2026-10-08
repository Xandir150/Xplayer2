# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
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

# VITURE One SDK (full flavor only): closed-source AAR that ships with NO consumer proguard rules
# of its own (checked app/libs/VITURE-SDK-1.0.7.aar — no proguard.txt inside). Exempt it entirely
# from shrinking/obfuscation rather than guess at what it needs internally; the `play` flavor
# doesn't include this dependency at all, so the rule is a harmless no-op there.
-keep class com.viture.sdk.** { *; }
-dontwarn com.viture.sdk.**

# SMB client (jcifs-ng): loads its providers and config classes by name.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**

# Release builds drop informational logging: every Log.v/d/i call (and the string building feeding
# it) is removed by R8. Warnings and errors stay, they are the only trace of a field failure.
# The rule applies to libraries too; debug builds are not minified and keep all logs.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
