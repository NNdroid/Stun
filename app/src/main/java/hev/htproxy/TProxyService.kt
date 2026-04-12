package hev.htproxy

import app.fjj.stun.repo.StunLogger

/**
 * 这个类必须放在 hev.sockstun 包下，
 * 因为 libhev-socks5-tunnel.so 内部硬编码了对该路径的引用。
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
    // 启动服务：传入配置文件路径和虚拟网卡的文件描述符(FD)
    @JvmStatic
    external fun TProxyStartService(config_path: String?, fd: Int)

    // 停止服务
    @JvmStatic
    external fun TProxyStopService()

    // 获取统计数据（可选）
    @JvmStatic
    @Suppress("unused")
    external fun TProxyGetStats(): LongArray?
}