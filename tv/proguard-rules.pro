# ─── 1. Stack Trace & Obfuscation Mapping ───
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ─── 2. ZXing & QR Generator ───
# zxing-android-embedded 4.3.0 与 zxing-core 都不带 proguard.txt（configuration.txt 里
# 没有这两个库的规则段头，只有我们下面自己写的），无 consumer rules 兜底，所以入口类要
# 具名保名。
#
# 全模块只有 tv/src/main/java/app/fjj/stun/tv/MainActivity.kt:48,49,191 用到，且只用
# `BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, size, size)` 这一条编码
# 路径。原先两条 `**` 通配把两个库全部类钉死（约 380 个），其中绝大多数 TV 端根本用不到
# —— 扫码侧的 DecoratedBarcodeView、CaptureActivity、BarcodeCallback、DefaultDecoderFactory
# 在本模块源码里一处都没引（全工程只在 :app 里用）。
# 收窄后其余类靠 R8 可达性自动保留：MultiFormatWriter → QRCodeWriter → BitMatrix 全是直调，
# BarcodeFormat 的分发是 switch 不是反射，保这两个入口类够用。
# 明确的一处假设：这条规则假定 zxing-core 编码路径无反射。要进一步收紧到零 keep，得先在
# TV 真机上跑一次生成 QR 码 —— 本仓库当前没有覆盖该路径的自动化测试，所以这里保守留两条。
-keep class com.journeyapps.barcodescanner.BarcodeEncoder { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-dontwarn com.google.zxing.**

# ─── 3. Leanback（已删，勿加回）───
# 原先是 -keep class androidx.leanback.** { *; }，锁死 784 个 TV 端从不引用的类。
# 删除依据：全模块源码 0 处 import、layout 资源 0 处引用 leanback 控件。唯一的「leanback」
# 字样是 AndroidManifest.xml:20 的
#   <uses-feature android:name="android.software.leanback" android.required="true" />
# 那是 Play Store 的机型过滤标签，对运行时代码零影响（不引入类、不触发反射），留着是正确
# 且必要的（TV 机型识别），但与 proguard 无关。
# 原先保留这条 keep 的理由是「1.2.0-alpha04 的 AAR 里没有 proguard.txt，无法证实反射点被
# 覆盖」—— 而本模块根本没引用这个库，那个顾虑不成立。真要恢复 leanback UI，需按当时的顾虑
# 重新评估 alpha04 的反射面，而不是把这行原样抄回来。

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
