const path = require('path');
const fs = require('fs');
const net = require('net');
const os = require('os');
const { MongoMemoryServer } = require('mongodb-memory-server');
const {
  loadOrCreateCredentials,
  applyCredentialsToEnv,
  parseEnvFile,
} = require('../../scripts/lib/credential-store');
const { reconcileCredentialMetadata } = require('../../scripts/lib/credential-metadata');

const rootDir = path.resolve(__dirname, '..');
const repoRoot = path.resolve(__dirname, '..', '..');

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
  const appData =
    process.env.APPDATA ||
    (process.platform === 'darwin'
      ? path.join(os.homedir(), 'Library', 'Preferences')
      : path.join(os.homedir(), '.local', 'share'));
  const target = path.join(appData, 'LibreChat');
  if (!fs.existsSync(target)) {
    fs.mkdirSync(target, { recursive: true });
  }
  return target;
}

function ensureEnv() {
  const envPath = path.join(rootDir, '.env');
  const examplePath = path.join(rootDir, '.env.example');

  let content = '';
  if (fs.existsSync(envPath)) {
    content = fs.readFileSync(envPath, 'utf8');
  } else if (fs.existsSync(examplePath)) {
    content = fs.readFileSync(examplePath, 'utf8');
  }

  /** Non-secret defaults only; credentials live in the app credential store. */
  const defaults = {
    HOST: '0.0.0.0',
    PORT: '3080',
    MONGO_URI: 'mongodb://127.0.0.1:27017/LibreChat',
    DOMAIN_CLIENT: 'http://localhost:3080',
    DOMAIN_SERVER: 'http://localhost:3080',
    NO_INDEX: 'true',
    LOCAL_USER: 'true',
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

  const fileValues = parseEnvFile(envPath);
  for (const [key, fallback] of Object.entries(defaults)) {
    if (process.env[key]) {
      continue;
    }
    process.env[key] = fileValues[key] || fallback;
  }
}

/**
 * Loads the stable app credentials and injects them into the process before
 * the backend starts. After a one-time import from `.env`, the store is the
 * single source of truth, so Set Key entries and sessions survive restarts.
 */
function loadAppCredentials() {
  const result = loadOrCreateCredentials({
    appDataDir: getAppDataDir(),
    legacyEnvPaths: [path.join(rootDir, '.env'), path.join(repoRoot, '.env')],
  });

  const overridden = applyCredentialsToEnv(result);

  if (result.imported.length > 0) {
    console.log(
      `[start-local] Imported ${result.imported.join(', ')} from .env into the app credential store (one-time).`,
    );
  }
  if (result.generated.length > 0) {
    console.log(
      `[start-local] Generated ${result.generated.join(', ')} into the app credential store.`,
    );
  }
  if (overridden.length > 0) {
    console.log(
      `[start-local] Ignoring environment values for ${overridden.join(', ')}; using the app credential store.`,
    );
  }
  console.log(`[start-local] Credential store: ${result.filePath}`);
  return result;
}

async function main() {
  console.log('[start-local] Preparing LibreChat environment...');
  ensureEnv();
  const credentials = loadAppCredentials();

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
        storageEngine: 'wiredTiger',
      },
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

  // Align the database credential marker with the app store, but only when the
  // stored encrypted records actually decrypt with these credentials.
  await reconcileCredentialMetadata({
    values: credentials.values,
    mongoUri: process.env.MONGO_URI || 'mongodb://127.0.0.1:27017/LibreChat',
  });

  console.log('[start-local] Starting LibreChat backend server...');
  require('../../api/server/index.js');
}

if (require.main === module) {
  main().catch((err) => {
    console.error('[start-local] Fatal startup error:', err);
    process.exit(1);
  });
}

module.exports = { main };
