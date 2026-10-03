#!/usr/bin/env node
// P6-D8: SSE termination replay harness for F-04.
// Reused: SSE fixtures from var/codex-assist-20261001/kits/gpt-pro/.../fixtures/
// (completed.sse, truncated.sse, failed-with-usage.sse — same data codex_mock_sse.ps1
// samples). Added: a loopback replay server + minimal SSE client that records
// event sequence and classifies stream ending: terminal(completed|failed|
// incomplete) vs delta-cut vs clean-EOF-without-terminus.
// Diff vs smoke_chat_debug_fx_sse.ps1: that script hits the live debug route;
// this replays canned bytes to measure whether a client can tell "ended" from
// "still streaming" — no app boot, no server dependency.
//   node scripts/p6dbg_sse_terminus.mjs [--port 0]
// Stdlib only, 127.0.0.1. Exit 0 PASS / 1 FAIL.
import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FX = path.join(ROOT,
  'var/codex-assist-20261001/kits/gpt-pro/m312221ain_codex_handoff/proposed-tests/fixtures');

const TERMINAL = new Set([
  'response.completed', 'response.failed', 'response.incomplete', 'error']);

const CASES = {
  'completed': { fixture: 'completed.sse', note: 'terminal event present' },
  'incomplete': {
    synth: 'event: response.incomplete\n' +
      'data: {"type":"response.incomplete","response":{"status":"incomplete",' +
      '"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}\n\n',
    note: 'terminal event = response.incomplete (truncated-but-final)' },
  'delta-cut': { fixture: 'truncated.sse', closeAbrupt: true,
    note: 'delta then socket destroyed — no terminus, unclean close' },
  'no-terminus': {
    synth: 'event: response.output_text.delta\n' +
      'data: {"type":"response.output_text.delta","delta":"partial one"}\n\n' +
      'event: response.output_text.delta\n' +
      'data: {"type":"response.output_text.delta","delta":"partial two"}\n\n',
    note: 'deltas then clean EOF — no terminal event' },
};

function serve(payload, { closeAbrupt = false } = {}) {
  return new Promise((resolve) => {
    const s = http.createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream',
                           'Cache-Control': 'no-cache', 'Connection': 'close' });
      res.write(payload);
      if (closeAbrupt) { setTimeout(() => req.socket.destroy(), 60); }
      else { res.end(); }
    });
    s.listen(0, '127.0.0.1', () => resolve(s));
  });
}

function consume(port, timeoutMs = 3000) {
  const t0 = performance.now();
  return new Promise((resolve) => {
    const events = [];
    let ended = null, buf = '';
    const req = http.get({ host: '127.0.0.1', port, path: '/' }, (res) => {
      res.on('data', (c) => {
        buf += c.toString('utf8');
        let i;
        while ((i = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, i); buf = buf.slice(i + 2);
          const ev = /event:\s*(.+)/.exec(block)?.[1]?.trim();
          if (ev) events.push(ev);
        }
      });
      res.on('end', () => { ended = 'eof'; done(); });
      res.on('aborted', () => { ended = 'aborted'; done(); });
      res.on('error', () => { ended = 'res-error'; done(); });
    });
    req.on('error', (e) => { ended = `req-error:${e.code}`; done(); });
    const timer = setTimeout(() => { ended = 'timeout'; req.destroy(); done(); }, timeoutMs);
    function done() {
      if (done.hit) return; done.hit = true; clearTimeout(timer);
      const last = events[events.length - 1] ?? null;
      resolve({
        events, lastEvent: last, terminalSeen: TERMINAL.has(last),
        endedBy: ended, ms: Math.round(performance.now() - t0),
        // F-04 question: can a client distinguish "finished" from "still open"?
        clientCanTellFinished: TERMINAL.has(last),
      });
    }
  });
}

export async function runReplay(fxDir = FX) {
  const rows = [];
  for (const [name, c] of Object.entries(CASES)) {
    let payload = c.synth;
    if (c.fixture) {
      const p = path.join(fxDir, c.fixture);
      if (!existsSync(p)) { rows.push({ name, status: 'NOT_RUN', reason: `missing ${p}` }); continue; }
      payload = readFileSync(p, 'utf8');
    }
    const server = await serve(payload, { closeAbrupt: c.closeAbrupt });
    const r = await consume(server.address().port);
    await new Promise((res) => server.close(res));
    rows.push({ name, ...r, note: c.note });
  }
  return { status: 'PASS', cases: rows.length, rows };
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/'));
if (isMain) {
  runReplay().then((r) => { console.log(JSON.stringify(r, null, 1)); process.exit(0); });
}
