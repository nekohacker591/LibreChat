const { spawn, exec } = require('child_process');
const path = require('path');
const fs = require('fs');
const http = require('http');
const os = require('os');

let backendProcess = null;

function checkHttpReady(urlStr, timeoutMs = 2000) {
  return new Promise((resolve) => {
    try {
      const u = new URL(urlStr);
      const req = http.get({
        hostname: u.hostname,
        port: u.port || 3080,
        path: '/',
        timeout: timeoutMs
      }, (res) => {
        resolve(res.statusCode < 500);
      });
      req.on('error', () => resolve(false));
      req.on('timeout', () => {
        req.destroy();
        resolve(false);
      });
    } catch {
      resolve(false);
    }
  });
}

function getLocalIpAddress() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return '127.0.0.1';
}

function getRepoRoot() {
  // 1. Search upwards from __dirname
  let curr = __dirname;
  for (let i = 0; i < 10; i++) {
    if (fs.existsSync(path.join(curr, 'scripts', 'start-local.js')) && fs.existsSync(path.join(curr, 'api', 'server', 'index.js'))) {
      return curr;
    }
    const parent = path.dirname(curr);
    if (parent === curr) break;
    curr = parent;
  }

  // 2. Search upwards from process.execPath (e.g. electron/dist/win-unpacked/LibreChat.exe)
  curr = path.dirname(process.execPath);
  for (let i = 0; i < 10; i++) {
    if (fs.existsSync(path.join(curr, 'scripts', 'start-local.js')) && fs.existsSync(path.join(curr, 'api', 'server', 'index.js'))) {
      return curr;
    }
    const parent = path.dirname(curr);
    if (parent === curr) break;
    curr = parent;
  }

  // 3. Check known standard locations
  const knownCandidates = [
    path.join(os.homedir(), 'Downloads', 'LibreChat'),
    path.resolve(__dirname, '..', '..', '..'),
    path.resolve(__dirname, '..'),
    path.join(process.resourcesPath, 'app')
  ];

  for (const cand of knownCandidates) {
    if (fs.existsSync(path.join(cand, 'scripts', 'start-local.js')) && fs.existsSync(path.join(cand, 'api', 'server', 'index.js'))) {
      return cand;
    }
  }

  // Fallback to user downloads if folder exists
  const userDownloads = path.join(os.homedir(), 'Downloads', 'LibreChat');
  if (fs.existsSync(userDownloads)) {
    return userDownloads;
  }

  return path.resolve(__dirname, '..');
}

async function startBackend({ userDataPath, onStatus }) {
  const isAlreadyRunning = await checkHttpReady('http://127.0.0.1:3080');
  if (isAlreadyRunning) {
    if (onStatus) onStatus('LibreChat backend is already active!');
    console.log('[BackendManager] LibreChat backend is already running on port 3080.');
    return { success: true, alreadyRunning: true };
  }

  const repoRoot = getRepoRoot();
  console.log('[BackendManager] Resolved repo root:', repoRoot);

  let startScript = path.join(repoRoot, 'scripts', 'start-local.js');
  if (!fs.existsSync(startScript)) {
    const bundledScript = path.join(__dirname, 'scripts', 'start-local.js');
    if (fs.existsSync(bundledScript)) {
      startScript = bundledScript;
    }
  }

  if (!fs.existsSync(startScript)) {
    console.warn('[BackendManager] start-local script not found at:', startScript);
    return { success: false, error: 'Start script not found at ' + startScript };
  }

  if (onStatus) onStatus('Starting database and backend server...');
  console.log('[BackendManager] Launching backend supervisor:', startScript);

  const logPath = path.join(userDataPath, 'backend.log');
  const logStream = fs.createWriteStream(logPath, { flags: 'a' });

  let executable = 'node';
  const env = {
    ...process.env,
    PORT: '3080',
    HOST: '0.0.0.0',
    NODE_ENV: 'production'
  };

  try {
    backendProcess = spawn(executable, [startScript], {
      cwd: repoRoot,
      env: env,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true
    });
  } catch (err) {
    console.warn('[BackendManager] System node spawn failed, falling back to Electron runner:', err.message);
    executable = process.execPath;
    env.ELECTRON_RUN_AS_NODE = '1';
    backendProcess = spawn(executable, [startScript], {
      cwd: repoRoot,
      env: env,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true
    });
  }

  backendProcess.stdout.pipe(logStream);
  backendProcess.stderr.pipe(logStream);

  backendProcess.on('error', (err) => {
    console.error('[BackendManager] Backend process error:', err);
  });

  backendProcess.on('exit', (code, signal) => {
    console.log(`[BackendManager] Backend exited with code ${code} signal ${signal}`);
    backendProcess = null;
  });

  // Poll until HTTP server is ready (up to 45 seconds)
  const maxAttempts = 90;
  for (let i = 1; i <= maxAttempts; i++) {
    if (onStatus && i % 4 === 0) {
      onStatus(`Initializing services and database (${Math.min(95, Math.floor((i / maxAttempts) * 100))}%)`);
    }

    const ready = await checkHttpReady('http://127.0.0.1:3080');
    if (ready) {
      if (onStatus) onStatus('Ready! Opening LibreChat...');
      console.log('[BackendManager] Server is ready on http://127.0.0.1:3080');
      return { success: true, startedByUs: true };
    }

    await new Promise((r) => setTimeout(r, 500));
  }

  return { success: false, error: 'Server startup timed out. Check backend.log for details.' };
}

function stopBackend() {
  if (backendProcess && backendProcess.pid) {
    console.log('[BackendManager] Stopping backend PID:', backendProcess.pid);
    try {
      if (process.platform === 'win32') {
        exec(`taskkill /pid ${backendProcess.pid} /T /F`);
      } else {
        backendProcess.kill('SIGTERM');
      }
    } catch (err) {
      console.warn('[BackendManager] Error terminating backend:', err.message);
    }
    backendProcess = null;
  }
}

module.exports = {
  startBackend,
  stopBackend,
  getLocalIpAddress,
  checkHttpReady
};
