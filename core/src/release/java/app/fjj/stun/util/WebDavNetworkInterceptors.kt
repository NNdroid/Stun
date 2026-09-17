package app.fjj.stun.util

import okhttp3.Interceptor

/** release 变体：不注册任何网络拦截器（debugoverlay 不在 release 依赖里）。 */
internal fun webDavNetworkInterceptors(): List<Interceptor> = emptyList()
