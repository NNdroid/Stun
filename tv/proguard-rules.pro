# ─── 1. Stack Trace & Obfuscation Mapping ───
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ─── 2. ZXing & QR Generator ───
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# ─── 3. ViewBinding & AndroidX Leanback / Material ───
-keep class app.fjj.stun.tv.databinding.** { *; }
-keep class androidx.leanback.** { *; }
-keep class com.google.android.material.** { *; }

# ─── 4. JNI & Logging ───
-keep class hev.htproxy.** { *; }
-keep class myssh.** { *; }
-keep class app.fjj.stun.repo.StunLogger { *; }

# ─── 5. Strip Android Log in Release Build ───
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

