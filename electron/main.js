const { app, BrowserWindow, Menu, Tray, ipcMain, shell, dialog, nativeImage, clipboard, session } = require('electron');
const path = require('path');
const fs = require('fs');
const http = require('http');
const https = require('https');
const { initAutoUpdater, checkForUpdatesManual } = require('./updater');
const { startBackend, stopBackend, getLocalIpAddress } = require('./backend-manager');

// Configuration management
const CONFIG_PATH = path.join(app.getPath('userData'), 'desktop-config.json');

function loadConfig() {
  const defaults = {
    serverUrl: 'http://localhost:3080',
    windowBounds: { width: 1280, height: 850 },
    isMaximized: false,
    autoStartBackend: true
  };
  try {
    if (fs.existsSync(CONFIG_PATH)) {
      const data = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
      return { ...defaults, ...data };
    }
  } catch (err) {
    console.error('Error reading desktop-config.json:', err);
  }
  return defaults;
}

function saveConfig(updates) {
  try {
    const current = loadConfig();
    const updated = { ...current, ...updates };
    fs.writeFileSync(CONFIG_PATH, JSON.stringify(updated, null, 2), 'utf8');
    return updated;
  } catch (err) {
    console.error('Error writing desktop-config.json:', err);
    return null;
  }
}

let mainWindow = null;
let tray = null;
let config = loadConfig();

function getAppIcon() {
  const iconPath = path.join(__dirname, 'assets', 'icon.png');
  if (fs.existsSync(iconPath)) {
    return nativeImage.createFromPath(iconPath);
  }
  return null;
}

function isLocalAddress(urlStr) {
  try {
    const u = new URL(urlStr);
    return u.hostname === 'localhost' || u.hostname === '127.0.0.1' || u.hostname === '0.0.0.0';
  } catch {
    return false;
  }
}

function testServerConnection(urlStr) {
  return new Promise((resolve) => {
    try {
      const url = new URL(urlStr);
      const client = url.protocol === 'https:' ? https : http;
      const req = client.get(urlStr, { timeout: 4000 }, (res) => {
        resolve({ success: res.statusCode >= 200 && res.statusCode < 500, statusCode: res.statusCode });
      });
      req.on('error', (err) => resolve({ success: false, error: err.message }));
      req.on('timeout', () => {
        req.destroy();
        resolve({ success: false, error: 'Connection timed out' });
      });
    } catch (err) {
      resolve({ success: false, error: 'Invalid URL format' });
    }
  });
}

function createMainWindow() {
  const icon = getAppIcon();

  mainWindow = new BrowserWindow({
    width: config.windowBounds.width || 1280,
    height: config.windowBounds.height || 850,
    x: config.windowBounds.x,
    y: config.windowBounds.y,
    minWidth: 800,
    minHeight: 600,
    icon: icon,
    title: 'LibreChat Desktop',
    backgroundColor: '#171717',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: true
    },
    show: false
  });

  if (config.isMaximized) {
    mainWindow.maximize();
  }

  mainWindow.on('resize', () => {
    if (!mainWindow.isMaximized()) {
      saveConfig({ windowBounds: mainWindow.getBounds(), isMaximized: false });
    }
  });

  mainWindow.on('maximize', () => saveConfig({ isMaximized: true }));
  mainWindow.on('unmaximize', () => saveConfig({ isMaximized: false }));

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  // External links open in default OS browser
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http://') || url.startsWith('https://')) {
      try {
        const targetHost = new URL(url).host;
        const currentHost = new URL(config.serverUrl).host;
        if (targetHost === currentHost) {
          return { action: 'allow' };
        }
      } catch (e) {}
      shell.openExternal(url);
      return { action: 'deny' };
    }
    return { action: 'allow' };
  });

  setupApplicationMenu();
  setupSystemTray();
  initAutoUpdater(mainWindow);

  // Initialize and load application
  launchAppLifecycle();
}

async function launchAppLifecycle() {
  const targetUrl = config.serverUrl || 'http://localhost:3080';

  if (isLocalAddress(targetUrl) && config.autoStartBackend !== false) {
    // Show smooth splash screen while starting backend
    mainWindow.loadFile(path.join(__dirname, 'splash.html'));

    const updateStatus = (msg) => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('backend-status', msg);
      }
    };

    try {
      const startResult = await startBackend({
        userDataPath: app.getPath('userData'),
        onStatus: updateStatus
      });

      if (startResult.success) {
        // Small delay to let splash show ready state
        setTimeout(() => {
          if (mainWindow && !mainWindow.isDestroyed()) {
            loadLibreChatOrDialog(targetUrl);
          }
        }, 600);
      } else {
        console.warn('Backend failed to start:', startResult.error);
        mainWindow.loadFile(path.join(__dirname, 'connection-dialog.html'), {
          query: { failedUrl: targetUrl, error: startResult.error }
        });
      }
    } catch (err) {
      console.error('Lifecycle error:', err);
      mainWindow.loadFile(path.join(__dirname, 'connection-dialog.html'), {
        query: { failedUrl: targetUrl, error: err.message }
      });
    }
  } else {
    // Remote server mode: connect directly
    loadLibreChatOrDialog(targetUrl);
  }
}

function loadLibreChatOrDialog(url) {
  mainWindow.loadURL(url).catch((err) => {
    console.warn(`Failed to load ${url}:`, err.message);
    mainWindow.loadFile(path.join(__dirname, 'connection-dialog.html'), {
      query: { failedUrl: url, error: err.message }
    });
  });
}

function showMobileAccessDialog() {
  const localIp = getLocalIpAddress();
  const mobileUrl = `http://${localIp}:3080`;

  dialog.showMessageBox(mainWindow, {
    type: 'info',
    title: 'Mobile Access (Android App)',
    message: `Connect Android to LibreChat`,
    detail: `Your PC's Local Network Address:\n${mobileUrl}\n\n1. Ensure your Android phone is connected to the same Wi-Fi.\n2. Open the LibreChat Android App.\n3. Enter "${mobileUrl}" in the Server URL setting.`,
    buttons: ['Copy Address', 'OK'],
    defaultId: 0
  }).then(({ response }) => {
    if (response === 0) {
      clipboard.writeText(mobileUrl);
    }
  });
}

function setupApplicationMenu() {
  const template = [
    {
      label: 'File',
      submenu: [
        {
          label: 'New Chat',
          accelerator: 'CmdOrCtrl+N',
          click: () => {
            if (mainWindow) {
              mainWindow.loadURL(`${config.serverUrl.replace(/\/$/, '')}/c/new`);
            }
          }
        },
        { type: 'separator' },
        {
          label: 'Server Settings...',
          accelerator: 'CmdOrCtrl+,',
          click: () => {
            if (mainWindow) {
              mainWindow.loadFile(path.join(__dirname, 'connection-dialog.html'));
            }
          }
        },
        { type: 'separator' },
        {
          label: 'Exit',
          accelerator: 'CmdOrCtrl+Q',
          click: () => app.quit()
        }
      ]
    },
    {
      label: 'Edit',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' }
      ]
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload', accelerator: 'CmdOrCtrl+R' },
        { role: 'forceReload', accelerator: 'CmdOrCtrl+Shift+R' },
        { role: 'toggleDevTools', accelerator: 'F12' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' }
      ]
    },
    {
      label: 'Server',
      submenu: [
        {
          label: 'Restart / Reconnect',
          accelerator: 'F5',
          click: () => {
            launchAppLifecycle();
          }
        },
        {
          label: 'Change Server URL...',
          click: () => {
            if (mainWindow) {
              mainWindow.loadFile(path.join(__dirname, 'connection-dialog.html'));
            }
          }
        },
        { type: 'separator' },
        {
          label: 'Mobile Access (Android App)...',
          click: () => {
            showMobileAccessDialog();
          }
        }
      ]
    },
    {
      label: 'Help',
      submenu: [
        {
          label: 'Check for Updates...',
          click: () => {
            checkForUpdatesManual(mainWindow);
          }
        },
        { type: 'separator' },
        {
          label: 'View Backend Log',
          click: () => {
            const logPath = path.join(app.getPath('userData'), 'backend.log');
            if (fs.existsSync(logPath)) {
              shell.openPath(logPath);
            } else {
              dialog.showMessageBox(mainWindow, {
                type: 'info',
                title: 'Backend Log',
                message: 'No backend log found yet at:\n' + logPath,
                buttons: ['OK']
              });
            }
          }
        },
        {
          label: 'Open Logs Folder',
          click: () => {
            const userDataPath = app.getPath('userData');
            shell.openPath(userDataPath);
          }
        },
        {
          label: 'Open Server Logs Directory',
          click: () => {
            const serverLogsPath = path.resolve(__dirname, '..', 'api', 'logs');
            if (fs.existsSync(serverLogsPath)) {
              shell.openPath(serverLogsPath);
            } else {
              shell.openPath(path.resolve(__dirname, '..'));
            }
          }
        },
        { type: 'separator' },
        {
          label: 'LibreChat Documentation',
          click: () => shell.openExternal('https://www.librechat.ai/docs')
        },
        {
          label: 'GitHub Repository',
          click: () => shell.openExternal('https://github.com/danny-avila/LibreChat')
        },
        { type: 'separator' },
        {
          label: 'About LibreChat Desktop',
          click: () => {
            dialog.showMessageBox(mainWindow, {
              type: 'info',
              title: 'About LibreChat Desktop',
              message: `LibreChat Desktop Wrapper\nVersion: ${app.getVersion()}\nServer: ${config.serverUrl}`,
              buttons: ['OK']
            });
          }
        }
      ]
    }
  ];

  const menu = Menu.buildFromTemplate(template);
  Menu.setApplicationMenu(menu);
}

function setupSystemTray() {
  const icon = getAppIcon();
  if (!icon) return;

  try {
    tray = new Tray(icon.resize({ width: 16, height: 16 }));
    const contextMenu = Menu.buildFromTemplate([
      {
        label: 'Show LibreChat',
        click: () => {
          if (mainWindow) {
            mainWindow.show();
            mainWindow.focus();
          }
        }
      },
      {
        label: 'Mobile Access (Android)...',
        click: () => showMobileAccessDialog()
      },
      {
        label: 'Change Server URL...',
        click: () => {
          if (mainWindow) {
            mainWindow.show();
            mainWindow.loadFile(path.join(__dirname, 'connection-dialog.html'));
          }
        }
      },
      { type: 'separator' },
      {
        label: 'Quit',
        click: () => app.quit()
      }
    ]);

    tray.setToolTip('LibreChat Desktop');
    tray.setContextMenu(contextMenu);

    tray.on('double-click', () => {
      if (mainWindow) {
        if (mainWindow.isVisible()) {
          mainWindow.hide();
        } else {
          mainWindow.show();
          mainWindow.focus();
        }
      }
    });
  } catch (err) {
    console.warn('System tray error:', err);
  }
}

// IPC Handlers
ipcMain.handle('get-config', () => config);

ipcMain.handle('save-server-url', (event, newUrl) => {
  let formattedUrl = newUrl.trim();
  if (!/^https?:\/\//i.test(formattedUrl)) {
    formattedUrl = 'http://' + formattedUrl;
  }
  config = saveConfig({ serverUrl: formattedUrl });
  launchAppLifecycle();
  return { success: true, url: config.serverUrl };
});

ipcMain.handle('test-connection', async (event, testUrl) => {
  let formattedUrl = testUrl.trim();
  if (!/^https?:\/\//i.test(formattedUrl)) {
    formattedUrl = 'http://' + formattedUrl;
  }
  return await testServerConnection(formattedUrl);
});

function setupNetworkInterception() {
  const filter = {
    urls: [
      '*://*.llmgateway.io/*',
      '*://llmgateway.io/*',
      '*://*.opencode.ai/*',
      '*://opencode.ai/*'
    ]
  };

  try {
    session.defaultSession.webRequest.onBeforeSendHeaders(filter, (details, callback) => {
      const requestHeaders = { ...details.requestHeaders };
      requestHeaders['x-source'] = 'opencode';
      requestHeaders['User-Agent'] = 'opencode/1.18.30';
      callback({ requestHeaders });
    });
    console.log('[Desktop] LLM Gateway & OpenCode network interceptor registered with x-source and opencode spoofing.');
  } catch (err) {
    console.warn('[Desktop] Failed to register webRequest interceptor:', err);
  }
}

// App Lifecycle
const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    setupNetworkInterception();
    createMainWindow();
  });

  app.on('before-quit', () => {
    stopBackend();
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      stopBackend();
      app.quit();
    }
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createMainWindow();
    }
  });
}
