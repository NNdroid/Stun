package app.fjj.stun.repo

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Profile 凭据字段的掩码规则 —— MCP / 只读接口对外输出节点配置时的单一事实来源。
 *
 * ## 为什么需要
 * `Profile` 里 `pass` / `privateKey` / `keyPass` / `proxyAuthToken` / `proxyAuthPass` 与
 * 各隧道的 PSK 都是**明文存储的真实凭据**。MCP 的 `get_profile_detail` 工具与
 * `stun://profiles` 资源此前直接 `gson.toJson(profile)`，把整条凭据送进 AI 客户端的
 * 会话上下文、日志与聊天记录 —— 而这些出口的调用方只需要知道"这个字段配没配、
 * 是不是那个值"，不需要真值。真正需要真值的路径只有一条，且不经过这里：
 *
 * - `export_profiles`（服务端加密 + PIN 保护后再给出去）
 *
 * WebUI 节点编辑页**也走**本掩码：GET 拿到 `*****` 后前端把哨兵挪进 `input.dataset.secret`
 * 显示成「空框 + hint」，提交前再还原回框里原样发回（`/api/profiles/update` 由
 * [dropMaskedSecrets] 兜住）。真值始终不出本机，走的正是本文档这套双向契约。
 *
 * ## 与写入侧的契约（双向哨兵）
 * [MASK] 同时是"读出掩码"和"写回时表示保持原值"的哨兵。**写入侧必须按此约定忽略它**，
 * 否则 AI 客户端最常见的 `get_profile_detail` → 改一个字段 → `update_profile` 工作流
 * 会把真实密码覆盖成五个星号：节点从此连不上，而且用户看不出原因。
 * 两条写入路径（`update_profile` / `create_profile`）都调用 `dropMaskedSecrets` 兜住。
 */
object ProfileSecrets {

    /** 掩码字面量。读出即此值；写回表示"不修改"。 */
    const val MASK = "*****"

    /**
     * 需要掩码的字段名（Gson `@SerializedName` 名，与数据库列名一致）。
     *
     * 只收**凭据**。以下刻意**不**掩码，因为它们是校验/协商材料而非机密，
     * 且调用方常要拿它们做比对或展示：
     * - 公钥：`noisePublicKey` / `udpCustomPublicKey` / `dnsTunnelPublicKey` / `icmpCustomPublicKey`
     * - 指纹：`serverFingerprint` / `serverCertFingerprint`
     * - 用户名：`user` / `proxyAuthUser`（单独存在无法通过认证）
     */
    val SECRET_FIELDS: List<String> = listOf(
        "pass",
        "privateKey",
        "keyPass",
        "proxyAuthToken",
        "proxyAuthPass",
        "icmpCustomPsk",
        "udpCustomPsk",
        "dnsTunnelPsk",
        "kcpPassword"
    )

    /**
     * 就地掩码 [json] 中的凭据字段。
     *
     * 语义细节：
     * - **空串保持空串**。调用方据此区分"未设置"与"已设置但不展示"，
     *   若把空串也换成 `*****`，就再也分不出"没填密码"和"填了但被挡住"。
     * - 非字符串取值原样保留，避免把类型改错（这些字段在实体里都是 String，
     *   出现其他类型说明上游已损坏，掩盖它只会让问题更晚暴露）。
     */
    fun maskInPlace(json: JsonObject) {
        for (name in SECRET_FIELDS) {
            val element = json.get(name) ?: continue
            if (!element.isJsonPrimitive) continue
            val primitive = element.asJsonPrimitive
            if (!primitive.isString) continue
            if (primitive.asString.isEmpty()) continue
            json.addProperty(name, MASK)
        }
    }

    /** 该入参是否应当被理解为"用户没有修改这个字段"。 */
    fun isMask(value: String?): Boolean = value == MASK

    /**
     * 从请求参数里剔除值为 [MASK] 的凭据字段，使下游的 `args.has(name)` 判断自然跳过它们。
     *
     * 这是 [MASK] 双向契约的写入侧落点：掩码值代表"保持原值"，绝不能落库。
     */
    fun dropMaskedSecrets(args: JsonObject) {
        for (name in SECRET_FIELDS) {
            val element = args.get(name) ?: continue
            if (!element.isJsonPrimitive) continue
            val primitive = element.asJsonPrimitive
            if (!primitive.isString) continue
            if (isMask(primitive.asString)) args.remove(name)
        }
    }

    /**
     * [MutableMap] 形态的请求参数（ktor `call.receive<Map<String, Any?>>()`）里剔除掩码值。
     *
     * 这里把值置成 `null` 而不是删 key，是为了贴合 WebServer 现有的赋值写法：
     * 全部字段都写成 `(body["pass"] as? String) ?: existing.pass` —— 取到 null 就回落到
     * 库里已有的值，正好是"保持原值"。用户真想清空时提交的是**空串**（不是掩码），
     * 空串不是 null，仍会照常写库，所以"清空"这条路没有被堵上。
     */
    fun dropMaskedSecrets(args: MutableMap<String, Any?>) {
        for (name in SECRET_FIELDS) {
            val value = args[name]
            if (value is String && isMask(value)) args[name] = null
        }
    }

    private val gsonLazy: Gson by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GsonBuilder()
            .registerTypeAdapter(Profile::class.java, RedactingSerializer)
            .create()
    }

    /**
     * 只读出口专用 Gson：序列化 [Profile]（含 `List<Profile>`）时把凭据换成 [MASK]。
     *
     * 对外输出节点配置一律用它，不要自己 `Gson()` —— 后者会把明文密码/私钥写出去，
     * 而这类遗漏既不报错也过不了评审，只会在客户端日志里静默留下凭据。
     */
    fun redactingGson(): Gson = gsonLazy

    /**
     * 给调用方自己的 [GsonBuilder] 挂上同一套脱敏 adapter。
     *
     * 用在"没法接管 Gson 实例"的地方 —— 典型是 ktor 的
     * `install(ContentNegotiation) { gson { … } }`：`call.respond(profile)` 的序列化走框架
     * 内部那个 Gson，不注册的话保存成功后会把刚写进去的明文凭据原样回吐给前端。
     */
    fun registerRedaction(builder: GsonBuilder): GsonBuilder =
        builder.registerTypeAdapter(Profile::class.java, RedactingSerializer)

    /**
     * 刻意做成 TypeAdapter 而非在调用点逐个手工脱敏：出口是"默认安全"的，
     * 以后新增只读接口不会因为忘记调用脱敏函数而漏出凭据。
     */
    private object RedactingSerializer : JsonSerializer<Profile> {

        /** 独立实例：不能复用注册了本 adapter 的那个 Gson，否则 toJsonTree 会无限递归。 */
        private val delegate = Gson()

        override fun serialize(src: Profile, type: Type, ctx: JsonSerializationContext): JsonElement {
            val json = delegate.toJsonTree(src).asJsonObject
            maskInPlace(json)
            return json
        }
    }
}
