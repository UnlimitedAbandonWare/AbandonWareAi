(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.AwxDisplayBoot = factory().start(root);
}(typeof window !== 'undefined' ? window : this, function () {
  'use strict';
  function createId(host) {
    if (host.crypto && typeof host.crypto.randomUUID === 'function') return host.crypto.randomUUID().replace(/-/g, '');
    if (host.crypto && typeof host.crypto.getRandomValues === 'function') {
      var bytes = new Uint8Array(16); host.crypto.getRandomValues(bytes);
      return Array.prototype.map.call(bytes, function (n) { return ('0' + n.toString(16)).slice(-2); }).join('');
    }
    // A volatile subscriber correlation label, never an authentication credential.
    var id = ''; for (var i = 0; i < 32; i++) id += Math.floor(Math.random() * 16).toString(16); return id;
  }
  function start(host) {
    var id = createId(host), sent = 0, windowAt = Date.now(), seen = {}, receiverStarted = false;
    var events = ['html_received','init_started','receiver_started','response_received','dom_updated','script_error','resource_error','async_error','transport_error','page_hidden','page_visible'];
    function note(event, data) {
      data = data || {}; if (events.indexOf(event) < 0) return;
      if (event === 'receiver_started') receiverStarted = true;
      var now = Date.now(); if (now - windowAt >= 60000) { sent = 0; windowAt = now; seen = {}; }
      var key = event + ':' + (data.code || 'none');
      if (sent >= 16 || (seen[key] && now - seen[key] < 15000)) return;
      seen[key] = now; sent++;
      if (typeof host.XMLHttpRequest !== 'function') return;
      var code = /^(none|http_[1-5][0-9][0-9]|network|timeout|contract|initialization|unsupported)$/.test(data.code || '') ? data.code : 'none';
      var body = {runtimeId:id,event:event,sequence:Math.max(0,Math.min(9007199254740991,Number(data.sequence)||0)),code:code,visible:!host.document.hidden};
      try {
        var xhr = new host.XMLHttpRequest(); xhr.open('POST','/api/assist/display/relay/diagnostics',true); xhr.timeout = 3000;
        xhr.setRequestHeader('Content-Type','application/json'); xhr.setRequestHeader('X-Display-Client','1'); xhr.setRequestHeader('X-Display-Runtime',id);
        var match = /(?:^|[?&])channel=(test-[a-f0-9]{16,32})(?:&|$)/.exec(host.location.search || '');
        if (match) xhr.setRequestHeader('X-Display-Test-Channel',match[1]);
        xhr.send(JSON.stringify(body));
      } catch (ignored) {}
    }
    function fail(code) {
      var status = host.document.getElementById('connection'); if (status) status.textContent = '초기화 오류 · 고정 화면 유지';
      note('script_error',{code:code || 'initialization'});
    }
    host.addEventListener('error',function (event) { if (event.target && event.target !== host) note('resource_error'); else fail('initialization'); },true);
    host.addEventListener('unhandledrejection',function () { note('async_error'); });
    host.addEventListener('load',function () { if (!receiverStarted) fail('unsupported'); });
    note('html_received');note('init_started');
    return {id:id,note:note,fail:fail,createId:function () {return createId(host);}};
  }
  return {start:start,createId:createId};
}));
