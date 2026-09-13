'use strict';

/**
 * Credential-drift reconciliation for the local launcher.
 *
 * `librechatCredentialMetadata` records fingerprints (hashes only) of the
 * credentials a database was created with, and the backend warns on mismatch.
 * Blindly overwriting that marker hides real drift, so this module only
 * rewrites it after sampling stored encrypted records and proving they decrypt
 * with the active credential store. Otherwise the marker is left intact and an
 * actionable warning is logged.
 */

const crypto = require('crypto');
const { MongoClient } = require('mongodb');
const { fingerprintCredentials } = require('./credential-store');

const METADATA_COLLECTION = 'librechatCredentialMetadata';
const METADATA_ID = 'primary';

/** Known encrypted credential fields, sampled before trusting the active credentials. */
const ENCRYPTED_SAMPLE_FIELDS = [
  { collection: 'keys', field: 'value', format: 'v1' },
  { collection: 'pluginauths', field: 'value', format: 'v1' },
  { collection: 'skillsynccredentials', field: 'encryptedToken', format: 'v2' },
];

const MAX_SAMPLES_PER_COLLECTION = 25;

async function decryptV1(value, cryptoKey, iv) {
  const { subtle } = crypto.webcrypto;
  const buffer = await subtle.decrypt(
    { name: 'AES-CBC', iv },
    cryptoKey,
    Buffer.from(value, 'hex'),
  );
  return new TextDecoder().decode(buffer);
}

async function decryptV2(value, cryptoKey) {
  const { subtle } = crypto.webcrypto;
  const separator = value.indexOf(':');
  const ivHex = value.slice(0, separator);
  const cipherHex = value.slice(separator + 1);
  const buffer = await subtle.decrypt(
    { name: 'AES-CBC', iv: Buffer.from(ivHex, 'hex') },
    cryptoKey,
    Buffer.from(cipherHex, 'hex'),
  );
  return new TextDecoder().decode(buffer);
}

/**
 * Attempts to decrypt one sample per encrypted record. Returns the list of
 * `collection.field` paths that could not be decrypted with these credentials.
 */
async function verifyStoredCredentials(db, values) {
  const key = Buffer.from(values.CREDS_KEY, 'hex');
  const iv = Buffer.from(values.CREDS_IV, 'hex');
  const { subtle } = crypto.webcrypto;
  const cryptoKey = await subtle.importKey('raw', key, { name: 'AES-CBC' }, false, ['decrypt']);

  const failures = [];
  for (const sample of ENCRYPTED_SAMPLE_FIELDS) {
    let docs;
    try {
      docs = await db
        .collection(sample.collection)
        .find({ [sample.field]: { $type: 'string' } })
        .limit(MAX_SAMPLES_PER_COLLECTION)
        .toArray();
    } catch {
      continue;
    }
    for (const doc of docs) {
      const value = doc[sample.field];
      if (typeof value !== 'string' || value.length < 16) {
        continue;
      }
      try {
        if (sample.format === 'v2') {
          await decryptV2(value, cryptoKey);
        } else {
          await decryptV1(value, cryptoKey, iv);
        }
      } catch {
        failures.push(`${sample.collection}.${sample.field}`);
      }
    }
  }
  return [...new Set(failures)];
}

/**
 * Aligns the database credential marker with the active store, but only when
 * stored encrypted records actually decrypt with it (or none exist yet).
 */
async function reconcileCredentialMetadata({ values, mongoUri, logger = console }) {
  const client = new MongoClient(mongoUri, { serverSelectionTimeoutMS: 5000 });
  try {
    await client.connect();
    const db = client.db();
    const collection = db.collection(METADATA_COLLECTION);

    const fingerprints = fingerprintCredentials(values);
    const existing = await collection.findOne({ _id: METADATA_ID });
    const mismatched = Object.keys(fingerprints).filter(
      (name) =>
        !existing || !existing.fingerprints || existing.fingerprints[name] !== fingerprints[name],
    );

    if (mismatched.length === 0) {
      return { status: 'unchanged' };
    }

    const failures = await verifyStoredCredentials(db, values);
    if (failures.length > 0) {
      logger.warn(
        `[start-local] ${failures.length} stored credential field(s) cannot be decrypted with the current app credentials: ${failures.join(', ')}`,
      );
      logger.warn(
        '[start-local] Left the database credential marker untouched. Re-enter the affected API keys via Set Key; do not rotate credentials.',
      );
      return { status: 'mismatch', failures };
    }

    await collection.updateOne(
      { _id: METADATA_ID },
      {
        $set: { fingerprints },
        $setOnInsert: { createdAt: new Date() },
      },
      { upsert: true },
    );
    logger.log('[start-local] Credential marker aligned with the app credential store.');
    return { status: 'aligned', mismatched };
  } catch (error) {
    logger.warn('[start-local] Could not verify stored credentials:', error.message);
    return { status: 'skipped', error: error.message };
  } finally {
    await client.close().catch(() => {
      /* connection may have failed before opening */
    });
  }
}

module.exports = {
  METADATA_COLLECTION,
  METADATA_ID,
  ENCRYPTED_SAMPLE_FIELDS,
  verifyStoredCredentials,
  reconcileCredentialMetadata,
};
