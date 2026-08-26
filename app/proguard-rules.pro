# ─── 1. Stack Trace & Obfuscation Mapping ───
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ─── 2. ZXing & Barcode Scanner ───
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# ─── 3. Shizuku API ───
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ─── 4. ViewBinding & Material Components ───
-keep class app.fjj.stun.databinding.** { *; }
-keep class com.google.android.material.** { *; }

# ─── 5. JNI & Logging ───
-keep class hev.htproxy.** { *; }
-keep class myssh.** { *; }
-keep class app.fjj.stun.repo.StunLogger { *; }

# ─── 6. Strip Android Log in Release Build ───
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}