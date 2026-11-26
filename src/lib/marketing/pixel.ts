export type TikTokEvent =
  | 'ViewContent' | 'Search' | 'AddToCart' | 'AddToWishlist' | 'InitiateCheckout'
  | 'PlaceAnOrder' | 'CompleteRegistration' | 'Purchase';

type PixelMessage =
  | { type: 'grant' | 'revoke' }
  | { type: 'page'; path: string }
  | { type: 'event'; event: TikTokEvent; id: string; properties: Record<string, unknown> };

export function publicPage(path: string): string | null {
  return ['/', '/shop', '/cart', '/checkout', '/account'].includes(path)
    || /^\/product\/[a-z0-9-]{1,180}$/.test(path) || /^\/policies\/[a-z0-9-]{1,60}$/.test(path) ? path : null;
}

export function createPixel(frameOrigin: string, onCookie: (ttp: string) => void) {
  let frame: HTMLIFrameElement | undefined;
  let framePixel: string | undefined;
  let grantedPixel: string | undefined;
  const pending: PixelMessage[] = [];

  function post(message: PixelMessage) {
    frame?.contentWindow?.postMessage({ source: 'olvenio-store', ...message }, frameOrigin);
  }

  function receive(message: MessageEvent) {
    if (message.origin !== frameOrigin || message.source !== frame?.contentWindow
        || message.data?.source !== 'olvenio-pixel') return;
    if (message.data.ready && !framePixel && typeof message.data.pixel === 'string') {
      framePixel = message.data.pixel;
      if (grantedPixel === framePixel) {
        post({ type: 'grant' });
        pending.splice(0).forEach(post);
      }
    }
    if (grantedPixel && grantedPixel === framePixel && typeof message.data.ttp === 'string' && /^[A-Za-z0-9._~-]{8,512}$/.test(message.data.ttp)) {
      onCookie(message.data.ttp);
    }
  }

  function enable(pixelId: string) {
    if (grantedPixel === pixelId) return;
    disable();
    grantedPixel = pixelId;
    if (framePixel === grantedPixel) {
      post({ type: 'grant' });
      return;
    }
    if (frame || frameOrigin === window.location.origin) return;
    window.addEventListener('message', receive);
    frame = document.createElement('iframe');
    frame.hidden = true;
    frame.title = 'Medición publicitaria';
    frame.referrerPolicy = 'no-referrer';
    frame.sandbox.add('allow-same-origin', 'allow-scripts');
    frame.src = new URL('/marketing/pixel', frameOrigin).href;
    document.body.append(frame);
  }

  function disable() {
    if (!grantedPixel) return;
    if (framePixel === grantedPixel) post({ type: 'revoke' });
    grantedPixel = undefined;
    pending.length = 0;
  }

  function send(message: PixelMessage) {
    if (!grantedPixel) return;
    if (framePixel === grantedPixel) post(message);
    else if (!framePixel && pending.length < 40) pending.push(message);
  }

  return { enable, disable, send };
}
