package app.fjj.stun.util

import app.fjj.stun.repo.StunLogger
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端（OkHttp）：
 * PUT 上传（目录缺失自动 MKCOL 补建并重试）/ GET / HEAD / PROPFIND 列表 /
 * MKCOL / DELETE / Basic Auth。
 *
 * 为什么用 OkHttp 而不是 HttpURLConnection（坚果云 409 排查结论）：
 * 1. 坚果云对不带尾斜杠的集合操作回 301 跳转到带斜杠 URL；
 *    HttpURLConnection 对非 GET 方法绝不自动跟随，OkHttp 保留方法+体地
 *    跟随全部 3xx——这正是「Via 能建目录、我们不能」的根因；
 * 2. Content-Type 只随真实请求体发送，不会给无体的 MKCOL 添乱；
 * 3. debug 构建经 webDavNetworkInterceptors()（src/debug 源集）接入
 *    debugoverlay 悬浮层做请求追踪，release 变体零开销。
 *
 * URL 处理保留历史教训：自动补 https://、逐段百分号编码（中文/空格目录）。
 * 日志：DEBUG 记录方法/URL/状态码/字节数；绝不输出 Authorization 或文件内容。
 */
object WebDavClient {

    private const val LOG_TAG = "WebDAV"
    private const val UA = "Stun-Android/3.0.0 (Android WebDAV client)"

    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:href/><d:resourcetype/></d:prop></d:propfind>"

    private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
    private val OCTET_MEDIA = "application/octet-stream".toMediaType()

    // 复用应用内全局唯一 OkHttpClient（配置与历史完全一致，见 StunHttpClient）。
    private val client: OkHttpClient get() = StunHttpClient.client

    class WebDavException(message: String, val statusCode: Int = -1) : IOException(message)

    /**
     * 规整基址：补全协议、百分号编码路径段、去掉尾部斜杠。
     * 返回可安全拼接 "/rel/path" 的基址。
     */
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return s
        val original = s
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "https://$s"
        }
        val schemeEnd = s.indexOf("://") + 3
        val pathStart = s.indexOf('/', schemeEnd)
        val result = if (pathStart < 0) s else {
            val origin = s.substring(0, pathStart)
            val encodedPath = s.substring(pathStart)
                .split('/')
                .joinToString("/") { segment -> if (segment.isEmpty()) segment else encodeSegment(segment) }
            origin + encodedPath
        }
        if (result != original) {
            StunLogger.d(LOG_TAG, "URL normalized: \"$raw\" -> \"$result\"")
        }
        return result
    }

    /** 上传文件；404/409（父目录缺失）时自动补建集合后重试，共 3 次机会。 */
    fun put(baseUrl: String, relPath: String, user: String, pass: String, data: ByteArray) {
        val url = buildUrl(baseUrl, relPath)
        StunLogger.d(LOG_TAG, "PUT $url (${data.size} B)")
        var lastError: WebDavException? = null
        for (attempt in 1..3) {
            try {
                execute(Request.Builder().url(url).put(data.toRequestBody(OCTET_MEDIA)), user, pass) { resp ->
                    if (resp.code !in 200..299) {
                        StunLogger.d(LOG_TAG, "PUT → HTTP ${resp.code}")
                        throw WebDavException("HTTP ${resp.code}", resp.code)
                    }
                    StunLogger.d(LOG_TAG, "PUT → ${resp.code} ✅")
                }
                return
            } catch (e: WebDavException) {
                lastError = e
                if (e.statusCode == 404 || e.statusCode == 409) {
                    StunLogger.d(LOG_TAG, "PUT ${e.statusCode} -> auto-creating missing collection, retrying (attempt $attempt)")
                    ensureCollections(baseUrl, relPath, user, pass)
                    Thread.sleep(600) // 部分网盘（坚果云）建目录后立刻 PUT 会短暂 409
                    continue
                }
                throw e
            }
        }
        throw WebDavException(
            "HTTP ${lastError?.statusCode}（自动创建 Stun 目录失败：请在网盘网页端手动新建 Stun 文件夹，或把地址指向一个已存在的文件夹）",
            lastError?.statusCode ?: -1
        )
    }

    fun get(baseUrl: String, relPath: String, user: String, pass: String): ByteArray {
        val url = buildUrl(baseUrl, relPath)
        StunLogger.d(LOG_TAG, "GET $url")
        return execute(Request.Builder().url(url).get(), user, pass) { resp ->
            if (resp.code == 404) {
                StunLogger.d(LOG_TAG, "GET 404 not found")
                throw WebDavException("404 not found", 404)
            }
            if (resp.code !in 200..299) {
                StunLogger.w(LOG_TAG, "GET → HTTP ${resp.code}")
                throw WebDavException("HTTP ${resp.code}", resp.code)
            }
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            StunLogger.d(LOG_TAG, "GET ${resp.code} ← ${bytes.size} B")
            bytes
        }
    }

    /** 远端对象是否存在（HEAD，不拉内容）。 */
    fun exists(baseUrl: String, relPath: String, user: String, pass: String): Boolean {
        val url = buildUrl(baseUrl, relPath)
        StunLogger.d(LOG_TAG, "HEAD $url")
        return try {
            execute(Request.Builder().url(url).head(), user, pass) { resp ->
                StunLogger.d(LOG_TAG, "HEAD → ${resp.code}")
                resp.code in 200..299
            }
        } catch (e: IOException) {
            StunLogger.d(LOG_TAG, "HEAD network error: ${e.message}")
            false
        }
    }

    /**
     * PROPFIND Depth:1 列目录。返回子项名（已 URL 解码），目录名带尾部 "/"。
     * 404（集合不存在）→ 空列表。
     */
    fun listChildren(baseUrl: String, relPath: String, user: String, pass: String): List<String> {
        val url = buildUrl(baseUrl, relPath)
        StunLogger.d(LOG_TAG, "PROPFIND $url (Depth:1)")
        val request = Request.Builder().url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
            .header("Depth", "1")
        val xml = execute(request, user, pass) { resp ->
            if (resp.code == 404) {
                StunLogger.d(LOG_TAG, "PROPFIND 404 (collection missing) -> empty list")
                return@execute null
            }
            if (resp.code !in 200..299) {
                StunLogger.w(LOG_TAG, "PROPFIND → HTTP ${resp.code}")
                throw WebDavException("HTTP ${resp.code}", resp.code)
            }
            resp.body?.string()
        } ?: return emptyList()

        val baseSegs = decodedSegments(url)
        val out = ArrayList<String>()
        // 按 <response> 块解析：部分服务器（坚果云）目录 href 不带尾部斜杠，
        // 必须靠 resourcetype 的 <collection/> 判定目录。
        Regex("<(?:[A-Za-z0-9_-]+:)?response>([\\s\\S]*?)</(?:[A-Za-z0-9_-]+:)?response>")
            .findAll(xml).forEach { block ->
                val body = block.groupValues[1]
                val href = Regex("<(?:[A-Za-z0-9_-]+:)?href>([^<]*)</(?:[A-Za-z0-9_-]+:)?href>")
                    .find(body)?.groupValues?.getOrNull(1) ?: return@forEach
                val isDir = Regex("<(?:[A-Za-z0-9_-]+:)?collection\\s*/?>").containsMatchIn(body) ||
                    href.endsWith("/")
                childOf(href, baseSegs, isDir)?.let { out.add(it) }
            }
        val result = out.distinct()
        StunLogger.d(LOG_TAG, "PROPFIND -> 2xx, ${result.size} item(s): ${result.joinToString(", ")}")
        return result
    }

    /** DELETE 对象或集合（尽力而为；失败静默，调用方自行决定重试策略）。 */
    fun delete(baseUrl: String, relPath: String, user: String, pass: String) {
        val url = buildUrl(baseUrl, relPath)
        StunLogger.d(LOG_TAG, "DELETE $url")
        try {
            execute(Request.Builder().url(url).delete(), user, pass) { resp ->
                StunLogger.d(LOG_TAG, "DELETE → ${resp.code}")
            }
        } catch (e: IOException) {
            StunLogger.d(LOG_TAG, "DELETE network error: ${e.message}")
        }
    }

    /**
     * 逐级确保 relPath 的父集合存在（先父后子）。
     * 判存只用 2xx；其余状态码（404/405/500…在不同服务器语义不一）一律
     * 「未知 → 继续尝试创建」，创建结果用 MKCOL 的 405=已存在 语义兜底，
     * 最终以 PROPFIND 实测确认落盘——不盲信任何单一状态码。
     */
    private fun ensureCollections(baseUrl: String, relPath: String, user: String, pass: String) {
        var acc = baseUrl
        relPath.split('/').dropLast(1).filter { it.isNotBlank() }.forEach { dir ->
            acc = "$acc/${encodeSegment(dir)}"
            if (propfindExists(acc, user, pass)) {
                StunLogger.d(LOG_TAG, "Collection already exists: $acc")
                return@forEach
            }
            if (mkcol(acc, user, pass) || mkcol("$acc/", user, pass)) {
                if (propfindExists(acc, user, pass)) {
                    StunLogger.d(LOG_TAG, "Collection ready: $acc")
                } else {
                    StunLogger.d(LOG_TAG, "Collection ready (per MKCOL result, not re-verified): $acc")
                }
            } else {
                StunLogger.w(LOG_TAG, "MKCOL failed (both URL variants tried): $acc - please create the folder manually in the cloud drive web UI")
            }
        }
    }

    /** PROPFIND Depth:0；仅 207/200 判定存在，其它状态码/异常 → false（未知，交给创建流程处理）。 */
    private fun propfindExists(url: String, user: String, pass: String): Boolean {
        val request = Request.Builder().url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
            .header("Depth", "0")
        return try {
            execute(request, user, pass) { resp ->
                val exists = resp.code in 200..299
                StunLogger.d(LOG_TAG, "PROPFIND Depth:0 $url -> ${resp.code}${if (exists) " (exists)" else ""}")
                exists
            }
        } catch (e: IOException) {
            StunLogger.d(LOG_TAG, "PROPFIND Depth:0 failed: ${e.message}")
            false
        }
    }

    /** MKCOL（无请求体）；OkHttp 自动跟随 3xx；2xx=创建成功，405=已存在，均返回 true。 */
    private fun mkcol(url: String, user: String, pass: String): Boolean {
        StunLogger.d(LOG_TAG, "MKCOL $url")
        return try {
            execute(Request.Builder().url(url).method("MKCOL", null), user, pass) { resp ->
                val ok = resp.code in 200..299 || resp.code == 405
                StunLogger.d(LOG_TAG, "MKCOL -> ${resp.code} (${if (resp.code == 405) "exists" else if (ok) "created" else "failed"})")
                ok
            }
        } catch (e: IOException) {
            StunLogger.d(LOG_TAG, "MKCOL network error: ${e.message}")
            false
        }
    }

    /** 统一执行：补 Auth/UA 头、确保 Response 关闭、网络异常转 IOException 冒泡。 */
    private fun <T> execute(builder: Request.Builder, user: String, pass: String, handle: (okhttp3.Response) -> T): T {
        val request = builder
            .header("Authorization", Credentials.basic(user, pass))
            .header("User-Agent", UA)
            .build()
        client.newCall(request).execute().use { resp ->
            return handle(resp)
        }
    }

    /** href（可能是全路径 URL 或纯路径）相对基集合的直接子项名；目录带尾部 "/"；非子项返回 null。 */
    private fun childOf(href: String, baseSegs: List<String>, isDir: Boolean): String? {
        val pathPart = if (href.contains("://")) {
            href.substringAfter("://").let { it.substringAfter("/", "") }
        } else {
            href
        }
        val segs = pathPart.split('/').filter { it.isNotEmpty() }.map { decodeSegmentSafe(it) }
        if (segs.size <= baseSegs.size) return null
        for (i in baseSegs.indices) if (segs[i] != baseSegs[i]) return null
        val name = segs[baseSegs.size]
        return if (isDir) "$name/" else name
    }

    /** URL（或路径）中已编码路径段的解码列表（authority 之后）。 */
    private fun decodedSegments(url: String): List<String> {
        val afterScheme = url.substringAfter("://", url)
        val path = afterScheme.substringAfter("/", "")
        return path.split('/').filter { it.isNotEmpty() }.map { decodeSegmentSafe(it) }
    }

    private fun decodeSegmentSafe(s: String): String =
        try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }

    private fun buildUrl(baseUrl: String, relPath: String): String =
        baseUrl.trimEnd('/') + "/" + relPath.split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
}
