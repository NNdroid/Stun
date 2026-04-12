# Stun

Stun is a powerful and lightweight Android proxy client designed for efficiency and ease of use. It leverages TProxy and SSH technologies to provide a secure and flexible networking experience, complete with modern Material 3 design and Android 15 support.

## 🚀 Features

- **TProxy Support:** Seamlessly intercept and proxy system-wide traffic.
- **SSH Tunneling:** Integrated SSH support via `myssh` for secure connections.
- **GeoData Routing:** Advanced routing using Geosite and GeoIP data to distinguish between direct and proxied traffic.
- **Per-App Proxy:** Fine-grained control over which applications use the proxy.
- **Modern UI:** Built with Material 3 components, featuring full Edge-to-Edge support for Android 15.
- **QR Code Integration:** Easily import or share configurations via QR codes.
- **Multilingual:** Supports English, Chinese (Simplified/Traditional), French, and Japanese.
- **Dark Mode:** Fully compatible with system-wide dark and light themes.

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
