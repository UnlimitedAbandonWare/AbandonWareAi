#!/usr/bin/env node
// P6-D5: slow/faulty upstream mock for model-catalog style endpoints.
// Reused: none (Node stdlib http only). Added: delay/500/empty/truncated/drop
// modes + a client mode that fires K concurrent requests and reports whether a
// slow upstream request blocks siblings (head-of-line / lock observation).
// Binds 127.0.0.1 only. No external calls. No npm deps.
//   server: node p6dbg_slow_upstream.mjs --port 18081 --mode delay --delay-ms 3000
//   client: node p6dbg_slow_upstream.mjs --client --port 18081 --requests 8
//   selftest wiring config key name: chat.model-catalog.* (observation only)
import http from 'node:http';
import { performance } from 'node:perf_hooks';

const MODES = new Set(['ok', 'delay', 'error500', 'empty', 'truncated', 'drop']);

export function modelListBody(count = 2) {
  const data = Array.from({ length: count }, (_, i) => ({
    id: `mock-model-${i}`, object: 'model', created: 0, owned_by: 'mock',
  }));
  return JSON.stringify({ object: 'list', data });
}

export function createServer({ mode = 'ok', delayMs = 0, port = 0 } = {}) {
  if (!MODES.has(mode)) throw new Error(`mode-not-allowed: ${mode}`);
  const server = http.createServer((req, res) => {
    const t0 = performance.now();
    const finish = (fn) => {
      const wait = mode === 'delay' ? delayMs : 0;
      setTimeout(() => { fn(); }, wait);
    };
    if (mode === 'drop') {
      // destroy the socket after delay — client sees ECONNRESET
      setTimeout(() => req.socket.destroy(), delayMs);
      return;
    }
    finish(() => {
      try {
        if (mode === 'error500') {
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'mock-500' }));
        } else if (mode === 'empty') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ object: 'list', data: [] }));
        } else if (mode === 'truncated') {
          const body = modelListBody(4);
          res.writeHead(200, {
            'Content-Type': 'application/json',
            'Content-Length': String(Buffer.byteLength(body)),
          });
          res.end(body.slice(0, Math.floor(body.length / 2))); // shorter than declared
        } else { // ok | delay
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(modelListBody(2));
        }
      } catch { /* socket already gone */ }
      void t0;
    });
  });
  return server;
}

export async function fetchOnce(port, path = '/api/v1/models', timeoutMs = 8000) {
  const t0 = performance.now();
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path, timeout: timeoutMs },
      (res) => {
        let bytes = 0;
        res.on('data', (c) => { bytes += c.length; });
        res.on('end', () => resolve({
          status: res.statusCode, bytes,
          ms: Math.round(performance.now() - t0), error: null,
        }));
        res.on('error', (e) => resolve({ status: null, bytes, ms: Math.round(performance.now() - t0), error: String(e.code || e) }));
      });
    req.on('timeout', () => { req.destroy(new Error('timeout')); });
    req.on('error', (e) => resolve({
      status: null, bytes: 0,
      ms: Math.round(performance.now() - t0), error: String(e.code || e.message),
    }));
  });
}

export async function runClient({ port, requests = 8, timeoutMs = 8000 }) {
  const results = await Promise.all(
    Array.from({ length: requests }, (_, i) =>
      fetchOnce(port).then((r) => ({ idx: i, ...r }))));
  const times = results.map((r) => r.ms);
  const maxMs = Math.max(...times);
  const blocked = results.filter((r) => r.error === null && r.ms >= maxMs * 0.9).length;
  return {
    requests, results,
    observation: {
      maxMs, minMs: Math.min(...times),
      concurrentBlockedCount: blocked,
      note: 'if a slow upstream blocks siblings, most ms values cluster near maxMs',
    },
  };
}

async function main() {
  const a = process.argv.slice(2);
  const arg = (k, d) => {
    const i = a.indexOf(k);
    return i >= 0 ? a[i + 1] : d;
  };
  if (a.includes('--help') || a.includes('-h')) {
    console.log(JSON.stringify({
      usage: 'p6dbg_slow_upstream.mjs [--client] --port N [--mode M] [--delay-ms D] [--requests K]',
      modes: [...MODES], loopbackOnly: true,
    }));
    return 0;
  }
  const port = Number(arg('--port', '0'));
  if (a.includes('--client')) {
    const out = await runClient({
      port, requests: Number(arg('--requests', '8')),
      timeoutMs: Number(arg('--timeout-ms', '8000')),
    });
    console.log(JSON.stringify(out));
    return out.results.every((r) => r.error === null) ? 0 : 1;
  }
  const server = createServer({
    mode: arg('--mode', 'ok'), delayMs: Number(arg('--delay-ms', '0')), port,
  });
  await new Promise((res) => server.listen(port, '127.0.0.1', res));
  const bound = server.address();
  if (bound.address !== '127.0.0.1') {
    server.close();
    console.log(JSON.stringify({ status: 'FAIL', reason: 'bind-not-loopback' }));
    return 1;
  }
  console.log(JSON.stringify({
    status: 'LISTENING', port: bound.port, mode: arg('--mode', 'ok'),
    pid: process.pid,
  }));
  return 0;
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/'));
if (isMain) main().then((c) => { if (!process.exitCode) process.exitCode = c; });
