package app.fjj.stun.service

/**
 * 「分应用代理」最终生效的那份配置（已解析成包名列表）。
 *
 * @param mode [AppFilterResolver.MODE_BLOCK] = 这些应用不走代理；[AppFilterResolver.MODE_ALLOW] = 只有这些应用走代理
 * @param packages 用户选中的包名，已去空白、已丢弃空项
 */
internal data class AppFilter(
    val mode: Int,
    val packages: List<String>,
) {
    val isAllowList: Boolean get() = mode == AppFilterResolver.MODE_ALLOW

    /** 不做任何分应用过滤（等价于 VPN 模式下不调 `addAllowed/DisallowedApplication`）。 */
    val isEmpty: Boolean get() = packages.isEmpty()

    companion object {
        val EMPTY = AppFilter(AppFilterResolver.MODE_BLOCK, emptyList())
    }
}

/**
 * 「分应用代理」取值的**唯一**收口。
 *
 * 背景：这份配置有「profile 覆盖全局」一层语义（`Profile.appFilterOverride` 为真时用节点自带的
 * 列表与模式，否则用设置页的全局值），而取值逻辑原先**内联在 `MyVpnService.applyAppFiltering`
 * 里**，tproxy 模式压根没读 —— 于是「分应用代理」在 tproxy 下静默失效（配了等于没配，且无提示）。
 * 现在两种模式都从这里取，且这里刻意做成**纯函数**（不碰 Context / SP / Room），
 * 这样 override 语义可以被纯 JVM 单测钉死，而不是靠两份复制粘贴保持一致。
 */
internal object AppFilterResolver {

    /** 这些应用不走代理（VPN 的 `addDisallowedApplication` / tproxy 的 bypass 列表）。 */
    const val MODE_BLOCK = 0

    /** 只有这些应用走代理（VPN 的 `addAllowedApplication` / tproxy 的 proxy 列表）。 */
    const val MODE_ALLOW = 1

    /**
     * 解析生效配置。`overrideEnabled` 对应 `Profile.appFilterOverride`。
     *
     * 语义与旧的内联实现逐字一致，包括两点容易被"顺手修好"反而改变行为的地方：
     *  - **空列表 = 不过滤**（不是"什么都不代理"）。VPN 侧靠 `if (isNotBlank())` 短路实现，
     *    tproxy 侧必须同样处理，否则全局列表为空时会退化成"白名单模式下一个应用都不代理"。
     *  - **非 1 的 mode 一律当黑名单**（旧代码是 `if (mode == 1) 允许 else 禁止`）。
     */
    fun resolve(
        overrideEnabled: Boolean,
        profileFilterApps: String,
        profileFilterMode: Int,
        globalFilterApps: String,
        globalFilterMode: Int,
    ): AppFilter {
        val rawApps = if (overrideEnabled) profileFilterApps else globalFilterApps
        val rawMode = if (overrideEnabled) profileFilterMode else globalFilterMode
        val packages = parsePackageList(rawApps)
        val mode = if (rawMode == MODE_ALLOW) MODE_ALLOW else MODE_BLOCK
        return if (packages.isEmpty()) AppFilter.EMPTY else AppFilter(mode, packages)
    }

    /**
     * 包名列表是逗号分隔的字符串（`SettingsManager.KEY_FILTER_APPS` / `Profile.filterApps` 都是）。
     * 单条 `user:package` 形式原样透传给 `tproxy.sh`（它的 `find_packages_uid` 认这个格式），
     * 这里不去拆分、不校验包名是否存在 —— 与 VPN 侧 `addAllowedApplication` 的"抛异常就跳过"同构。
     */
    fun parsePackageList(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
