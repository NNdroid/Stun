package app.fjj.stun.remote

/**
 * MCP 控制台页面（浏览器打开的那张 dashboard）的 HTML 模板。
 *
 * ## 为什么要单独成文件
 * 原来这是 [StunMcpServer] 里一个 550 行的 `renderDashboardHtml`，其中 **513 行是
 * 一整块 HTML raw string**（含 CSS 与页面 JS），前面只有 39 行数据准备。
 * 混在一个 3000+ 行的服务端对象里意味着：改一行 HTML 要在服务主循环里翻，
 * 浏览器里报错时无法把"模板问题"与"路由/鉴权问题"分开定位。
 *
 * 现在这里是**纯模板**：零控制流、零 `Context`/`SettingsManager` 依赖，
 * 文案经 [t] 回调取、其余值由参数传入 ⇒ 可以单独渲染、单独快照、单独审。
 *
 * ## 调用方仍需负责的（**不要搬进本文件**）
 *  - `htmlEscape`：配置 JSON/TOML 在进模板**之前**就要转义，否则节点名里的 `<`
 *    会破坏页面结构。本文件是展示层，不做转义。
 *  - `getClaudeConfigJson(..., maskSecrets = true)`：控制台页面走**掩码版**配置。
 *    这是浏览器打开的页面，明文 API Key 落在 DOM 里就会随截屏 / 投屏 / 局域网访问外流。
 *    要可直接粘贴使用的完整配置，走 App 设置页的"分享配置"（本地、用户主动触发）。
 *
 * @param t 文案查找（等价 `McpI18n.get(key, lang)`）。所有可见文本都必须经过它 ——
 *   模板末尾那张法语表就是 `lang == "fr"` 时 `t` 的实现。
 */
internal object McpDashboardHtml {

    /**
     * @param lang 当前语言代码（`"en"`/`"zh"`/`"ja"`/`"de"`/`"fr"`），用于语言按钮高亮。
     * @param scheme 服务对外协议（`http`/`https`），决定是否显示 TLS 相关提示。
     * @param baseUrl 本服务对外可达的根地址（页面里的连接示例要用它拼绝对 URL）。
     * @param currentMcpUrl MCP 端点完整地址，等于 `$baseUrl/mcp`。
     * @param geminiUrl Gemini 函数声明端点，等于 `$baseUrl/gemini/declarations`。
     * @param certUrl 证书端点，等于 `$baseUrl/mcp/cert`。
     * @param authModeName 鉴权方式的可读名（已本地化）。
     * @param authDesc 鉴权方式的一句话说明（已本地化，可能为空串）。
     * @param configJson **已 htmlEscape、已掩码**的 Claude 配置片段。
     * @param codexConfigToml **已 htmlEscape、已掩码**的 Codex 配置片段。
     * @param isOauth 鉴权是否为 OAuth —— 模板里唯一的一处条件逻辑（决定要不要显示
     *   `/token` 端点说明）。传布尔值而非 [SettingsManager] 常量，模板才不依赖任何单例。
     * @param serverPort 服务端口（页面底部展示用）。
     */
    fun render(
        t: (String) -> String,
        lang: String,
        scheme: String,
        baseUrl: String,
        currentMcpUrl: String,
        geminiUrl: String,
        certUrl: String,
        authModeName: String,
        authDesc: String,
        isOauth: Boolean,
        configJson: String,
        codexConfigToml: String,
        serverPort: Int,
    ): String = """<!DOCTYPE html>
<html lang="$lang">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${t("title")}</title>
<style>
    :root { --bg: #0f172a; --card: #1e293b; --text: #f8fafc; --muted: #94a3b8; --heading: #cbd5e1; --tool-bg: #0f172a; --accent: #38bdf8; --green: #10b981; --gemini: #818cf8; --border: #334155; --code: #090d16; --code-text: #a5f3fc; }
    [data-theme="light"] { --bg: #f1f5f9; --card: #ffffff; --text: #0f172a; --muted: #475569; --heading: #334155; --tool-bg: #f8fafc; --accent: #0284c7; --green: #059669; --gemini: #6366f1; --border: #cbd5e1; --code: #0b1220; --code-text: #a5f3fc; }
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); padding: 24px; margin: 0; line-height: 1.5; transition: background .2s, color .2s; }
    .container { max-width: 880px; margin: 0 auto; }
    .lang-bar { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-bottom: 16px; font-size: 13px; }
    .lang-btn { color: var(--muted); text-decoration: none; padding: 4px 8px; border-radius: 6px; border: 1px solid var(--border); }
    .lang-btn.active { color: #fff; background: #0284c7; border-color: #0284c7; font-weight: bold; }
    .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
    h1 { color: var(--accent); margin-top: 0; display: flex; align-items: center; gap: 10px; font-size: 24px; }
    h2 { font-size: 18px; margin-top: 0; color: var(--heading); display: flex; align-items: center; gap: 8px; }
    .badge { background: #059669; color: #fff; padding: 4px 10px; border-radius: 9999px; font-size: 12px; font-weight: bold; }
    pre { background: var(--code); border: 1px solid var(--border); padding: 14px; border-radius: 8px; overflow-x: auto; color: var(--code-text); font-size: 13px; font-family: monospace; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; }
    .tool-item { background: var(--tool-bg); padding: 10px 14px; border-radius: 8px; border: 1px solid var(--border); font-size: 13px; }
    .tool-name { font-weight: bold; color: var(--accent); font-family: monospace; }
    .btn-cert { display: inline-block; background: #0284c7; color: #fff; text-decoration: none; padding: 8px 16px; border-radius: 6px; font-size: 13px; font-weight: bold; margin-top: 6px; }
    .btn-cert:hover { background: #0369a1; }
    .theme-btn { background: none; border: 1px solid var(--border); border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 15px; color: var(--text); }
</style>
<script>
    (function(){var r=document.documentElement,s=localStorage.getItem('mcp_theme');
    if(s){r.setAttribute('data-theme',s);}else if(window.matchMedia&&window.matchMedia('(prefers-color-scheme:light)').matches){r.setAttribute('data-theme','light');}})();
</script>
</head>
<body>
<div class="container">
    <div class="lang-bar">
        <a class="lang-btn ${if (lang == "en") "active" else ""}" href="?lang=en">English</a>
        <a class="lang-btn ${if (lang == "zh") "active" else ""}" href="?lang=zh">简体中文</a>
        <a class="lang-btn ${if (lang == "ja") "active" else ""}" href="?lang=ja">日本語</a>
        <a class="lang-btn ${if (lang == "de") "active" else ""}" href="?lang=de">Deutsch</a>
        <a class="lang-btn ${if (lang == "fr") "active" else ""}" href="?lang=fr">Français</a>
        <button onclick="toggleTheme()" id="theme-btn" class="theme-btn" title="Toggle theme">🌙</button>
    </div>
    <div class="card">
        <h1>🚀 ${t("title")} <span class="badge">${t("badge_online")}</span></h1>
        <p>${t("server_desc")}</p>
        <p><strong>🌐 ${t("connected_sse")}:</strong> <br><code style="color:var(--accent)">$currentMcpUrl</code></p>
        <p><strong>🔒 ${t("protocol_mode")}:</strong> <span class="badge" style="background:#0284c7;">${scheme.uppercase()} (${t("single_port_mux")})</span></p>
        <p><strong>♊ ${t("gemini_schema")}:</strong> <code style="color:var(--gemini)">$geminiUrl</code></p>
        ${if (scheme == "https") """
        <div style="margin-top: 12px;">
            <a class="btn-cert" href="$certUrl">${t("download_cert")}</a>
        </div>
        """ else ""}
    </div>

    <div class="card">
        <h2>🛡️ ${t("security_title")}: <span style="color:var(--accent)">$authModeName</span></h2>
        <p>$authDesc</p>
        ${if (isOauth) """
        <p><strong>${t("token_endpoint")}:</strong> <code>POST $baseUrl/token</code></p>
        <pre>curl -X POST $baseUrl/token -d "grant_type=client_credentials&amp;client_id=stun-client&amp;client_secret=YOUR_SECRET"</pre>
        """ else ""}
    </div>

    <div class="card">
        <h2>📱 ${t("claude_mobile_title")}</h2>
        <p>${t("claude_mobile_steps")}</p>
        <ul>
            <li><strong>${t("server_name")}:</strong> Stun Phone</li>
            <li><strong>${t("server_url")}:</strong> <code style="color:var(--green)">$currentMcpUrl</code></li>
        </ul>
        ${if (scheme == "https") "<p style=\"font-size:12px; color:var(--muted);\">${t("claude_mobile_tip")}</p>" else ""}
    </div>

    <div class="card">
        <h2>⚙️ ${t("desktop_title")}</h2>
        <p>${t("desktop_hint")}</p>
        <h3>Codex <code>config.toml</code></h3>
        <pre>$codexConfigToml</pre>
        <h3>Claude Code <code>.mcp.json</code></h3>
        <pre>$configJson</pre>
    </div>

    <div class="card">
        <h2>♊ ${t("gemini_title")}</h2>
        <p>${t("gemini_hint")}</p>
        <pre>
import google.generativeai as genai
import requests

# 1. Fetch live declarations from phone
tools_decl = requests.get("$geminiUrl").json()

# 2. Start chat with phone control
model = genai.GenerativeModel("gemini-2.0-flash", tools=tools_decl["functionDeclarations"])
chat = model.start_chat(enable_automatic_function_calling=True)
res = chat.send_message("Check VPN status on Stun and pick the fastest node")
print(res.text)
        </pre>
    </div>

    <div class="card">
        <h2>🛠️ ${t("tools_title")} (28)</h2>
        <div class="grid">
            <div class="tool-item"><div class="tool-name">get_vpn_status</div>${t("tool_get_vpn_status")}</div>
            <div class="tool-item"><div class="tool-name">start_vpn</div>${t("tool_start_vpn")}</div>
            <div class="tool-item"><div class="tool-name">stop_vpn</div>${t("tool_stop_vpn")}</div>
            <div class="tool-item"><div class="tool-name">restart_vpn</div>${t("tool_restart_vpn")}</div>
            <div class="tool-item"><div class="tool-name">list_profiles</div>${t("tool_list_profiles")}</div>
            <div class="tool-item"><div class="tool-name">get_profile_detail</div>${t("tool_get_profile_detail")}</div>
            <div class="tool-item"><div class="tool-name">create_profile</div>${t("tool_create_profile")}</div>
            <div class="tool-item"><div class="tool-name">update_profile</div>${t("tool_update_profile")}</div>
            <div class="tool-item"><div class="tool-name">delete_profile</div>${t("tool_delete_profile")}</div>
            <div class="tool-item"><div class="tool-name">select_profile</div>${t("tool_select_profile")}</div>
            <div class="tool-item"><div class="tool-name">test_node_latency</div>${t("tool_test_node_latency")}</div>
            <div class="tool-item"><div class="tool-name">get_app_filter_list</div>${t("tool_get_app_filter_list")}</div>
            <div class="tool-item"><div class="tool-name">set_app_filter</div>${t("tool_set_app_filter")}</div>
            <div class="tool-item"><div class="tool-name">update_geodata</div>${t("tool_update_geodata")}</div>
            <div class="tool-item"><div class="tool-name">get_settings</div>${t("tool_get_settings")}</div>
            <div class="tool-item"><div class="tool-name">set_settings</div>${t("tool_set_settings")}</div>
            <div class="tool-item"><div class="tool-name">get_logs</div>${t("tool_get_logs")}</div>
            <div class="tool-item"><div class="tool-name">get_device_info</div>${t("tool_get_device_info")}</div>
            <div class="tool-item"><div class="tool-name">get_webdav_config</div>${t("tool_get_webdav_config")}</div>
            <div class="tool-item"><div class="tool-name">set_webdav_config</div>${t("tool_set_webdav_config")}</div>
            <div class="tool-item"><div class="tool-name">list_backups</div>${t("tool_list_backups")}</div>
            <div class="tool-item"><div class="tool-name">backup_now</div>${t("tool_backup_now")}</div>
            <div class="tool-item"><div class="tool-name">sync_now</div>${t("tool_sync_now")}</div>
            <div class="tool-item"><div class="tool-name">restore_backup</div>${t("tool_restore_backup")}</div>
            <div class="tool-item"><div class="tool-name">list_subscriptions</div>${t("tool_list_subscriptions")}</div>
            <div class="tool-item"><div class="tool-name">sync_subscriptions</div>${t("tool_sync_subscriptions")}</div>
            <div class="tool-item"><div class="tool-name">import_profiles</div>${t("tool_import_profiles")}</div>
            <div class="tool-item"><div class="tool-name">export_profiles</div>${t("tool_export_profiles")}</div>
        </div>
    </div>
</div>
<script>
(function(){
  var root=document.documentElement, btn=document.getElementById('theme-btn');
  function upd(){ var t=root.getAttribute('data-theme')||'dark'; if(btn) btn.textContent=(t==='light')?'☀️':'🌙'; }
  upd();
  window.toggleTheme=function(){
var cur=root.getAttribute('data-theme')||'dark';
var next=(cur==='light')?'dark':'light';
root.setAttribute('data-theme',next);
localStorage.setItem('mcp_theme',next);
upd();
  };
})();
</script>
</body>
</html>
    """.trimIndent()
}
