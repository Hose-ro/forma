import assert from 'node:assert/strict';
import test from 'node:test';
import { safeRedirect } from '../src/lib/safe-redirect.ts';

test('post-login redirects preserve local routes', () => {
  for (const path of ['/admin', '/shop?category=audio', '/account/orders/123#items']) {
    assert.equal(safeRedirect(path), path);
  }
});

test('post-login redirects reject external authorities and browser normalization tricks', () => {
  for (const path of ['https://evil.example', '//evil.example', '/\\evil.example', '/%5cevil.example',
    '/%2fevil.example', '/\nevil.example', 'javascript:alert(1)', '/%00evil']) {
    assert.equal(safeRedirect(path), null, path);
  }
});
