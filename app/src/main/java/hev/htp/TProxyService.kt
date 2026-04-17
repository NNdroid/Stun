package hev.htp

import androidx.annotation.Keep

@Keep
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tproxy")
    }

    @JvmStatic
    external fun TProxyStartService(config_path: String?)

    @JvmStatic
    external fun TProxyStopService()
}
