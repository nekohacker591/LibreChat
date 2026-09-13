const path = require('path');
require('module-alias')({ base: path.resolve(__dirname, 'api') });

process.env.LOCAL_USER = 'true';

const { isLocalUserEnabled, sanitizeLocalUser } = require('./api/server/services/LocalUserService');

console.log('--- Test 1: isLocalUserEnabled ---');
const enabled = isLocalUserEnabled();
console.log('isLocalUserEnabled:', enabled);
if (!enabled) {
  console.error('FAIL: isLocalUserEnabled should be true');
  process.exit(1);
}

console.log('--- Test 2: sanitizeLocalUser ---');
const mockUser = {
  _id: '64f8a1234567890abcdef123',
  name: 'Local User',
  email: 'user@librechat.local',
  password: 'hashed-password-secret',
  totpSecret: 'secret-totp',
  role: 'ADMIN'
};
const sanitized = sanitizeLocalUser(mockUser);
console.log('Sanitized user:', sanitized);
if (sanitized.password || sanitized.totpSecret) {
  console.error('FAIL: password/totpSecret was not stripped');
  process.exit(1);
}
if (sanitized.id !== '64f8a1234567890abcdef123') {
  console.error('FAIL: id was not properly derived from _id');
  process.exit(1);
}

console.log('SUCCESS: LocalUserService unit tests passed!');
process.exit(0);
