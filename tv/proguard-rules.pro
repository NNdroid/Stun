# ─── 1. Stack Trace & Obfuscation Mapping ───
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ─── 2. ZXing & QR Generator ───
# 同 app 模块：zxing-android-embedded 4.3.0 不带 proguard.txt，无 consumer rules 兜底。
# 注：DecoratedBarcodeView（layout）与 CaptureActivity（manifest）AGP 已按资源引用自动保名。
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# ─── 3. Leanback（保留）───
# leanback 是本模块整套 UI 框架，且当前锁定的 1.2.0-alpha04 AAR 里**没有** proguard.txt
# （"Intentionally empty … safe to shrink" 那份规则只从 1.2.0 正式版才开始带）。
# 无法证实它的反射点都被覆盖，而 TV 端 UI 一旦静默崩掉，单元测试与无设备环境都发现不了
# —— 保留，升级到 1.2.0 正式版后再重新评估。
-keep class androidx.leanback.** { *; }

# ─── 4. 已删除的过宽规则（有意为之，勿加回）───
# -keep class com.google.android.material.** { *; }
#   该库（1.14.0）自带 consumer rules，覆盖自己的反射点。
#   原整包保名把 1290 个条目钉死，是本工程 noObfuscation 65% 的最大单一来源。
# -keep class app.fjj.stun.tv.databinding.** { *; }
#   绑定类只有编译期直引；按名反射的安全点 DataBinderMapperImpl 由 androidx.databinding
#   自带的 keep 规则覆盖，本行重复。
# -keep class app.fjj.stun.repo.StunLogger { *; }
#   类上已带 @Keep 注解，R8 自动保名。

# ─── 5. JNI 本地方法 ───
# 类名与方法名两侧都被 C 字符串绑定（Java_<pkg>_<Class>_<method>），改名即 UnsatisfiedLinkError。
# 原规则写的 hev.htproxy 在本仓库不存在，真实 JNI 类是 hev.htp.TTunnelService。
-keep class hev.htp.** { *; }
-keep class myssh.** { *; }

# ─── 6. Strip Android Log in Release Build ───
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
