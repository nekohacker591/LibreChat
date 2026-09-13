'use strict';

const { test, beforeEach } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
  CREDENTIAL_NAMES,
  isValidCredential,
  loadOrCreateCredentials,
  applyCredentialsToEnv,
  parseEnvFile,
} = require('./credential-store');

const valid = {
  CREDS_KEY: 'a'.repeat(64),
  CREDS_IV: 'b'.repeat(32),
  JWT_SECRET: 'c'.repeat(64),
  JWT_REFRESH_SECRET: 'd'.repeat(64),
};

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'librechat-creds-'));
}

function writeEnvFile(filePath, values) {
  fs.writeFileSync(
    filePath,
    Object.entries(values)
      .map(([key, value]) => `${key}=${value}`)
      .join('\n'),
  );
}

beforeEach(() => {
  for (const name of CREDENTIAL_NAMES) {
    delete process.env[name];
  }
});

test('generates and persists all credentials when nothing exists', () => {
  const dir = makeTempDir();
  const result = loadOrCreateCredentials({
    storePath: path.join(dir, 'credentials.json'),
    legacyEnvPaths: [],
  });

  assert.equal(result.created, true);
  assert.deepEqual(result.generated.slice().sort(), CREDENTIAL_NAMES.slice().sort());
  assert.ok(fs.existsSync(result.filePath));
  for (const name of CREDENTIAL_NAMES) {
    assert.ok(isValidCredential(name, result.values[name]), `${name} should be valid`);
  }
});

test('keeps the same credentials across reloads', () => {
  const dir = makeTempDir();
  const storePath = path.join(dir, 'credentials.json');
  const first = loadOrCreateCredentials({ storePath, legacyEnvPaths: [] });
  const second = loadOrCreateCredentials({ storePath, legacyEnvPaths: [] });

  assert.equal(second.created, false);
  assert.deepEqual(second.values, first.values);
});

test('imports legacy credentials once and ignores later legacy changes', () => {
  const dir = makeTempDir();
  const envPath = path.join(dir, '.env');
  const storePath = path.join(dir, 'credentials.json');
  writeEnvFile(envPath, valid);

  const first = loadOrCreateCredentials({ storePath, legacyEnvPaths: [envPath] });
  assert.deepEqual(first.values, valid);
  assert.deepEqual(first.imported.slice().sort(), CREDENTIAL_NAMES.slice().sort());

  writeEnvFile(
    envPath,
    Object.fromEntries(
      Object.entries(valid).map(([key, value]) => [key, 'f'.repeat(value.length)]),
    ),
  );
  const second = loadOrCreateCredentials({ storePath, legacyEnvPaths: [envPath] });
  assert.deepEqual(second.values, valid);
  assert.deepEqual(second.imported, []);
});

test('replaces invalid stored values while keeping valid ones', () => {
  const dir = makeTempDir();
  const storePath = path.join(dir, 'credentials.json');
  fs.writeFileSync(
    storePath,
    JSON.stringify({
      version: 1,
      credentials: {
        CREDS_KEY: 'not-hex',
        CREDS_IV: valid.CREDS_IV,
        JWT_SECRET: valid.JWT_SECRET,
        JWT_REFRESH_SECRET: valid.JWT_REFRESH_SECRET,
      },
    }),
  );

  const result = loadOrCreateCredentials({ storePath, legacyEnvPaths: [] });
  assert.ok(isValidCredential('CREDS_KEY', result.values.CREDS_KEY));
  assert.equal(result.values.CREDS_IV, valid.CREDS_IV);
  assert.deepEqual(result.generated, ['CREDS_KEY']);
});

test('parses quoted, unquoted and commented env values', () => {
  const dir = makeTempDir();
  const envPath = path.join(dir, '.env');
  fs.writeFileSync(
    envPath,
    ['# comment', 'CREDS_KEY="abc"', "CREDS_IV='def'", 'JWT_SECRET = spaced '].join('\n'),
  );

  assert.deepEqual(parseEnvFile(envPath), {
    CREDS_KEY: 'abc',
    CREDS_IV: 'def',
    JWT_SECRET: 'spaced',
  });
});

test('applyCredentialsToEnv overrides environment values and reports them', () => {
  const env = { CREDS_KEY: 'stale', JWT_SECRET: 'stale' };
  const overridden = applyCredentialsToEnv({ values: valid }, env);

  assert.deepEqual(overridden.slice().sort(), ['CREDS_KEY', 'JWT_SECRET']);
  for (const name of CREDENTIAL_NAMES) {
    assert.equal(env[name], valid[name]);
  }
});
