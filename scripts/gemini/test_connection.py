#!/usr/bin/env python3
"""
Quick Connectivity & Tool Validation Script for Stun Android
Tests HTTP / HTTPS connection to phone's Gemini/MCP server and prints status & available tools.
"""

import os
import sys
import json
import requests

DEFAULT_HOST = os.environ.get("STUN_PHONE_HOST", "127.0.0.1")
DEFAULT_PORT = int(os.environ.get("STUN_PHONE_PORT", "37180"))
DEFAULT_SCHEME = os.environ.get("STUN_SCHEME", "http")
STUN_API_KEY = os.environ.get("STUN_API_KEY", "")


def get_headers():
    headers = {}
    if STUN_API_KEY:
        headers["Authorization"] = f"Bearer {STUN_API_KEY}"
    return headers


def test_connection(host=DEFAULT_HOST, port=DEFAULT_PORT, scheme=DEFAULT_SCHEME):
    base_url = f"{scheme}://{host}:{port}"
    print(f"[*] 📡 Testing Stun Server at {base_url} ...")

    # 1. Health Status
    try:
        status_resp = requests.get(f"{base_url}/mcp/status", headers=get_headers(), timeout=4)
        if status_resp.status_code == 200:
            print("[+] ✅ Stun Server Status: ONLINE")
            data = status_resp.json()
            print(f"    - VPN State:       {data.get('vpnState')}")
            print(f"    - Active Node:     {data.get('currentProfileName')}")
            print(f"    - Service Mode:    {data.get('serviceMode')}")
            print(f"    - Uplink Rate:     {data.get('txRateFormatted')}")
            print(f"    - Downlink Rate:   {data.get('rxRateFormatted')}")
        else:
            print(f"[!] ⚠️ Server returned status {status_resp.status_code}: {status_resp.text}")
    except Exception as e:
        print(f"[!] ❌ Failed to connect to /mcp/status: {e}")
        print("    Hints:")
        print("    1. If testing over USB: run 'adb reverse tcp:37180 tcp:37180'")
        print("    2. If testing over Wi-Fi: pass phone IP (e.g. python test_connection.py 192.168.1.100)")
        return False

    # 2. Gemini Declarations
    try:
        gemini_resp = requests.get(f"{base_url}/gemini/declarations", headers=get_headers(), timeout=4)
        if gemini_resp.status_code == 200:
            decls = gemini_resp.json().get("functionDeclarations", [])
            print(f"\n[+] 🛠️ Gemini Tools Schema: {len(decls)} Function Declarations Loaded:")
            for i, decl in enumerate(decls, 1):
                name = decl.get("name")
                desc = decl.get("description", "")
                print(f"    {i:2d}. {name:<24} - {desc}")
        else:
            print(f"[!] ⚠️ /gemini/declarations returned {gemini_resp.status_code}")
    except Exception as e:
        print(f"[!] ❌ Failed to fetch Gemini declarations: {e}")

    # 3. Test direct tool execution
    try:
        print("\n[*] 🧪 Testing direct tool call: 'get_device_info' ...")
        call_resp = requests.post(
            f"{base_url}/gemini/call",
            headers=get_headers(),
            json={"name": "get_device_info", "args": {}},
            timeout=5
        )
        if call_resp.status_code == 200:
            out = call_resp.json().get("functionResponse", {}).get("response", {}).get("output", "")
            print(f"[+] ✅ Result:\n    {out}")
        else:
            print(f"[!] ⚠️ Call returned status {call_resp.status_code}: {call_resp.text}")
    except Exception as e:
        print(f"[!] ❌ Tool execution failed: {e}")

    print("\n[+] 🚀 Verification Finished!")
    return True


if __name__ == "__main__":
    h = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_HOST
    p = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_PORT
    s = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_SCHEME
    test_connection(h, p, s)
