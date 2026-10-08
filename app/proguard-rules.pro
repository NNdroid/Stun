# ─── 1. Stack Trace & Obfuscation Mapping ───
# SourceFile / LineNumberTable 让混淆后的堆栈能被 mapping.txt 反解回源码行；
# -renamesourcefileattribute 让异常里的 sourceFile 字段也走映射表，不至于只剩 a/b/c 文件名。
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ─── 2. ZXing & Barcode Scanner ───
# 保留整包：zxing-android-embedded 4.3.0 的 AAR 里**没有** proguard.txt，没有 consumer rules 兜底；
# zxing-core 是纯 JAR 依赖，同样不带规则。扫码是主入口，而这类运行期静默失败单元测试抓不到，
# 为约 3% 的 dex 体积去赌主流程是亏的。
# 注：DecoratedBarcodeView（layout）与 CaptureActivity（manifest）AGP 已按资源引用自动保名。
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# ─── 3. Shizuku API ───
# 保留整包：dev.rikka.shizuku 13.1.5 的 AAR 里 proguard.txt 是 **0 字节**，规则只能我们自己给。
# 走 binder + AIDL 代理，类名一旦改名是静默断链，不是编译期报错。
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ─── 4. 已删除的过宽规则（有意为之，勿加回）───
# -keep class com.google.android.material.** { *; }
#   该库（1.14.0，本工程实际版本）自带 2101 字节的 consumer rules，覆盖它自己的全部反射点：
#   CoordinatorLayout$Behavior 子类构造器、MaterialComponentsViewInflater、FocusRingDrawable。
#   原整包保名把 1290 个条目钉死，是本工程 noObfuscation 65% 的最大单一来源。
# -keep class app.fjj.stun.databinding.** { *; }
#   绑定类只有编译期直引（XxxBinding.inflate），按名反射的安全点 DataBinderMapperImpl
#   由 androidx.databinding 自带的 "-keep public class * extends androidx.databinding.DataBinderMapper"
#   覆盖，本行重复。
# -keep class app.fjj.stun.repo.StunLogger { *; }
#   类上已带 @Keep 注解，R8 自动保名。

# ─── 5. JNI 本地方法 ───
# 类名与方法名两侧都被 C 字符串绑定（Java_<pkg>_<Class>_<method>），改名即 UnsatisfiedLinkError。
# 原规则写的是 hev.htproxy —— 该包在本仓库不存在（seeds.txt 命中 0 行），真实 JNI 类是
# hev.htp.TTunnelService（hev-socks5-tunnel 的 Java 侧，3 个 external fun）。
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
