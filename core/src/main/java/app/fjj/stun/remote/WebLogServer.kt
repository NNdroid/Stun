package app.fjj.stun.remote

import android.content.Context
import app.fjj.stun.repo.StunLogger
import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebLogServer: 内嵌 HTTP 服务，通过 SSE 实时向浏览器推送日志。
 *
 * 设计要点:
 * - 零额外依赖：复用 core 模块已有的 Ktor CIO
 * - 零阻塞生产者：通过 SharedFlow 订阅，消费端断开后自动取消协程
 * - 零历史丢失：SharedFlow replay=200，新客户端连上即可看到最近日志
 * - 优雅停止：stop() 会等待 Ktor 完成握手再关闭
 */
object WebLogServer {

    private const val TAG = "WebLogServer"
    const val DEFAULT_PORT = 7878

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val isRunning = AtomicBoolean(false)

    /** 当前会话 Token，每次 start() 重新生成，对外只读 */
    var token: String = ""; private set

    private fun generateToken(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 去除易混淆字符 O/0/I/1
        return (1..8).map { chars.random() }.joinToString("")
    }

    /** 启动服务，返回实际监听的端口，失败返回 -1 */
    fun start(context: Context, port: Int = DEFAULT_PORT): Int {
        if (isRunning.compareAndSet(false, true)) {
            token = generateToken()
            val actualPort = try {
                ServerSocket(port).use { it.localPort }
            } catch (e: Exception) {
                ServerSocket(0).use { it.localPort }
            }

            server = embeddedServer(CIO, port = actualPort) {
                routing {
                    // ── 主页 ───────────────────────────────────────────────────
                    get("/") {
                        if (!call.checkToken(token)) return@get
                        call.respondText(buildHtmlPage(token), ContentType.Text.Html)
                    }

                    // ── SSE 实时日志流 ─────────────────────────────────────────
                    get("/logs/stream") {
                        if (!call.checkToken(token)) return@get
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.response.header(HttpHeaders.Connection, "keep-alive")
                        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                        call.respondBytesWriter(ContentType.parse("text/event-stream")) {
                            writeFully(": connected\n\n".toByteArray())
                            flush()
                            try {
                                StunLogger.logFlow.collect { line ->
                                    val sseData = buildString {
                                        line.trimEnd().split("\n").forEach { append("data: $it\n") }
                                        append("\n")
                                    }
                                    writeFully(sseData.toByteArray())
                                    flush()
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    // ── 清屏 ───────────────────────────────────────────────────
                    get("/logs/clear") {
                        if (!call.checkToken(token)) return@get
                        StunLogger.i(TAG, "--- Log cleared by web console ---")
                        call.respond(HttpStatusCode.OK, "ok")
                    }
                }
            }.start(wait = false)

            StunLogger.i(TAG, "Web log server started → http://${getLocalIp(context)}:$actualPort/?token=$token")
            return actualPort
        }
        return -1
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            server?.stop(500, 1000)
            server = null
            token = ""
            StunLogger.i(TAG, "Web log server stopped")
        }
    }

    fun isRunning() = isRunning.get()

    /** 获取设备当前局域网 IP，兼容 Android 12+ */
    fun getLocalIp(context: Context): String {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val network = cm.activeNetwork ?: return "localhost"
            val lp = cm.getLinkProperties(network) ?: return "localhost"
            lp.linkAddresses
                .map { it.address }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: "localhost"
        } catch (_: Exception) {
            "localhost"
        }
    }

    // ── Token 校验（每个路由调用）──────────────────────────────────────────────
    private suspend fun io.ktor.server.application.ApplicationCall.checkToken(expectedToken: String): Boolean {
        val reqToken = request.queryParameters["token"]
        if (reqToken == expectedToken) return true
        respond(HttpStatusCode.Unauthorized, "401 — provide ?token=YOUR_TOKEN")
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内嵌 HTML — 深色终端风格，SSE 实时接收，虚拟滚动，一键复制 / 清屏
    // ─────────────────────────────────────────────────────────────────────────
    private fun buildHtmlPage(token: String) = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>📡 Stun TV · Log Console</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  :root{
    --bg:#0d1117;--bg2:#161b22;--bg3:#21262d;
    --border:#30363d;--text:#e6edf3;--muted:#8b949e;
    --debug:#6e7681;--info:#3fb950;--warn:#d29922;--error:#f85149;
    --accent:#58a6ff;--font:'Cascadia Code','Fira Code','JetBrains Mono',monospace;
  }
  html,body{height:100%;background:var(--bg);color:var(--text);font-family:var(--font)}
  /* ── 顶栏 ── */
  header{
    display:flex;align-items:center;justify-content:space-between;
    padding:12px 20px;background:var(--bg2);border-bottom:1px solid var(--border);
    position:sticky;top:0;z-index:10;
  }
  .brand{font-size:1rem;font-weight:700;color:var(--accent);letter-spacing:.03em}
  .brand span{color:var(--muted);font-weight:400}
  .status-dot{
    display:inline-block;width:8px;height:8px;border-radius:50%;
    background:var(--error);margin-right:6px;transition:background .3s;
  }
  .status-dot.connected{background:var(--info);box-shadow:0 0 6px var(--info)}
  .stats{font-size:.75rem;color:var(--muted);margin-left:12px}
  .toolbar{display:flex;gap:8px;align-items:center}
  /* ── 按钮 ── */
  button{
    padding:6px 14px;border:1px solid var(--border);border-radius:6px;
    background:var(--bg3);color:var(--text);cursor:pointer;font-size:.8rem;
    transition:background .15s,border-color .15s;
  }
  button:hover{background:var(--border);border-color:var(--accent)}
  button.danger:hover{border-color:var(--error);color:var(--error)}
  /* ── 过滤条 ── */
  #filter-bar{
    display:flex;align-items:center;gap:10px;padding:10px 20px;
    background:var(--bg2);border-bottom:1px solid var(--border);
  }
  #search{
    flex:1;padding:6px 12px;background:var(--bg3);border:1px solid var(--border);
    border-radius:6px;color:var(--text);font-family:var(--font);font-size:.82rem;
    outline:none;transition:border-color .15s;
  }
  #search:focus{border-color:var(--accent)}
  .level-btn{
    padding:4px 10px;border-radius:4px;font-size:.75rem;font-weight:700;
    cursor:pointer;opacity:.45;transition:opacity .15s;user-select:none;
  }
  .level-btn.active{opacity:1}
  .level-D{border:1px solid var(--debug);color:var(--debug)}
  .level-I{border:1px solid var(--info);color:var(--info)}
  .level-W{border:1px solid var(--warn);color:var(--warn)}
  .level-E{border:1px solid var(--error);color:var(--error)}
  /* ── 日志区 ── */
  #log-container{
    height:calc(100vh - 102px);overflow-y:auto;padding:8px 4px;
  }
  #log-list{list-style:none}
  .log-line{
    display:flex;padding:2px 16px;font-size:.8rem;line-height:1.65;
    border-left:2px solid transparent;transition:background .1s;
    word-break:break-all;white-space:pre-wrap;
  }
  .log-line:hover{background:var(--bg3)}
  .log-line.D{border-color:var(--debug);color:var(--debug)}
  .log-line.I{border-color:var(--info);color:var(--text)}
  .log-line.W{border-color:var(--warn);color:var(--warn)}
  .log-line.E{border-color:var(--error);color:var(--error)}
  .log-line.hidden{display:none}
  /* ── 底部状态栏 ── */
  footer{
    position:fixed;bottom:0;left:0;right:0;
    padding:4px 20px;background:var(--bg2);border-top:1px solid var(--border);
    font-size:.72rem;color:var(--muted);display:flex;justify-content:space-between;
  }
  ::-webkit-scrollbar{width:5px}
  ::-webkit-scrollbar-track{background:var(--bg)}
  ::-webkit-scrollbar-thumb{background:var(--border);border-radius:3px}
  ::-webkit-scrollbar-thumb:hover{background:var(--muted)}
</style>
</head>
<body>
<header>
  <div style="display:flex;align-items:center;gap:10px">
    <span class="brand">📡 Stun TV <span>Log Console</span></span>
    <span id="status-dot" class="status-dot"></span>
    <span id="status-text" style="font-size:.75rem;color:var(--muted)">Connecting...</span>
    <span id="stats" class="stats"></span>
  </div>
  <div class="toolbar">
    <button id="btn-scroll" title="Auto-scroll">⬇ Auto</button>
    <button id="btn-copy" title="Copy visible logs">📋 Copy</button>
    <button id="btn-clear" class="danger" title="Clear console">🗑 Clear</button>
  </div>
</header>

<div id="filter-bar">
  <input id="search" type="text" placeholder="Search logs..." autocomplete="off" spellcheck="false">
  <span class="level-btn level-D active" data-level="D">DEBUG</span>
  <span class="level-btn level-I active" data-level="I">INFO</span>
  <span class="level-btn level-W active" data-level="W">WARN</span>
  <span class="level-btn level-E active" data-level="E">ERROR</span>
</div>

<div id="log-container">
  <ul id="log-list"></ul>
</div>

<footer>
  <span id="footer-left">Total: <b id="total-count">0</b> lines · Shown: <b id="shown-count">0</b></span>
  <span id="footer-right">Stun TV Web Console</span>
</footer>

<script>
  // ── 状态 ──────────────────────────────────────────────────────
  let autoScroll = true;
  let totalCount = 0;
  let activeLevels = new Set(['D','I','W','E']);
  let searchText = '';
  // 虚拟化: 只保留最近 N 条 DOM 节点
  const MAX_DOM_LINES = 1500;

  const list = document.getElementById('log-list');
  const container = document.getElementById('log-container');
  const totalEl = document.getElementById('total-count');
  const shownEl = document.getElementById('shown-count');
  const dot = document.getElementById('status-dot');
  const statusText = document.getElementById('status-text');

  // ── 日志解析 ───────────────────────────────────────────────────
  function parseLevel(line) {
    if (/\bERROR\b|\bFATAL\b/.test(line)) return 'E';
    if (/\bWARN\b|\bWARNING\b/.test(line))  return 'W';
    if (/\bDEBUG\b/.test(line))             return 'D';
    return 'I';
  }

  function matchesFilter(text, level) {
    if (!activeLevels.has(level)) return false;
    if (searchText && !text.toLowerCase().includes(searchText)) return false;
    return true;
  }

  function appendLine(rawText) {
    // 过滤掉 SSE 心跳注释
    if (!rawText || rawText.startsWith(':')) return;

    totalCount++;
    const level = parseLevel(rawText);
    const li = document.createElement('li');
    li.className = 'log-line ' + level;
    li.textContent = rawText;
    if (!matchesFilter(rawText, level)) li.classList.add('hidden');
    list.appendChild(li);

    // 虚拟化：移除最早的节点防止 DOM 膨胀
    if (list.children.length > MAX_DOM_LINES) {
      list.removeChild(list.firstChild);
    }

    updateCounts();
    if (autoScroll) container.scrollTop = container.scrollHeight;
  }

  function updateCounts() {
    totalEl.textContent = totalCount;
    shownEl.textContent = list.querySelectorAll('.log-line:not(.hidden)').length;
  }

  function reapplyFilter() {
    for (const li of list.children) {
      const level = [...li.classList].find(c => ['D','I','W','E'].includes(c)) || 'I';
      li.classList.toggle('hidden', !matchesFilter(li.textContent, level));
    }
    updateCounts();
  }

  // ── SSE 连接 (带指数退避自动重连) ─────────────────────────────
  const WEB_TOKEN = '$token';
  let retryDelay = 1000;
  function connect() {
    const es = new EventSource('/logs/stream?token=' + WEB_TOKEN);
    es.onopen = () => {
      dot.classList.add('connected');
      statusText.textContent = 'Live';
      retryDelay = 1000;
    };
    es.onmessage = (e) => appendLine(e.data);
    es.onerror = () => {
      dot.classList.remove('connected');
      statusText.textContent = 'Reconnecting...';
      es.close();
      setTimeout(connect, retryDelay = Math.min(retryDelay * 2, 30000));
    };
  }
  connect();

  // ── 过滤器交互 ────────────────────────────────────────────────
  document.querySelectorAll('.level-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const lv = btn.dataset.level;
      if (activeLevels.has(lv)) { activeLevels.delete(lv); btn.classList.remove('active'); }
      else                       { activeLevels.add(lv);    btn.classList.add('active'); }
      reapplyFilter();
    });
  });
  document.getElementById('search').addEventListener('input', e => {
    searchText = e.target.value.toLowerCase();
    reapplyFilter();
  });

  // ── 按钮 ──────────────────────────────────────────────────────
  document.getElementById('btn-scroll').addEventListener('click', () => {
    autoScroll = !autoScroll;
    document.getElementById('btn-scroll').style.color = autoScroll ? 'var(--accent)' : '';
    if (autoScroll) container.scrollTop = container.scrollHeight;
  });
  document.getElementById('btn-copy').addEventListener('click', () => {
    const visible = [...list.querySelectorAll('.log-line:not(.hidden)')].map(l=>l.textContent).join('\n');
    navigator.clipboard.writeText(visible).catch(()=>{});
  });
  document.getElementById('btn-clear').addEventListener('click', () => {
    fetch('/logs/clear?token=' + WEB_TOKEN).then(() => { list.innerHTML = ''; totalCount = 0; updateCounts(); });
  });

  // 用户滚动时暂停 auto-scroll
  container.addEventListener('scroll', () => {
    const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 40;
    if (autoScroll !== atBottom) {
      autoScroll = atBottom;
      document.getElementById('btn-scroll').style.color = autoScroll ? 'var(--accent)' : '';
    }
  });
</script>
</body>
</html>"""
}
