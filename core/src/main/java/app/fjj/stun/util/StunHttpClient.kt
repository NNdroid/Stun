package app.fjj.stun.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 应用内全局唯一 OkHttpClient —— WebDAV、订阅拉取等所有 HTTP 需要共用同一个实例。
 *
 * 配置刻意与历史 WebDavClient 保持一致（10s 连接 / 20s 读 / 跟随 3xx / 失败重试），
 * 并复用 WebDAV 的 debug 网络拦截器（release 变体为空列表，零开销），
 * 这样把 WebDavClient 也改指本实例后，行为与改动前完全一致，真正做到「应用内全局同一个」。
 *
 * UA 可由调用方经 [userAgent] 指定（订阅拉取用它；WebDAV 仍用自身 UA 常量）。
 */
object StunHttpClient {

    /** 可指定的 User-Agent；订阅拉取默认用它。 */
    @Volatile
    var userAgent: String = "Stun-Android/3.0.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .apply { webDavNetworkInterceptors().forEach { addNetworkInterceptor(it) } }
            .build()
    }
}
