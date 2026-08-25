# Ktor & Netty/CIO Management APIs not on Android
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# Ktor Server & Client & Engine Reflection Keep Rules (Fixes KTOR-7298 / "Array has more than one element")
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-keep class * implements io.ktor.server.engine.ApplicationEngineFactory { *; }
-keep class * implements io.ktor.server.application.Plugin { *; }

# Keep DTO models and Core classes
-keep class app.fjj.stun.remote.** { *; }
-keep class app.fjj.stun.repo.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keep class myssh.** { *; }
-keep class hev.htproxy.* { *; }
