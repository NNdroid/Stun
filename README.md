# Stun

<div style="text-align: center;">
  <img src="./logo.svg" alt="Stun Icon" width="128" />
</div>

Stun is a powerful and lightweight Android proxy client designed for efficiency and ease of use. It leverages TProxy and SSH technologies to provide a secure and flexible networking experience, complete with modern Material 3 design and Android 15 support.

## 📸 Screenshots

<div style="text-align: center;">
  <img src="./screenshots/0_app.png" width="200" />
  <img src="./screenshots/1_main.png" width="200" />
  <img src="./screenshots/2_main.png" width="200" />
</div>
<div style="text-align: center;">
  <img src="./screenshots/3_add_profile.png" width="200" />
  <img src="./screenshots/4_add_profile.png" width="200" />
  <img src="./screenshots/5_settings.png" width="200" />
</div>
<div style="text-align: center;">
  <img src="./screenshots/6_settings.png" width="200" />
  <img src="./screenshots/7_panel.png" width="200" />
  <img src="./screenshots/8_about.png" width="200" />
</div>

## Installation

You can download and install **Stun** using either of the following methods:

### Method 1: Google Play Testing (Recommended)
Join our testing program to install the app and receive automatic updates directly through the Google Play Store. You can opt into the test using either link below:

* **Join on Web:** 👉 [Opt-in via your web browser](https://play.google.com/apps/testing/app.fjj.stun)
* **Join on Android:** 👉 [Open directly in Google Play App](https://play.google.com/store/apps/details?id=app.fjj.stun)

*(Note: Once you opt-in via the web link, you can use the Android link to download it directly to your device.)*

### Method 2: GitHub Releases
If you prefer not to use Google Play, you can download the latest compiled APK directly from our repository.

* 👉 [Download from GitHub Releases](https://github.com/NNdroid/Stun/releases)

*(Note: You may need to enable "Install from Unknown Sources" in your Android device settings to install the downloaded APK.)*

## 🚀 Features

- **TProxy Support:** Seamlessly intercept and proxy system-wide traffic.
- **SSH Tunneling:** Integrated SSH support via `myssh` for secure connections.
- **GeoData Routing:** Advanced routing using Geosite and GeoIP data to distinguish between direct and proxied traffic.
- **Per-App Proxy:** Fine-grained control over which applications use the proxy.
- **Modern UI:** Built with Material 3 components, featuring full Edge-to-Edge support for Android 15.
- **QR Code Integration:** Easily import or share configurations via QR codes.
- **Multilingual:** Supports English, Chinese (Simplified/Traditional), French, and Japanese.
- **Dark Mode:** Fully compatible with system-wide dark and light themes.

## 🌐 Supported Protocols & Server Implementations

Stun supports a wide range of underlying transport protocols to bypass network restrictions and optimize performance. You can use the following open-source server implementations:

| Protocol / Tunnel | Description | Server Implementation |
| :--- | :--- | :--- |
| **`UDP_CUSTOM`** | Lightweight, reliable ARQ UDP stream tunnel with sliding-window anti-replay, multi-PSK, and Noise encryption | 🔗 [**NNdroid/udp_custom**](https://github.com/NNdroid/udp_custom) |
| **`ICMP_CUSTOM`** | SSH-over-ICMP tunnel with PSK / Noise encryption, IP family selection, MTU probing, and packet pacing | 🔗 [**NNdroid/myssh**](https://github.com/NNdroid/myssh) |
| **`ICMP_CUSTOM`** | SSH-over-ICMP tunnel: PSK / Noise, echo ID pool, MTU probing, and packet pacing | 🔗 [**NNdroid/myssh**](https://github.com/NNdroid/myssh) |
| **`H2` / `H3` / `MASQUE` / `WEBTRANSPORT` / `GRPC`** | All-in-one high-performance HTTP/2, HTTP/3 (QUIC), WebTransport, MASQUE (RFC 9298), and gRPC multiplexing tunnel with auto TLS and health probes | 🔗 [**NNdroid/h2tunnel**](https://github.com/NNdroid/h2tunnel) |
| **`XHTTP`** | Modern Chunked / Split-HTTP streaming tunnel with ring buffer for CDN, WAF, and reverse proxy camouflage | 🔗 [**NNdroid/xhttptunnel**](https://github.com/NNdroid/xhttptunnel) |
| **`DNS` / `DNS_CUSTOM`** | Tunnel traffic through DNS queries with 8 record types, Noise_NK AEAD + optional PSK auth and custom tunnel marker (both ends must match), UDP/DoH/DoT upstream | 🔗 [**NNdroid/dns_custom**](https://github.com/NNdroid/dns_custom) / [**dnstt**](https://www.bamsoftware.com/software/dnstt/) |
| **`KCP`** | High-performance ARQ reliable UDP with Reed-Solomon FEC forward error correction | 🔗 [**xtaci/kcptun**](https://github.com/xtaci/kcptun) |
| **`WEBSOCKET`** | WebSocket stream tunnel with CDN & reverse proxy support (Cloudflare, Nginx, Caddy), TLS toggle | 🔗 [**erebe/wstunnel**](https://github.com/erebe/wstunnel) / [**Nginx**](https://nginx.org) |
| **`RAW` (TLS toggle) / `HTTP`** | Direct TCP SSH connection (optional uTLS SNI spoofing) & standard HTTP CONNECT / TLS SNI proxy | 🔗 [**OpenSSH**](https://www.openssh.com/) / [**Squid**](http://www.squid-cache.org/) / [**HAProxy**](https://www.haproxy.org/) |

## 🛠 Tech Stack

- **Language:** 100% Kotlin
- **Build System:** Gradle (Kotlin DSL)
- **Database:** Room for profile management
- **Background Tasks:** WorkManager for GeoData updates
- **Native:** JNI/NDK for high-performance core logic
- **UI:** ViewBinding, Material 3, and ConstraintLayout

## 📦 Building from Source

### Prerequisites
- Android Studio Ladybug (or newer)
- Android SDK 36
- Android NDK (defined in your local.properties or project structure)
- JDK 17

### Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/NNdroid/Stun.git
   ```
2. Open the project in Android Studio.
3. Sync Gradle and build the project.
4. Run the `app` module on your device or emulator.

## ⚙️ Configuration

- **Remote DNS:** Support for DoH (DNS over HTTPS).
- **UDP Gateway:** Configurable UDPGW address for handling UDP traffic over SSH.
- **Routing Rules:** Custom tags for bypassing specific regions or domains (e.g., `cn`, `apple`, `private`).

## 📡 Subscription Format

The app supports **multiple subscription URLs**: add as many as you need (side panel → **Subscription**, each with its own optional PIN), and a sync fetches all of them and merges the nodes — duplicates across subscriptions are de-duplicated by node `id` (first subscription wins). Each URL is fetched with a plain `HTTP(S) GET` request (`User-Agent: Stun-Android/<version>`, connect timeout 10s, read timeout 15s), and the UTF-8 response body must match **one of the three formats below** — they are auto-detected in this order:

### Format 1: JSON Array (recommended)

A JSON array of node objects. Every node follows the same JSON structure as the app's exported backup files (unknown fields are ignored):

```json
[
  {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "name": "Tokyo Node",
    "sshAddr": "203.0.113.10:22",
    "user": "sshuser",
    "pass": "sshpass",
    "authType": "password",
    "tunnelType": "tls",
    "proxyAddr": "203.0.113.10:443",
    "customHost": "tunnel.example.com",
    "serverName": "tunnel.example.com"
  },
  {
    "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "name": "Backup Node",
    "sshAddr": "198.51.100.20:22",
    "user": "sshuser",
    "pass": "sshpass",
    "tunnelType": "base"
  }
]
```

### Format 2: Base64-encoded JSON

The entire response body is a Base64 string (standard alphabet, line breaks/whitespace tolerated) whose decoded content is the JSON array from Format 1.

### Format 3: PIN-encrypted share payload

The response body uses the **same encrypted format as the in-app share dialog's "Copy Link" button**: one or more `stun://<encrypted payload>` links. Payloads are PIN-encrypted, so each subscription entry can store its own PIN (entered in the native subscription sheet or the web console) which is used when syncing.

Two shapes are accepted:

* **Whole body is a single `stun://...` link** — the decrypted content may be a single node object or a node array (i.e. an encrypted backup produced by the in-app *Export* feature):

  ```
  stun://<encrypted-payload>
  ```

* **One `stun://...` link per line** — each line decrypts to a node object or a node array:

  ```
  stun://<encrypted-payload>
  stun://<encrypted-payload>
  ```

> ⚠️ Note: plain (unencrypted) `stun://` links are **not** supported in subscription files — only the PIN-encrypted payload format generated by the share dialog. Plain node data should be delivered via Format 1 or Format 2 instead.

### Node JSON core fields

| Field | Type | Description |
| :--- | :--- | :--- |
| `id` | string | Stable unique node ID. Nodes are **merged by `id`** on sync: an existing `id` updates the local node, a new `id` is added, and a blank `id` is auto-generated. Keep it stable so subsequent syncs update instead of duplicating. |
| `name` | string | Display name of the node. |
| `sshAddr` | string | SSH server address, `host:port`. |
| `user` / `pass` | string | SSH credentials when `authType` is `"password"`. |
| `authType` | string | `"password"` (default) or `"privatekey"`. |
| `privateKey` / `keyPass` | string | Private key (and its passphrase) when `authType` is `"privatekey"`. |
| `tunnelType` | string | One of `raw` (direct TCP, TLS toggle), `websocket`, `h2`, `grpc`, `xhttp` (TLS toggle), `quic`, `h3`, `masque`, `webtransport`, `http`, `kcptun` (KCP, displayed as "KCP"), `dns_custom`, `udp_custom`, `icmp_custom`. Companion boolean `tunnelTlsEnabled` controls TLS for the toggle-capable types. |
| `proxyAddr` | string | Tunnel server address, `host:port`. Exceptions: `udp_custom` accepts a port range (`1.1.1.1:1024-23000,25000`); `icmp_custom` is ICMP (network layer) — the peer is a **bare IP without port**; a stray `:port` is stripped automatically. |
| `customHost` / `serverName` / `customPath` | string | Tunnel host, TLS SNI / hostname, and path — `serverName` is only meaningful while TLS is active (fixed-TLS or toggle-capable with `tunnelTlsEnabled` on). |

Tunnel-specific options (KCP, DNS tunnel, UDP Custom, Noise public key, fingerprints, DNS/app-filter overrides, etc.) use the same field names as the JSON produced by the in-app **Export** feature — the simplest way to get a correct node JSON is to configure one node in the app and export it as a reference.

## ☁️ Cloud Backup (WebDAV)

Any standard WebDAV server works (坚果云/Jianguoyun, Nextcloud, self-hosted nginx+DAV). The app creates and maintains its own folder tree under the configured base URL — missing collections are auto-created (MKCOL), so pointing at an empty account is fine:

```text
<base>/Stun/
├── 20260912-063005/               # one folder per backup, UTC timestamp (newest 5 kept, older auto-deleted)
│   ├── profiles.json.enc          # all nodes, JSON → AES-GCM keyed by your backup PIN (PBKDF2)
│   ├── settings.json.enc          # global settings snapshot, same PIN encryption
│   └── section_subscription.json.enc  # pluggable section: subscriptions (only if non-empty)
└── 20260911-214417/...
```

- **Privacy**: every file is encrypted with your backup PIN *before* upload (same crypto as the share URIs) — the drive provider and the developer cannot read them.
- **Pluggable sections**: `WebDavBackupManager` knows nothing about individual settings. It simply iterates a registry of `BackupSection` implementations, each writing one encrypted file. Adding a new class of backed-up settings means adding one `BackupSection` — the backup pipeline itself never changes. `settings.json.enc` keeps its historical name so backups made before/after this change remain mutually restorable.
- **Zero-maintenance settings snapshot**: the settings section enumerates *all* keys of the portable store, so a newly added setting is backed up automatically. Value types are tagged at runtime (so numbers do not collapse into `Double`), `Set<String>` is supported, and secrets are detected automatically — anything the local Keystore wrapped is stored as `ENC:…`, so the snapshot exports the decrypted value and the receiving device re-wraps it with its own key. No per-field list needs updating, ever.
- **What is / isn't in the snapshot**: device-local state lives in a *separate* store (`stun_device_state`) and therefore can never leak into a backup — this covers the selected node, last-backup/last-geo-update timestamps, and `webdav_pin` itself (the vault key). The WebDAV account password *is* exported decrypted (re-encrypted with the backup PIN) so a new device only needs to re-enter the PIN once.
- **Restore**: pick a backup from the server-side list in-app (Android) or in the WebUI; nodes are merged **by id**, settings overwritten, and sections merged (subscriptions are unioned by URL so local entries are never clobbered) — all behind an explicit confirmation. A corrupt or missing section is skipped without affecting the rest.
- No Room/DB migration is involved: backup configuration lives in `stun_settings` (portable) and `stun_device_state` (device-local) SharedPreferences.

## 🤝 Contributing

Contributions are welcome! If you have suggestions for improvements or want to report a bug, please open an issue or submit a pull request.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📝 License

Distributed under the [MIT License](LICENSE.txt). See `LICENSE.txt` for more information.

## 📬 Feedback

For bug reports or feature requests, please use the [GitHub Issues](https://github.com/NNdroid/Stun/issues/new) page.

---
*Developed with ❤️ by the Stun Team.*
