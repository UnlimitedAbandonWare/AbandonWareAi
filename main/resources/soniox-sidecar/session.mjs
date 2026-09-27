// The only speech boundary: volatile PCM in, bounded transcription events out.
// SDK token finality is not utterance finality. No audio/text is logged or persisted.
export function createSession({ sdk, emit, fail, connectTimeoutMs = 8000, wallTimeoutMs = 600000, finishTimeoutMs = 3000 }) {
  let closed = false, ready = false, finishing = false, providerFinished = false, sequence = 0, bytes = 0, revision = 0, utterance = 0;
  let committed = '', partial = '', timer, connectionTimer, finishTimer, processedMs = 0;
  let committedWords = [], partialWords = [];
  const listeners = [];
  const on = (name, handler) => { listeners.push([name, handler]); sdk.on(name, handler); };
  const runtime = { provider: 'soniox', device: 'remote', reason: 'primary', transport: 'node_sdk' };
  function close() {
    if (closed) return;
    closed = true; ready = false; clearTimeout(timer); clearTimeout(connectionTimer); clearTimeout(finishTimer);
    committed = partial = ''; committedWords = partialWords = [];
    for (const [name, handler] of listeners) sdk.off(name, handler);
    listeners.length = 0;
    try { sdk.close(); } catch { /* error details never cross this boundary */ }
  }
  function error(reason) { if (!closed) { close(); fail(reason); } }
  function transcript(final) {
    const text = final ? committed : committed + partial;
    if (text.length > 2048) return error('ASR_TRANSCRIPT_LIMIT');
    const words = final ? committedWords : [...committedWords, ...partialWords];
    const confidences = words.map(w => w.confidence).filter(Number.isFinite);
    if (text.trim()) emit({ type: 'transcript', text, final, utteranceId: `sx-${utterance}`, revision: ++revision, runtime,
      ...(words.length ? { words } : {}), ...(confidences.length ? { confidence: confidences.reduce((a,b)=>a+b,0)/confidences.length } : {}) });
    if (final) { committed = partial = ''; committedWords = partialWords = []; utterance++; }
  }
  function endpoint() { if (!closed) transcript(true); }
  on('error', cause => error(providerError(cause)));
  on('disconnected', () => error('ASR_PROVIDER_DISCONNECTED'));
  on('finished', () => { if (!closed) { endpoint(); providerFinished = true; if (!finishing) error('ASR_STREAM_ENDED'); } });
  on('endpoint', endpoint);
  on('result', result => {
    if (closed) return;
    if (result?.raw?.error_type) return error(providerError({raw:result.raw}));
    if (!Array.isArray(result?.tokens) || result.tokens.length > 512) return error('ASR_TRANSCRIPT_LIMIT');
    if (Number.isFinite(result.total_audio_proc_ms) && result.total_audio_proc_ms >= 0)
      processedMs = Math.max(processedMs, Math.min(bytes / 32, result.total_audio_proc_ms));
    emit({type:'progress',processedMs,runtime});
    partial = ''; partialWords = []; let ended = false;
    for (const token of result.tokens) {
      if (typeof token.text !== 'string' || token.text.length > 2048) return error('ASR_TRANSCRIPT_LIMIT');
      if (token.text === '<end>' && token.is_final) { ended = true; continue; }
      if (token.text.startsWith('<')) continue;
      if (token.is_final) committed += token.text;
      else partial += token.text;
      if (Number.isFinite(token.start_ms) && Number.isFinite(token.end_ms) && token.start_ms >= 0 && token.end_ms >= token.start_ms && token.end_ms <= 600000) {
        const word = { text:token.text, start:token.start_ms/1000, end:token.end_ms/1000,
          confidence:Number.isFinite(token.confidence)&&token.confidence>=0&&token.confidence<=1?token.confidence:null,
          speaker:/^\d{1,2}$/.test(String(token.speaker))?Number(token.speaker):null };
        (token.is_final ? committedWords : partialWords).push(word);
        if (committedWords.length + partialWords.length > 256) return error('ASR_TRANSCRIPT_LIMIT');
      }
      if (committed.length + partial.length > 2048) return error('ASR_TRANSCRIPT_LIMIT');
    }
    transcript(ended);
  });
  async function finish() {
    if (closed || finishing) return;
    if (!ready) return error('ASR_PROTOCOL_FAILED');
    finishing = true; ready = false;
    finishTimer = setTimeout(() => error('ASR_FINISH_TIMEOUT'), Math.max(1, Math.min(3000, finishTimeoutMs)));
    try {
      await sdk.finish();
      if (closed) return;
      if (!providerFinished) return error('ASR_PROVIDER_DISCONNECTED');
      close(); emit({type:'finished',reason:'finished',processedMs,runtime});
    } catch (cause) { if (!closed) error(providerError(cause)); }
  }
  return {
    async start() {
      timer = setTimeout(() => error('ASR_CAPTURE_LIMIT'), Math.min(600000, wallTimeoutMs));
      try {
        const timeout = new Promise((_, reject) => { connectionTimer = setTimeout(() => reject(new Error('timeout')), connectTimeoutMs); });
        await Promise.race([sdk.connect(), timeout]);
        clearTimeout(connectionTimer);
        if (closed) { try { sdk.close(); } catch {} return; }
        ready = true; emit({ type: 'ready', runtime });
      } catch { error('ASR_CONNECT_TIMEOUT'); }
    },
    audio(line) {
      if (closed) return;
      try {
        if (typeof line !== 'string' || line.length > 12000) throw new Error();
        const frame = JSON.parse(line);
        if (frame.type === 'finish' && Object.keys(frame).length === 1) { void finish(); return; }
        if (!ready || finishing) throw new Error();
        if (!Number.isSafeInteger(frame.seq) || frame.seq !== sequence || typeof frame.pcm !== 'string'
            || frame.pcm.length > 10240 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(frame.pcm)) throw new Error();
        const pcm = Buffer.from(frame.pcm, 'base64');
        if (!pcm.length || pcm.length > 7680 || pcm.length % 640 || bytes + pcm.length > 19200000) throw new Error();
        if (bytes + pcm.length > (processedMs + 5000) * 32) return error('ASR_BACKPRESSURE');
        sdk.sendAudio(pcm); // ws/SDK owns its send buffer; do not zero it before the send completes.
        bytes += pcm.length; sequence++;
        emit({ type: 'ack', seq: frame.seq, scope: 'node_sdk_accepted', queueLength: 0 });
      } catch { error('ASR_PROTOCOL_FAILED'); }
    }, close, finish
  };
}

// SDK 2.3.0 keeps the original STT error frame in error.raw; never forward its message.
function providerError(cause) {
  const raw = cause?.raw || {}, type = raw.error_type || cause?.error_type;
  const code = raw.error_code ?? cause?.statusCode;
  if (code === 401 || code === 403 || ['unauthorized','authentication_error','invalid_api_key'].includes(type)) return 'ASR_AUTH_FAILED';
  if (code === 402 || ['insufficient_funds','quota_exceeded'].includes(type)) return 'ASR_QUOTA_EXCEEDED';
  if (code === 429 || type === 'rate_limit_exceeded') return 'ASR_RATE_LIMITED';
  if (code === 400 || ['invalid_request','invalid_model','unsupported_model','model_not_available'].includes(type)) return 'ASR_AUDIO_FORMAT_INVALID';
  return 'ASR_PROVIDER_FAILED';
}
