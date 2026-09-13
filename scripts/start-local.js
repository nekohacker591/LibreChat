const path = require('path');
const fs = require('fs');
const net = require('net');
const crypto = require('crypto');
const os = require('os');
const { MongoMemoryServer } = require('mongodb-memory-server');

function isPortInUse(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', (err) => resolve(err.code === 'EADDRINUSE'));
    server.once('listening', () => {
      server.close();
      resolve(false);
    });
    server.listen(port, '127.0.0.1');
  });
}

function getAppDataDir() {
  const appData = process.env.APPDATA || (process.platform === 'darwin' ? path.join(os.homedir(), 'Library', 'Preferences') : path.join(os.homedir(), '.local', 'share'));
  const target = path.join(appData, 'LibreChat');
  if (!fs.existsSync(target)) {
    fs.mkdirSync(target, { recursive: true });
  }
  return target;
}

function ensureEnv() {
  const rootDir = path.resolve(__dirname, '..');
  const envPath = path.join(rootDir, '.env');
  const examplePath = path.join(rootDir, '.env.example');

  let content = '';
  if (fs.existsSync(envPath)) {
    content = fs.readFileSync(envPath, 'utf8');
  } else if (fs.existsSync(examplePath)) {
    content = fs.readFileSync(examplePath, 'utf8');
  }

  const genHex = (n) => crypto.randomBytes(n).toString('hex');
  const defaults = {
    HOST: '0.0.0.0',
    PORT: '3080',
    MONGO_URI: 'mongodb://127.0.0.1:27017/LibreChat',
    DOMAIN_CLIENT: 'http://localhost:3080',
    DOMAIN_SERVER: 'http://localhost:3080',
    JWT_SECRET: genHex(32),
    JWT_REFRESH_SECRET: genHex(32),
    CREDS_KEY: genHex(32),
    CREDS_IV: genHex(16),
    NO_INDEX: 'true',
    LOCAL_USER: 'true'
  };

  let modified = false;
  for (const [key, val] of Object.entries(defaults)) {
    const lineRegex = new RegExp(`^${key}=(.*)$`, 'm');
    const match = content.match(lineRegex);
    if (!match) {
      content += `\n${key}=${val}`;
      modified = true;
    } else if (!match[1].trim()) {
      content = content.replace(lineRegex, `${key}=${val}`);
      modified = true;
    }
  }

  if (modified || !fs.existsSync(envPath)) {
    fs.writeFileSync(envPath, content, 'utf8');
    console.log('[start-local] .env file configured.');
  }

  // Also apply to process.env
  for (const [k, v] of Object.entries(defaults)) {
    if (!process.env[k]) {
      process.env[k] = v;
    }
  }
}

async function main() {
  console.log('[start-local] Preparing LibreChat environment...');
  ensureEnv();

  const mongoInUse = await isPortInUse(27017);
  let mongoServer = null;

  if (mongoInUse) {
    console.log('[start-local] Existing MongoDB detected on port 27017.');
  } else {
    console.log('[start-local] Starting persistent embedded MongoDB on port 27017...');
    const dbDir = path.join(getAppDataDir(), 'database');
    if (!fs.existsSync(dbDir)) {
      fs.mkdirSync(dbDir, { recursive: true });
    }

    mongoServer = await MongoMemoryServer.create({
      instance: {
        port: 27017,
        dbPath: dbDir,
        storageEngine: 'wiredTiger'
      }
    });
    console.log('[start-local] Embedded database ready at:', mongoServer.getUri());
  }

  const cleanExit = async () => {
    console.log('\n[start-local] Shutting down...');
    if (mongoServer) {
      await mongoServer.stop();
    }
    process.exit(0);
  };

  process.on('SIGINT', cleanExit);
  process.on('SIGTERM', cleanExit);

  console.log('[start-local] Starting LibreChat backend server...');
  require('../api/server/index.js');
}

if (require.main === module) {
  main().catch((err) => {
    console.error('[start-local] Fatal startup error:', err);
    process.exit(1);
  });
}

module.exports = { main };
