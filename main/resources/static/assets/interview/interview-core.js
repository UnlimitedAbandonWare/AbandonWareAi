(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.InterviewCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  function cardText(text, kind) {
    if (!['question', 'hint', 'answer'].includes(kind)) throw new Error('invalid-kind');
    if (typeof text !== 'string' || !text.trim()) throw new Error('empty-card');
    if (Array.from(text).length > 120) throw new Error('card-too-long');
    return text;
  }
  function deliveryState(snapshot, sent, now = Date.now()) {
    if (!snapshot || snapshot.state !== 'RUNNING' || (sent && snapshot.epoch !== sent.epoch)) return 'DISCONNECTED';
    if (!sent) return snapshot.outputConnections > 0 ? 'CONNECTED' : 'WAITING_CONNECTION';
    if (!snapshot.card || snapshot.card.expiresAt <= now) return 'EXPIRED';
    const m = snapshot.metrics || {};
    if (m.lastOutputAckEpoch === sent.epoch && m.lastOutputAckVersion >= sent.version) return 'RECEIVER_ACK';
    return 'WAITING_RECEIPT';
  }
  function debugEvent(stage, data = {}) {
    const row = { stage: /^[a-z._-]{1,48}$/.test(stage) ? stage : 'unknown' };
    for (const key of ['httpStatus', 'roundTripMs', 'sourceCount', 'chars', 'epoch', 'version', 'outputs']) {
      if (Number.isFinite(data[key]) && data[key] >= 0) row[key] = Math.round(data[key]);
    }
    for (const key of ['requestId', 'reason', 'kind']) {
      if (typeof data[key] === 'string' && /^[a-zA-Z0-9._:-]{1,128}$/.test(data[key])) row[key] = data[key];
    }
    return row;
  }
  function receiverUrl(origin, id, epoch) {
    if (!/^[a-f0-9-]{36}$/i.test(id) || !Number.isSafeInteger(epoch) || epoch < 1) throw new Error('invalid-output-session');
    const url = new URL('/assets/display/receiver.html', origin);
    url.hash = new URLSearchParams({ session: id, epoch: String(epoch) }).toString();
    return url.href;
  }
  function shortHint(answer) {
    if (typeof answer !== 'string' || !answer.trim()) throw new Error('summary-needs-edit');
    const text=answer.trim();
    const sentences=typeof Intl.Segmenter==='function'?[...new Intl.Segmenter('ko',{granularity:'sentence'}).segment(text)].flatMap(s=>s.segment.split('\n')):text.split(/(?<=[.!?。])\s+|\n/u);
    const clean=sentences.map(s=>s.trim().replace(/^(?:#{1,6}\s+|[-*]\s+)/u,'')).filter(Boolean);
    for(let i=0;i<clean.length;i++){
      let selected=clean[i];
      if(Array.from(selected).length<12||Array.from(selected).length>120)continue;
      if(clean[i+1]&&/^(단[ ,]|다만|그러나|하지만|조건|unless\b|except\b|only\b)/iu.test(clean[i+1]))selected+=' '+clean[i+1];
      if(Array.from(selected).length>120)throw new Error('summary-needs-edit');
      return selected;
    }
    if(Array.from(text).length<=120)return text;
    throw new Error('summary-needs-edit');
  }
  function publicOrigin(value) {
    try{const u=new URL(value);return u.protocol==='https:'&&!u.username&&!u.password&&u.pathname==='/'&&!u.search&&!u.hash?u.origin:'';}catch(_){return '';}
  }
  function outputClientId() {return Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');}
  // Projection of the already received response. No excerpts, URLs, storage or generation.
  function ragCardMetadata(state) {
    const requestId=state?.metrics?.requestId;
    if(state?.phase!=='RESULT'||typeof requestId!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(requestId))return {};
    const sourceTitles=(Array.isArray(state.result?.sources)?state.result.sources:[]).slice(0,4)
      .map(source=>[source?.marker,source?.title].filter(value=>typeof value==='string'&&value.trim()).join(' '))
      .map(title=>Array.from(title.replace(/[\u0000-\u001f\u007f-\u009f]/g,' ').trim()).slice(0,120).join('')).filter(Boolean);
    return {requestId,sourceTitles};
  }
  return { cardText, deliveryState, debugEvent, receiverUrl, shortHint, publicOrigin, outputClientId, ragCardMetadata };
});
