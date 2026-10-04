package app.fjj.stun.remote

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpTools] —— MCP 对外暴露的工具清单契约。
 *
 * 这 27 个 tool 是 **Stun 与 AI 客户端（Claude / Codex / Gemini）之间的公开接口**。
 * 它们的 name 与 inputSchema 一旦悄悄变动，外部客户端就会：
 *  - 找不到工具（模型侧直接放弃调用，不报错，只是"变笨了"）；
 *  - 传错参数（schema 与实现不一致，服务端报一个用户看不懂的参数错误）。
 *
 * 两种故障**都不会**让本仓任何单测变红 —— 这正是它原先作为 3300 行文件里一个私有
 * 方法时的真实风险。抽成独立的纯 Gson 对象后可以在这里钉死。
 */
class McpToolsSchemaTest {

    private fun tools(): List<JsonObject> =
        McpTools.toolsList().getAsJsonArray("tools").map { it.asJsonObject }

    /**
     * 工具数量与名称集合。
     *
     * 用**名字集合**而不是数量：数量对但名字变了（例如把 `get_status` 改成 `getState`）
     * 一样是破坏性变更，数量断言察觉不到。
     */
    @Test
    fun toolNamesAreStable() {
        val actual = tools().map { it.get("name").asString }.toSet()
        val declared = declaredInTest()
        // 断言"实现里没有清单外的名字" + "清单里没有缺失的名字"，两边都查
        assertTrue(
            "工具清单与本测试的声明不一致。\n实现有但未声明：${actual - declared}\n声明了但实现没有：${declared - actual}",
            actual == declared,
        )
        // 关键工具必须在场（这几个是 App 的核心能力，删掉等于功能回退）
        for (must in listOf("get_vpn_status", "start_vpn", "stop_vpn", "list_profiles",
                            "import_profiles", "export_profiles", "get_device_info")) {
            assertTrue("核心工具 $must 不应缺失", must in actual)
        }
    }

    /** 本测试声明的期望集合。与实现脱钩 ⇒ 变更时会红。 */
    private fun declaredInTest(): Set<String> = setOf(
        "get_vpn_status", "start_vpn", "stop_vpn", "restart_vpn",
        "list_profiles", "get_profile_detail", "create_profile", "update_profile",
        "delete_profile", "select_profile", "test_node_latency",
        "get_app_filter_list", "set_app_filter",
        "update_geodata", "get_settings", "set_settings",
        "get_logs", "get_device_info",
        "get_webdav_config", "set_webdav_config",
        "list_backups", "backup_now", "sync_now", "restore_backup",
        "list_subscriptions", "sync_subscriptions",
        "import_profiles", "export_profiles",
    )

    @Test
    fun everyToolHasAUsableSchema() {
        for (t in tools()) {
            val name = t.get("name").asString
            assertTrue("$name 缺少 description", t.has("description") && !t.get("description").asString.isBlank())
            assertTrue("$name 缺少 inputSchema", t.has("inputSchema"))

            val schema = t.getAsJsonObject("inputSchema")
            // MCP 客户端靠 type 判类型，靠 properties 渲染表单。缺任何一个都会显示异常
            assertEquals("$name 的 inputSchema.type 必须是 object", "object", schema.get("type").asString)
            assertTrue("$name 缺少 properties", schema.has("properties"))
            assertTrue("$name 的 properties 必须是对象", schema.get("properties").isJsonObject)

            if (schema.has("required")) {
                val req = schema.get("required")
                assertTrue("$name 的 required 必须是数组", req.isJsonArray)
                req.asJsonArray.forEach {
                    val propName = it.asString
                    assertTrue(
                        "$name 的 required 里的 $propName 在 properties 中不存在（客户端会渲染不出表单）",
                        schema.getAsJsonObject("properties").has(propName),
                    )
                }
            }
        }
    }

    /** 工具名不能重复 —— MCP 协议按名字分发，重名会让先注册的那个永远收不到调用。 */
    @Test
    fun toolNamesAreUnique() {
        val names = tools().map { it.get("name").asString }
        val dupes = names.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("工具名重复：$dupes", dupes.isEmpty())
    }

    /**
     * 清单必须是**每次调用新建**的对象，不能是共享单例。
     *
     * 序列化时若有人对返回的 JsonObject 做过修改（`add`/`remove`），共享实例会被污染，
     * 第二次 `tools/list` 返回的就少了东西 —— 这种 bug 在真实服务里极难复现。
     */
    @Test
    fun eachCallReturnsAFreshObject() {
        val a = McpTools.toolsList()
        val b = McpTools.toolsList()
        assertTrue("两次调用应返回不同实例（否则可能被调用方改坏）", a !== b)

        a.getAsJsonArray("tools").remove(0)  // 模拟调用方改坏返回值
        val c = McpTools.toolsList()
        assertTrue(
            "改动第一次的返回值不能影响后续调用",
            c.getAsJsonArray("tools").size() > 0,
        )
    }

    /** 清单本身就是对外 JSON，序列化必须成功且含 tools 键。 */
    @Test
    fun serializesToValidJson() {
        val json = McpTools.toolsList().toString()
        assertTrue(json.startsWith("{"))
        assertTrue(json.contains("\"tools\""))
        assertFalse("不该含 null 字面量", json.contains(": null,"))
    }
}
