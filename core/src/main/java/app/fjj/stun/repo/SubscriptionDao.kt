package app.fjj.stun.repo

import androidx.room.*

/**
 * `subscriptions` 表的 DAO。
 *
 * 全部为**阻塞式**方法（与 [ProfileDao] 一致）——调用方自行决定在哪个线程执行；
 * `SubscriptionManager` 的公开函数一律在主线程调用点之外使用（同步在 IO 协程里跑）。
 */
@Dao
interface SubscriptionDao {

    /**
     * 全部订阅，按 [Subscription.sortIndex] 升序。
     *
     * 二级排序键用 `subId`（不是 url）：url 可变，拿它当 tie-breaker 会让"改 URL 后
     * 面板顺序跳变"，而这正是本次重构要消灭的漂移。
     */
    @Query("SELECT * FROM subscriptions ORDER BY sortIndex ASC, subId ASC")
    fun getAll(): List<Subscription>

    @Query("SELECT * FROM subscriptions WHERE subId = :subId")
    fun getById(subId: String): Subscription?

    /**
     * 按 URL 反查（可能有多行同 URL —— 表上并没有 url 唯一约束）。
     *
     * 唯一的用途是**认领**：调用方拿着一个只有 URL、没有 [Subscription.subId] 的旧格式条目
     * （WebUI 旧前端 / 旧备份 / 临时构造的条目）来时，靠它找回原本那一行，
     * 从而把用量、同步计时、节点归属一起接回去。
     */
    @Query("SELECT * FROM subscriptions WHERE url = :url ORDER BY sortIndex ASC, subId ASC LIMIT 1")
    fun getByUrl(url: String): Subscription?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(sub: Subscription)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(subs: List<Subscription>)

    @Query("DELETE FROM subscriptions")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM subscriptions")
    fun count(): Int

    /** `count < 0` 表示"旧数据未记录"，保持原值不动（与 `setLastSyncForUrl(count = -1)` 同义）。 */
    @Query(
        "UPDATE subscriptions SET lastSyncTime = :time, " +
            "syncCount = CASE WHEN :count >= 0 THEN :count ELSE syncCount END " +
            "WHERE subId = :subId"
    )
    fun updateSyncMeta(subId: String, time: Long, count: Int)

    /** 同步成功后回写响应头元信息（只动这三列，不连用量/计时/提醒标记一起重写）。 */
    @Query("UPDATE subscriptions SET name = :name, homePage = :homePage, updateIntervalHours = :hours WHERE subId = :subId")
    fun updateHeaderMeta(subId: String, name: String, homePage: String, hours: Int)

    @Query("UPDATE subscriptions SET usageJson = :json WHERE subId = :subId")
    fun updateUsageJson(subId: String, json: String)

    @Query("UPDATE subscriptions SET notifiedOverquota = :over, notifiedExpiring = :expiring WHERE subId = :subId")
    fun updateNotifiedFlags(subId: String, over: Boolean, expiring: Boolean)

    /**
     * 删除不在 [keepIds] 里的订阅（`saveSubscriptions` 的整体覆盖语义）。
     *
     * ⚠️ 空集合不能直接进 `NOT IN ()`（生成的 SQL 是 `NOT IN ()`，SQLite 会报语法错），
     * 所以调用方必须**显式分叉**：空集合时用 [deleteAll]。
     */
    @Query("DELETE FROM subscriptions WHERE subId NOT IN (:keepIds)")
    fun deleteNotIn(keepIds: List<String>)
}
