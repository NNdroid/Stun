package app.fjj.stun.geo

import java.io.File
import org.junit.Assume.assumeTrue

/**
 * 定位本地的 mmdb 对拍数据。
 *
 * 44 MB 二进制与向量文件都不入库，所以找不到时**跳过**而不是失败 ——
 * 在没放这些文件的机器和 CI 上跑测试也不该红。
 *
 * 查找顺序：系统属性 `stun.mmdb.dir` → 环境变量 `STUN_MMDB_DIR` →
 * 从工作目录逐级向上找 `.workbuddy/tmp/`（本地开发时的默认位置）。
 */
internal object MmdbFixtures {

    const val V4_NAME = "city-ipv4.mmdb"
    const val V6_NAME = "city-ipv6.mmdb"
    const val VECTORS_NAME = "mmdb_vectors.json"

    val dir: File? by lazy {
        val candidates = LinkedHashSet<File>()
        System.getProperty("stun.mmdb.dir")?.let { candidates.add(File(it)) }
        System.getenv("STUN_MMDB_DIR")?.let { candidates.add(File(it)) }

        var current: File? = File("").absoluteFile
        repeat(6) {
            val directory = current ?: return@repeat
            candidates.add(File(directory, ".workbuddy/tmp"))
            current = directory.parentFile
        }

        candidates.firstOrNull { File(it, V4_NAME).isFile && File(it, V6_NAME).isFile }
    }

    /** 拿不到就跳过当前测试。 */
    fun requireDir(): File {
        val found = dir
        assumeTrue("未找到 mmdb 对拍数据，跳过（见 MmdbReaderTest 类注释）", found != null)
        return found!!
    }

    fun v4File(): File = File(requireDir(), V4_NAME)

    fun v6File(): File = File(requireDir(), V6_NAME)
}
