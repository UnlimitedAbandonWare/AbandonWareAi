const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const http = require('node:http');

const { createFaultFixture } = require('./chat_ui_browser_fault_fixture');

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject);
      resolve(server.address().port);
    });
  });
}

function close(server) {
  return new Promise((resolve) => server.close(resolve));
}

function request({ port, path, method = 'GET', body = '' }) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: '127.0.0.1',
      port,
      path,
      method,
      headers: body ? {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(body),
      } : {},
    }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => resolve({
        statusCode: res.statusCode,
        headers: res.headers,
        body: Buffer.concat(chunks).toString('utf8'),
      }));
    });
    req.once('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

async function main() {
  let upstreamCallCount = 0;
  const upstream = http.createServer((req, res) => {
    upstreamCallCount += 1;
    if (req.url === '/js/chat.js') {
      res.writeHead(200, { 'content-type': 'application/javascript' });
      res.end('window.__fixtureAsset = "source-proxy";');
      return;
    }
    if (req.url.startsWith('/chat-ui')) {
      res.writeHead(200, { 'content-type': 'text/html' });
      res.end([
        '<html data-upstream="true">',
        '<link rel="stylesheet" href="/css/chat-style.css">',
        '<script defer src="/js/fetch-wrapper.js"></script>',
        '<script defer src="/js/brain-state-ui.js"></script>',
        '<script defer src="/js/chat.js?v=chat-ui-20260708-quick-prompt-persist-v12"></script>',
        '</html>',
      ].join(''));
      return;
    }
    res.writeHead(200, { 'content-type': 'application/octet-stream' });
    res.end('fixture-safe-read-only-response');
  });
  const upstreamPort = await listen(upstream);
  const fixture = createFaultFixture({
    targetPort: upstreamPort,
    scenario: 'error-late',
    lateDelayMs: 5,
  });
  const fixturePort = await listen(fixture);
  const cancelFixture = createFaultFixture({
    targetPort: upstreamPort,
    scenario: 'cancel-late',
    lateDelayMs: 5,
  });
  const cancelFixturePort = await listen(cancelFixture);

  try {
    const page = await request({ port: fixturePort, path: '/chat-ui?codexSmoke=fault-fixture' });
    assert.equal(page.statusCode, 200);
    const dependencyPaths = [...page.body.matchAll(/(?:href|src)="([^"]+)"/g)]
      .map((match) => match[1]);
    assert.deepEqual(dependencyPaths, [
      '/css/chat-style.css',
      '/js/fetch-wrapper.js',
      '/js/brain-state-ui.js',
      '/js/chat.js?v=chat-ui-20260708-quick-prompt-persist-v12',
    ]);
    for (const dependencyPath of dependencyPaths) {
      const dependency = await request({ port: fixturePort, path: dependencyPath });
      assert.equal(dependency.statusCode, 200, dependencyPath);
    }
    for (const readOnlyHealthPath of ['/api/chat/ui-heartbeat', '/agent/db-context/pipeline-health']) {
      const health = await request({ port: fixturePort, path: readOnlyHealthPath });
      assert.equal(health.statusCode, 200, readOnlyHealthPath);
    }

    const asset = await request({ port: fixturePort, path: '/js/chat.js' });
    assert.equal(asset.statusCode, 200);
    assert.equal(asset.body, 'window.__fixtureAsset = "source-proxy";');
    assert.equal(
      asset.headers['x-awx-asset-sha256'],
      crypto.createHash('sha256').update(asset.body).digest('hex'),
    );

    const callsAfterAsset = upstreamCallCount;
    const blockedUnknownWrite = await request({
      port: fixturePort,
      path: '/api/diagnostics/debug/triadic-adjudication',
      method: 'POST',
      body: JSON.stringify({ decision: 'neutral-sentinel' }),
    });
    assert.equal(blockedUnknownWrite.statusCode, 404);
    assert.equal(upstreamCallCount, callsAfterAsset);

    const stream = await request({
      port: fixturePort,
      path: '/api/chat/stream',
      method: 'POST',
      body: JSON.stringify({ message: 'synthetic terminal fixture' }),
    });
    assert.equal(stream.statusCode, 200);
    assert.match(stream.headers['content-type'], /^text\/event-stream/);
    const errorIndex = stream.body.indexOf('event: error');
    const tokenIndex = stream.body.indexOf('event: token');
    const finalIndex = stream.body.indexOf('event: final');
    assert.ok(errorIndex >= 0, stream.body);
    assert.ok(tokenIndex > errorIndex, stream.body);
    assert.ok(finalIndex > tokenIndex, stream.body);
    assert.doesNotMatch(stream.body, /synthetic terminal fixture/);

    const cancelStreamPromise = request({
      port: cancelFixturePort,
      path: '/api/chat/stream',
      method: 'POST',
      body: JSON.stringify({ message: 'synthetic cancel fixture' }),
    });
    await new Promise((resolve) => setTimeout(resolve, 10));
    const cancel = await request({
      port: cancelFixturePort,
      path: '/api/chat/cancel',
      method: 'POST',
      body: JSON.stringify({ requestId: 'synthetic-request' }),
    });
    const cancelStream = await cancelStreamPromise;
    assert.equal(cancel.statusCode, 200);
    assert.equal(JSON.parse(cancel.body).cancelled, true);
    const sessionIndex = cancelStream.body.indexOf('event: session');
    const cancelTokenIndex = cancelStream.body.indexOf('event: token');
    assert.ok(sessionIndex >= 0, cancelStream.body);
    assert.ok(cancelTokenIndex > sessionIndex, cancelStream.body);
    assert.ok(cancelStream.body.indexOf('event: token') >= 0, cancelStream.body);
    assert.ok(
      cancelStream.body.indexOf('event: final') > cancelStream.body.indexOf('event: token'),
      cancelStream.body,
    );
    assert.doesNotMatch(cancelStream.body, /synthetic cancel fixture|synthetic-request/);
  } finally {
    await close(cancelFixture);
    await close(fixture);
    await close(upstream);
  }

  process.stdout.write('[PASS] chat_ui_browser_fault_fixture_tests\n');
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
});
