package app.fjj.stun.repo

import com.google.gson.JsonObject

/**
 * 「入参 → Profile 字段」的取值器，屏蔽**载荷载体**的差异。
 *
 * ## 为什么要这一层
 * 同一组 Profile 字段要被三个入口读取，而它们拿到的载荷类型不同：
 *
 * | 入口 | 载荷 | 缺失键的判断 |
 * |---|---|---|
 * | MCP `create_profile` / `update_profile` | Gson `JsonObject` | `args.has(k)` |
 * | WebUI `POST /api/profiles/update` | `MutableMap<String, Any?>` | `(body[k] as? T) ?: fallback` |
 * | WebUI `import_profiles`（批量新建） | `List<*>` 里的 `Map` | 同上 |
 *
 * 在此之前每个入口各写一份平铺（`fromArgs` 78 条 / `copy(...)` 77 条 / `mergeInto` 78 条），
 * 于是「加一个字段要改三处」，而漏改的表现是**静默丢参**——本仓已因此出过两次事故
 * （2026-09-15 的 8 个字段、2026-10 的 24 个字段）。
 *
 * 现在规则只有一份：[ProfileFields]。入口只负责把自己的载荷包成这个读取器。
 *
 * ## 为什么是接口而不是「一个类 + 五个 lambda 字段」
 * 第一版是 `class ProfileArgReader(has: (String)->Boolean, str: ..., bool: ...)`，
 * 结果 `fun bool(key: String) = bool(key)` **解析到了函数自身**（成员函数优先于
 * 同名函数类型的属性），一调用就 `StackOverflowError`；`has`/`int`/`long` 同样中招，
 * 只有 `string()` 因为内部字段叫 `str` 才侥幸没炸。
 *
 * 编译期完全静默，测试也只是报一句 `StackOverflowError` 指向 `ProfileArgReader.kt:47`，
 * 很难一眼看出是「同名遮蔽」而不是「逻辑写错」。
 *
 * 改成接口 + 两个私有实现后，取值器与字段名不再共处一个作用域，这类遮蔽无处可藏。
 *
 * ## 两种实现的语义必须一致（但**刻意不完全相同**）
 * - [of]（Gson）：类型不符时 `asString` 会抛 `IllegalStateException`。
 *   MCP 侧外面有 `try/catch` 兜着，与既有行为一致。
 * - [of]（Map）：类型不符时 `as?` 给 null，等同"键不存在"⇒ 退回 fallback。
 *   这是 WebUI 的既有行为（前端传错类型不会 500，只是该字段不生效）。
 *
 * 这点差异是**刻意保留**的，不是遗漏 —— 改任何一边都会让现有调用方的错误表现变化。
 */
interface ProfileArgReader {

    /** 该键是否存在（[ProfileFields.applyTo] 的"只改出现的键"语义依赖它）。 */
    fun has(key: String): Boolean

    /** 字符串读取；trim 与否由 [ProfileFields] 的规则决定，这里不做预处理。 */
    fun string(key: String): String?

    fun int(key: String): Int?
    fun long(key: String): Long?
    fun bool(key: String): Boolean?

    companion object {

        /** 包一份 Gson [JsonObject]（MCP 侧）。 */
        fun of(args: JsonObject): ProfileArgReader = GsonReader(args)

        /** 包一份 `Map<String, Any?>`（ktor `call.receive<Map<...>>()` 的结果）。 */
        fun of(body: Map<String, Any?>): ProfileArgReader = MapReader(body)
    }
}

/** MCP 侧的载体：`has` 只看键在不在，`JsonNull` 视为读取失败（返回 null）。 */
private class GsonReader(private val obj: JsonObject) : ProfileArgReader {

    override fun has(key: String): Boolean = obj.has(key)

    override fun string(key: String): String? = obj.get(key)?.takeUnless { it.isJsonNull }?.asString

    override fun int(key: String): Int? = obj.get(key)?.takeUnless { it.isJsonNull }?.asInt

    override fun long(key: String): Long? = obj.get(key)?.takeUnless { it.isJsonNull }?.asLong

    override fun bool(key: String): Boolean? = obj.get(key)?.takeUnless { it.isJsonNull }?.asBoolean
}

/**
 * WebUI 侧的载体。
 *
 * ⚠️ 两处与 [GsonReader] 的**刻意差异**，都不是遗漏：
 *  1. [has] 把 `null` 值也算"不存在" —— `ProfileSecrets.dropMaskedSecrets`
 *     会把 `*****` 哨兵**置 null**（而不是删键），若 `has` 仍返回 true，
 *     掩码值就会被当成用户输入写回库。
 *  2. 数字用 `as? Number`，能吃下前端发来的 JSON double（如 `512.0`）；
 *     Gson 侧则要求客户端发整数。这是既有的 WebUI 行为，收口时保留。
 */
private class MapReader(private val m: Map<String, Any?>) : ProfileArgReader {

    private fun num(key: String): Number? = m[key] as? Number

    override fun has(key: String): Boolean = m.containsKey(key) && m[key] != null

    override fun string(key: String): String? = m[key] as? String

    override fun int(key: String): Int? = num(key)?.toInt()

    override fun long(key: String): Long? = num(key)?.toLong()

    override fun bool(key: String): Boolean? = m[key] as? Boolean
}
