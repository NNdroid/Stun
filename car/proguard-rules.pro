# ─── 1. Stack Trace & Obfuscation Mapping ───
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ─── 2. ZXing & QR Generator（已删，勿加回）───
# 原先是两条 `**` 通配，把 zxing + journeyapps 全部类钉死。删除依据：
#   - 本模块源码 0 处 import、layout 0 处引用、manifest 0 处注册 CaptureActivity；
#   - 本模块也没有调用 :core 的 app.fjj.stun.qr 包（QrFrameRenderer / QRUtils /
#     AnimatedQr*），二维码功能只存在于 :app 和 :tv。
#
# 重要修正：只删这两条 keep **并不会**让 zxing 从 dex 里消失。zxing-android-embedded 的
# AAR manifest 声明了 com.journeyapps.barcodescanner.CaptureActivity，而 AGP 合并 manifest
# 后 manifest 中的组件声明是 R8 的 keep 根 —— CaptureActivity 及其整个引用闭包（约 143 个
# zxing / journeyapps 类）被 keep 住，与我们的 proguard 规则无关。实测：删掉这两条 keep 后
# car 的 mapping.txt 里仍有 106 个 com.google.zxing 类与 37 个 com.journeyapps 类。
# 真正起作用的删法在 car/src/main/AndroidManifest.xml：
#   <activity android:name="com.journeyapps.barcodescanner.CaptureActivity" tools:node="remove" />
# 那一步才是把 keep 根摘掉、让 R8 能移除这些类。两处必须一起看。
#
# 顺带一笔待办（本次不动 build.gradle）：car/build.gradle.kts:185-187 仍声明了
# zxing-android-embedded 与 zxing-core，与本模块 0 引用的事实不符。留着不占 dex 体积
# （R8 会移掉不可达类），但占依赖图与增量编译时间，属于可顺手清掉的项。
# wear / xr 的 build.gradle.kts 压根没声明这两个依赖，说明 car 这份是历史残留。

# ─── 3. 已删除的过宽规则（有意为之，勿加回）───
# -keep class com.google.android.material.** { *; }
#   该库（1.14.0）自带 consumer rules，覆盖自己的反射点。
#   原整包保名把 1290 个条目钉死，是本工程 noObfuscation 65% 的最大单一来源。
# -keep class app.fjj.stun.car.databinding.** { *; }
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
