# ─── 1. Ktor / Netty / CIO / SLF4J ───
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn sun.misc.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,kotlin.Metadata

-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-keep class * implements io.ktor.server.engine.ApplicationEngineFactory { *; }
-keep class * implements io.ktor.server.application.Plugin { *; }
-keep class io.ktor.server.cio.** { *; }

# Ktor 的 module 载体类。Ktor 对 Function1 形态的 module 走 clazz.declaredConstructors.single()
# 反射实例化；-allowaccessmodification 会让 R8 把编译器生成的 lambda 壳子合并进一个带多个构造器的
# 共享 hub 类（release 直接崩，见 WebServer.WebConsoleModule 的注释）。具名 class 类身份确定，
# 单独锁定，不依赖 -keep class app.fjj.stun.remote.** 这条通配符规则。
-keep class app.fjj.stun.remote.WebServer$WebConsoleModule { *; }

# ─── 2. JNI & Native Library Bindings ───
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class myssh.** { *; }
-keep class hev.htproxy.** { *; }

# ─── 3. Room Database & DAOs ───
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-dontwarn androidx.room.paging.**

# ─── 4. JSON Serialization / Gson / DTOs ───
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.Expose <fields>;
}
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
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**

# ─── 6. Coroutines ───
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

