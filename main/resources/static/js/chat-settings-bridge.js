/* Validated browser defaults. Apply through the four existing DOM controls. */
(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else {
    root.AwxSettingsCore = api;
    const start = () => api.installBridge(root);
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', start, { once: true });
    } else start();
  }
})(typeof window === 'undefined' ? globalThis : window, function () {
  'use strict';
  const STORAGE_KEY = 'awx.settings.v1.preferences';
  const REMEMBER_KEY = 'awx.settings.v1.rememberChatChanges';
  const MAX_BYTES = 16384;
  const KEYS = Object.freeze(['model', 'modelSelectionMode', 'searchMode', 'useRag']);
  // Explanatory template fallbacks. Never persist this merged object on page load.
  const DEFAULTS = Object.freeze({ modelSelectionMode: 'preferred', searchMode: 'OFF', useRag: false });
  const IDS = Object.freeze({ model: 'modelSelect', modelSelectionMode: 'modelSelectionMode',
    searchMode: 'searchModeSelect', useRag: 'useRagToggle' });

  function isRecord(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const proto = Object.getPrototypeOf(value);
    return proto === Object.prototype || proto === null;
  }
  function validModelId(value) {
    return typeof value === 'string' && value.length <= 180 &&
      /^[a-zA-Z0-9][a-zA-Z0-9._/:+-]*$/.test(value) &&
      !/^(sk-|AIza|eyJ)/.test(value);
  }
  function validateValues(value) {
    if (!isRecord(value)) throw new Error('설정은 객체여야 합니다.');
    const clean = {};
    for (const key of Object.keys(value)) {
      if (!KEYS.includes(key)) throw new Error('지원하지 않는 설정 키가 있습니다.');
      const v = value[key];
      const valid = key === 'model' ? validModelId(v) : key === 'useRag' ? typeof v === 'boolean' :
        key === 'modelSelectionMode' ? ['preferred', 'strict', 'auto'].includes(v) :
          ['AUTO', 'OFF', 'FORCE_LIGHT', 'FORCE_DEEP'].includes(v);
      if (!valid) throw new Error('설정 값의 형식 또는 범위가 맞지 않습니다.');
      clean[key] = v;
    }
    return clean;
  }
  function mergeDefaults(value) {
    return Object.assign({}, DEFAULTS, validateValues(value));
  }
  function exportSettings(value) {
    return JSON.stringify({ version: 1, values: validateValues(value) }, null, 2);
  }
  function importSettings(text) {
    if (typeof text !== 'string' || text.length > MAX_BYTES ||
        new TextEncoder().encode(text).byteLength > MAX_BYTES) {
      throw new Error('설정 파일은 UTF-8 기준 16 KiB 이하여야 합니다.');
    }
    let envelope;
    try { envelope = JSON.parse(text); } catch { throw new Error('올바른 JSON 파일이 아닙니다.'); }
    if (!isRecord(envelope) || envelope.version !== 1 || !Object.hasOwn(envelope, 'values') ||
        Object.keys(envelope).some(key => !['version', 'values'].includes(key))) {
      throw new Error('지원하지 않는 설정 파일 버전 또는 구조입니다.');
    }
    return validateValues(envelope.values);
  }
  function readSettings(storage) {
    const raw = storage.getItem(STORAGE_KEY);
    return raw === null ? {} : importSettings(raw);
  }
  function writeSettings(storage, value) {
    const clean = validateValues(value);
    // One synchronous setItem: failed validation never touches storage.
    storage.setItem(STORAGE_KEY, exportSettings(clean));
    return clean;
  }
  function withoutKeys(value, keys) {
    const next = validateValues(value);
    for (const key of keys) {
      if (!KEYS.includes(key)) throw new Error('초기화 범위를 확인해 주세요.');
      delete next[key];
    }
    return next;
  }

  const PREFERENCE_KEYS = Object.freeze([...KEYS, 'temperature', 'topP', 'frequencyPenalty',
    'presencePenalty', 'maxTokens', 'useWebSearch', 'ragAnswerPolicy']);
  const CACHE_KEY = 'awx.settings.v2.preferences';
  function validatePreferences(value) {
    if (!isRecord(value)) throw new Error('설정은 객체여야 합니다.');
    const clean = {};
    for (const [key, v] of Object.entries(value)) {
      if (!PREFERENCE_KEYS.includes(key)) throw new Error('지원하지 않는 설정 키입니다.');
      if (KEYS.includes(key)) Object.assign(clean, validateValues({[key]:v}));
      else {
        const ranges = {temperature:[0,2], topP:[0,1], frequencyPenalty:[-2,2], presencePenalty:[-2,2], maxTokens:[1,2147483647]};
        const valid = key === 'useWebSearch' ? typeof v === 'boolean' :
          key === 'ragAnswerPolicy' ? ['adaptive','evidence_only'].includes(v) :
            typeof v === 'number' && Number.isFinite(v) && v >= ranges[key][0] && v <= ranges[key][1]
              && (key !== 'maxTokens' || Number.isInteger(v));
        if (!valid) throw new Error('설정 값의 형식 또는 범위가 맞지 않습니다.');
        clean[key] = v;
      }
    }
    return clean;
  }
  function snapshot(value) {
    if (!isRecord(value) || !Number.isSafeInteger(value.revision) || value.revision < 0 ||
        !(value.hash === null || /^[0-9a-f]{64}$/.test(value.hash)) ||
        typeof value.defaultsVersion !== 'string' || value.defaultsVersion.length > 64) throw new Error('설정 응답 형식 오류');
    const sources = {};
    for (const [key, source] of Object.entries(value.sources || {})) {
      if (!PREFERENCE_KEYS.includes(key) || !['REQUEST','SESSION','USER','ADMIN_DB','ADMIN_CONFIG','FACTORY'].includes(source)) throw new Error('설정 출처 형식 오류');
      sources[key] = source;
    }
    return {overrides:validatePreferences(value.overrides), effective:validatePreferences(value.effective),
      factoryDefaults:validatePreferences(value.factoryDefaults), sources, revision:value.revision,
      hash:value.hash, defaultsVersion:value.defaultsVersion, ownerScope:'cookie'};
  }
  async function requestPreferences(win, options) {
    const response = await win.fetch('/api/settings/preferences', Object.assign({
      credentials:'same-origin', cache:'no-store', headers:{Accept:'application/json'}
    }, options));
    if (!response.ok || response.redirected) {
      const error = new Error('개인 설정 요청 실패 (' + response.status + ')');
      error.status = response.status;
      throw error;
    }
    return snapshot(await response.json());
  }
  function cacheSnapshot(win, value) {
    try { win.localStorage.setItem(CACHE_KEY, JSON.stringify({version:2, ...value})); } catch { /* cache is optional */ }
  }
  async function readPreferences(win) {
    const value = await requestPreferences(win);
    cacheSnapshot(win, value);
    return value;
  }
  async function savePreferences(win, baseline, set, unset = [], migrate = false) {
    const clean = validatePreferences(set);
    if (unset.some(key => !PREFERENCE_KEYS.includes(key) || Object.hasOwn(clean,key))) throw new Error('초기화 범위 오류');
    const ack = await requestPreferences(win, {method:'PATCH', headers:{Accept:'application/json','Content-Type':'application/json'},
      body:JSON.stringify({expectedRevision:baseline.revision, expectedHash:baseline.hash, set:clean, unset})});
    const observed = await readPreferences(win);
    if (ack.revision !== observed.revision || ack.hash !== observed.hash) throw new Error('저장 확인 충돌 · 다시 읽어 주세요.');
    if (migrate) {
      try { win.localStorage.removeItem(STORAGE_KEY); } catch { /* legacy copy survives a storage error */ }
    }
    return observed;
  }
  function importPreferences(text) {
    if (typeof text !== 'string' || new TextEncoder().encode(text).byteLength > MAX_BYTES) throw new Error('설정 파일 크기 제한');
    const envelope = JSON.parse(text);
    if (!isRecord(envelope) || ![1,2].includes(envelope.version) ||
        Object.keys(envelope).some(key => !['version','values'].includes(key))) throw new Error('설정 파일 형식 오류');
    return validatePreferences(envelope.values);
  }

  function installBridge(win) {
    const doc = win.document;
    const controls = Object.fromEntries(KEYS.map(key => [key, doc.getElementById(IDS[key])]));
    if (KEYS.some(key => !controls[key]) || controls.model.dataset.awxSettingsBridge === 'bound') return;
    controls.model.dataset.awxSettingsBridge = 'bound';
    const save = doc.getElementById('chat-save-defaults');
    const status = doc.getElementById('chat-defaults-status');
    const send = doc.getElementById('sendBtn');
    let baseline = null, pending = {}, applying = false, catalogReady = false, generation = 0, ready = false;
    const edited = new Set();
    const freshSession = () => {
      try { return !win.sessionStorage.getItem('chat.currentSessionId') && !win.sessionStorage.getItem('chat.activeRun'); }
      catch { return false; }
    };
    function message(code, text) {
      controls.model.dataset.awxSettingsStatus = code;
      if (status) status.textContent = text || code;
      doc.dispatchEvent(new win.CustomEvent('awx:settings-bridge-status', {detail:{code}}));
    }
    function gate(value) {
      ready = value; controls.model.dataset.awxSettingsReady = value ? 'ready' : 'loading';
      if (send && !value) send.disabled = true;
      if (save) save.disabled = !value;
    }
    function selectable(control, value) {
      return Array.from(control.options || []).some(option => option.value === value && !option.disabled && !option.parentElement?.disabled);
    }
    function applyAvailable() {
      if (applying || !baseline || !catalogReady) return;
      if (!freshSession()) { pending = {}; gate(true); message('session-preserved','현재 대화 설정 유지'); return; }
      const mode = pending.modelSelectionMode;
      applying = true;
      try {
        for (const key of Object.keys(pending)) {
          const control = controls[key];
          // The existing request builder maps auto mode to llmrouter.auto; the catalog keeps concrete choices.
          if (key === 'model' && pending[key] === 'llmrouter.auto' && mode === 'auto') { delete pending[key]; continue; }
          if (control.disabled || (key !== 'useRag' && !selectable(control, pending[key]))) continue;
          const value = pending[key];
          if (key === 'useRag') control.checked = value; else control.value = value;
          delete pending[key];
          control.dispatchEvent(new win.Event('change', {bubbles:true}));
        }
        // The picker can change mode synchronously when model changes.
        if (mode && selectable(controls.modelSelectionMode,mode)) {
          controls.modelSelectionMode.value = mode;
          controls.modelSelectionMode.dispatchEvent(new win.Event('change', {bubbles:true}));
        }
      } finally { applying = false; }
      gate(Object.keys(pending).length === 0 && selectable(controls.model,controls.model.value));
      message(ready ? 'server-applied' : 'pending-unavailable', ready ? '서버 개인 기본값 적용 · 현재 선택은 저장 버튼으로 저장' : '저장된 모델 가용성 확인 필요 · 사용할 모델을 선택하세요.');
    }
    async function begin() {
      const ticket = ++generation; pending = {}; edited.clear();
      if (!freshSession()) { gate(true); message('session-preserved','현재 대화 설정 유지'); return; }
      gate(false); message('server-loading','서버 개인 설정 확인 중');
      try {
        const value = await readPreferences(win);
        if (ticket !== generation || !freshSession()) return;
        baseline = value;
        pending = Object.fromEntries(KEYS.filter(key => !edited.has(key) && Object.hasOwn(value.effective,key)).map(key => [key,value.effective[key]]));
        applyAvailable();
      } catch { if (ticket === generation) { gate(false); message('server-unavailable','개인 설정을 확인하지 못했습니다 · 페이지를 다시 열어 주세요.'); } }
    }
    for (const [key, control] of Object.entries(controls)) control.addEventListener('change', () => {
      if (applying) return;
      // Changing the composer affects this conversation only. It is never a save.
      edited.add(key); delete pending[key];
      if (baseline && catalogReady && Object.keys(pending).length === 0 && selectable(controls.model, controls.model.value)) gate(true);
    });
    doc.addEventListener('chat:model-catalog', event => {
      catalogReady = event.detail?.hydrated === true || event.detail?.ready === true;
      if (catalogReady) applyAvailable(); else if (freshSession()) gate(false);
    });
    if (save) save.addEventListener('click', async () => {
      if (!ready) return;
      const set = Object.fromEntries(KEYS.map(key => [key, key === 'useRag' ? controls[key].checked : controls[key].value]));
      try { baseline = await savePreferences(win,baseline || await readPreferences(win),set); message('server-saved','개인 기본값 저장됨 · 다음 새 대화부터 적용'); }
      catch { message('save-failed','저장 실패 · 현재 대화 선택은 유지합니다. 설정 페이지에서 서버 값을 확인하세요.'); }
    });
    doc.addEventListener('brain-state:session', event => {
      if (event.detail?.sessionId) { generation++; pending = {}; gate(true); message('session-preserved','현재 대화 설정 유지'); }
      else void begin();
    });
    doc.addEventListener('click', event => {
      if (!ready && event.target?.closest?.('#sendBtn')) { event.preventDefault(); event.stopImmediatePropagation(); }
    }, true);
    doc.addEventListener('keydown', event => {
      if (!ready && event.key === 'Enter' && !event.shiftKey && event.target?.closest?.('textarea')) {
        event.preventDefault(); event.stopImmediatePropagation();
      }
    }, true);
    win.addEventListener('storage', event => {
      if (event.key === CACHE_KEY || event.key === null) {
        void readPreferences(win).then(value => {baseline=value; message('next-new-chat','다른 탭의 저장 확인 · 다음 새 대화부터 적용');})
          .catch(() => message('server-unavailable','서버 설정 재확인 실패'));
      }
    });
    win.addEventListener('pagehide', () => { generation++; });
    void begin();
  }
  return Object.freeze({ STORAGE_KEY, REMEMBER_KEY, MAX_BYTES, DEFAULTS, KEYS, validModelId,
    validateValues, mergeDefaults, exportSettings, importSettings, readSettings,
    writeSettings, withoutKeys, installBridge, PREFERENCE_KEYS, CACHE_KEY, validatePreferences,
    readPreferences, savePreferences, importPreferences });
});
