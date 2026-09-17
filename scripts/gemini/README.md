# Stun Android - Google Gemini AI Agent Integration

This directory contains lightweight scripts to connect **Google Gemini (Gemini 2.0 Flash / 1.5 Pro)** directly to Stun on your Android device via Function Calling and Model Context Protocol (MCP).

---

## 🚀 Quick Start

### 1. Install Dependencies
```bash
pip install -r requirements.txt
```

### 2. Connect Your Phone
- **Via USB (ADB Reverse)**:
  ```bash
  adb reverse tcp:37180 tcp:37180
  ```
- **Via Wi-Fi (Local LAN)**:
  Ensure your PC and Android phone are on the same Wi-Fi network. Find your phone's IP in Stun Settings (e.g. `192.168.1.100`).

### 3. Test Connection
```bash
# Via USB:
python test_connection.py

# Via Wi-Fi:
python test_connection.py 192.168.1.100
```

### 4. Run Interactive Gemini Controller
```bash
export GEMINI_API_KEY="your-google-api-key"
# Optional if Stun requires auth:
export STUN_API_KEY="your-stun-api-key"

# Via USB:
python gemini_control.py

# Via Wi-Fi:
python gemini_control.py 192.168.1.100
```

---

## 🤖 3-Line Python Code Snippet

```python
import google.generativeai as genai
import requests

# 1. Fetch live 18 tools schema from phone
tools_decl = requests.get("http://127.0.0.1:37180/gemini/declarations").json()

# 2. Configure Gemini with automatic tool execution
genai.configure(api_key="YOUR_GEMINI_API_KEY")
model = genai.GenerativeModel("gemini-2.0-flash", tools=tools_decl["functionDeclarations"])
chat = model.start_chat(enable_automatic_function_calling=True)

# 3. Chat with Gemini to control Stun
response = chat.send_message("Check VPN status, test latencies, and connect to the fastest node")
print(response.text)
```

---

## 🛠️ Available Gemini Tools (18)

1. `get_vpn_status`: Live VPN state, mode, server, rates, and traffic.
2. `start_vpn`: Start VPN tunnel (optional `profileId` or `profileName`).
3. `stop_vpn`: Disconnect active VPN.
4. `restart_vpn`: Restart/reconnect tunnel.
5. `list_profiles`: List all configured nodes.
6. `get_profile_detail`: Complete parameters of a node.
7. `create_profile`: Create a new proxy node with custom fields.
8. `update_profile`: Update parameters of an existing node.
9. `delete_profile`: Delete a node.
10. `select_profile`: Switch active node with auto-reconnect.
11. `test_node_latency`: Ping/RTT latency benchmark for nodes.
12. `get_app_filter_list`: List installed apps on phone and proxy status.
13. `set_app_filter`: Configure split-tunneling (allow/bypass packages).
14. `update_geodata`: Trigger atomic update of GeoIP & Geosite databases.
15. `get_settings`: Retrieve global DNS, UDPGW, and routing settings.
16. `set_settings`: Update global DNS, routing rules, or log levels.
17. `get_logs`: Query runtime error & info logs with level filter.
18. `get_device_info`: Hardware model, battery level, Android version, and IP.
