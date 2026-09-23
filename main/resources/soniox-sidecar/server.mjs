import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import { SonioxNodeClient } from '@soniox/node';
import { createSession } from './session.mjs';

export function validKey(value) {
  return typeof value === 'string' && value.trim().length > 0
    && value.length <= 8192 && !/^(?:(?:test|dummy|changeme|sk-local|placeholder)(?:$|[-_])|null$|undefined$)/i.test(value.trim()) && !value.includes('${');
}

export async function startServer({ bearerSeed, credential, model = 'stt-rt-v5', region = 'us', enabled = true, client } = {}) {
  if (typeof bearerSeed !== 'string' || bearerSeed.length < 32) throw new Error('sidecar_token_required');
  const expected = Buffer.from(`Bearer ${bearerSeed}`);
  const authorized = request => {
    const actual = Buffer.from(request.headers.authorization || '');
    return !request.headers.origin && actual.length === expected.length && timingSafeEqual(actual, expected);
  };
  const disabledReason = !enabled ? 'disabled' : !validKey(credential) ? 'missing_key'
    : !['us', 'eu', 'jp', 'in'].includes(region) ? 'invalid_region'
    : !['stt-rt-v5', 'stt-rt-v4'].includes(model) ? 'invalid_model' : '';
  const configured = !disabledReason;
  const sdkClient = client ?? (configured ? new SonioxNodeClient(Object.fromEntries([['api_key', credential.trim()], ['region', region]])) : null);
  const streams = new Set(); let stopping = false;
  const server = http.createServer((request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('Content-Type', 'application/json');
    if (!authorized(request)) { response.writeHead(403); response.end('{}'); return; }
    if (request.method === 'GET' && request.url === '/health') {
      response.end(JSON.stringify({ status: stopping ? 'stopping' : 'ready', configured, disabledReason, protocol: 1, sessions: streams.size }));
    } else { response.writeHead(404); response.end('{}'); }
  });
  server.requestTimeout = 2000; server.headersTimeout = 2000; server.maxHeadersCount = 16;
  const wss = new WebSocketServer({ noServer: true, maxPayload: 12000, perMessageDeflate: false });
  server.on('upgrade', (request, socket, head) => {
    if (stopping || !authorized(request) || request.url !== '/stream' || !configured || streams.size >= 1) {
      socket.end('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n'); return;
    }
    wss.handleUpgrade(request, socket, head, ws => wss.emit('connection', ws));
  });
  wss.on('connection', ws => {
    let session;
    const send = event => {
      if (ws.readyState !== WebSocket.OPEN) return;
      if (ws.bufferedAmount > 65536) { session?.close(); ws.terminate(); return; }
      ws.send(JSON.stringify(event));
      if (event.type === 'finished') ws.close(1000, 'finished');
    };
    try {
      const sdk = sdkClient.realtime.stt({ model, audio_format: 'pcm_s16le', sample_rate: 16000,
        num_channels: 1, language_hints: ['ko', 'en'], enable_endpoint_detection: true });
      session = createSession({ sdk, emit: send, fail: reason => { send({ type: 'error', reason }); ws.close(1011, 'speech_unavailable'); } });
      streams.add(session);
      ws.on('message', (data, binary) => { if (binary) { session.close(); ws.close(1003); } else session.audio(data.toString('utf8')); });
      ws.on('close', () => { session.close(); streams.delete(session); });
      ws.on('error', () => { session.close(); streams.delete(session); });
      void session.start();
    } catch { send({ type: 'error', reason: 'ASR_PROVIDER_FAILED' }); ws.close(1011); }
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return {
    port: server.address().port,
    async close() {
      if (stopping) return; stopping = true;
      for (const stream of streams) stream.close(); streams.clear();
      for (const ws of wss.clients) ws.terminate();
      wss.close(); server.closeAllConnections();
      await new Promise(resolve => server.close(resolve));
    }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const runtime = await startServer({ bearerSeed: process.env.AWX_SONIOX_SIDECAR_TOKEN,
      credential: process.env.SONIOX_API_KEY, model: process.env.SONIOX_MODEL || 'stt-rt-v5',
      region: process.env.SONIOX_STT_REGION || 'us', enabled: process.env.SONIOX_STT_ENABLED === 'true' });
    process.stdout.write(JSON.stringify({ port: runtime.port, protocol: 1 }) + '\n');
    let stopping = false;
    const stop = () => { if (!stopping) { stopping = true; void runtime.close().finally(() => process.exit(0)); } };
    // The pipe belongs to Spring: EOF also covers abrupt JVM termination and Windows.
    process.stdin.resume(); process.stdin.on('end', stop); process.stdin.on('error', stop);
    process.on('SIGTERM', stop); process.on('SIGINT', stop);
  } catch { process.stderr.write('soniox_sidecar_start_failed\n'); process.exitCode = 1; }
}
