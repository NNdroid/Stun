package app.fjj.stun.repo

enum class VpnState {
    DISCONNECTED,  // 已断开
    CONNECTING,    // 连接中（初始连接）
    CONNECTED,     // 已连接
    RECONNECTING,  // 异常断开，正在自动重连中
    ERROR          // 发生致命错误退出
}