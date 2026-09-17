#!/usr/bin/env python3
"""
Stun Android - Google Gemini Live Interactive Controller
Allows Gemini (Gemini 2.0 Flash / 1.5 Pro) to directly inspect, control, and optimize Stun VPN on Android.
"""

import os
import sys
import json
import requests

try:
    import google.generativeai as genai
except ImportError:
    print("❌ Error: google-generativeai is not installed.")
    print("👉 Run: pip install -r requirements.txt")
    sys.exit(1)

DEFAULT_HOST = os.environ.get("STUN_PHONE_HOST", "127.0.0.1")
DEFAULT_PORT = int(os.environ.get("STUN_PHONE_PORT", "37180"))
DEFAULT_SCHEME = os.environ.get("STUN_SCHEME", "http")
STUN_API_KEY = os.environ.get("STUN_API_KEY", "")


def get_auth_headers():
    headers = {}
    if STUN_API_KEY:
        headers["Authorization"] = f"Bearer {STUN_API_KEY}"
    return headers


def fetch_tools(host, port, scheme=DEFAULT_SCHEME):
    url = f"{scheme}://{host}:{port}/gemini/declarations"
    resp = requests.get(url, headers=get_auth_headers(), timeout=6)
    resp.raise_for_status()
    data = resp.json()
    return data.get("functionDeclarations", [])


def main():
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        api_key = input("🔑 Enter your Google GEMINI_API_KEY (from Google AI Studio): ").strip()
        if not api_key:
            print("❌ GEMINI_API_KEY is required to use Gemini cloud models.")
            sys.exit(1)

    host = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_HOST
    port = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_PORT
    scheme = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_SCHEME

    print(f"\n📡 Connecting to Stun Phone at {scheme}://{host}:{port} ...")
    try:
        tools_decl = fetch_tools(host, port, scheme)
        print(f"✅ Loaded {len(tools_decl)} AI Tool Declarations from Stun Android!")
    except Exception as e:
        print(f"❌ Failed to connect to Stun: {e}")
        print("💡 Tips:")
        print("   1. If connected via USB: run 'adb reverse tcp:37180 tcp:37180'")
        print("   2. If connected via Wi-Fi: pass phone IP, e.g.: python gemini_control.py 192.168.1.100")
        print("   3. If Stun requires an API Key: export STUN_API_KEY=your_key")
        sys.exit(1)

    genai.configure(api_key=api_key)
    model = genai.GenerativeModel(
        model_name="gemini-2.0-flash",
        tools=tools_decl,
        system_instruction="You are an expert AI assistant that controls and monitors the Stun VPN and transparent proxy client on the user's Android phone using the provided tools."
    )
    chat = model.start_chat(enable_automatic_function_calling=True)

    print("\n" + "=" * 60)
    print("🦊 Stun + Google Gemini AI Controller Ready!")
    print("💬 Type your natural language command (or 'exit' / 'quit' to exit)")
    print("=" * 60 + "\n")

    while True:
        try:
            prompt = input("\n👤 You > ").strip()
            if not prompt:
                continue
            if prompt.lower() in ["exit", "quit", "q"]:
                print("👋 Bye!")
                break

            print("🤖 Gemini is thinking & calling phone tools...")
            res = chat.send_message(prompt)
            print(f"\n🤖 Gemini >\n{res.text}\n")

        except KeyboardInterrupt:
            print("\n👋 Exiting...")
            break
        except Exception as e:
            print(f"⚠️ Error: {e}")


if __name__ == "__main__":
    main()
