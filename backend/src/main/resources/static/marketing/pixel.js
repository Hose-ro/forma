(() => {
  const { parent: parentOrigin, pixel } = document.currentScript.dataset;
  if (window.parent === window) return;

  const sdkUrl = 'https://analytics.tiktok.com/i18n/pixel/events.js';
  const methods = [
    'page', 'track', 'identify', 'instances', 'debug', 'on', 'off', 'once', 'ready',
    'alias', 'group', 'enableCookie', 'disableCookie', 'holdConsent', 'revokeConsent', 'grantConsent',
  ];
  const events = new Set([
    'ViewContent', 'Search', 'AddToCart', 'AddToWishlist', 'InitiateCheckout', 'PlaceAnOrder', 'CompleteRegistration', 'Purchase',
  ]);
  let sdk;
  let active = false;
  let sdkUnavailable = false;
  let lastTtp;
  let cookieTimer;

  function defer(target, method) {
    target[method] = (...args) => target.push([method, ...args]);
  }

  function load() {
    // The official base-code queue. This document has no storefront DOM, referrer, query, or credentials.
    window.TiktokAnalyticsObject = 'ttq';
    const queue = window.ttq = [];
    queue.methods = methods;
    queue.setAndDefer = defer;
    methods.forEach(method => defer(queue, method));
    queue._i = { [pixel]: Object.assign([], { _u: sdkUrl }) };
    queue._t = { [pixel]: Date.now() };
    queue._o = { [pixel]: { historyObserver: false } };
    queue.instance = id => {
      const instance = queue._i[id] || [];
      methods.forEach(method => defer(instance, method));
      return instance;
    };
    queue.holdConsent();
    const script = document.createElement('script');
    script.async = true;
    script.referrerPolicy = 'no-referrer';
    script.src = `${sdkUrl}?sdkid=${encodeURIComponent(pixel)}&lib=ttq`;
    script.onerror = () => {
      sdkUnavailable = true;
      queue.length = 0;
    };
    document.head.append(script);
    return queue;
  }

  function shareCookie() {
    if (!active) return;
    try {
      const ttp = document.cookie.match(/(?:^|;\s*)_ttp=([A-Za-z0-9._~-]{8,512})(?:;|$)/)?.[1];
      if (ttp && lastTtp !== ttp) {
        lastTtp = ttp;
        window.parent.postMessage({ source: 'olvenio-pixel', ttp }, parentOrigin);
      }
    } catch { /* The browser may block storage in embedded documents. */ }
  }

  function removeCookies() {
    const expires = '; Path=/; Max-Age=0; SameSite=Lax' + (location.protocol === 'https:' ? '; Secure' : '');
    const parentHost = new URL(parentOrigin).hostname;
    for (const name of ['_ttp', '_tt_enable_cookie', 'ttcsid', `ttcsid_${pixel}`]) {
      document.cookie = name + '=' + expires;
      if (location.hostname.endsWith('.' + parentHost)) {
        document.cookie = name + '=' + expires + '; Domain=' + parentHost;
      }
    }
  }

  function safePage(path) {
    return typeof path === 'string' && (
      ['/', '/shop', '/cart', '/checkout', '/account'].includes(path)
      || /^\/product\/[a-z0-9-]{1,180}$/.test(path)
      || /^\/policies\/[a-z0-9-]{1,60}$/.test(path)
    );
  }

  window.addEventListener('message', ({ origin, source, data }) => {
    if (origin !== parentOrigin || source !== window.parent || data?.source !== 'olvenio-store') return;
    try {
      if (data.type === 'revoke') {
        active = false;
        clearInterval(cookieTimer);
        lastTtp = undefined;
        // Clear queued events if the SDK is still waiting on a blocked/slow script.
        if (Array.isArray(window.ttq)) window.ttq.length = 0;
        sdk?.revokeConsent();
        sdk?.disableCookie();
        removeCookies();
        return;
      }
      if (data.type === 'grant' && !active) {
        active = true;
        sdk ??= load();
        sdk = window.ttq;
        sdk.grantConsent();
        sdk.enableCookie();
        shareCookie();
        clearInterval(cookieTimer);
        let attempts = 0;
        cookieTimer = setInterval(() => {
          shareCookie();
          if (++attempts >= 8) clearInterval(cookieTimer);
        }, 500);
        return;
      }
      if (!active || !sdk || sdkUnavailable) return;
      if (data.type === 'page' && safePage(data.path)) {
        // Only sanitized, canonical storefront paths are given to the Pixel.
        sdk.page({ url: parentOrigin + data.path, referrer: '' });
      }
      if (data.type === 'event' && events.has(data.event) && /^[a-f0-9-]{32,64}$/.test(data.id)) {
        sdk.track(data.event, data.properties, { event_id: data.id });
        shareCookie();
      }
    } catch { /* Tracking availability does not affect the storefront. */ }
  });
  window.parent.postMessage({ source: 'olvenio-pixel', ready: true, pixel }, parentOrigin);
})();
