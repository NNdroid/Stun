# ─── 1. Ktor / Netty / CIO / SLF4J ───
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn sun.misc.**

# *Annotation* 是通配，把 dex 里所有注解属性一律保留，比需要的宽。
# 只保留 Gson 需要的两个运行时注解属性（@SerializedName 已用 javap 核实是
# RetentionPolicy.RUNTIME，RuntimeVisibleAnnotations 即可保住）；
# kotlin.Metadata 是 Kotlin 反射的命根，不能动；SourceFile/LineNumberTable 供
# 线上崩溃堆栈映射回源码。
#
# 明确的一处取舍：这里**不**包含 RuntimeInvisibleAnnotations，所以 CLASS retention 的
# 注解（Room 的 @Entity/@Dao/@Database 就是，全部写在 RuntimeInvisibleAnnotations）
# 会被剥掉。今天没有缺口 —— Room 走 KSP、编译期消费完就不需要它；将来若有人在运行时
# 反射读这类注解（例如按注解扫描实体），必须先在这里补回该属性。
# -keepattributes 在 R8 full mode 下只对同时被 -keep 命中的类生效，所以上述判断的
# 前提是 Room 注解类仍留在 dex 里；即便如此，属性本身照样被剥，别把它当成还在。
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,kotlin.Metadata

-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-keep class * implements io.ktor.server.engine.ApplicationEngineFactory { *; }
-keep class * implements io.ktor.server.application.Plugin { *; }
-keep class io.ktor.server.cio.** { *; }

# Ktor 的 module 载体类。Ktor 对 Function1 形态的 module 走 clazz.declaredConstructors.single()
# 反射实例化；-allowaccessmodification 会让 R8 把编译器生成的 lambda 壳子合并进一个带多个构造器的
# 共享 hub 类（release 直接崩，见 WebServer.WebConsoleModule 的注释）。
-keep class app.fjj.stun.remote.WebServer$WebConsoleModule { *; }
# 上面这条其实是**冗余但值得留**的：WebConsoleModule 同时被下面的
# `-keep class app.fjj.stun.remote.** { *; }` 覆盖。留它是因为 module 载体这类
# 「类身份被反射消费」的点，单列一条具名规则比靠通配符命中要可读得多 —— 将来有人
# 收窄或删掉 remote 的通配符时，这条会立刻暴露出来，而不是让崩溃自己上门。
#
# 全工程 4 个 embeddedServer 调用点的覆盖情况（逐点核对过）：
#   WebServer.kt:237        WebConsoleModule（具名 class）—— 本条 + remote 通配符，双保险
#   StunMcpServer.kt:512    内联 lambda，类落在 app.fjj.stun.remote.** → remote 通配符覆盖
#   RemoteSyncManager.kt:91 内联 lambda，同上 → remote 通配符覆盖
#   DbWebServer.kt:91       内联 lambda，但类落在 app.fjj.stun.dbwebui.** ——
#                           consumer rules 里没有该包的规则（dbwebui/consumer-rules.pro
#                           原本只有一行空注释），只能靠结构性规避：module 是文件级函数
#                           dbWebModule()，调用点是普通 lambda 而非 KFunction，绕开
#                           kotlin-reflect 的 methodName 路径（见 DbWebServer.kt 的注释）。
#                           dbwebui 侧已补上对应 keep，见 dbwebui/consumer-rules.pro。

# ─── 2. JNI & Native Library Bindings ───
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class myssh.** { *; }
# hev.htp 是真正的 JNI 包（core/.../hev/htp/TTunnelService.kt）。
# 这里原先写的是 hev.htproxy —— 一个不存在的包，规则从未命中，而 app 级的
# proguard-rules.pro 已经被我改成 hev.htp；consumer rules 会被 5 个模块继承，
# 所以这行必须一起改，否则包名不一致会在下一个包重构时埋雷。
-keep class hev.htp.** { *; }

# ─── 3. Room Database & DAOs ───
# 这里原先五条规则已全部删除，两条原因：
#   a) 本仓库 Room 2.8.4 走 KSP（com.google.devtools.ksp 2.3.6），注解在编译期被
#      room-compiler 消费，生成代码直引实体字段与 DAO 方法，运行时不反射这三个注解。
#   b) 全工程 @Entity/@Dao/@Database 只出现在 app.fjj.stun.repo 包的 5 个文件里
#      （Profile/Subscription/AppDatabase/ProfileDao/SubscriptionDao），而该包已被下面
#      `-keep class app.fjj.stun.repo.**` 整包保名 —— 注解规则命中的全是已保名的类。
# `extends RoomDatabase { void <init>(); }` 与 `-dontwarn androidx.room.paging.**` 则与
# room-runtime 自带的 consumer rules 逐字重复（configuration.txt:560-561 可见那份原版），
# room-runtime 的规则 AGP 会照提，重复声明只会掩盖「这份规则其实不用我们写」这个事实。
# 唯一仍在生效的运行时反射点是 RoomDatabase 子类的无参构造器，room-runtime 自己保着。

# ─── 4. JSON Serialization / Gson / DTOs ───
# 这里原先是 -keep class com.google.gson.** { *; }，把 Gson 222 个类全量锁死。已删。
# 中间还试过把官方 gson.pro 抄进来当替代品，那也是一次误判：我当时的判断是
# AGP 只自动套用 AAR 根目录的 proguard.txt、不认 jar 里 META-INF/proguard/*.pro，
# 所以 gson 自己的规则等于不存在、必须手动搬运。
# 实际上 AGP 的 shrink-rules transform 两者都提。实测证据在
# app/build/outputs/mapping/release/configuration.txt:858-932 —— 段头写明 gson 的规则
# 取自 .../shrink-rules/lib/META-INF/proguard/gson.pro，与 okhttp3.pro、kotlin-reflect.pro、
# ktor.pro 并列被合并进同一个 R8 配置。jar 规则一直都在生效。
# 所以那份手写副本是纯重复，而且还漏抄了官方前半段的两条 -if/-keepclasseswithmembers
# （allowobfuscation 版），是「比原版更弱还多占一份配置」。整段已删。
#
# 同理 gson.pro 已经覆盖了 @SerializedName / @Expose 字段的 keep，本仓库所有被这两个
# 注解标记的 DTO 都在 app.fjj.stun.repo 包内（全工程 grep 确认只有 repo 下的 4 个文件），
# 由本段末尾的全包 keep 钉住字段名，不需要再抄一遍。
#
# 下面两条全包 keep 的理由成立且与 gson 是否自带规则无关 —— 真正跨线序列化的 DTO
# 都在 app.fjj.stun.repo / .remote 里，字段名靠这里保住。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class app.fjj.stun.repo.Profile { *; }
-keep class app.fjj.stun.repo.Profile$* { *; }
-keep class app.fjj.stun.remote.** { *; }
-keep class app.fjj.stun.repo.** { *; }

# ─── 5. Security, Tink & Keystore ───
-keep class com.google.crypto.tink.** { *; }
# 上面这条是全工程最贵的一条 keep：mapping.txt 里 com.google.crypto(tink) 1903 条，
# 其中 1878 条原名保留、0 条被删。注意这组数字**不能**证明本规则有没有在干活 ——
# 「0 条被删」只能说明这些类都在 dex 里，区分不了「靠规则留着」和「本来就可达」。
# 唯一能回答它的是删掉规则重建一次。
#
# 已核实的两点倾向性证据（倾向「可以收窄」，但不足以直接删）：
#   - 非 shaded 部分只有 2 个类用到反射：TinkFipsUtil、ConscryptUtil，各 3 处，
#     全是 FIPS / Conscrypt 能力探测，探测失败走降级分支。
#   - TinkConfig.registerStandard() 直接调用各 provider 的 register() 静态方法，
#     所以 provider 是直调可达，规则至多是在阻止改名、不是在阻止移除。
#   - shaded protobuf（com.google.crypto.tink.\shaded\protobuf）用反射，但它自带
#     protobuf.pro，AGP 会提（同 gson 那条证据）。
# 结论：值得排一次「删掉这条重建 + 跑加密相关用例」的 A/B，别在没数据时动它。
# 加密路径崩一次是不可接受的故障模式，这里的保守不是疏忽。
# 这里原先还有 -keep class androidx.security.crypto.** { *; }，已删：
# dependencyInsight 显示 releaseRuntimeClasspath 里根本没有 androidx.security
# 依赖，代码里也零 import。和 hev.htproxy 一样是死规则，留着只会让人误以为
# EncryptedSharedPreferences 那条路径被保护着。
-dontwarn com.google.crypto.tink.**

# ─── 6. Coroutines ───
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

