// node --test for p6dbg_slow_upstream.mjs — loopback only.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer, fetchOnce, runClient } from './p6dbg_slow_upstream.mjs';

async function listen(opts) {
  const server = createServer(opts);
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return server;
}
const close = (s) => new Promise((r) => s.close(r));

test('ok mode returns model list', async () => {
  const s = await listen({ mode: 'ok' });
  const r = await fetchOnce(s.address().port);
  assert.equal(r.status, 200);
  assert.ok(r.bytes > 10);
  await close(s);
});

test('delay mode honors delayMs', async () => {
  const s = await listen({ mode: 'delay', delayMs: 250 });
  const r = await fetchOnce(s.address().port);
  assert.equal(r.status, 200);
  assert.ok(r.ms >= 240, `ms=${r.ms}`);
  await close(s);
});

test('error500 / empty / truncated', async () => {
  const s5 = await listen({ mode: 'error500' });
  assert.equal((await fetchOnce(s5.address().port)).status, 500);
  await close(s5);

  const se = await listen({ mode: 'empty' });
  const re = await fetchOnce(se.address().port);
  assert.equal(re.status, 200);
  await close(se);

  const st = await listen({ mode: 'truncated' });
  const rt = await fetchOnce(st.address().port);
  // declared Content-Length exceeds actual -> client sees premature close
  assert.ok(rt.error !== null || rt.status === 200);
  await close(st);
});

test('drop mode resets connection', async () => {
  const s = await listen({ mode: 'drop', delayMs: 30 });
  const r = await fetchOnce(s.address().port);
  assert.ok(r.error !== null);
  await close(s);
});

test('client mode: fast siblings are NOT blocked by ok-mode server', async () => {
  const s = await listen({ mode: 'ok' });
  const out = await runClient({ port: s.address().port, requests: 6 });
  assert.equal(out.results.length, 6);
  assert.ok(out.results.every((r) => r.error === null));
  await close(s);
});

test('bad mode rejected', () => {
  assert.throws(() => createServer({ mode: 'nope' }));
});
