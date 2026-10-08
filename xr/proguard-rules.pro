# ─── 1. Stack Trace & Obfuscation Mapping ───
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ─── 2. ZXing & QR Generator（已删，勿加回）───
# 原先是两条 `**` 通配。删除依据：本模块源码 0 处 import、layout 0 处引用、没有调用 :core
# 的 app.fjj.stun.qr 包，xr/build.gradle.kts 里也没有 zxing 依赖。
#
# 重要修正：与 wear 一样，这两条不是「0 命中」的死规则 —— 依赖图里仍传进来了 zxing，规则
# 命中了类。正确说法是「冗余」：zxing-android-embedded 的 AAR manifest 声明的
# CaptureActivity 是 R8 keep 根，那 ~143 个 zxing / journeyapps 类被 keep 住与我们的规则
# 无关。实测删掉这两条 keep 后 xr 的 mapping.txt 里仍剩 106 个 com.google.zxing 与 37 个
# com.journeyapps 类。真正起作用的删法在 xr/src/main/AndroidManifest.xml 的
# tools:node="remove"。
#
# 同 wear 的提醒：AR/XR 端若要做扫码，journeyapps 的全屏 CaptureActivity 不适用，需另选
# 方案，别把这两行原样抄回来。

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

