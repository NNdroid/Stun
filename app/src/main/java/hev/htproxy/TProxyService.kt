package hev.htproxy

import android.util.Log

/**
 * 这个类必须放在 hev.sockstun 包下，
 * 因为 libhev-socks5-tunnel.so 内部硬编码了对该路径的引用。
 */
object TProxyService {
    // 启动服务：传入配置文件路径和虚拟网卡的文件描述符(FD)
    external fun TProxyStartService(config_path: String?, fd: Int)

    // 停止服务
    external fun TProxyStopService()

    // 获取统计数据（可选）
    external fun TProxyGetStats(): LongArray?

    init {
        // 加载 C++ 库
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("HEV-JNI", "无法加载库: " + e.message)
        }
    }
}