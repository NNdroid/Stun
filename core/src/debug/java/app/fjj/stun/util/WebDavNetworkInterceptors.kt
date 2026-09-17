package app.fjj.stun.util

import com.ms.square.debugoverlay.extension.okhttp.DebugOverlayNetworkInterceptor
import okhttp3.Interceptor

/**
 * debug 变体：WebDAV 的 OkHttpClient 注册 debugoverlay 网络拦截器，
 * 悬浮层里可视化查看每个 PROPFIND/MKCOL/PUT/GET/DELETE 请求与响应。
 * 默认已脱敏 Authorization 等常见认证头与查询参数。
 * release 变体由同名文件提供空实现。
 */
internal fun webDavNetworkInterceptors(): List<Interceptor> = listOf(
    DebugOverlayNetworkInterceptor()
)
