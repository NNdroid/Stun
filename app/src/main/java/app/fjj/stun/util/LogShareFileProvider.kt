package app.fjj.stun.util

import androidx.core.content.FileProvider

/**
 * 日志分享专用的 [FileProvider] 子类。
 *
 * 单独建类而不是直接声明 `androidx.core.content.FileProvider`，是因为 debugoverlay 依赖库里
 * 已经声明了一个同名 provider，manifest 合并会把两个"同名类"当成同一个节点而在
 * authorities / meta-data 上冲突。换个类名，两者就各是各的。
 *
 * `getUriForFile` 按 authority 查表，不认类名，所以调用侧仍用父类静态方法即可。
 */
class LogShareFileProvider : FileProvider()
