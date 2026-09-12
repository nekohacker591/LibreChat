<p align="center">
  <img src="client/public/assets/logo.svg" height="128" alt="LibreChat Logo">
  <h1 align="center">LibreChat — Desktop & Android Standalone Edition</h1>
  <p align="center">
    <strong>All-in-One AI Chatbot Platform with Embedded Zero-Config Backend, Mobile App, and Native LLM Gateway / DevPass Integration</strong>
  </p>
</p>

<p align="center">
  <a href="https://github.com/nekohacker591/LibreChat/releases/latest">
    <img src="https://img.shields.io/github/v/release/nekohacker591/LibreChat?color=blue&label=Latest%20Release" alt="Latest Release">
  </a>
  <img src="https://img.shields.io/badge/Platform-Windows%20%7C%20Android-green" alt="Platforms">
  <img src="https://img.shields.io/badge/LLM%20Gateway-DevPass%20Native-purple" alt="LLM Gateway">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
</p>

---

## 🚀 Highlights & Features

This edition of LibreChat provides a **ready-to-use, zero-terminal experience** across Desktop and Mobile:

### 1. 🖥️ Seamless Windows Desktop App (Electron)
- **Built-in Embedded MongoDB**: Automatically manages an embedded MongoDB instance that saves chats to `%APPDATA%\LibreChat\database`. **No manual Docker or database configuration needed.**
- **Automatic Supervisor Lifecycle**: Starts the database and backend automatically on launch with an animated startup splash screen, and shuts down cleanly on exit.
- **In-App Auto-Updating**: Checks GitHub Releases for updates automatically and updates seamlessly.
- **Local Network Wi-Fi Bridge**: Automatically binds to `0.0.0.0:3080` so devices on your Wi-Fi network can connect to your local desktop instance.

### 2. 📱 Standalone Android App (APK)
- **On-Device Internal Server**: Embeds a lightweight, native HTTP server (`LocalServer`) and local SQLite database (`librechat_local.db`).
- **100% Standalone Mobile Operation**: Run LibreChat directly on your phone **without needing a running PC or desktop server**.
- **Dual-Mode Connectivity**:
  - **On-Device Server Mode**: Complete privacy, offline UI, local SQLite chat history, and direct proxying to LLM providers.
  - **Remote PC Mode**: Connect over Wi-Fi to your PC desktop server with auto-subnet scanning and automatic reconnect.
- **Signed with Test Keystore**: Pre-signed with RSA 2048-bit key (`v2` signature scheme), ready to install directly on real Android 8.0+ devices.

### 3. 🌐 Native LLM Gateway & DevPass Integration
- **Direct API Token Architecture**: Conforms strictly to developer token attribution policies using API tokens (no username/password login accounts required).
- **Mandatory Attribution Header**: Automatically attaches `x-source: devpass-code` to all outbound LLM Gateway requests and validates inbound API requests.
- **Dynamic 264+ Model Catalog Indexing**: Immediately discovers and lists models (`gpt-4o`, `gpt-4o-mini`, `claude-sonnet-4-5`, `o1`, `o3-mini`, `gemini-2.0-flash`, etc.) dynamically from `https://api.llmgateway.io/v1/models`.
- **In-UI Key Management**: Enter your personal API token easily via **Settings -> Provider Keys** or the model prompt dialog.

---

## 📥 Downloads

Download the latest pre-compiled binaries from the **[Releases](https://github.com/nekohacker591/LibreChat/releases/latest)** page:

| Platform | Download Link | Description | Size |
| :--- | :--- | :--- | :--- |
| **Android** | [**`LibreChat-v1.0.0-release.apk`**](https://github.com/nekohacker591/LibreChat/releases/download/v1.0.0/LibreChat-v1.0.0-release.apk) | Standalone signed APK with on-device server & SQLite | ~13.2 MB |
| **Windows** | [**`LibreChat Setup 1.0.0.exe`**](https://github.com/nekohacker591/LibreChat/releases/download/v1.0.0/LibreChat.Setup.1.0.0.exe) | Complete installer with embedded MongoDB & auto-updater | ~82.8 MB |
| **Windows** | [**`LibreChat 1.0.0.exe`**](https://github.com/nekohacker591/LibreChat/releases/download/v1.0.0/LibreChat.1.0.0.exe) | Portable single-executable edition | ~82.5 MB |

---

## 📱 Android Quick Start

1. **Install the APK**:
   - Transfer `LibreChat-v1.0.0-release.apk` to your phone or download directly in mobile browser.
   - Tap to install. If prompted by Android Play Protect (since this is signed with a developer test key), tap **Install anyway**.
2. **Choose Your Mode**:
   - **Run Internal Server (On-Device)**: Operates standalone on the phone. All conversations and settings are stored locally.
   - **Connect to PC Server**: Enter your desktop's local IP (e.g. `http://192.168.1.50:3080`) or tap **Auto-Detect** to scan your Wi-Fi subnet.
3. **Configure Your API Token**:
   - Select **LLM Gateway** or **DevPass** in the top model selector.
   - Open **Settings** (gear icon) -> **Provider Keys**.
   - Paste your LLM Gateway API token (`llmgtwy_...` or DevPass token).
   - Start chatting!

---

## 🖥️ Desktop Quick Start

1. Run `LibreChat Setup 1.0.0.exe` or `LibreChat 1.0.0.exe`.
2. The app will automatically launch its embedded MongoDB and backend server, showing an animated splash screen while preparing.
3. Once loaded, click the gear icon (Settings) -> **Provider Keys** -> enter your API token.
4. (Optional) To connect from Android on the same Wi-Fi, click **Server -> Mobile Access (Android App)...** from the top menu to view your LAN IP address.

---

## 🛠️ Building from Source

### Prerequisites
- **Node.js**: v20.x or higher
- **JDK**: Java 17 (e.g. Eclipse Adoptium / Temurin 17)
- **Android SDK**: Build-Tools 34.0.0, Platform API 34

### 1. Build Client & Packages
```bash
# Clone the repository
git clone https://github.com/nekohacker591/LibreChat.git
cd LibreChat

# Install dependencies
npm install

# Build data providers and client frontend
npm run build:packages
npm run build:client
```

### 2. Package Electron Desktop App
```bash
# Run in development
npm run electron:start

# Build Windows installer and portable executables
npm run electron:build
```
Output binaries will be placed in `electron/dist/`.

### 3. Build Android Release APK
```bash
# Build release APK using gradle wrapper
cd android
./gradlew assembleRelease
```
The signed release APK will be located at `android/app/build/outputs/apk/release/app-release.apk`.

Or run the automated PowerShell script:
```powershell
.\build-apps.ps1 -Target all
```

---

## 🔄 CI/CD & Automated Releases

A GitHub Actions workflow is included at [`.github/workflows/build-and-release.yml`](.github/workflows/build-and-release.yml).
Whenever a version tag is pushed (e.g. `git tag v1.0.1 && git push origin v1.0.1`), GitHub Actions will:
1. Compile the Windows Electron installer and portable binary.
2. Compile and sign the Android release APK.
3. Publish a new GitHub Release with the artifacts and auto-update metadata (`latest.yml`).

---

## 📄 License & Acknowledgments

- Built upon the open-source [LibreChat](https://github.com/danny-avila/LibreChat) project by Danny Avila and contributors under the MIT License.
- Mobile WebView wrapper and embedded Android HTTP server powered by [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd).
- LLM Gateway and DevPass support based on [LLM Gateway Documentation](https://docs.llmgateway.io/).
