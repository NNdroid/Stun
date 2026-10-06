package app.fjj.stun.remote

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守护 [app.fjj.stun.remote.RemoteSyncManager.lanBaseUrl] 的明文前置条件。
 *
 * 这个开关删掉后不会出现编译错误、不会崩、也不会有一条日志点出「网络策略」：三个客户端方法
 * 只是各自拿到 null / ERROR / false，界面上就是「设备能搜到、蓝牙能用、但状态全显示无法连接」——
 * 和「电视端服务没起」长得一模一样，排查代价远高于一条 manifest 属性。所以把因果关系钉在这里。
 */
class LanCleartextPolicyTest {

    private fun read(relative: String): String {
        val hit = listOf(File(relative), File("../$relative")).firstOrNull { it.exists() }
        assertNotNull("找不到 $relative（工作目录 ${File(".").absolutePath}）", hit)
        return hit!!.readText()
    }

    @Test
    fun `手机端 manifest 必须放行明文 http，否则局域网同步在握手前就被拦掉`() {
        val manifest = read("src/main/AndroidManifest.xml")
        assertTrue(
            "targetSdk 37 默认拒绝明文流量；没有这个开关，RemoteSyncManager.lanBaseUrl() 拼出的 " +
                "http://<局域网地址>:<端口>/api/status 永远不会有回应。发现（mDNS）不受此约束、" +
                "蓝牙（RFCOMM）不是 HTTP，所以两端都正常，唯独状态拉不下来 —— 该删前请先把" +
                "「设备能搜到但连不上」复现一遍。",
            manifest.contains("android:usesCleartextTraffic=\"true\"")
        )
    }

    @Test
    fun `放行明文是必需的，不是可以顺手清掉的多余配置`() {
        val remoteSync = read("core/src/main/java/app/fjj/stun/remote/RemoteSyncManager.kt")
        assertTrue(
            "lanBaseUrl 仍是明文 http（局域网里做不了 TLS 证书协商），所以上面的开关删不得",
            remoteSync.contains("return \"http://\$authority:\$port\"")
        )
    }

    @Test
    fun `开关旁边写清了原因，否则会被当成无用配置删掉`() {
        val manifest = read("src/main/AndroidManifest.xml")
        // 取属性正上方那条注释块。
        val block = manifest.substringBefore("android:usesCleartextTraffic").substringAfterLast("<!--")
        assertTrue(
            "ExitIpProbe 里恰好有相反的做法（探测源全站 HTTPS），不写明理由迟早被「对齐」掉；" +
                "Android 的网络安全配置按 FQDN 收敛，没法只放行局域网段，这条限制也得说清楚。" +
                "实际注释块：$block",
            block.contains("ExitIpProbe") && block.contains("targetSdk")
        )
    }
}
