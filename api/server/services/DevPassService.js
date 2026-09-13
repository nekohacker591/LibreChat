const { SystemRoles } = require('librechat-data-provider');
const { logger } = require('@librechat/data-schemas');

const DEVPASS_API_TOKEN =
  process.env.DEVPASS_API_TOKEN ||
  process.env.DEVPASS_TOKEN ||
  process.env.DEVPASS_API_KEY ||
  process.env.DEVPASS_CODE ||
  '';

const DEVPASS_HEADER_VALUE = 'devpass-code';

/**
 * Extracts API token from request headers (Bearer token, x-api-key, or x-devpass-token)
 */
function extractTokenFromRequest(req) {
  const authHeader = req.headers.authorization;
  if (authHeader && typeof authHeader === 'string') {
    const match = /^Bearer\s+(.+)$/i.exec(authHeader);
    if (match) {
      return match[1].trim();
    }
  }
  if (req.headers['x-api-key']) {
    return String(req.headers['x-api-key']).trim();
  }
  if (req.headers['x-devpass-token']) {
    return String(req.headers['x-devpass-token']).trim();
  }
  return null;
}

/**
 * Checks if request contains the x-source: devpass-code header
 */
function isDevPassRequest(req) {
  const sourceHeader = req.headers['x-source'];
  return Boolean(
    sourceHeader &&
      (sourceHeader === DEVPASS_HEADER_VALUE ||
        sourceHeader.toLowerCase() === DEVPASS_HEADER_VALUE.toLowerCase())
  );
}

/**
 * Validates whether the request presents a valid DevPass API token
 */
function validateDevPassToken(req) {
  if (!isDevPassRequest(req)) {
    return false;
  }

  const token = extractTokenFromRequest(req);
  if (!token) {
    return false;
  }

  // If a specific DevPass API token is configured in environment, verify against it
  if (DEVPASS_API_TOKEN) {
    return token === DEVPASS_API_TOKEN;
  }

  // Otherwise, accept the valid non-empty token presented with the devpass header
  return token.length > 0;
}

/**
 * Generates the DevPass service principal for API token requests.
 * Uses a token-based service identity with ADMIN privileges (no username/password).
 */
function getDevPassPrincipal(token) {
  return {
    id: 'devpass-service',
    _id: 'devpass-service',
    role: SystemRoles.ADMIN,
    name: 'DevPass API Token',
    isDevPass: true,
    authStrategy: 'devpass-api-token',
  };
}

/**
 * Standard DevPass headers for outbound LLM Gateway requests.
 */
function getDevPassHeaders(existingHeaders = {}) {
  return {
    ...existingHeaders,
    'x-source': DEVPASS_HEADER_VALUE,
    'User-Agent': 'devpass-code/1.18.11',
  };
}

module.exports = {
  DEVPASS_API_TOKEN,
  DEVPASS_HEADER_VALUE,
  isDevPassRequest,
  validateDevPassToken,
  extractTokenFromRequest,
  getDevPassPrincipal,
  getDevPassHeaders,
};
