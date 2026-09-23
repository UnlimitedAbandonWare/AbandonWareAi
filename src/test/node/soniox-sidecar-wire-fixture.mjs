// Copied beside the packaged sidecar by the Java integration test. Loopback only.
import { once } from 'node:events';
import { createServer } from 'node:http';
import { WebSocketServer } from 'ws';
import { SonioxNodeClient } from '@soniox/node';
import { startServer } from './server.mjs';

const stats = { opened: 0, closed: 0, finishRequests:0, audioBytes:0 };
const upstreamHttp = createServer((_req, res) => {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify(stats));
});
const upstream = new WebSocketServer({ server: upstreamHttp });
upstreamHttp.listen(0, '127.0.0.1');
await once(upstreamHttp, 'listening');
upstream.on('connection', ws => {
  stats.opened++;
  ws.once('close', () => { stats.closed++; });
  ws.on('message', (data, binary) => {
  if (!data.length) {
    stats.finishRequests++;
    ws.send(JSON.stringify({tokens:[{text:'끝',is_final:true,start_ms:20,end_ms:40,confidence:0.9,speaker:'1'}],finished:true}));
    ws.close(1000);return;
  }
  if (!binary) return;
  // Selected synthetic error frames exercise SDK 2.3.0 decoding, including
  // error_type without error_code and a 1000 close without finished.
  const failures = [null,
    {error_code:401,error_type:'unauthorized'},
    {error_type:'insufficient_funds'},
    {error_code:429,error_type:'rate_limit_exceeded'},
    {error_code:400,error_type:'invalid_request'}];
  if (data[0] >= 1 && data[0] <= 5) {
    if (failures[data[0]]) ws.send(JSON.stringify(failures[data[0]]));
    ws.close(1000); return;
  }
  stats.audioBytes+=data.length;
  ws.send(JSON.stringify({ tokens: [{ text: '안녕하세요', is_final: true }, { text: '<end>', is_final: true }],
    total_audio_proc_ms: data.length / 32, final_audio_proc_ms: data.length / 32 }));
  });
});
const credential = 'synthetic-local-fixture';
const client = new SonioxNodeClient(Object.fromEntries([['api_key', credential],
  ['realtime', { ws_base_url: 'ws://127.0.0.1:' + upstreamHttp.address().port }]]));
const runtime = await startServer({ bearerSeed: process.env.AWX_SONIOX_SIDECAR_TOKEN, credential, client });
process.stdout.write(JSON.stringify({ port: runtime.port, upstreamPort: upstreamHttp.address().port }) + '\n');
process.stdin.resume();
process.stdin.once('end', async () => {
  await runtime.close();
  for (const ws of upstream.clients) ws.terminate();
  upstream.close(() => upstreamHttp.close(() => process.exit(0)));
});
