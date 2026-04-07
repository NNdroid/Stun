package myssh

import (
	"fmt"
	"net/http"
	"os"
)

// StartWebLogger 启动一个迷你的 HTTP 服务器来在浏览器展示日志
// port: Web 界面监听的端口 (例如 8888)
// logPath: 之前传入 InitLogger 的那个日志文件绝对路径
func StartWebLogger(port int, logPath string) {
	// 1. 提供一个酷炫的 HTML 前端页面
	http.HandleFunc("/log-ui", func(w http.ResponseWriter, r *http.Request) {
		html := `<!DOCTYPE html>
<html>
<head>
    <title>GoMySsh 实时日志监控</title>
    <meta charset="utf-8">
    <style>
        body { background-color: #1e1e1e; color: #4af626; font-family: Consolas, monospace; padding: 20px; font-size: 14px; line-height: 1.5; }
        #logs { white-space: pre-wrap; word-wrap: break-word; }
        .footer { position: fixed; bottom: 10px; right: 20px; color: #888; font-size: 12px; }
    </style>
</head>
<body>
    <div id="logs">日志加载中...</div>
    <div class="footer">Auto-refreshing every 2 seconds</div>
    <script>
        function fetchLogs() {
            // 请求原始日志数据
            fetch('/log-raw')
                .then(response => response.text())
                .then(text => {
                    const logDiv = document.getElementById('logs');
                    // 判断用户是否正在看历史记录（没有滚到底部），防止刷新时强行把用户拉到底部
                    const isScrolledToBottom = window.innerHeight + window.scrollY >= document.body.offsetHeight - 50;
                    
                    logDiv.textContent = text;
                    
                    if (isScrolledToBottom) {
                        window.scrollTo(0, document.body.scrollHeight);
                    }
                })
                .catch(err => console.error("读取日志失败", err));
        }
        // 每 2 秒自动去拉取一次最新的日志
        setInterval(fetchLogs, 2000);
        fetchLogs();
    </script>
</body>
</html>`
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(html))
	})

	// 2. 提供一个原始日志读取接口 (供前端 JS 调用)
	http.HandleFunc("/log-raw", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		// 读取文件内容
		data, err := os.ReadFile(logPath)
		if err != nil {
			w.Write([]byte("无法读取日志文件: " + err.Error()))
			return
		}
		w.Write(data)
	})

	// 3. 在后台协程启动 Web 服务
	addr := fmt.Sprintf("0.0.0.0:%d", port)
	
	// 直接复用 main.go 里的 zlog 和 TAG 变量
	if zlog != nil {
		zlog.Infof("%s [WebLog] 🌐 Web 日志监控已启动: http://%s/log-ui", TAG, addr)
	}
	
	go func() {
		// ListenAndServe 会阻塞，必须放在协程里
		if err := http.ListenAndServe(addr, nil); err != nil {
			if zlog != nil {
				zlog.Errorf("%s [WebLog] Web 服务异常退出: %v", TAG, err)
			}
		}
	}()
}