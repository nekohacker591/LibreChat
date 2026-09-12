const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getConfig: () => ipcRenderer.invoke('get-config'),
  saveServerUrl: (url) => ipcRenderer.invoke('save-server-url', url),
  testConnection: (url) => ipcRenderer.invoke('test-connection', url),
  onStatusUpdate: (callback) => ipcRenderer.on('backend-status', (_event, value) => callback(value)),
  platform: process.platform
});
