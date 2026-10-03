#!/usr/bin/env node
// P6-D8: XHR fallback observation harness for F-04.
// Reused: chat.js xhrApiCall is EXTRACTED (read-only) and run under node:vm —
// the product file is never modified. Fake XMLHttpRequest scripts delay/hang/
// mid-cut so we can observe timeout wiring, AbortController honoring, and
// whether the returned promise ever settles.
// Diff vs smoke_chat_debug_fx_sse.ps1: that script smokes the live debug SSE
// endpoint; this probes the *XHR fallback path* in chat.js offline.
//   node scripts/p6dbg_xhr_fallback_probe.mjs [--chat-js <path>]
// Stdlib only (node:vm, node:fs). No external calls. Exit 0 PASS / 1 FAIL.
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHAT_JS = path.join(ROOT, 'main/resources/static/js/chat.js');

export function extractFunction(src, name) {
  const start = src.indexOf(`function ${name}(`);
  if (start < 0) return null;
  // body opens at ") {" after the signature — skips default-arg braces like `= {}`
  const sig = src.indexOf(') {', start);
  const brace = sig >= 0 ? sig + 1 : src.indexOf('{', start);
  let depth = 0, i = brace;
  for (; i < src.length; i++) {
    const ch = src[i];
    if (ch === '{') depth++;
    else if (ch === '}') { depth--; if (depth === 0) break; }
  }
  return src.slice(start, i + 1);
}

class FakeXHR {
  static behavior = 'delay';
  static delayMs = 400;
  static last = null;
  constructor() { FakeXHR.last = this; this.timeout = 0; this._open = false; }
  open(m, u) { this.method = m; this.url = u; this._open = true; }
  setRequestHeader(k, v) { (this.headers ??= {})[k] = v; }
  send() {
    const b = FakeXHR.behavior;
    if (b === 'hang') return;                       // never fires a callback
    if (b === 'cut') {
      this.onprogress?.({ loaded: 12 });
      setTimeout(() => this.onerror?.(new Error('net-cut')), 60);
      return;
    }
    setTimeout(() => { this.status = 200; this.responseText = '{}'; this.onload?.(); },
               b === 'delay' ? FakeXHR.delayMs : 5);
  }
  abort() { this.aborted = true; this.onerror?.(new Error('aborted')); }
  getResponseHeader() { return null; }
}

async function probe(fnSrc, behavior, { delayMs = 400, abortAt = null } = {}) {
  FakeXHR.behavior = behavior;
  FakeXHR.delayMs = delayMs;
  const sandbox = { XMLHttpRequest: FakeXHR, Promise, setTimeout, console };
  const ctx = vm.createContext(sandbox);
  vm.runInContext(`${fnSrc}\nglobalThis.__fn = xhrApiCall;`, ctx);
  const t0 = performance.now();
  const ac = new AbortController();
  if (abortAt) setTimeout(() => ac.abort(), abortAt);
  let settled = false, error = null, status = null;
  const timer = setTimeout(() => {}, 3000);
  await Promise.race([
    ctx.__fn('/api/chat/state', { method: 'GET', signal: ac.signal })
      .then((r) => { settled = true; status = r.status; })
      .catch((e) => { settled = true; error = String(e.message || e); }),
    new Promise((r) => setTimeout(r, 2000)),
  ]);
  clearTimeout(timer);
  return {
    behavior, settled, status, error,
    ms: Math.round(performance.now() - t0),
    xhrTimeoutConfigured: FakeXHR.last?.timeout ?? null,
    abortCalled: Boolean(FakeXHR.last?.aborted),
    signalIgnored: abortAt !== null && !FakeXHR.last?.aborted,
  };
}

export async function runProbes(chatJsPath = CHAT_JS) {
  const src = readFileSync(chatJsPath, 'utf8');
  const fn = extractFunction(src, 'xhrApiCall');
  if (!fn) return { status: 'NOT_RUN', reason: 'xhrApiCall not found' };
  const observations = [];
  observations.push(await probe(fn, 'delay', { delayMs: 400 }));
  observations.push(await probe(fn, 'hang'));
  observations.push(await probe(fn, 'cut'));
  observations.push(await probe(fn, 'hang', { abortAt: 150 }));
  return {
    status: 'PASS', source: chatJsPath, extracted: 'xhrApiCall',
    observations,
    findings: {
      timeoutWired: observations.every((o) => (o.xhrTimeoutConfigured ?? 0) > 0),
      abortHonored: !observations.some((o) => o.signalIgnored),
      hangSettles: observations.find((o) => o.behavior === 'hang')?.settled ?? null,
    },
  };
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/'));
if (isMain) {
  const i = process.argv.indexOf('--chat-js');
  runProbes(i > 0 ? process.argv[i + 1] : CHAT_JS)
    .then((r) => { console.log(JSON.stringify(r, null, 1)); process.exit(r.status === 'PASS' ? 0 : 1); });
}
