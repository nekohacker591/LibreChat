'use strict';

/**
 * App-managed credential store for the local launcher.
 *
 * The four encryption/signing secrets (CREDS_KEY, CREDS_IV, JWT_SECRET,
 * JWT_REFRESH_SECRET) must stay stable across restarts, or stored API keys
 * (Set Key) and sessions become unreadable. They live in a JSON file under the
 * app data directory and are injected into `process.env` before the backend is
 * required, so `dotenv` (which never overrides existing variables) cannot
 * replace them with `.env` values.
 *
 * On first run the store imports values from an existing `.env` (or the
 * process environment) one time; after that it is the single source of truth
 * and `.env` is no longer consulted for these names.
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

/** Environment variable that overrides the credential store location. */
const STORE_PATH_ENV = 'LIBRECHAT_CREDENTIALS_PATH';

/** Credentials that must stay stable for encrypted data and JWTs to survive restarts. */
const CREDENTIAL_NAMES = ['CREDS_KEY', 'CREDS_IV', 'JWT_SECRET', 'JWT_REFRESH_SECRET'];

const STORE_VERSION = 1;

function isValidCredential(name, value) {
  if (typeof value !== 'string') {
    return false;
  }
  const trimmed = value.trim();
  if (name === 'CREDS_KEY') {
    return /^[0-9a-f]{64}$/i.test(trimmed);
  }
  if (name === 'CREDS_IV') {
    return /^[0-9a-f]{32}$/i.test(trimmed);
  }
  return trimmed.length >= 32;
}

function generateCredential(name) {
  return crypto.randomBytes(name === 'CREDS_IV' ? 16 : 32).toString('hex');
}

function fingerprint(value) {
  return crypto
    .createHash('sha256')
    .update(value ?? '')
    .digest('hex');
}

function fingerprintCredentials(values) {
  return CREDENTIAL_NAMES.reduce((acc, name) => {
    acc[name] = fingerprint(values[name]);
    return acc;
  }, {});
}

function getDefaultAppDataDir() {
  const appData =
    process.env.APPDATA ||
    (process.platform === 'darwin'
      ? path.join(os.homedir(), 'Library', 'Preferences')
      : path.join(os.homedir(), '.local', 'share'));
  return path.join(appData, 'LibreChat');
}

function getDefaultStorePath(appDataDir) {
  const configured = process.env[STORE_PATH_ENV];
  if (configured && configured.trim()) {
    return path.resolve(configured.trim());
  }
  return path.join(appDataDir || getDefaultAppDataDir(), 'credentials.json');
}

/** Minimal KEY=VALUE parser for `.env`-style files; never throws. */
function parseEnvFile(filePath) {
  const values = {};
  let content;
  try {
    content = fs.readFileSync(filePath, 'utf8');
  } catch {
    return values;
  }
  for (const line of content.split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!match) {
      continue;
    }
    let value = match[2];
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
      value = value.slice(1, -1);
    } else if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
      value = value.slice(1, -1);
    }
    values[match[1]] = value;
  }
  return values;
}

function readStore(filePath) {
  try {
    const parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    if (!parsed || typeof parsed !== 'object') {
      return null;
    }
    const credentials =
      parsed.credentials && typeof parsed.credentials === 'object' ? parsed.credentials : parsed;
    return {
      version: typeof parsed.version === 'number' ? parsed.version : 0,
      createdAt: typeof parsed.createdAt === 'string' ? parsed.createdAt : undefined,
      credentials,
    };
  } catch {
    return null;
  }
}

function writeStore(filePath, credentials, createdAt) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const payload = {
    version: STORE_VERSION,
    createdAt: createdAt || new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    credentials,
  };
  const tempPath = `${filePath}.${process.pid}.${crypto.randomUUID()}.tmp`;
  fs.writeFileSync(tempPath, JSON.stringify(payload, null, 2), { mode: 0o600 });
  fs.renameSync(tempPath, filePath);
  try {
    fs.chmodSync(filePath, 0o600);
  } catch {
    /* Windows does not apply POSIX modes; the rename keeps the file private enough. */
  }
}

/** Collect valid values from the process environment, then legacy `.env` files. */
function collectLegacyValues(legacyEnvPaths) {
  const values = {};
  const sources = {};

  for (const name of CREDENTIAL_NAMES) {
    if (isValidCredential(name, process.env[name])) {
      values[name] = process.env[name].trim();
      sources[name] = 'environment';
    }
  }

  for (const envPath of legacyEnvPaths) {
    const parsed = parseEnvFile(envPath);
    for (const name of CREDENTIAL_NAMES) {
      if (values[name]) {
        continue;
      }
      if (isValidCredential(name, parsed[name])) {
        values[name] = parsed[name].trim();
        sources[name] = envPath;
      }
    }
  }

  return { values, sources };
}

/**
 * Loads the credential store, importing legacy values or generating missing
 * ones, and persists the result. Values never leave this module via logs.
 */
function loadOrCreateCredentials(options = {}) {
  const storePath = options.storePath || getDefaultStorePath(options.appDataDir);
  const store = readStore(storePath);
  const legacy = collectLegacyValues(options.legacyEnvPaths || []);

  const values = {};
  const sources = {};
  const imported = [];
  const generated = [];

  for (const name of CREDENTIAL_NAMES) {
    const stored = store && store.credentials ? store.credentials[name] : undefined;
    if (isValidCredential(name, stored)) {
      values[name] = stored.trim();
      sources[name] = 'store';
      continue;
    }
    if (legacy.values[name]) {
      values[name] = legacy.values[name];
      sources[name] = legacy.sources[name];
      imported.push(name);
      continue;
    }
    values[name] = generateCredential(name);
    sources[name] = 'generated';
    generated.push(name);
  }

  const needsWrite =
    !store || store.version !== STORE_VERSION || imported.length > 0 || generated.length > 0;

  if (needsWrite) {
    if (!store && fs.existsSync(storePath)) {
      /** Unreadable/corrupt store: keep it around instead of silently discarding it. */
      try {
        fs.renameSync(storePath, `${storePath}.corrupt-${Date.now()}`);
      } catch {
        /* best effort */
      }
    }
    writeStore(storePath, values, store ? store.createdAt : undefined);
  }

  return {
    filePath: storePath,
    values,
    sources,
    imported,
    generated,
    created: !store,
  };
}

/**
 * Writes the loaded credentials into the environment before the backend loads,
 * so `dotenv` and any stale shell values cannot replace them.
 * Returns the names whose previous environment value was different.
 */
function applyCredentialsToEnv(result, env = process.env) {
  const overridden = [];
  for (const name of CREDENTIAL_NAMES) {
    if (env[name] && env[name] !== result.values[name]) {
      overridden.push(name);
    }
    env[name] = result.values[name];
  }
  return overridden;
}

module.exports = {
  CREDENTIAL_NAMES,
  STORE_PATH_ENV,
  STORE_VERSION,
  isValidCredential,
  generateCredential,
  fingerprint,
  fingerprintCredentials,
  getDefaultAppDataDir,
  getDefaultStorePath,
  parseEnvFile,
  loadOrCreateCredentials,
  applyCredentialsToEnv,
};
