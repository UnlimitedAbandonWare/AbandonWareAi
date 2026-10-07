(function (root) {
  'use strict';
  const API = '/api/chat/sessions';
  function bind({ getCurrentSessionId, document = root.document, fetch = root.fetch.bind(root),
      URL = root.URL, download = null } = {}) {
    const panel = document.querySelector('[data-conversation-export]');
    if (!panel || typeof getCurrentSessionId !== 'function') return null;
    const list = panel.querySelector('[data-export-options]');
    const status = panel.querySelector('[data-export-status]');
    const more = panel.querySelector('[data-export-more]');
    const buttons = Array.from(panel.querySelectorAll('[data-export-format]'));
    const fresh = panel.querySelector('[data-export-fresh]');
    let selected = new Set(), capture = null, epoch = 0, busy = false, cursor = '', loaded = false, pendingReload = false;
    let selectionChangedByUser = false;
    const rows = new Map();
    const current = () => {
      const value = String(getCurrentSessionId() ?? '');
      return /^[1-9][0-9]{0,18}$/.test(value) ? value : null;
    };
    const say = text => { status.textContent = text; };
    function invalidate() { epoch++; capture = null; }
    function controls() {
      buttons.forEach(b => { b.disabled = busy || selected.size === 0; });
      fresh.disabled = busy || selected.size === 0;
      more.disabled = busy;
      rows.forEach(({ checkbox }) => { checkbox.disabled = busy; });
    }
    function row(id, title) {
      if (rows.has(id)) return;
      const label = document.createElement('label');
      const checkbox = document.createElement('input'); checkbox.type = 'checkbox';
      checkbox.checked = selected.has(id); checkbox.dataset.sessionId = id;
      const name = document.createElement('span'); name.textContent = title;
      checkbox.addEventListener('change', () => {
        if (checkbox.checked && selected.size >= 10) {
          checkbox.checked = false; say('최대 10개 대화를 선택할 수 있습니다.'); return;
        }
        selectionChangedByUser = true;
        if (checkbox.checked) selected.add(id); else selected.delete(id);
        invalidate(); controls(); say(selected.size ? '선택한 본인 대화를 새로 캡처합니다.' : '다운로드할 대화를 선택하세요.');
      });
      label.append(checkbox, name); list.append(label); rows.set(id, { checkbox, label });
    }
    async function response(res) {
      if (res.ok) return res;
      let reason = '';
      try { reason = (await res.json()).reasonCode || ''; } catch { /* safe transport fallback */ }
      const messages = {
        400: '다운로드할 대화를 선택하세요.', 403: '본인 대화 소유권을 확인할 수 없습니다.',
        404: '선택한 대화에 접근할 수 없습니다. 선택 목록을 다시 확인하세요.',
        409: '캡처 중 대화가 변경되었습니다. 새로 캡처하세요.',
        410: '캡처가 만료되었습니다. 새로 캡처하세요.',
        413: '내보내기 용량 제한을 초과했습니다. 선택한 대화 수를 줄이세요.',
        503: '내보내기가 사용 중이거나 저장소를 읽을 수 없습니다. 잠시 후 다시 시도하세요.'
      };
      const error = new Error(messages[res.status] || '다운로드를 완료하지 못했습니다. 다시 시도하세요.');
      error.status = res.status; error.reasonCode = reason; throw error;
    }
    async function load() {
      if (busy) return;
      busy = true; controls(); const generation = epoch;
      try {
        const res = await response(await fetch(`${API}/export-options?limit=50${cursor ? `&before=${encodeURIComponent(cursor)}` : ''}`,
          { credentials: 'same-origin', cache: 'no-store' }));
        const data = await res.json();
        if (generation !== epoch) return;
        data.items.forEach(item => row(String(item.sessionId), String(item.title || '대화')));
        cursor = data.nextCursor || ''; more.hidden = !data.hasMore; loaded = true;
        say(data.hasMore ? '이전 본인 대화가 더 있습니다. 더 불러오기를 누르세요.' : rows.size ? '본인 대화를 선택한 뒤 다운로드하세요.' : '보존된 본인 대화가 없습니다.');
      } catch (error) { if (generation === epoch) say(error.message); }
      finally { busy = false; controls(); if (pendingReload) { pendingReload = false; void load(); } }
    }
    function csrf() {
      const token = document.querySelector('meta[name="_csrf"]')?.content;
      const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
      return token ? { [header]: token } : {};
    }
    async function captureSelection(generation) {
      if (capture) return capture;
      const ids = Array.from(selected); const sid = current();
      const body = { sessionIds: ids, currentSessionId: sid && selected.has(sid) ? sid : null };
      const res = await response(await fetch(`${API}/exports`, { method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', ...csrf() }, body: JSON.stringify(body) }));
      const result = await res.json();
      if (generation !== epoch) return null;
      capture = result;
      return result;
    }
    function captureNote(result) {
      const fences = result.snapshot.fences.map(f => `${f.sessionId}:${f.highWatermark}`).join(', ');
      return `캡처 ${result.exportedAt} · 메시지 경계 ${fences} · ${result.snapshot.exportStatus === 'partial'
        ? '일부 과거 진단은 보존되지 않았습니다.' : result.snapshot.exportStatus === 'empty' ? '보존된 메시지가 없습니다.' : '수집 완료'}`;
    }
    async function save(format) {
      if (busy || selected.size === 0) return;
      busy = true; controls(); const generation = epoch;
      say('선택한 대화를 캡처하고 다운로드합니다.');
      try {
        const result = await captureSelection(generation);
        if (!result || generation !== epoch) return;
        const res = await response(await fetch(`${API}/exports/${encodeURIComponent(result.exportId)}?format=${format}`,
          { credentials: 'same-origin', cache: 'no-store' }));
        const blob = await res.blob();
        if (generation !== epoch) return;
        const filename = `conversation-context-${result.exportId}.${format}`;
        if (download) await download(blob, filename);
        else {
          const url = URL.createObjectURL(blob);
          try {
            const a = document.createElement('a'); a.href = url; a.download = filename;
            document.body.append(a); a.click(); a.remove();
          } finally { root.setTimeout(() => URL.revokeObjectURL(url), 1000); }
        }
        say(captureNote(result));
      } catch (error) {
        if (generation === epoch) {
          if ([403, 404, 409, 410].includes(error.status)) invalidate();
          say(error.message);
        }
      } finally { busy = false; controls(); if (pendingReload) { pendingReload = false; void load(); } }
    }
    function syncSession() {
      const sid = current();
      // Same-session events do not replace a retained JSON/ZIP pair.
      if (sid === lastSession) return;
      lastSession = sid; invalidate(); selected = new Set(sid ? [sid] : []);
      selectionChangedByUser = false; loaded = false; cursor = ''; rows.clear(); list.replaceChildren();
      if (sid) row(sid, '현재 대화'); more.hidden = true; controls();
      say(sid ? '현재 대화가 기본 선택되었습니다.' : '저장된 대화를 선택하세요.');
      if (panel.open) { if (busy) pendingReload = true; else void load(); }
    }
    let lastSession;
    syncSession();
    panel.addEventListener('toggle', () => { if (panel.open && !loaded) void load(); });
    more.addEventListener('click', () => { void load(); });
    fresh.addEventListener('click', () => { invalidate(); say('다음 다운로드에서 새로 캡처합니다.'); });
    buttons.forEach(button => button.addEventListener('click', () => { void save(button.dataset.exportFormat); }));
    document.addEventListener('brain-state:session', syncSession);
    document.addEventListener('brain-state:answer', () => {
      if (capture) say(`${captureNote(capture)} · 이후 대화가 변경되었을 수 있습니다. 최신 자료는 새로 캡처하세요.`);
    });
    return { load, save, syncSession, invalidate,
      getState: () => ({ sessionIds: Array.from(selected), capture, busy, selectionChangedByUser }) };
  }
  root.ChatConversationExport = { bind };
})(typeof window === 'undefined' ? globalThis : window);
