import { lookup } from 'node:dns/promises';
import { request } from 'node:https';
import { BlockList, isIP } from 'node:net';
import { load, type CheerioAPI } from 'cheerio/slim';

const nonPublic = new BlockList();
for (const [address, prefix] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8],
  ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24],
  ['192.88.99.0', 24], ['192.168.0.0', 16], ['198.18.0.0', 15], ['198.51.100.0', 24],
  ['203.0.113.0', 24], ['224.0.0.0', 3],
] as const) nonPublic.addSubnet(address, prefix);

export function supplierPageUrl(value: string, base?: URL) {
  try {
    const url = new URL(value, base);
    if (value.trim() && url.protocol === 'https:' && !url.username && !url.password
        && !url.port && /^[\w-]+(?:\.[\w-]+)+$/.test(url.hostname)
        && !isIP(url.hostname) && url.href.length <= 1000) return url;
  } catch {
    // The editor can accept the supplier information manually when a URL cannot be imported.
  }
  return undefined;
}

async function download(url: URL, redirects = 0, deadline = Date.now() + 7000): Promise<{ url: URL; html: string }> {
  const addresses = await lookup(url.hostname, { all: true, family: 4 });
  const [destination] = addresses;
  if (!destination || addresses.some(({ address }) => nonPublic.check(address))) {
    throw new Error('Invalid supplier destination');
  }
  const remaining = deadline - Date.now();
  if (remaining < 1) throw new Error('Supplier request timed out');
  const response = await new Promise<{ redirect: string } | { html: string }>((resolve, reject) => {
    const req = request(url, {
      agent: false,
      family: 4,
      // Pin the vetted address while keeping the original TLS hostname and certificate checks.
      lookup: (_hostname, _options, callback) => callback(null, destination.address, 4),
      signal: AbortSignal.timeout(remaining),
      headers: { Accept: 'text/html, application/xhtml+xml', 'User-Agent': 'OlvenioCatalog/1.0' },
    }, res => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode ?? 0) && res.headers.location) {
        res.destroy();
        resolve({ redirect: res.headers.location });
        return;
      }
      if (res.statusCode !== 200 || !/^(text\/html|application\/xhtml\+xml)\b/i.test(res.headers['content-type'] ?? '')) {
        res.destroy();
        reject(new Error('Supplier did not return a public HTML page'));
        return;
      }
      const chunks: Buffer[] = [];
      let length = 0;
      res.on('error', reject);
      res.on('data', (chunk: Buffer) => {
        length += chunk.length;
        if (length > 2 * 1024 * 1024) {
          reject(new Error('Supplier page is too large'));
          req.destroy();
        } else {
          chunks.push(chunk);
        }
      });
      res.on('end', () => resolve({ html: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('error', reject);
    req.end();
  });
  if ('html' in response) return { url, html: response.html };
  const next = supplierPageUrl(response.redirect, url);
  if (!next || redirects >= 3) throw new Error('Invalid supplier redirect');
  return download(next, redirects + 1, deadline);
}

type Data = Record<string, unknown>;
const object = (value: unknown): value is Data => !!value && typeof value === 'object' && !Array.isArray(value);
const entries = (value: unknown): unknown[] => Array.isArray(value) ? value : [value];
const clean = (value: string) => value.replace(/\s+/g, ' ').trim();

function structuredData($: CheerioAPI): Data[] {
  return $('script[type="application/ld+json"]').toArray().flatMap(script => {
    try {
      const root: unknown = JSON.parse($(script).text());
      return entries(root).filter(object).flatMap(item => [item, ...entries(item['@graph']).filter(object)]);
    } catch {
      return [];
    }
  });
}

function isType(item: Data, type: string) {
  return entries(item['@type']).some(value => typeof value === 'string' && (value === type || value.endsWith('/' + type)));
}

function text(value: unknown, max = 5000) {
  if (typeof value !== 'string' && typeof value !== 'number') return '';
  const $ = load(String(value), {}, false);
  $('script,style,template').remove();
  return clean($.root().text()).slice(0, max);
}

function blocks($: CheerioAPI, selectors: string[], max: number) {
  const selector = selectors.find(candidate => $(candidate).length > 0);
  if (!selector) return '';
  const content = $(selector).first().clone();
  content.find('script,style,template,noscript,nav,header,footer,form,button,[hidden]').remove();
  content.find('p,li,br,h1,h2,h3,h4,dt,dd,tr,div').before('\n');
  content.find('td,th').before(' ');
  return content.text().split('\n').map(clean).filter(Boolean).join('\n').slice(0, max);
}

export function extractSupplierPage(html: string, url: URL, kind: 'product') {
  const $ = load(html);
  const data = structuredData($);
  const product = data.find(item => isType(item, 'Product') || isType(item, 'ProductGroup'));
  const description = text(product?.description)
    || blocks($, ['[itemprop="description"]:not(meta)', '.product__description, #product-description, [data-product-description]'], 5000)
    || text($('meta[itemprop="description"], meta[property="og:description"], meta[name="description"]').first().attr('content'));
  const specifications = entries(product?.additionalProperty).filter(object).flatMap(property => {
    const name = text(property.name, 160);
    const value = text(property.value, 500);
    const unit = text(property.unitText, 50);
    return name && value ? [name + ': ' + [value, unit].filter(Boolean).join(' ')] : [];
  });
  for (const [key, label] of [['material', 'Material'], ['color', 'Color'], ['weight', 'Peso'],
    ['width', 'Ancho'], ['height', 'Alto'], ['depth', 'Profundidad']] as const) {
    const value = product?.[key];
    const detail = object(value) ? [text(value.value), text(value.unitText || value.unitCode)].filter(Boolean).join(' ') : text(value);
    if (detail && !specifications.some(line => line.toLowerCase().startsWith(label.toLowerCase() + ':'))) {
      specifications.push(label + ': ' + detail);
    }
  }
  return {
    productUrl: url.href, description,
    specifications: specifications.join('\n').slice(0, 5000)
      || blocks($, ['#product-specifications, .product-specifications, [data-product-specifications]', '[itemprop="additionalProperty"]'], 5000),
  };
}

export async function importSupplierPage(rawUrl: string, kind: 'product') {
  const url = supplierPageUrl(rawUrl);
  if (!url) throw new Error('Invalid supplier URL');
  const page = await download(url);
  return extractSupplierPage(page.html, page.url, kind);
}
