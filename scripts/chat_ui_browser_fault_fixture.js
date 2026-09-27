'use strict';

const http = require('node:http');
const crypto = require('node:crypto');

const FIXTURE_HOST = '127.0.0.1';
const ALLOWED_SCENARIOS = new Set(['error-late', 'cancel-late']);
const SAFE_PROXY_PATHS = new Set([
  '/chat-ui',
  '/css/chat-style.css',
  '/js/fetch-wrapper.js',
  '/js/brain-state-ui.js',
  '/js/chat.js',
  '/js/image-jobs-ui.js',
  '/api/chat/ui-heartbeat',
  '/agent/db-context/pipeline-health',
]);
const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
]);

function sseEvent(name, payload) {
  return `event: ${name}\ndata: ${JSON.stringify(payload)}\n\n`;
}

function safeResponseHeaders(headers) {
  return Object.fromEntries(Object.entries(headers)
    .filter(([name]) => !HOP_BY_HOP_HEADERS.has(name.toLowerCase())));
}

function createFaultFixture({
  targetHost = FIXTURE_HOST,
  targetPort,
  scenario = 'error-late',
  lateDelayMs = 75,
} = {}) {
  if (!Number.isInteger(targetPort) || targetPort <= 0 || targetPort > 65535) {
    throw new TypeError('targetPort must be a valid TCP port');
  }
  if (targetHost !== FIXTURE_HOST && targetHost !== 'localhost') {
    throw new TypeError('targetHost must be loopback');
  }
  if (!ALLOWED_SCENARIOS.has(scenario)) {
    throw new TypeError(`unsupported scenario: ${scenario}`);
  }
  const activeCancelStreams = new Set();

  return http.createServer((request, response) => {
    const requestUrl = new URL(request.url || '/', `http://${FIXTURE_HOST}`);

    if (request.method === 'POST'
        && requestUrl.pathname === '/api/chat/stream'
        && scenario === 'error-late') {
      request.resume();
      response.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      });
      response.write(sseEvent('error', { type: 'error', data: 'synthetic_failure' }));
      setTimeout(() => {
        if (response.destroyed) return;
        response.write(sseEvent('token', { type: 'token', data: 'late_synthetic_token' }));
        response.end(sseEvent('final', { type: 'final', data: 'late_synthetic_final' }));
      }, Math.max(0, lateDelayMs));
      return;
    }

    if (request.method === 'POST'
        && requestUrl.pathname === '/api/chat/stream'
        && scenario === 'cancel-late') {
      request.resume();
      response.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      });
      response.write(sseEvent('session', {
        type: 'session',
        sessionId: 424242,
        runToken: 'synthetic-run',
      }));
      response.write(sseEvent('token', { type: 'token', data: 'synthetic_before_cancel' }));
      activeCancelStreams.add(response);
      response.once('close', () => activeCancelStreams.delete(response));
      return;
    }

    if (request.method === 'POST'
        && requestUrl.pathname === '/api/chat/cancel'
        && scenario === 'cancel-late') {
      request.resume();
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ ok: true, cancelled: true, fixture: true }));
      setTimeout(() => {
        for (const streamResponse of activeCancelStreams) {
          if (streamResponse.destroyed) continue;
          streamResponse.end(sseEvent('final', {
            type: 'final',
            data: 'late_synthetic_final_after_cancel',
          }));
        }
        activeCancelStreams.clear();
      }, Math.max(0, lateDelayMs));
      return;
    }

    if (request.method !== 'GET' || !SAFE_PROXY_PATHS.has(requestUrl.pathname)) {
      request.resume();
      response.writeHead(404, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ error: 'fixture_route_not_allowed' }));
      return;
    }

    const upstreamRequest = http.request({
      host: targetHost,
      port: targetPort,
      path: request.url,
      method: 'GET',
    }, (upstreamResponse) => {
      const headers = safeResponseHeaders(upstreamResponse.headers);
      if (requestUrl.pathname !== '/js/chat.js') {
        response.writeHead(upstreamResponse.statusCode || 502, headers);
        upstreamResponse.pipe(response);
        return;
      }

      const chunks = [];
      upstreamResponse.on('data', (chunk) => chunks.push(chunk));
      upstreamResponse.on('end', () => {
        const body = Buffer.concat(chunks);
        headers['content-length'] = String(body.length);
        headers['x-awx-asset-sha256'] = crypto.createHash('sha256').update(body).digest('hex');
        response.writeHead(upstreamResponse.statusCode || 502, headers);
        response.end(body);
      });
    });
    upstreamRequest.once('error', () => {
      if (response.headersSent) {
        response.destroy();
        return;
      }
      response.writeHead(502, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ error: 'fixture_upstream_unavailable' }));
    });
    upstreamRequest.end();
  });
}

function readArg(args, name, fallback) {
  const index = args.indexOf(name);
  return index >= 0 && index + 1 < args.length ? args[index + 1] : fallback;
}

function main(args = process.argv.slice(2)) {
  const listenPort = Number.parseInt(readArg(args, '--listen-port', ''), 10);
  const targetPort = Number.parseInt(readArg(args, '--target-port', ''), 10);
  const scenario = readArg(args, '--scenario', 'error-late');
  const lateDelayMs = Number.parseInt(readArg(args, '--late-delay-ms', '75'), 10);
  if (!Number.isInteger(listenPort) || listenPort <= 0 || listenPort > 65535) {
    throw new TypeError('--listen-port must be a valid TCP port');
  }

  const server = createFaultFixture({ targetPort, scenario, lateDelayMs });
  server.listen(listenPort, FIXTURE_HOST, () => {
    process.stdout.write(`${JSON.stringify({
      type: 'chat_ui_browser_fault_fixture_ready',
      listenHost: FIXTURE_HOST,
      listenPort,
      targetHost: FIXTURE_HOST,
      targetPort,
      scenario,
      rawPromptStored: false,
      rawStreamStored: false,
    })}\n`);
  });
  const shutdown = () => server.close(() => process.exit(0));
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
}

module.exports = { createFaultFixture };

if (require.main === module) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`${JSON.stringify({
      type: 'chat_ui_browser_fault_fixture_error',
      error: error && error.name ? error.name : 'Error',
    })}\n`);
    process.exitCode = 1;
  }
}
