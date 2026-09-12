const { autoUpdater } = require('electron-updater');
const { dialog, app } = require('electron');

let updateCheckInProgress = false;
let isManualCheck = false;

function initAutoUpdater(mainWindow) {
  // Only enable automatic checks if packaged or testing updater
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;

  autoUpdater.on('checking-for-update', () => {
    console.log('[AutoUpdater] Checking for updates...');
  });

  autoUpdater.on('update-available', (info) => {
    console.log('[AutoUpdater] Update available:', info.version);
    if (mainWindow && !mainWindow.isDestroyed()) {
      dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: 'Update Available',
        message: `A new version of LibreChat Desktop (v${info.version}) is available.`,
        detail: 'Downloading update in the background. You will be notified when it is ready to install.',
        buttons: ['OK']
      });
    }
  });

  autoUpdater.on('update-not-available', (info) => {
    console.log('[AutoUpdater] Update not available. Current version is up to date.');
    if (isManualCheck && mainWindow && !mainWindow.isDestroyed()) {
      dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: 'No Updates Found',
        message: `LibreChat Desktop is up to date.`,
        detail: `You are running version ${app.getVersion()}.`,
        buttons: ['OK']
      });
      isManualCheck = false;
    }
  });

  autoUpdater.on('download-progress', (progressObj) => {
    const percent = Math.floor(progressObj.percent);
    console.log(`[AutoUpdater] Download speed: ${progressObj.bytesPerSecond} - Downloaded ${percent}%`);
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.setProgressBar(progressObj.percent / 100);
    }
  });

  autoUpdater.on('update-downloaded', (info) => {
    console.log('[AutoUpdater] Update downloaded:', info.version);
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.setProgressBar(-1);
      dialog.showMessageBox(mainWindow, {
        type: 'info',
        title: 'Update Ready to Install',
        message: `LibreChat Desktop v${info.version} has been downloaded.`,
        detail: 'Restart the application now to apply the update, or continue and it will apply next time you launch.',
        buttons: ['Restart and Install', 'Later'],
        defaultId: 0,
        cancelId: 1
      }).then(({ response }) => {
        if (response === 0) {
          autoUpdater.quitAndInstall();
        }
      });
    }
  });

  autoUpdater.on('error', (err) => {
    console.error('[AutoUpdater] Error during update:', err == null ? 'unknown' : (err.stack || err).toString());
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.setProgressBar(-1);
    }
    if (isManualCheck && mainWindow && !mainWindow.isDestroyed()) {
      dialog.showMessageBox(mainWindow, {
        type: 'error',
        title: 'Update Check Failed',
        message: 'Unable to check for updates.',
        detail: err.message || 'Please check your internet connection or try again later.',
        buttons: ['OK']
      });
      isManualCheck = false;
    }
  });

  // Background check on launch if packaged
  if (app.isPackaged) {
    setTimeout(() => {
      autoUpdater.checkForUpdatesAndNotify().catch((err) => {
        console.warn('[AutoUpdater] Initial check error:', err.message);
      });
    }, 5000);
  }
}

function checkForUpdatesManual(mainWindow) {
  if (updateCheckInProgress) return;
  isManualCheck = true;
  updateCheckInProgress = true;

  if (!app.isPackaged) {
    dialog.showMessageBox(mainWindow, {
      type: 'info',
      title: 'Development Mode',
      message: 'Automatic updates are active in packaged production builds.',
      detail: `Current development version: ${app.getVersion()}`,
      buttons: ['OK']
    });
    updateCheckInProgress = false;
    isManualCheck = false;
    return;
  }

  autoUpdater.checkForUpdates().finally(() => {
    updateCheckInProgress = false;
  });
}

module.exports = {
  initAutoUpdater,
  checkForUpdatesManual
};
