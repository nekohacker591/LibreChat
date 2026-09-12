const { validateDevPassToken, extractTokenFromRequest, getDevPassPrincipal, getDevPassHeaders } = require('./api/server/services/DevPassService');
const { resolveHeaders } = require('./packages/api/dist/index.cjs');

console.log('--- Test 1: DevPass Headers ---');
const headers = getDevPassHeaders({ 'custom-header': 'value' });
console.log('DevPass Outbound Headers:', headers);
if (headers['x-source'] !== 'devpass-code') throw new Error('Header x-source mismatch');

console.log('--- Test 2: Inbound API Token Validation ---');
const reqWithToken = {
  headers: {
    'x-source': 'devpass-code',
    'authorization': 'Bearer devpass-secret-token-12345'
  }
};
const isValid = validateDevPassToken(reqWithToken);
console.log('Valid with Bearer token:', isValid);
if (!isValid) throw new Error('Failed to validate Bearer token');

const principal = getDevPassPrincipal('devpass-secret-token-12345');
console.log('Principal:', principal);
if (principal.authStrategy !== 'devpass-api-token') throw new Error('Invalid principal auth strategy');

console.log('--- Test 3: Outbound resolveHeaders check ---');
process.env.DEVPASS_API_TOKEN = 'test-token';
const resolved = resolveHeaders({ headers: { 'content-type': 'application/json' } });
console.log('Resolved Outbound Headers:', resolved);
if (resolved['x-source'] !== 'devpass-code') throw new Error('resolveHeaders did not inject x-source: devpass-code');

console.log('ALL DEVPASS API TOKEN TESTS PASSED SUCCESSFULLY!');
