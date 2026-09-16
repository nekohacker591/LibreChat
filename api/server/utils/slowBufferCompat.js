/**
 * Node 26 removed the long-deprecated `buffer.SlowBuffer`, and the `jsonwebtoken` chain
 * (`jws` -> `jwa` -> `buffer-equal-constant-time`) reads `require('buffer').SlowBuffer` at
 * module scope, so loading that chain on Node 26 dies with
 * `Cannot read properties of undefined (reading 'prototype')` before the server starts.
 * Aliasing the constructor keeps the chain loadable; `SlowBuffer` and `Buffer` have been
 * interchangeable for every allocation the remaining users make.
 *
 * Must run before anything that requires `jsonwebtoken`, which is why the server entry and
 * the desktop launcher require this file first.
 */
const buffer = require('buffer');

if (buffer.SlowBuffer == null) {
  buffer.SlowBuffer = buffer.Buffer;
}
