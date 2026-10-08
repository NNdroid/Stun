# ─── 1. Stack Trace & Obfuscation Mapping ───
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ─── 2. ZXing & QR Generator（已删，勿加回）───
# 原先是两条 `**` 通配。删除依据：本模块源码 0 处 import、layout 0 处引用、没有调用 :core
# 的 app.fjj.stun.qr 包（二维码功能只存在于 :app 和 :tv），而且 wear/build.gradle.kts 里
# 根本没有 zxing 相关依赖。
#
# 重要修正：这两条**不是**「0 命中」的死规则 —— 依赖图里仍传进来了 zxing，规则确实命中
# 了类。正确说法是「冗余」：zxing-android-embedded 的 AAR manifest 声明了
# com.journeyapps.barcodescanner.CaptureActivity，manifest 组件声明是 R8 的 keep 根，
# 那 ~143 个 zxing / journeyapps 类被 keep 住与我们的规则无关。实测删掉这两条 keep 后，
# wear 的 mapping.txt 里仍剩 106 个 com.google.zxing 与 38 个 com.journeyapps 类。
# 真正起作用的删法在 wear/src/main/AndroidManifest.xml 的 tools:node="remove"。
#
# 将来若可穿戴端要做扫码，不能把这两行原样抄回来：journeyapps 的 CaptureActivity 是全屏
# Activity，在 watch 上跑不了，需要另选方案。

# ─── 3. 已删除的过宽规则（有意为之，勿加回）───
# -keep class com.google.android.material.** { *; }
#   该库（1.14.0）自带 consumer rules，覆盖自己的反射点。
#   原整包保名把 1290 个条目钉死，是本工程 noObfuscation 65% 的最大单一来源。
# -keep class androidx.leanback.** { *; }
#   本模块 build.gradle.kts 里**没有** leanback 依赖（只有 tv 有），这条规则从来没匹配过东西。
# -keep class app.fjj.stun.tv.databinding.** { *; }
#   包名还是抄错的：本模块 namespace 是 app.fjj.stun.wear，所以这条也从没匹配过。
#   而且绑定类只有编译期直引，按名反射的安全点 DataBinderMapperImpl 由 androidx.databinding
#   自带的 keep 规则覆盖，不需要手写。
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
