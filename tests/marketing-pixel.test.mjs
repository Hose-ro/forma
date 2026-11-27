import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { runInNewContext } from 'node:vm';
import { createPixel, publicPage } from '../src/lib/marketing/pixel.ts';

const frameOrigin = 'https://api.example.test';
const parentOrigin = 'https://store.example.test';
const pixelId = 'StagingPixel123';
const frameScript = readFileSync(new URL('../backend/src/main/resources/static/marketing/pixel.js', import.meta.url), 'utf8');

test('browser relay waits for consent and the matching frame, then drops events on revocation', () => {
  const messages = [];
  const frames = [];
  const cookies = [];
  const frameWindow = { postMessage: (message, origin) => messages.push({ message, origin }) };
  let receive;
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;
  globalThis.window = { location: { origin: parentOrigin }, addEventListener: (_, listener) => { receive = listener; } };
  globalThis.document = {
    body: { append: frame => frames.push(frame) },
    createElement: () => ({ contentWindow: frameWindow, sandbox: { add: () => {} } }),
  };

  try {
    const pixel = createPixel(frameOrigin, value => cookies.push(value));
    const page = { type: 'page', path: '/shop' };
    pixel.send(page);
    pixel.disable();
    assert.equal(frames.length, 0);

    pixel.enable(pixelId);
    pixel.enable(pixelId);
    pixel.send(page);
    assert.equal(frames.length, 1);
    assert.equal(frames[0].referrerPolicy, 'no-referrer');
    const ready = { source: 'olvenio-pixel', ready: true, pixel: pixelId };
    receive({ origin: 'https://other.example.test', source: frameWindow, data: ready });
    assert.equal(messages.length, 0);
    receive({ origin: frameOrigin, source: frameWindow, data: ready });
    assert.deepEqual(messages.map(({ message }) => message.type), ['grant', 'page']);
    assert.ok(messages.every(({ origin }) => origin === frameOrigin));
    pixel.disable();
    pixel.send(page);
    receive({ origin: frameOrigin, source: frameWindow, data: { source: 'olvenio-pixel', ttp: 'FixtureTiktokCookie' } });
    assert.deepEqual(messages.map(({ message }) => message.type), ['grant', 'page', 'revoke']);
    assert.deepEqual(cookies, []);
  } finally {
    globalThis.window = originalWindow;
    globalThis.document = originalDocument;
  }
});

test('frame loads the SDK once after consent, keeps event IDs, and revokes a pending SDK', () => {
  const scripts = [];
  const parent = { postMessage() {} };
  let receive;
  const window = { parent, addEventListener: (_, fn) => { receive = fn; } };
  const document = {
    currentScript: { dataset: { parent: parentOrigin, pixel: pixelId } },
    head: { append: script => scripts.push(script) },
    createElement: () => ({}), cookie: '',
  };
  runInNewContext(frameScript, {
    window, document, location: new URL(frameOrigin), URL,
    setInterval: () => 1, clearInterval: () => {},
  });

  function send(data, origin = parentOrigin) {
    receive({ origin, source: parent, data: { source: 'olvenio-store', ...data } });
  }
  send({ type: 'grant' }, 'https://other.example.test');
  send({ type: 'page', path: '/shop' });
  assert.equal(scripts.length, 0);
  send({ type: 'grant' });
  send({ type: 'grant' });
  assert.equal(scripts.length, 1);
  assert.match(scripts[0].src, /sdkid=StagingPixel123/);

  send({ type: 'page', path: '/shop' });
  send({ type: 'page', path: '/shop?q=someone@example.test' });
  send({ type: 'page', path: '/admin' });
  const id = 'a'.repeat(64);
  send({ type: 'event', event: 'Purchase', id, properties: { currency: 'MXN', value: 100 } });

  let queued = JSON.parse(JSON.stringify(window.ttq));
  assert.equal(queued.filter(([method]) => method === 'page').length, 1);
  assert.equal(queued.find(([method]) => method === 'page')[1].url, parentOrigin + '/shop');
  assert.equal(queued.find(([method]) => method === 'track')[3].event_id, id);
  assert.ok(!JSON.stringify(queued).includes('someone@example.test'));
  assert.equal(window.ttq._o[pixelId].historyObserver, false);

  send({ type: 'revoke' });
  send({ type: 'event', event: 'Purchase', id, properties: {} });
  queued = JSON.parse(JSON.stringify(window.ttq));
  assert.deepEqual(queued.map(([method]) => method), ['revokeConsent', 'disableCookie']);
  send({ type: 'grant' });
  assert.equal(scripts.length, 1);
  assert.doesNotThrow(() => scripts[0].onerror());
});

test('only canonical public paths are visible to the Pixel', () => {
  for (const path of ['/', '/shop', '/product/mug-mx', '/policies/cookies']) {
    assert.equal(publicPage(path), path);
  }
  for (const path of ['/admin', '/account/reset', '/checkout/success', '/shop?q=private', '//other.test']) {
    assert.equal(publicPage(path), null);
  }
});
