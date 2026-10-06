package app.fjj.stun.util

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.repo.AppDatabase
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 回归测试：TV「右上角计数=1、列表一个卡片都没有」。
 *
 * 数据链（insert → Room LiveData → decrypt → 发射 size=1）由 TvProfileListProbeTest 钉死；
 * 这里钉死**渲染侧**：TV 主界面的真实接线是 `GridSpans.bind(rv)` + `adapter.submitList()`。
 * 历史 bug：GridSpans 曾在布局回调里**同步替换 LayoutManager 实例**，而布局回调触发在
 * `View.setFrame` 里（onMeasure 的 auto-measure 已填完子 View、onLayout 还没跑），
 * 替换会把子 View 全部回收、紧跟着的 dispatchLayout 不再重填 —— adapter 有数据、
 * 渲染 0 个子 View、后续遍历不自愈。TV 上 Room 的初始发射恰好落在首次遍历之前
 * （计数更新了），首帧布局时触发替换，于是「计数=1 但列表空」。
 * 修复后 GridSpans 统一用 GridLayoutManager、只动 spanCount，本文件两条时序都必须渲染出子 View。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvListRenderProbeTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    /**
     * Robolectric 同一 JVM 里 Room 的 `stun_database` 在各测试方法间**不重置**，
     * 而这两个探针都断言"库里恰好 1 条"—— 进出各清一次表，避免测试顺序不同结果就不同。
     */
    private fun clearProfiles() {
        val worker = Thread {
            AppDatabase.getDatabase(app).profileDao().deleteAll()
        }
        worker.start()
        worker.join(15_000)
    }

    @org.junit.Before
    fun resetDb() = clearProfiles()

    @org.junit.After
    fun cleanDb() = clearProfiles()

    /** 极简版 ProfileAdapterTV：同样的 ListAdapter + DiffUtil 结构。 */
    private class ProbeAdapter :
        ListAdapter<Profile, ProbeAdapter.VH>(object : DiffUtil.ItemCallback<Profile>() {
            override fun areItemsTheSame(a: Profile, b: Profile) = a.id == b.id
            override fun areContentsTheSame(a: Profile, b: Profile) = a == b
        }) {
        class VH(v: View) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context)
            tv.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            (holder.itemView as TextView).text = getItem(position).name
        }
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun relayout(rv: RecyclerView) {
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY),
        )
        rv.layout(0, 0, 1600, 1400)
    }

    @Test
    fun gridSpansSwapDoesNotSwallowFirstItem() {
        // Keystore 必须在读写凭据前就位（各端 Application.onCreate 都做了这件事）
        runCatching { KeystoreUtils.init(app) }
            .onFailure { println("PROBE keystore init failed: ${it.message}") }

        // 1) 真实 Room + 加密链路：插 1 条
        val db = AppDatabase.getDatabase(app)
        Thread {
            val p = Profile().apply {
                id = "probe-render-1"
                name = "Probe Node"
                sshAddr = "1.2.3.4:22"
            }
            db.profileDao().insert(ProfileManager.encryptProfile(p))
        }.apply { start() }.join(15_000)

        // 2) TV 主界面同款接线：宽度 0 时先装单列，attach adapter
        val rv = RecyclerView(app)
        val adapter = ProbeAdapter()
        GridSpans.bind(rv)
        rv.adapter = adapter

        val parent = FrameLayout(app) // 1600x1400，TV 横屏右栏的量级
        parent.addView(rv, ViewGroup.LayoutParams(1600, 1400))

        // 3) 数据还没到（真实场景首帧就是空列表）：先完整走一遍布局 —— 此时宽度从 0 变成 1600，
        //    GridSpans 会在布局回调里把列数从 1 重算成 2
        relayout(rv)
        println("PROBE render first layout children=${rv.childCount} lm=${rv.layoutManager?.javaClass?.simpleName}")

        // 4) LiveData 发射 → submitList（与 MainActivity.observeData 的观察者一致）
        val latch = CountDownLatch(1)
        val obs = Observer<List<Profile>> {
            adapter.submitList(it)
            println("PROBE render emitted size=${it.size}")
            latch.countDown()
        }
        ProfileManager.getProfilesLiveData(app).observeForever(obs)
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && !latch.await(50, TimeUnit.MILLISECONDS)) idle()
            idle() // 让 AsyncListDiffer 的 main-thread commit + RecyclerView 二次布局跑完
            relayout(rv)
            val lm = rv.layoutManager
            println("PROBE render final children=${rv.childCount} lm=${lm?.javaClass?.simpleName} adapterCount=${adapter.itemCount}")
            assertTrue("adapter 应持有 1 条", adapter.itemCount == 1)
            assertEquals("RecyclerView 应渲染出 1 个子 View（TV 列表空即此症状）", 1, rv.childCount)
        } finally {
            // observeForever 挂在每次新建的 MediatorLiveData 上，无法按实例摘除；测试进程退出即回收
        }
    }

    /**
     * 另一条真实时序：Room 的初始查询在首次遍历**之前**就完成 —— 首次布局时 adapter 已有数据，
     * 布局回调里的列数重算发生在**带着子 View** 的遍历中。
     * 修复前：GridSpans 在这个窗口里同步换 LayoutManager 实例，RecyclerView 的布局状态机被打断
     * （子 View 被回收后不再重填）—— adapter 有数据、渲染 0 个子 View、后续遍历也不自愈，
     * 即 TV「右上角计数=1 但列表空」。
     */
    @Test
    fun swapDuringLayoutWithItemStillRenders() {
        runCatching { KeystoreUtils.init(app) }
            .onFailure { println("PROBE keystore init failed: ${it.message}") }

        val db = AppDatabase.getDatabase(app)
        Thread {
            val p = Profile().apply {
                id = "probe-render-2"
                name = "Probe Node 2"
                sshAddr = "5.6.7.8:22"
            }
            db.profileDao().insert(ProfileManager.encryptProfile(p))
        }.apply { start() }.join(15_000)

        val rv = RecyclerView(app)
        val adapter = ProbeAdapter()
        GridSpans.bind(rv)
        rv.adapter = adapter

        val latch = CountDownLatch(1)
        val obs = Observer<List<Profile>> {
            adapter.submitList(it)
            latch.countDown()
        }
        ProfileManager.getProfilesLiveData(app).observeForever(obs)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !latch.await(50, TimeUnit.MILLISECONDS)) idle()
        idle()

        // 首次布局：数据已在 adapter 里，宽度 0 → 1600，布局回调里重算列数。
        // 修复前这里同步换 LayoutManager 实例，会把布局状态机打断：children=0 永不自愈。
        relayout(rv)
        println("PROBE swap-with-item after 1st layout: children=${rv.childCount} lm=${rv.layoutManager?.javaClass?.simpleName} adapterCount=${adapter.itemCount}")

        // 再走一遍遍历（真实设备上 requestLayout 会让下一帧重排）
        relayout(rv)
        println("PROBE swap-with-item children=${rv.childCount} lm=${rv.layoutManager?.javaClass?.simpleName} adapterCount=${adapter.itemCount}")
        assertEquals("带着数据的首布局重算列数后必须渲染出子 View", 1, rv.childCount)
    }
}
