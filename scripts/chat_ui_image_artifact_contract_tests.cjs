const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const sourcePath = process.env.AWX_IMAGE_CARD_SOURCE || path.resolve('main/resources/static/js/image-jobs-ui.js');
const source = fs.readFileSync(sourcePath, 'utf8');
function harness() {
  function element(tag = 'div') {
    const node = { tag, dataset: {}, children: [], attributes: {}, style: {}, textContent: '', isConnected: true,
      append(...children) { this.children.push(...children); },
      appendChild(child) { this.children.push(child); return child; },
      replaceChildren(...children) { this.children = children; },
      setAttribute(key, value) { this.attributes[key] = String(value); },
      querySelector(selector) {
        const role = /data-role=['"]([^'"]+)/.exec(selector)?.[1];
        for (const child of this.children) {
          if ((role && child.dataset?.role === role) || (!role && child.tag === selector)) return child;
          const nested = child.querySelector?.(selector); if (nested) return nested;
        }
        return null;
      }
    }; return node;
  }
  const context = vm.createContext({ URL, window: { location: { origin: 'https://fixture.invalid' } },
    document: { createElement: element, createTextNode: text => ({ textContent: text }) } });
  vm.runInContext(source.replace(/^export /gm, ''), context);
  const card = context.renderImageJobCard(element(), { id: 'fixture-job', status: 'PENDING' });
  return { card, update: job => context.updateImageJobCard(card, { id: 'fixture-job', ...job }) };
}
function artifact(card) { return card.querySelector("[data-role='image-job-artifact']"); }
function manifest(card) { return card.querySelector("[data-role='image-job-manifest']"); }
test('authoritative succeeded storage URL produces an accessible image and open link', () => {
  const h = harness(); h.update({ status: 'SUCCEEDED', publicUrl: '/generated-images/2026/09/fixture.png' });
  const link = artifact(h.card), img = h.card.querySelector('img');
  assert.ok(link); assert.ok(img);
  assert.equal(link.href, 'https://fixture.invalid/generated-images/2026/09/fixture.png');
  assert.equal(img.src, link.href); assert.equal(img.alt, 'Generated image');
  assert.match(link.textContent, /Open.*image/i); assert.equal(link.rel, 'noopener noreferrer');
  assert.equal(img.style.maxWidth, '100%'); assert.equal(img.style.height, 'auto');
});
test('configured public prefix is not hardcoded', () => {
  const h = harness(); h.update({ status: 'SUCCEEDED', publicUrl: '/custom-images/fixture.png' });
  assert.equal(artifact(h.card)?.href, 'https://fixture.invalid/custom-images/fixture.png');
});
test('returned owner-authorized manifest is usable independently of image availability', () => {
  const h = harness(); h.update({ status: 'PENDING', manifestUrl: '/api/image-plugin/jobs/fixture-job/manifest' });
  assert.equal(manifest(h.card)?.href, 'https://fixture.invalid/api/image-plugin/jobs/fixture-job/manifest');
  assert.equal(h.card.querySelector('img'), null);
});
test('pending and failed payloads cannot display image artifacts', () => {
  for (const status of ['PENDING', 'IN_PROGRESS', 'FAILED']) {
    const h = harness(); h.update({ status, publicUrl: '/generated-images/fixture.png', content: '[image generated]' });
    assert.equal(artifact(h.card), null); assert.equal(h.card.querySelector('img'), null);
  }
});
test('unsafe and malformed image URLs cannot become navigable content', () => {
  for (const publicUrl of ['javascript:alert(1)', 'data:image/png;base64,AAAA', '//outside.invalid/a.png',
    'https://outside.invalid/a.png', 'https://fixture.invalid/a.png', '/\\outside.invalid/a.png',
    '/a\nb.png', '/a%zz.png', { toString: () => '/a.png' }]) {
    const h = harness(); h.update({ status: 'SUCCEEDED', publicUrl });
    assert.equal(artifact(h.card), null); assert.equal(h.card.querySelector('img'), null);
  }
});
test('artifact identity alone does not invent a URL', () => {
  const h = harness(); h.update({ status: 'SUCCEEDED', artifactRef: 'fixture-opaque-hash' });
  assert.equal(artifact(h.card), null); assert.equal(manifest(h.card), null); assert.equal(h.card.querySelector('img'), null);
});
test('manifest URL must match the current job and the declared endpoint', () => {
  for (const manifestUrl of ['/api/image-plugin/jobs/another-job/manifest', '/logout', '//outside.invalid/manifest',
    '/api/image-plugin/jobs/fixture-job/manifest?token=fixture', '/api/image-plugin/jobs/fixture-job/manifest#fragment']) {
    const h = harness(); h.update({ status: 'SUCCEEDED', manifestUrl }); assert.equal(manifest(h.card), null);
  }
});
test('rerender removes stale image and manifest nodes', () => {
  const h = harness(); h.update({ status: 'SUCCEEDED', publicUrl: '/generated-images/fixture.png', manifestUrl: '/api/image-plugin/jobs/fixture-job/manifest' });
  assert.ok(artifact(h.card)); assert.ok(manifest(h.card));
  h.update({ status: 'PENDING' });
  assert.equal(artifact(h.card), null); assert.equal(manifest(h.card), null); assert.equal(h.card.querySelector('img'), null);
});
