package app.fjj.stun.repo

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Observer
import app.fjj.stun.util.KeystoreUtils
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
 * 探针：TV 端「有节点但列表空」的定位。
 *
 * 走的是和 `tv/MainActivity.observeData()` 完全同一条链：
 * `ProfileManager.getProfilesLiveData(ctx)` → Room `getAll()` → `map { decryptProfile }`。
 * 用真 Room（不是 mock）才能暴露 `decrypt` 抛异常 / LiveData 不发射这类问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvProfileListProbeTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun liveDataEmitsInsertedProfile() {
        // Keystore 必须在读写凭据前就位（各端 Application.onCreate 都做了这件事）。
        runCatching { KeystoreUtils.init(app) }
            .onFailure { println("PROBE keystore init failed: ${it.message}") }

        // Room 禁止主线程访问，DB 操作一律走 IO（与产品代码的调用约束一致）。
        val worker = Thread {
            val dao = AppDatabase.getDatabase(app).profileDao()
            val p = Profile().apply {
                id = "probe-1"
                name = "Probe Node"
                sshAddr = "1.2.3.4:22"
                pass = "secret"
            }
            dao.insert(ProfileManager.encryptProfile(p))

            // 同步侧：WebUI /api/profiles 用的就是这条
            val sync = ProfileManager.getProfiles(app)
            println("PROBE sync size=${sync.size} names=${sync.map { it.name }}")
        }
        worker.start()
        worker.join(15_000)

        // LiveData 侧：TV / car / xr 三个列表用的就是这条
        val latch = CountDownLatch(1)
        var emitted: List<Profile>? = null
        val liveData = ProfileManager.getProfilesLiveData(app)
        val obs = Observer<List<Profile>> { value ->
            emitted = value
            latch.countDown()
        }
        liveData.observeForever(obs)
        try {
            // Room 的 LiveData 在**主线程 looper** 上计算初始值并派发 map 变换；Robolectric
            // 默认不自动跑 looper，必须手动 idle，否则会误判成"LiveData 从不发射"。
            val deadline = System.currentTimeMillis() + 10_000
            var got = false
            while (System.currentTimeMillis() < deadline && !got) {
                shadowOf(Looper.getMainLooper()).idle()
                got = latch.await(50, TimeUnit.MILLISECONDS)
            }
            println("PROBE liveData emitted=$got value=${emitted?.map { it.name }}")
            assertTrue("LiveData 10s 内没有发射任何值（TV 列表空即此症状）", got)
            assertEquals(1, emitted!!.size)
        } finally {
            liveData.removeObserver(obs)
        }
    }
}
