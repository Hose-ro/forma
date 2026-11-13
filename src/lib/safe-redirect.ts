/** Canonicalize with the browser's URL rules; backslashes and encoded authorities are rejected. */
export function safeRedirect(value: string): string | null {
  if (!value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u0020\u007f]/.test(value)) return null;
  try {
    const decoded = decodeURIComponent(value);
    if (decoded.startsWith('//') || /[\\\u0000-\u0020\u007f]/.test(decoded)) return null;
    const url = new URL(value, 'https://velora.invalid');
    return url.origin === 'https://velora.invalid' ? url.pathname + url.search + url.hash : null;
  } catch { return null; }
}
