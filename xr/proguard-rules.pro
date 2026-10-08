# ─── 1. Stack Trace & Obfuscation Mapping ───
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ─── 2. ZXing & QR Generator ───
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# ─── 3. 已删除的过宽规则（有意为之，勿加回）───
# -keep class com.google.android.material.** { *; }
#   该库（1.14.0）自带 consumer rules，覆盖自己的反射点。
#   原整包保名把 1290 个条目钉死，是本工程 noObfuscation 65% 的最大单一来源。
# -keep class app.fjj.stun.xr.databinding.** { *; }
#   绑定类只有编译期直引；按名反射的安全点 DataBinderMapperImpl 由 androidx.databinding
#   自带的 keep 规则覆盖，本行重复。
# -keep class app.fjj.stun.repo.StunLogger { *; }
#   类上已带 @Keep 注解，R8 自动保名。

# ─── 4. JNI 本地方法 ───
# 类名与方法名两侧都被 C 字符串绑定（Java_<pkg>_<Class>_<method>），改名即 UnsatisfiedLinkError。
# 原规则写的 hev.htproxy 在本仓库不存在，真实 JNI 类是 hev.htp.TTunnelService。
-keep class hev.htp.** { *; }
-keep class myssh.** { *; }

# ─── 5. Strip Android Log in Release Build ───
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

