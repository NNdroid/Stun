package app.fjj.stun.repo

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room schema ↔ 迁移 DDL 的**逐字对齐**回归（v25：订阅升格为独立表 + 关联键由 URL 换成 subId）。
 *
 * ## 为什么需要它
 * Room 只在**运行时**校验"迁移建出来的表"与"实体声明的表"是否一致，对不上就抛
 * `IllegalStateException: Migration didn't properly handle ...` —— 编译期、lint、单元测试
 * 全都不报错。而本项目开了 `fallbackToDestructiveMigration`，这条路一失败就是用户数据全灭。
 * 手抄的列清单（`PROFILES_V*_COLUMN_DEFS`）与实体各写一份，正是最容易漂移的地方。
 *
 * ## 判据怎么来的
 * KSP 在每次版本变更时把实体 schema 快照写到 `core/schemas/<pkg>.AppDatabase/<version>.json`
 * （见 `core/build.gradle.kts` 的 `room.schemaLocation`），里面的 `createSql` 就是
 * **Room 自己认可的建表语句**。于是可以逐字比对：
 *
 * - `AppDatabase.SUBSCRIPTIONS_TABLE_DDL` ↔ `25.json` 里 subscriptions 的 createSql
 * - 迁移里拼出来的 profiles 建表语句 ↔ `25.json` / `24.json` 里 profiles 的 createSql
 * - `PROFILES_V*_COLUMN_NAMES`（INSERT ... SELECT 用的列序）↔ schema 的列序
 * - 实体字段名 ↔ 建表列名
 *
 * 刻意不挂 Robolectric：这些都只是字符串/反射，不需要 Android 运行时。
 */
class SubscriptionSchemaPinTest {

    private val pkgDir = "schemas/${AppDatabase::class.java.`package`?.name}.AppDatabase"

    private fun schema(version: Int): File {
        val rel = "$pkgDir/$version.json"
        // 单测的工作目录通常是模块目录（core/），但不同 Gradle/IDE 下会变，所以多试两个候选。
        val candidates = listOf(File(rel), File("core/$rel"))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 Room schema 快照 $rel（cwd=${File("").absolutePath}）；" +
                "先跑一次 :core:compileDebugKotlin 让 KSP 导出它")
    }

    private fun entityJson(version: Int, table: String) = JsonParser
        .parseString(schema(version).readText())
        .asJsonObject.getAsJsonObject("database")
        .getAsJsonArray("entities")
        .map { it.asJsonObject }
        .firstOrNull { it.get("tableName").asString == table }
        ?: error("$version.json 里没有 $table 表")

    /** schema 里 Room 认可的建表语句，`${TABLE_NAME}` 换成真实表名。 */
    private fun createSql(version: Int, table: String): String =
        entityJson(version, table).get("createSql").asString.replace("\${TABLE_NAME}", table)

    /** schema 里的列名（保持 Room 自己写的顺序）。 */
    private fun schemaColumns(version: Int, table: String): List<String> =
        entityJson(version, table).getAsJsonArray("fields")
            .map { it.asJsonObject.get("columnName").asString }

    /** 从 `\`a\`, \`b\` …` 形式的反引号清单里抽列名。 */
    private fun parseBackticked(list: String): List<String> =
        Regex("`([A-Za-z0-9_]+)`").findAll(list).map { it.groupValues[1] }.toList()

    @Test
    fun `v25 subscriptions 建表语句与 Room schema 逐字一致`() {
        assertEquals(createSql(25, "subscriptions"), AppDatabase.SUBSCRIPTIONS_TABLE_DDL)
    }

    @Test
    fun `v25 profiles 建表语句与 Room schema 逐字一致`() {
        val built = "CREATE TABLE IF NOT EXISTS `profiles` " +
            "(${AppDatabase.PROFILES_V25_COLUMN_DEFS}, PRIMARY KEY(`id`))"
        assertEquals(createSql(25, "profiles"), built)
    }

    /**
     * 迁移里的 `INSERT INTO profiles_new_v25 (V25_NAMES) SELECT V23_NAMES, ''` 两条清单
     * 必须**逐位对应**同一个列序：只是列数对得上而顺序错了，数据会整列错位到别的字段上
     * （比"列数不符"坏得多 —— 那是静默的脏数据）。
     */
    @Test
    fun `v25 profiles 的列名清单与 schema 列序一致_且等于 v23 清单加 subId`() {
        val columns = schemaColumns(25, "profiles")
        assertEquals(columns, parseBackticked(AppDatabase.PROFILES_V25_COLUMN_NAMES))
        assertEquals(
            parseBackticked(AppDatabase.PROFILES_V23_COLUMN_NAMES) + "subId",
            parseBackticked(AppDatabase.PROFILES_V25_COLUMN_NAMES)
        )
    }

    @Test
    fun `v24 profiles 建表语句与 Room schema 逐字一致_确认升级到 v25 前的起点没写错`() {
        val built = "CREATE TABLE IF NOT EXISTS `profiles` " +
            "(${AppDatabase.PROFILES_V24_COLUMN_DEFS}, PRIMARY KEY(`id`))"
        assertEquals(createSql(24, "profiles"), built)
    }

    @Test
    fun `v24 profiles 的列名清单与 schema 列序一致`() {
        assertEquals(
            schemaColumns(24, "profiles"),
            parseBackticked(AppDatabase.PROFILES_V24_COLUMN_NAMES)
        )
    }

    /** 重构的核心：URL 关联键退役、subId 上位。列还在的话说明迁移删列没删干净。 */
    @Test
    fun `v25 profiles 已有 subId 且不再有 sourceSubscriptionUrl`() {
        val columns = schemaColumns(25, "profiles")
        assertTrue("v25 profiles 缺 subId", "subId" in columns)
        assertFalse("v25 profiles 仍有 sourceSubscriptionUrl", "sourceSubscriptionUrl" in columns)
    }

    /** 实体字段名 = 建表列名（Room 用字段名当列名，名字对不上就会生成另一张表）。 */
    @Test
    fun `Subscription 实体字段名与 subscriptions 建表列名完全一致`() {
        val fields = Subscription::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .sorted()
        assertEquals(schemaColumns(25, "subscriptions").sorted(), fields)
    }

    /** 反事实：Profile 实体确实已经不再有 sourceSubscriptionUrl 字段。 */
    @Test
    fun `Profile 实体字段里没有 sourceSubscriptionUrl`() {
        val fields = Profile::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }
        assertTrue("Profile 缺 subId", "subId" in fields)
        assertFalse("Profile 仍有 sourceSubscriptionUrl", "sourceSubscriptionUrl" in fields)
    }
}
