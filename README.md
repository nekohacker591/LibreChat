<p align="center">
  <img src="client/public/assets/logo.svg" height="128" alt="LibreChat Logo">
  <h1 align="center">LibreChat — Standalone Desktop & Mobile Edition</h1>
  <p align="center">
    <strong>Zero-Config AI Chatbot Platform with Embedded Backends, Standalone Android Server, Local User Mode, and Native DevPass / LLM Gateway Integration</strong>
  </p>
</p>

<p align="center">
  <a href="https://github.com/nekohacker591/LibreChat/releases/latest">
    <img src="https://img.shields.io/github/v/release/nekohacker591/LibreChat?color=blue&label=Release%20v1.1.1" alt="Latest Release">
  </a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20Android-green" alt="Platforms">
  <img src="https://img.shields.io/badge/Backend-Embedded%20MongoDB%20%7C%20On--Device%20SQLite-purple" alt="Databases">
  <img src="https://img.shields.io/badge/Integration-OpenCode%20Go%20%26%20Zen%20%7C%20DevPass-orange" alt="Integration">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
</p>

---

## 🌟 What Makes This Edition Different from Standard LibreChat?

Standard LibreChat is a powerful self-hosted web application designed primarily for servers, Docker deployments, and multi-user environments. While versatile, it typically requires setting up Docker Compose, managing an external MongoDB instance, configuring complex reverse proxies, and creating username/password accounts with email verification.

This edition transforms LibreChat into a **turnkey, zero-dependency consumer application for Windows and Android** with deep, purpose-built **DevPass** and **LLM Gateway** integration.

### 📊 Comparison at a Glance

| Feature | Standard LibreChat | This Standalone Edition |
| :--- | :--- | :--- |
| **Setup & Dependencies** | Requires Docker, Node.js, external MongoDB & Redis | **Zero setup.** Double-click the installer or APK and start chatting immediately. |
| **Windows Desktop** | Terminal / web browser workflow only | **Native Electron App** with embedded MongoDB supervisor and auto-updater. |
| **Authentication on Desktop** | Requires user registration, email, password, and `/login` screens | **Local User Mode**: Auto-provisions an offline `Local User` with full `ADMIN` rights; never redirects to a login screen. |
| **Mobile Operation (Android)** | Requires an active remote PC or VPS running LibreChat | **100% Standalone On-Device Server**: Embeds an internal NanoHTTPD server and native SQLite database right inside the APK. |
| **Mobile UI Performance** | Standard web view with pull-to-refresh jitter and potential touch freezes | **Mobile-Optimized**: 1:1 true device pixel ratio, zero-latency slide-out drawer, and defensive non-blocking queries. |
| **DevPass Integration** | Generic custom endpoint requiring manual YAML tweaking | **Native First-Class DevPass Tier**: Provider stripping, non-chat model filtering, `x-source: opencode`, and spoofed User-Agent. |
| **LLM Gateway Integration** | Manual API key setup with static model lists | **Live 498+ Model Discovery**: Automatic catalog fetching, provider-pinned routing, and full multimodal support. |

---

## ⚡ Unique DevPass & LLM Gateway Integration

This edition is engineered specifically around **[LLM Gateway](https://docs.llmgateway.io/)** and the **DevPass** ecosystem, providing two finely-tuned operational tiers side by side:

```
                                  ┌───────────────────────────┐
                                  │      User Chat Prompt     │
                                  └─────────────┬─────────────┘
                                                │
                       ┌────────────────────────┴────────────────────────┐
                       ▼                                                 ▼
        ┌──────────────────────────────┐                 ┌──────────────────────────────┐
        │        DevPass Tier          │                 │       LLM Gateway Tier       │
        │    (Included Subscription)   │                 │       (Pay-As-You-Go)        │
        ├──────────────────────────────┤                 ├──────────────────────────────┤
        │ • Plain Model IDs (gpt-4o)   │                 │ • Provider-Pinned (openai/..)│
        │ • Chat & Code Models Only    │                 │ • 498+ Models (All Types)    │
        │ • Embeddings/Images EXCLUDED │                 │ • Image & Video Gen Included │
        │ • Spoofed opencode UA        │                 │ • Full Multimodal Support    │
        └──────────────┬───────────────┘                 └──────────────┬───────────────┘
                       │                                                 │
                       └────────────────────────┬────────────────────────┘
                                                │
                                  ┌─────────────▼─────────────┐
                                  │    api.llmgateway.io      │
                                  │    x-source: opencode     │
                                  └───────────────────────────┘
```

### 1. 🎫 The DevPass Tier (Subscription Chat & Coding)
The DevPass tier is tailored exclusively for interactive chat, reasoning, and code generation. It automatically optimizes the model experience:
- **Automatic Provider Prefix Stripping**: Models are presented cleanly without provider prefixes (e.g., `openai/gpt-4o` becomes `gpt-4o`, `anthropic/claude-3-5-sonnet` becomes `claude-3-5-sonnet`). Duplicate offerings across multiple providers are automatically consolidated into clean, unique entries.
- **Smart Model Filtering**: Per provider terms, embeddings, rerankers, image generation, and video generation models are **not included** in DevPass. The backend automatically isolates and excludes all 72+ non-chat models (such as `dall-e-3`, `flux`, `veo-3.1`, `sora`, `text-embedding-3`, etc.), ensuring you only see and select models that are supported.
- **Protocol Attribution & User-Agent Spoofing**: Automatically attaches the upstream OpenCode header `x-source: opencode` and spoofs the client User-Agent as `opencode/1.18.30` (OpenCode upstream project specification), ensuring 100% compliance with upstream gateway validation.

### 2. 💳 The LLM Gateway Tier (Pay-As-You-Go Multimodal)
For users who want access to every tool and modality that LLM Gateway offers:
- **Full 498+ Model Catalog**: Dynamically indexed in real time from `https://api.llmgateway.io/v1/models?mapped=true`.
- **Explicit Provider-Pinned Routing**: Retains full provider namespaces (e.g. `openai/gpt-4o`, `azure/gpt-4o`, `google/gemini-2.5-pro`, `anthropic/claude-3-7-sonnet`) so you can pinpoint exactly which cloud infrastructure handles your request.
- **Unrestricted Multimodal Support**: Includes text generation, image generation, video generation, and embeddings.

---

## 🏗️ Deep Dive: Architecture & Implementation

### 📱 Android Standalone Edition
- **Embedded NanoHTTPD Server ([LocalServer.java](android/app/src/main/java/com/librechat/app/server/LocalServer.java))**:
  - Runs a lightweight, battery-efficient Java HTTP server directly within the Android process on `http://127.0.0.1:8080`.
  - Proxies chat requests directly to LLM Gateway using OkHttp with Server-Sent Events (SSE) streaming.
  - Features an asynchronous, zero-blocking model cache so opening the app or starting a new chat never stalls on network requests.
- **On-Device SQLite Persistence ([LocalDatabaseHelper.java](android/app/src/main/java/com/librechat/app/server/LocalDatabaseHelper.java))**:
  - All chat sessions, message histories, titles, and API credentials are stored securely on-device in SQLite (`librechat_local.db`).
- **Seamless Offline Auth**:
  - Replaces LibreChat's server auth routes (`/api/auth/refresh`, `/api/auth/login`, `/api/user`) with an internal session provider that grants instant access without any login forms.
- **Mobile UX Polish**:
  - Eliminated browser pull-down refresh bounce and accidental page reloads.
  - Removed empty dismiss buttons and banner containers.
  - Set 1:1 true device pixel ratio viewport for crisp, native-feeling typography.
  - High-performance slide-out sidebar with instantaneous touch release and zero UI thread freezing.
- **Dedicated Security**:
  - Signed with a unique, dedicated 2048-bit RSA release keystore (`my-release-key.jks`, alias `my-alias`) valid for 10,000 days.

### 🖥️ Windows Desktop App (Electron)
- **Embedded MongoDB Engine**:
  - On launch, [backend-manager.js](electron/backend-manager.js) verifies whether MongoDB is available. If not, it starts an embedded instance persisting data to `%APPDATA%\LibreChat\database`.
- **Local User Auto-Login ([LocalUserService.js](api/server/services/LocalUserService.js))**:
  - Seamlessly initializes and logs in as `Local User` (`user@librechat.local`, `ADMIN` role).
  - Bypasses all password and credential checks so you go straight to your chat without login walls.
- **Local Network (LAN) Pairing**:
  - Binds the backend to `0.0.0.0:3080` and includes a **Mobile Access** dialog in the menu bar displaying your PC's Wi-Fi IP address, making it easy to pair an Android device in Remote PC mode if desired.
- **Integrated Auto-Updater**:
  - Built with `electron-updater` and GitHub Releases, allowing the desktop app to update itself seamlessly in the background.

---

## 📥 Download Pre-Compiled Binaries

Download the ready-to-run release files from [**GitHub Releases v1.1.1**](https://github.com/nekohacker591/LibreChat/releases/tag/v1.1.1):

| Artifact | File Name | Size | Target Platform | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Android APK** | [**`LibreChat-v1.1.1-release.apk`**](https://github.com/nekohacker591/LibreChat/releases/download/v1.1.1/LibreChat-v1.1.1-release.apk) | ~12.6 MB | Android 8.0+ | Standalone APK with on-device HTTP server, SQLite, OpenCode Go & Zen, and DevPass integration. |
| **Desktop Installer** | [**`LibreChat-Setup-1.0.5.exe`**](https://github.com/nekohacker591/LibreChat/releases/download/v1.1.1/LibreChat-Setup-1.0.5.exe) | ~79.0 MB | Windows 10 / 11 (x64) | Full desktop installer with embedded MongoDB and background auto-updater. |
| **Desktop Portable** | [**`LibreChat-1.0.5.exe`**](https://github.com/nekohacker591/LibreChat/releases/download/v1.1.1/LibreChat-1.0.5.exe) | ~78.8 MB | Windows 10 / 11 (x64) | Standalone portable executable. Requires no installation. |
| **Update Manifest** | [`latest.yml`](https://github.com/nekohacker591/LibreChat/releases/download/v1.1.1/latest.yml) | 346 B | Auto-Updater | Differential update hash and release manifest. |

---

## 🚀 Quick Start Guide

### Android
1. **Download & Install**: Grab `LibreChat-v1.1.1-release.apk` and install it on your device (tap **Install anyway** if prompted by Play Protect).
2. **Launch & Choose Mode**:
   - **On-Device Server (Default)**: Runs 100% on the phone without any desktop connection required.
   - **Connect to PC Server**: Connect over your local Wi-Fi to LibreChat Desktop running on your PC.
3. **Add Your Token**:
   - Tap the settings gear icon (⚙️) -> **Provider Keys**.
   - Paste your LLM Gateway token (`llmgtwy_...`) or DevPass token.
   - Pick any model from the **DevPass** or **LLM Gateway** dropdown and start chatting!

### Windows Desktop
1. Run `LibreChat-Setup-1.0.5.exe` (or launch the portable `LibreChat-1.0.5.exe`).
2. An animated splash screen will display while the embedded database and services initialize.
3. Once the chat window opens (automatically signed in as Local User), click **Settings** (⚙️) -> **Provider Keys** -> enter your API token.
4. Select your preferred model and start chatting immediately!

---

## 🛠️ Building from Source

### Prerequisites
- **Node.js**: v20.x or higher
- **JDK**: Java 17 (e.g. Eclipse Adoptium / Temurin 17)
- **Android SDK**: Platform API 34, Build-Tools 34.0.0

```bash
# 1. Clone the repository
git clone https://github.com/nekohacker591/LibreChat.git
cd LibreChat

# 2. Install workspace dependencies
npm install

# 3. Build data providers, API package, and web client
npm run build:packages
npm --prefix packages/api run build
npm run build:client

# 4. Build Windows Desktop Installer & Portable Executable
npm --prefix electron run dist:win

# 5. Build Android Standalone Release APK
cd android
./gradlew assembleRelease
```

---

## 📄 License & Credits

- Based on the [LibreChat](https://github.com/danny-avila/LibreChat) open-source project by Danny Avila and contributors (MIT License).
- Embedded Android HTTP server powered by [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd).
- LLM Gateway and DevPass support based on [LLM Gateway Documentation](https://docs.llmgateway.io/).
