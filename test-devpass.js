const { validateDevPassToken, extractTokenFromRequest, getDevPassPrincipal, getDevPassHeaders } = require('./api/server/services/DevPassService');
const { resolveHeaders } = require('./packages/api/dist/index.cjs');

console.log('--- Test 1: DevPass Headers ---');
const headers = getDevPassHeaders({ 'custom-header': 'value' });
console.log('DevPass Outbound Headers:', headers);
if (headers['x-source'] !== 'opencode') throw new Error('Header x-source mismatch');
if (headers['User-Agent'] !== 'opencode/1.18.30') throw new Error('Header User-Agent mismatch');

console.log('--- Test 2: Inbound API Token Validation ---');
const reqWithOpenCode = {
  headers: {
    'x-source': 'opencode',
    'authorization': 'Bearer devpass-secret-token-12345'
  }
};
const isValidOpenCode = validateDevPassToken(reqWithOpenCode);
console.log('Valid with opencode header & Bearer token:', isValidOpenCode);
if (!isValidOpenCode) throw new Error('Failed to validate opencode Bearer token');

const reqWithDevPass = {
  headers: {
    'x-source': 'devpass-code',
    'authorization': 'Bearer devpass-secret-token-12345'
  }
};
const isValidDevPass = validateDevPassToken(reqWithDevPass);
console.log('Valid with devpass-code backward-compatibility & Bearer token:', isValidDevPass);
if (!isValidDevPass) throw new Error('Failed to validate devpass-code Bearer token');

const principal = getDevPassPrincipal('devpass-secret-token-12345');
console.log('Principal:', principal);
if (principal.authStrategy !== 'devpass-api-token') throw new Error('Invalid principal auth strategy');

console.log('--- Test 3: Outbound resolveHeaders check ---');
process.env.DEVPASS_API_TOKEN = 'test-token';
const resolved = resolveHeaders({ headers: { 'content-type': 'application/json' } });
console.log('Resolved Outbound Headers:', resolved);
if (resolved['x-source'] !== 'opencode') throw new Error('resolveHeaders did not inject x-source: opencode');
if (resolved['User-Agent'] !== 'opencode/1.18.30') throw new Error('resolveHeaders did not inject User-Agent: opencode/1.18.30');

console.log('ALL DEVPASS API TOKEN TESTS PASSED SUCCESSFULLY!');
