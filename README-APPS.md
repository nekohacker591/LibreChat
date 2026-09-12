# LibreChat Desktop (Electron) & Android App Wrappers

This repository features integrated, full-stack wrappers for **Windows Desktop (Electron)** and **Android (APK)** with **embedded backend auto-startup**, **auto-updating**, and a unified build pipeline.

---

## 🚀 Key Improvements & Architecture

### 1. Seamless Desktop Startup (No Manual Terminal Needed)
- **Embedded Backend & Database Supervisor**: When you launch LibreChat Desktop, it automatically starts the database and backend server first before loading the chat interface.
- **Embedded MongoDB**: If no native MongoDB service is detected on port `27017`, LibreChat Desktop starts an embedded, persistent MongoDB instance that saves all your chats and settings to `%APPDATA%\LibreChat\database`.
- **Startup Splash Screen**: Displays an animated status screen (`splash.html`) indicating initialization progress and smoothly transitions to LibreChat once `http://localhost:3080` is healthy.
- **Clean Shutdown**: When you close the Desktop app, all background child processes and database instances are terminated cleanly.

### 2. Seamless Android Mobile Access
- **Local Network Wi-Fi Bridge**: LibreChat backend binds to `0.0.0.0:3080`, allowing any device on your Wi-Fi network to connect.
- **Easy Pairing**: In LibreChat Desktop, click **Server -> Mobile Access (Android App)...** to view and copy your PC's local network URL (e.g. `http://192.168.1.100:3080`).
- **Auto-Detect in Android App**: The Android app includes an **Auto-Detect** button that scans your local subnet for LibreChat and connects with a single tap.
- **Auto-Reconnect**: If you move away from Wi-Fi or restart your PC, the Android app displays a countdown and automatically reconnects once the server is back online.

---

## 🖥️ Desktop App (Electron)

### Pre-built Artifacts
The Windows executables are located in:
- **Installer**: `electron/dist/LibreChat Setup 1.0.0.exe` (~79 MB)
- **Portable**: `electron/dist/LibreChat 1.0.0.exe` (~79 MB)
- **Auto-Updater Manifest**: `electron/dist/latest.yml`

### Rebuilding the Windows `.exe`
```powershell
.\build-apps.ps1 -Target electron
# or
npm run electron:build
```

---

## 📱 Android App (APK)

### Features
- Native WebView wrapper targeting Android 14/15 (API 34/35).
- Full microphone permissions (Whisper voice chat) and camera/file attachment picker.
- In-App Auto-Updater (`UpdateManager.java`) that queries GitHub Releases for newer `.apk` versions, downloads them, and prompts the Android package installer.
- Subnet Auto-Discovery & Auto-Reconnect.

### Building the APK
```powershell
.\build-apps.ps1 -Target android
# or
npm run android:build
```
Or push to GitHub with a version tag (e.g. `git tag v1.0.1 && git push origin v1.0.1`) to let `.github/workflows/build-and-release.yml` build and publish both the `.exe` and `.apk` automatically.
