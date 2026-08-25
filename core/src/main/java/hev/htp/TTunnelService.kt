package hev.htp

import androidx.annotation.Keep

@Keep
object TTunnelService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    external fun TTunnelStartService(config_path: String?, fd: Int)

    @JvmStatic
    external fun TTunnelStopService()

    @JvmStatic
    external fun TTunnelGetStats(): LongArray?
}
