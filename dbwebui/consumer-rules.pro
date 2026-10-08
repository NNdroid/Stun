# :dbwebui consumer rules
#
# 本文件原先只有一行 `# :dbwebui consumer rules (empty)`，而 dbwebui 的代码是随 APK 一起
# 发布的 —— DbWebServer 跑的是和 :core 的 WebServer / StunMcpServer 完全同一套
# ktor-server-cio 栈，也走同一条脆弱的 module 反射路径。空文件等于把这个模块的混淆策略
# 隐式委托给「:core 的消费者规则会透传过来」，而这件事恰好没被任何人记下来。

# ─── Ktor module 载体 ───
# DbWebServer 只有一个 embeddedServer 调用点（DbWebServer.kt:91），module 是文件级函数
# dbWebModule()，调用点是一个普通 lambda。Ktor 对 Function1 形态的 module 会走
# clazz.declaredConstructors.single() 反射实例化；-allowaccessmodification 会让 R8 把编译器
# 生成的 lambda 壳子合并进一个带多个构造器的共享 hub 类，届时 single() 抛异常、
# DbWebServer.start() 直接崩 —— release 才会崩，单元测试与本地构建都发现不了。
#
# 这条规则锁住 DbWebServer 对象及其内部 lambda 壳子（编译器生成的类都是 DbWebServer 的
# 嵌套类，名字形如 DbWebServer$startServer$1$1），保证壳子既不改名也不被合并。
# :core 里对应的 app.fjj.stun.remote.** 通配符覆盖不到 app.fjj.stun.dbwebui.**，
# 所以这里必须单独有一条。
#
# 注意本文件的规则是**增量**的：:core 的 consumer rules（io.ktor.** 整包、gson、Tink 等）
# 作为 AAR 的 consumer proguard 会被 AGP 一并合并，dbwebui 不重复抄。
-keep class app.fjj.stun.dbwebui.DbWebServer { *; }
-keep class app.fjj.stun.dbwebui.DbWebServer$* { *; }

# dbWebModule 是文件级函数，编译器把它放进 dbWebModuleKt 这个 file facade 对象里。
# 调用点若写成方法引用，ktor 会拿 kotlin-reflect 去读它的 methodName；R8 在 release 会
# 剥掉 file facade 的 @Metadata，反射抛 "no members found"。当前调用点是普通 lambda
# （`{ dbWebModule() }`），不走那条路径 —— 这条规则是防止将来有人「顺手」改成方法引用时
# 没人知道这个约束。
-keep class app.fjj.stun.dbwebui.dbWebModuleKt { *; }

# ─── 已删除的过宽规则（有意为之，勿加回）───
# dbwebui 是 library module，自身不参与体积决策；能借 :core 的规则解决的都在这里借，
# 避免同一份 Ktor 规则在两个模块各维护一份、将来漂移。
