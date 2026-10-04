package app.fjj.stun.remote

import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileArgReader
import app.fjj.stun.repo.ProfileFields
import app.fjj.stun.repo.ProfileSecrets
import com.google.gson.JsonObject
import java.util.UUID

/**
 * MCP `create_profile` / `update_profile` 的**入口适配层**。
 *
 * ## 这里为什么只剩壳
 * 字段规则（trim / clamp / 枚举校验 / 凭据例外 / 派生字段）全部在
 * [ProfileFields] —— 它同时被 MCP 与 WebUI 共用，是**唯一事实来源**。
 * 本文件只负责两件事：
 *  1. 把 Gson [JsonObject] 包成 [ProfileArgReader]（载荷载体适配）；
 *  2. 掩码凭据剔除（[ProfileSecrets.dropMaskedSecrets]，见下）。
 *
 * 收口前这里有 235 行、78 条 `args.has(...)` 平铺，与 WebUI 那份 77 行 `copy(...)`
 * 各写一遍同样的规则。两份平铺已经因此出过两次静默丢参事故
 * （2026-09-15 漏 8 个字段、2026-10 漏 24 个字段），且各自漂移出行为差异
 * （KCP 分片数的 clamp、`kcpMode` 的枚举校验、`name`/`sshAddr` 的空串处理）。
 * 2026-10 已统一到 WebUI 语义（它是历史最久、验证最充分的实现）。
 *
 * ## 掩码凭据必须在读取之前剔除
 * 客户端可能把 `get_profile_detail` 读到的 `*****` 原样写回，不剔除就会把真实凭据
 * 整条覆盖成五个星号 —— **界面正常、节点连不上、用户完全看不出原因**。
 * 放在这里而不是让调用方记得，是因为"漏一次"就是一次静默的凭据损坏。
 *
 * 剔除后该键相当于不存在，于是 [ProfileFields.applyTo] 自然退化成"保持原值"；
 * 用户真想清空时提交的是**空串**（不是掩码），仍会照常写库。
 *
 * @see ProfileFields 字段规则的唯一事实来源
 * @see ProfileArgReader 载荷载体抽象（JsonObject / Map 双实现）
 */
internal object McpProfileArgs {

    /**
     * 由 tool 入参构造一个新的 [Profile]。
     *
     * 缺失键取 [Profile] 的声明默认值（见 [ProfileFields.createFrom]）。
     *
     * @param id 默认随机 UUID；仅测试需要固定值时才显式传入。
     */
    fun fromArgs(args: JsonObject, id: String = UUID.randomUUID().toString()): Profile {
        ProfileSecrets.dropMaskedSecrets(args)
        return ProfileFields.createFrom(ProfileArgReader.of(args), id)
    }

    /**
     * 把 tool 入参**增量**合并进已有的 [Profile]（只有 [args] 里出现的键才改）。
     *
     * [existing] 是**就地修改**，落库由调用方负责。
     */
    fun mergeInto(args: JsonObject, existing: Profile) {
        ProfileSecrets.dropMaskedSecrets(args)
        ProfileFields.applyTo(ProfileArgReader.of(args), existing)
    }
}