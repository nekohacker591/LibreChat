const bcrypt = require('bcryptjs');
const { SystemRoles } = require('librechat-data-provider');
const { logger } = require('@librechat/data-schemas');
const { findUser, createUser, updateUser } = require('~/models');

const LOCAL_USER_EMAIL = 'user@librechat.local';
const LOCAL_USER_USERNAME = 'user';
const LOCAL_USER_NAME = 'Local User';

/**
 * Determines whether local user mode is active.
 * Enabled by default in electron desktop wrapper or when LOCAL_USER=true.
 */
function isLocalUserEnabled() {
  const envVal = process.env.LOCAL_USER;
  if (envVal === 'true' || envVal === '1') {
    return true;
  }
  if (process.env.ELECTRON_RUN_AS_NODE === '1') {
    return true;
  }
  return false;
}

let cachedLocalUser = null;

/**
 * Retrieves the local user document from MongoDB, creating it if it doesn't exist.
 */
async function getOrCreateLocalUser() {
  if (cachedLocalUser && cachedLocalUser._id) {
    return cachedLocalUser;
  }

  try {
    let user = await findUser({ email: LOCAL_USER_EMAIL });
    if (!user) {
      user = await findUser({ username: LOCAL_USER_USERNAME });
    }

    if (!user) {
      const salt = bcrypt.genSaltSync(10);
      const passwordHash = bcrypt.hashSync('local-user-secret-' + Date.now(), salt);
      const newUserData = {
        email: LOCAL_USER_EMAIL,
        username: LOCAL_USER_USERNAME,
        name: LOCAL_USER_NAME,
        role: SystemRoles.ADMIN,
        emailVerified: true,
        provider: 'local',
        password: passwordHash,
        avatar: null,
      };

      const created = await createUser(newUserData, undefined, true, true);
      const userId = created?._id || created;
      await updateUser(userId, { emailVerified: true, role: SystemRoles.ADMIN });
      user = await findUser({ _id: userId });
      logger.info(`[LocalUser] Created and initialized Local User in database: ${userId}`);
    }

    if (user) {
      if (typeof user.toObject === 'function') {
        user = user.toObject();
      }
      user.id = (user._id || user.id).toString();
      cachedLocalUser = user;
    }

    return user;
  } catch (err) {
    logger.error('[LocalUser] Error getting or creating local user:', err);
    return null;
  }
}

/**
 * Sanitizes a user object for authentication responses.
 */
function sanitizeLocalUser(user) {
  if (!user) return null;
  const source = (typeof user.toObject === 'function' ? user.toObject() : user) || {};
  const {
    password: _pw,
    __v: _v,
    totpSecret: _ts,
    backupCodes: _bc,
    federatedTokens: _ft,
    ...safeUser
  } = source;
  if (safeUser._id && !safeUser.id) {
    safeUser.id = safeUser._id.toString();
  }
  return safeUser;
}

module.exports = {
  LOCAL_USER_EMAIL,
  LOCAL_USER_USERNAME,
  LOCAL_USER_NAME,
  isLocalUserEnabled,
  getOrCreateLocalUser,
  sanitizeLocalUser,
};
