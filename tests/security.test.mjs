import assert from 'node:assert/strict';
import test from 'node:test';
import { safeRedirect } from '../src/lib/safe-redirect.ts';
import { extractSupplierPage, supplierPageUrl } from '../src/lib/supplier-source.ts';

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

test('supplier imports accept public HTTPS hostnames and reject unsafe URL forms', () => {
  assert.equal(supplierPageUrl('/policies/refund', new URL('https://supplier.example/item'))?.href,
    'https://supplier.example/policies/refund');
  for (const url of ['http://supplier.example', 'https://supplier.example:8443',
    'https://127.0.0.1', 'https://[::1]', 'https://user:secret@supplier.example']) {
    assert.equal(supplierPageUrl(url), undefined);
  }
});

test('supplier product fields and return terms become plain text with a source link', () => {
  const url = new URL('https://supplier.example/products/radio');
  const data = {
    '@type': 'Product',
    description: '<p>Radio de bolsillo.</p><script>Contenido descartado</script>',
    additionalProperty: [{ name: 'Autonomía', value: 12, unitText: 'h' }],
    offers: { hasMerchantReturnPolicy: {
      merchantReturnLink: '/policies/refund',
      returnPolicyCategory: 'https://schema.org/MerchantReturnFiniteReturnWindow',
      merchantReturnDays: 15,
      applicableCountry: 'MX',
    } },
  };
  const product = extractSupplierPage(`<script type="application/ld+json">${JSON.stringify(data).replaceAll('<', '\\u003c')}</script>`, url, 'product');
  assert.equal(product.description, 'Radio de bolsillo.');
  assert.equal(product.specifications, 'Autonomía: 12 h');
  assert.match(product.returnPolicy, /15 días desde la entrega/);
  assert.equal(product.returnPolicyUrl, 'https://supplier.example/policies/refund');

  const policy = extractSupplierPage(
    '<main><nav>Menú</nav><div class="shopify-policy__body"><p>Envío por correo.</p><p>Conserva el comprobante.</p></div></main>',
    new URL(product.returnPolicyUrl), 'policy');
  assert.equal(policy.returnPolicy, 'Envío por correo.\nConserva el comprobante.');
});
