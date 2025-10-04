/**
 * chat.js – AI 서비스 통합 Front-End (Unified & Patched Version, DTO Aligned)
 * 경로: src/main/resources/static/js/chat.js
 * -----------------------------------------------------------------------
 * @version 2025-07-02
 * @description
 * • 백엔드 DTO 변경 반영 → 응답 필드 `reply`→`content`, `model`→`modelUsed`.
 * • 잘 작동하던 {스터프13} 기능과 {스터프19} 마이그레이션 코드를 완전 통합.
 * • `useRag` 파라미터 제거 및 관련 UI 토글 유지(백엔드 미사용).
 * • 세션 ID 갱신 로직이 `useHistory` 옵션과 무관하게 동작하도록 수정.
 * • RAG 검색, 적응형 번역, 모델 관리, 미세 조정 등 모든 모드를 지원.
 * • 기능 단위(상태, DOM, 헬퍼, 세션, 설정, 채팅, 관리자)로 코드 구조를 재정리.
 */

import { apiCall } from "./fetch-wrapper.js";
// Import the geolocation helper to watch the user's position.  This module
// wraps the browser's Geolocation API and invokes the supplied callback
// whenever a new coordinate is available.  When the location toggle is
// turned off the returned watch ID can be cleared via
// navigator.geolocation.clearWatch().
import { startWatch } from "./geo.js";

(() => {
  "use strict";
    // CSRF (SSE에도 적용)
    const CSRF = {
      header: document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN',
      token : document.querySelector('meta[name="_csrf"]')?.content || ''
    };

  // Key for persisting the understanding toggle in localStorage
  const UNDERSTANDING_TOGGLE_KEY = 'aw.understanding.enabled';

  // 🔐 Chat UI preference bundle 저장 키
  const PREFS_KEY = 'aw.chat.prefs.v2';

  // Key for storing the identifier of the last active session.  When the
  // page reloads or the user returns to the chat tab this value is used
  // to automatically restore the previous session and, if still running,
  // attach to the in‑flight generation via the /api/chat/stream?attach=true
  // endpoint.
  const LAST_SESSION_KEY = 'aw.chat.lastSessionId';

  /**
   * Read previously saved UI preferences from localStorage.  If the value
   * cannot be parsed or is absent an empty object is returned instead.
   *
   * @returns {Object} An object containing persisted preference values
   */
  function loadPrefs() {
    try {
      return JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
    } catch {
      return {};
    }
  }

  /**
   * Persist a patch of preference values to localStorage.  The existing
   * preferences are merged with the supplied patch and then stringified
   * back into storage.  Any failure to write is silently ignored.
   *
   * @param {Object} patch A partial set of preferences to persist
   * @returns {Object} The merged preferences that were written to storage
   */
  function savePrefs(patch) {
    const next = { ...loadPrefs(), ...patch };
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify(next));
    } catch {
      // ignore storage errors
    }
    return next;
  }

  /**
   * Save the current UI state (toggles, selects, slider values) into
   * localStorage.  Each call will merge the existing preferences with
   * the latest values exposed in the DOM.  Undefined values are left
   * untouched so that they do not overwrite previous entries.
   */
  function saveUIPrefs() {
    // Selected web providers need to be converted into an array of values
    const providers = Array.from(dom.webProvidersSelect?.selectedOptions || []).map(o => o.value);
    savePrefs({
      precisionSearch: !!dom.precisionToggle?.checked,
      precisionTopK: dom.sliders?.searchTopK?.el ? +dom.sliders.searchTopK.el.value : undefined,
      officialSourcesOnly: !!dom.officialSourcesOnly?.checked,
      searchMode: dom.searchModeSelect?.value,
      webProviders: providers,
      locationEnabled: !!dom.locToggle?.checked,
      geminiLearning: !!dom.useGeminiLearning?.checked
    });
  }

  /**
   * Apply a set of persisted preferences back onto the UI.  Each known
   * preference property is checked before assignment to avoid clobbering
   * defaults when a property was never saved.  Precision search will
   * automatically force the search mode to FORCE_DEEP when enabled.
   *
   * @param {Object} p The preference object returned from loadPrefs()
   */
  function applyPrefsToUI(p = loadPrefs()) {
    // precisionSearch toggle
    if (dom.precisionToggle && typeof p.precisionSearch === 'boolean') {
      dom.precisionToggle.checked = p.precisionSearch;
    }
    // precisionTopK slider
    if (dom.sliders?.searchTopK?.el && typeof p.precisionTopK === 'number') {
      dom.sliders.searchTopK.el.value = p.precisionTopK;
      dom.sliders.searchTopK.val.textContent = String(p.precisionTopK);
    }
    // official sources only toggle
    if (dom.officialSourcesOnly && typeof p.officialSourcesOnly === 'boolean') {
      dom.officialSourcesOnly.checked = p.officialSourcesOnly;
    }
    // search mode select
    if (dom.searchModeSelect && p.searchMode) {
      dom.searchModeSelect.value = p.searchMode;
    }
    // web providers multi-select
    if (dom.webProvidersSelect && Array.isArray(p.webProviders)) {
      Array.from(dom.webProvidersSelect.options).forEach(opt => {
        opt.selected = p.webProviders.includes(opt.value);
      });
    }
    // gemini learning toggle
    if (dom.useGeminiLearning && typeof p.geminiLearning === 'boolean') {
      dom.useGeminiLearning.checked = p.geminiLearning;
    }
    // location toggle
    if (dom.locToggle && typeof p.locationEnabled === 'boolean') {
      dom.locToggle.checked = p.locationEnabled;
    }
    // When precision search is enabled, ensure search mode is forced to deep
    if (dom.precisionToggle?.checked && dom.searchModeSelect) {
      dom.searchModeSelect.value = 'FORCE_DEEP';
    }
  }

  /* --------------------------------------------------
   * 1. 전역 상태 (Global State)
   * -------------------------------------------------- */
  const state = {
  pendingAttachments: [],
    currentSessionId: null,
    chatHistory: [],
    isLoading: false,
    isInitialLoad: true,
    /**
     * Flag indicating whether the current streaming request has been cancelled by the user.
     * When true, intermediate SSE events will be ignored until the final event arrives.
     */
    isCancelled: false,
    /**
     * 현재 선택된 첨부 파일. 파일 업로드 기능은 프론트에서만 표시되며
     * 서버로 전송하지 않습니다. 선택되지 않은 경우 null입니다.
     */
    selectedFile: null,
    /**
     * 지오로케이션 감시 ID입니다. 사용자가 위치 기반 기능을 켜면
     * startWatch()를 호출하여 이 필드에 반환된 ID를 저장합니다.  토글을
     * 끄면 이 ID를 navigator.geolocation.clearWatch()로 해제합니다.
     */
    geoWatchId: null,
  };

  // ==================================================
  // Feedback and model badge utilities (added by patch)
  // ==================================================
  // Toggle to enable or disable feedback controls. Set to true
  // to show like/dislike buttons. Duplicates are prevented within
  // attachFeedbackControls itself.
  const FEEDBACK_ENABLED = true;

  // Keys used for persisting model badges across sessions
  const STORAGE_KEYS = { modelBadgeBySession: 'chat.modelBadgeBySession' };

  /**
   * Insert or update a model badge on the provided message wrapper element.
   * The badge displays the model name one time above the assistant message.
   * If a badge already exists, it is updated instead of duplicated.
   */
  function upsertModelBadge(wrap, model) {
    if (!wrap || !model) return;
    let label = wrap.querySelector('[data-role="model-label"]');
    if (!label) {
      label = document.createElement('small');
      label.dataset.role = 'model-label';
      label.className = 'text-muted d-block mb-1 ms-3';
      wrap.prepend(label);
    }
    // Escape any HTML in the model name to prevent injection
    label.innerHTML = `<i class="bi bi-robot me-1"></i>model: <strong>${escapeHtml(String(model))}</strong>`;
  }

  /**
   * Remove any legacy 'model:' prefix from within the assistant message bubble.
   * This ensures the model name appears only once in the dedicated badge and
   * not inside the message body.
   */
  function stripModelPrefix(bubbleEl) {
    if (!bubbleEl) return;
    // Remove text nodes that start with 'model:' on the first line
    bubbleEl.childNodes.forEach(node => {
      if (node.nodeType === Node.TEXT_NODE && /^\s*model:\s*/i.test(node.textContent)) {
        node.textContent = node.textContent.replace(/^\s*model:\s.*(?:\n|$)/i, '');
      }
    });
    // Remove any block or inline element whose text starts with 'model:'
    bubbleEl.querySelectorAll('p,div,span,small,br').forEach(el => {
      if (/^\s*model:\s*/i.test(el.textContent || '')) el.remove();
    });
    // Fallback: use regex across the innerHTML to remove the first line with model:
    bubbleEl.innerHTML = bubbleEl.innerHTML.replace(/^(?:\s|<(?:p|div|span|br)[^>]*>)*model:\s.*?(?:\n|<br\s*\/?>|<\/p>|<\/div>|<\/span>)/i, '');
  }

  /**
   * Persist the association between a chat session ID and the model name of the
   * last assistant response.  This allows the badge to be restored when the
   * user revisits a session.
   */
  function persistModelBadge(sessionId, model) {
    if (!sessionId || !model) return;
    try {
      const map = JSON.parse(localStorage.getItem(STORAGE_KEYS.modelBadgeBySession) || '{}');
      map[String(sessionId)] = String(model);
      localStorage.setItem(STORAGE_KEYS.modelBadgeBySession, JSON.stringify(map));
    } catch {
      // ignore storage errors
    }
  }

  /**
   * Restore a persisted model badge for the given session ID.  The badge is
   * applied to the last assistant message wrapper in the chat history.
   */
  function restoreModelBadgeFromStorage(sessionId) {
    try {
      const map = JSON.parse(localStorage.getItem(STORAGE_KEYS.modelBadgeBySession) || '{}');
      const model = map[String(sessionId)];
      if (!model) return;
      const nodes = dom.chatMessages?.querySelectorAll('.msg-container.msg-assistant-container');
      const wrap = nodes?.[nodes.length - 1] || dom.chatMessages?.lastElementChild;
      if (wrap) upsertModelBadge(wrap, model);
    } catch {
      // ignore storage errors
    }
  }

  // Keys and defaults for applying a location bias to ambiguous queries
  const LOCATION_BIAS_KEY = 'search.locationBias';
  const DEFAULT_BIAS = { city: '대전', country: '대한민국', language: 'ko' };

  // Prefix for understanding summary meta persisted as a system message.
  // When encountered, the JSON payload will be parsed and rendered as a card.
  const USUM_META_PREFIX = '⎔USUM⎔';


  /**
   * Read the location bias object from localStorage, falling back to defaults.
   */
  function getLocationBias() {
    try {
      return { ...DEFAULT_BIAS, ...(JSON.parse(localStorage.getItem(LOCATION_BIAS_KEY) || '{}')) };
    } catch {
      return { ...DEFAULT_BIAS };
    }
  }

  /**
   * Detect whether a query about DW academy requires a Daejeon bias.
   * We look for DW academy terms without any explicit location hints.
   */
  function needsDaejeonBias(text) {
    const t = (text || '').toLowerCase().replace(/\s+/g, '');
    const looksDW = /(dw아카데미|dwacademy|dw아카데미학원)/.test(t);
    const hasLoc = /(대전|daejeon|대한민국|한국|korea|\bkr\b)/i.test(text);
    return looksDW && !hasLoc;
  }

  /**
   * If RAG is enabled and the query is ambiguous about location, append
   * a location hint indicating Daejeon, Korea. Otherwise return the query
   * unchanged.
   */
  function applyLocationBiasIfNeeded(text) {
    if (!dom.useRag?.checked) return text;
    if (!needsDaejeonBias(text)) return text;
    const { city, country, language } = getLocationBias();
    return `${text} (지역우선: ${city}, ${country}; 언어: ${language})`;
  }

  /* --------------------------------------------------
   * 2. DOM 요소 캐시
   * -------------------------------------------------- */
  const $ = (id) => document.getElementById(id);
  const dom = {
    // 채팅 관련
    chatWindow: $("chatWindow"),
    chatMessages: $("chatMessages"),
    messageInput: $("messageInput"),
    sendBtn: $("sendBtn"),
    // 첨부 파일 UI 요소들
    attachBtn: $("attachBtn"),
    fileInput: $("fileInput"),
    selectedFileWrap: $("selectedFileWrap"),
    selectedFileName: $("selectedFileName"),
    clearFileBtn: $("clearFileBtn"),
     // ✨ DOM 객체에 새 버튼 추가 (관리 용이)
     toggleToolsBtn: $("toggleToolsBtn"), // 버튼에 id="toggleToolsBtn" 추가 필요
     toolsCollapse: $("toolsCollapse"),
    // 세션 관련
    newChatBtn: $("newChatBtn"),
    sessionList: $("sessionList"),
    // 상단 옵션 토글(백엔드는 사용X 하지만 UI 유지)
    useRag: $("useRag"),
    useAdaptiveTranslator: $("useAdaptiveTranslator"),
    autoTranslate: $("autoTranslate"),
    useHistory: $("useHistory"),
    usePolish: $("usePolish"),
    // GPT 상세 설정
    advWrap: $("advWrap"),
    saveSettingsBtn: $("saveSettingsBtn"),
    systemPromptInput: $("systemPromptInput"),
    // 정밀 검색 토글
    precisionToggle: $("precisionToggle"),
    sliders: {
      temperature: { el: $("temperatureSlider"), val: $("temperatureValue") },
      topP: { el: $("topPSlider"), val: $("topPValue") },
      frequencyPenalty: { el: $("freqPenaltySlider"), val: $("freqPenaltyValue") },
      presencePenalty: { el: $("presPenaltySlider"), val: $("presPenaltyValue") },
      // ▼▼▼▼▼ [추가] 아래 3개의 토큰 슬라이더 객체를 추가합니다 ▼▼▼▼▼
          maxTokens: { el: $("maxTokensSlider"), val: $("maxTokensValue") },
          maxMemoryTokens: { el: $("maxMemorySlider"), val: $("maxMemoryValue") },
      maxRagTokens: { el: $("maxRagSlider"), val: $("maxRagValue") },
      /* (NEW) 웹 스니펫 개수 */
      searchTopK:   { el: $("searchTopKSlider"), val: $("searchTopKValue") },
    },
    // 기본 모델 설정
    defaultModelSelect: $("defaultModelSelect"),
    saveModelBtn: $("saveModelBtn"),
    // 관리자 기능
    adminStatus: $("adminStatus"),
    trainBtn: $("trainBtn"),
    startFineTuneBtn: $("startFineTuneBtn"),
    jobIdInput: $("jobIdInput"),
    checkFineTuneBtn: $("checkFineTuneBtn"),
    // 모바일 사이드바
    sideNav: $("sideNav"),
    sideOpen: $("sideOpen"),
    sideClose: $("sideClose"),
    // Thought process panel and stop button (optional)
    agentThoughtProcess: $("agent-thought-process"),
    stopBtn: $("stopBtn"),

    // Understanding summary toggle
    understandingToggle: $("understandingToggle"),

    // (NEW) GPT Web Search controls
    searchModeSelect: $("searchModeSelect"),
    webProvidersSelect: $("webProvidersSelect"),
    officialSourcesOnly: $("officialSourcesOnly"),

    // (Gemini) 실시간 연동 학습 토글
    useGeminiLearning: $("useGeminiLearning"),
    // (LOCATION) 위치 기반 기능 토글 및 버튼
    locToggle: $("locToggle"),
    sendLocBtn: $("sendLocBtn"),
  };

  // ---------------------------------------------------------------------
  // 정밀 검색 토글 UX: 사용자가 정밀 검색을 켜면 검색 모드가 자동으로 DEEP으로 전환됩니다.
  // 이는 백엔드 라우팅 힌트와 일관되도록 하기 위함입니다.
  if (dom.precisionToggle) {
  dom.precisionToggle.addEventListener('change', () => {
      if (dom.precisionToggle.checked && dom.searchModeSelect) {
        dom.searchModeSelect.value = 'FORCE_DEEP';
      }
      // 저장
      if (typeof saveUIPrefs === 'function') saveUIPrefs();
    });
  }

  // ---------------------------------------------------------------------
  // 로그인 오버레이 및 로그아웃 처리
  //
  // loginOverlay: 현재 표시되고 있는 로그인 팝업 요소를 추적합니다. 팝업은
  // body에 고정 위치로 삽입되며, 다시 호출하면 제거됩니다.
  let loginOverlay = null;

  /**
   * 서버에 POST /logout 요청을 보내고 성공 시 페이지를 새로고침합니다.
   * Spring Security에서 로그아웃은 302 상태코드로 리다이렉트하므로
   * 2xx와 3xx를 모두 성공으로 간주합니다. 실패 시 예외를 throw합니다.
   * @returns {Promise<void>}
   */
  async function doLogout() {
    // Simplified logout handler: POST to /logout and then navigate away
    try {
      await fetch('/logout', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { [CSRF.header]: CSRF.token, 'X-Requested-With': 'XMLHttpRequest' }
      });
    } catch (err) {
      // ignore network errors; always attempt to clear state below
    } finally {
      try {
        // clear persisted application state to avoid BFCache issues
        localStorage.clear();
        sessionStorage.clear();
      } catch {}
      // After logout, redirect to the login page with a query parameter
      window.location.replace('/login?logout');
    }
  }

  /**
   * 로그인 오버레이를 표시합니다. 이미 표시된 경우 기존 오버레이를 제거하고
   * 새로 생성합니다. 폼 전송 시 /login 엔드포인트로 POST 요청을 보내며
   * 성공하면 페이지를 새로고침하고, 실패하면 오류 메시지를 노출합니다.
   */
  function showLoginOverlay() {
    // 기존 팝업이 있으면 제거하여 토글처럼 동작
    if (loginOverlay) {
      loginOverlay.remove();
      loginOverlay = null;
    }
    const template = document.getElementById('login-form-template');
    if (!template) return;
    const overlay = document.createElement('div');
    overlay.className = 'aw-login-overlay';
    overlay.appendChild(template.content.cloneNode(true));
    const form = overlay.querySelector('form');
    const errDiv = overlay.querySelector('#popoverLoginError');
    if (form) {
      form.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        // 오류 메시지 초기화
        if (errDiv) {
          errDiv.classList.add('d-none');
          errDiv.textContent = '로그인 실패';
        }
        const fd = new FormData(form);
        const params = new URLSearchParams();
        fd.forEach((value, key) => {
          params.append(key, value);
        });
        try {
          const res = await fetch('/login', {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
              [CSRF.header]: CSRF.token,
              'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: params.toString()
          });
          // Treat successful responses, redirects and opaqueredirects as a successful login.
          const success =
            res.ok ||
            res.redirected ||
            res.type === 'opaqueredirect' ||
            (res.status >= 300 && res.status < 400);
          if (success) {
            // Reload the page to reflect the logged-in state.
            location.reload();
            return;
          }
          // If the response is not successful, fall through to error handling.
          let msg = '';
          try {
            msg = await res.text();
            msg = msg.replace(/<[^>]+>/g, '');
          } catch {}
          if (!msg) msg = '로그인 실패';
          if (errDiv) {
            errDiv.textContent = msg;
            errDiv.classList.remove('d-none');
          } else {
            alert(msg);
          }
        } catch (error) {
          // On fetch errors, attempt a fallback by submitting a hidden form.
          try {
            const fallbackForm = document.createElement('form');
            fallbackForm.method = 'POST';
            fallbackForm.action = '/login';
            fallbackForm.style.display = 'none';
            // Copy CSRF token
            const csrfInput = document.createElement('input');
            csrfInput.type = 'hidden';
            csrfInput.name = '_csrf';
            csrfInput.value = CSRF.token;
            fallbackForm.appendChild(csrfInput);
            // Copy username/password into hidden inputs
            fd.forEach((value, key) => {
              const input = document.createElement('input');
              input.type = 'hidden';
              input.name = key;
              input.value = value;
              fallbackForm.appendChild(input);
            });
            document.body.appendChild(fallbackForm);
            fallbackForm.submit();
            return;
          } catch (err2) {
            // If fallback fails, show error message as before.
            const msg = (error && error.message) ? error.message : '로그인 실패';
            if (errDiv) {
              errDiv.textContent = msg;
              errDiv.classList.remove('d-none');
            } else {
              alert(msg);
            }
          }
        }
      });
    }
    // 오버레이 바깥 영역 클릭 시 닫기
    overlay.addEventListener('click', (ev) => {
      if (ev.target === overlay) {
        overlay.remove();
        loginOverlay = null;
      }
    });
    document.body.appendChild(overlay);
    loginOverlay = overlay;
  }

  // 원본 버튼 HTML 저장
  let sendBtnHtml, saveModelBtnHtml, saveSettingsBtnHtml, adminStatusInitialHtml;

  /* --------------------------------------------------
   * 3. UI 헬퍼 함수
   * -------------------------------------------------- */
  /** 채팅 버블 렌더링 */
function renderMessage({ role, content, model }) {
    if (!dom.chatMessages) return;

    const wrap = document.createElement("div");
    wrap.className = `msg-container msg-${role.toLowerCase()}-container`;

    if (role.toUpperCase() === "SYSTEM") {
  const raw = String(content ?? '');
  // 1) understanding summary meta → parse JSON and render card
  if (raw.startsWith(USUM_META_PREFIX)) {
    try {
      const json = raw.slice(USUM_META_PREFIX.length).trim();
      const summary = JSON.parse(json);
      renderUnderstandingCard(summary);
    } catch {
      // ignore parsing errors
    }
    return;
  }
  // 2) (safety net) if TRACE meta prefix still present, strip or decode it.
  if (raw.startsWith('⎔TRACE⎔')) {
    content = raw.slice('⎔TRACE⎔'.length).trim();
  } else if (raw.startsWith('⎔TRACE64⎔')) {
    try {
      const b64 = raw.slice('⎔TRACE64⎔'.length).trim();
      content = new TextDecoder().decode(Uint8Array.from(atob(b64), c => c.charCodeAt(0)));
    } catch {
      content = '';
    }
  }
  // Determine if we should render as search trace panel or plain system message.
  const hasTrace = typeof content === "string" && content.includes('class="search-trace"');
  if (hasTrace) {
    // 검색과정 패널은 assistant 버블처럼 렌더
    const label = document.createElement("small");
    label.className = "text-muted d-block mb-1 ms-3";
    label.innerHTML = `<i class="bi bi-search-heart me-1"></i>검색 과정`;
    wrap.appendChild(label);

    const bubble = document.createElement("div");
    bubble.className = "msg msg-assistant";
    const rawHtml = String(content ?? "");
    bubble.innerHTML = rawHtml; // HTML 그대로
    wrap.appendChild(bubble);
    dom.chatMessages.appendChild(wrap);
    // Mark search trace bubbles so that they are ignored by TTS.  We
    // annotate both the bubble and its wrapper with data attributes to
    // allow the selector in findLastAssistantAnswerEl() to skip over
    // these nodes.  This also adds a CSS class for potential styling.
    try {
      bubble.dataset.role = 'trace';
      bubble.dataset.ttsIgnore = '1';
      wrap.dataset.ttsIgnore = '1';
      wrap.classList.add('msg-trace');
    } catch {
      // ignore failures during DOM annotation
    }
  } else {
    const systemMsg = document.createElement("div");
    systemMsg.className = "text-center text-muted small my-2";
    systemMsg.innerHTML = content;
    dom.chatMessages.appendChild(systemMsg);
  }
} else {
      // When rendering an assistant message with an associated model, use
      // upsertModelBadge() to attach or update a single badge on the
      // wrapper.  This method sets the appropriate data-role so that
      // subsequent calls (e.g. restoreModelBadgeFromStorage) do not
      // duplicate the label.  Avoid manual creation of the label here.
      if (role.toUpperCase() === 'ASSISTANT' && model) {
        upsertModelBadge(wrap, model);
      }
      const bubble = document.createElement("div");
      bubble.className = `msg msg-${role.toLowerCase()}`;
      const raw = String(content ?? "");
      const isHtml = /<[^>]+>/.test(raw);   // 답변이 HTML 조각이면 줄바꿈 치환 금지
      bubble.innerHTML = isHtml ? raw : raw.replace(/\n/g, "<br>");
      // Remove any legacy 'model:' prefix lines from assistant messages
      if (role.toUpperCase() === 'ASSISTANT') {
        stripModelPrefix(bubble);
      }
      wrap.appendChild(bubble);
      dom.chatMessages.appendChild(wrap);
    }
    dom.chatWindow.scrollTop = dom.chatWindow.scrollHeight;
  }

  /**
   * Render a structured understanding summary as a card in the chat.  The
   * summary object should contain tldr (string), keyPoints (array of
   * strings) and actionItems (array of strings).  Only non‑empty fields
   * are rendered.  The card is appended to the chat messages area.
   *
   * @param {Object} summary The parsed summary from the SSE event
   */
  function renderUnderstandingCard(summary) {
    if (!summary || !dom.chatMessages) return;
    const wrap = document.createElement('div');
    wrap.className = 'msg-container msg-system-container';
    const card = document.createElement('div');
    card.className = 'card border-secondary bg-light';
    const body = document.createElement('div');
    body.className = 'card-body p-2';
    // TL;DR
    if (summary.tldr) {
      const h = document.createElement('h6');
      h.className = 'card-title mb-1 fw-bold';
      h.textContent = '요약';
      const p = document.createElement('p');
      p.className = 'card-text mb-2';
      p.textContent = summary.tldr;
      body.appendChild(h);
      body.appendChild(p);
    }
    // Key points
    if (Array.isArray(summary.keyPoints) && summary.keyPoints.length > 0) {
      const h = document.createElement('h6');
      h.className = 'card-title mb-1 fw-bold';
      h.textContent = '주요 요점';
      body.appendChild(h);
      const ul = document.createElement('ul');
      ul.className = 'mb-2';
      summary.keyPoints.forEach(pt => {
        if (!pt) return;
        const li = document.createElement('li');
        li.textContent = pt;
        ul.appendChild(li);
      });
      body.appendChild(ul);
    }
    // Action items
    if (Array.isArray(summary.actionItems) && summary.actionItems.length > 0) {
      const h = document.createElement('h6');
      h.className = 'card-title mb-1 fw-bold';
      h.textContent = '실행 항목';
      body.appendChild(h);
      const ul = document.createElement('ul');
      ul.className = 'mb-0';
      summary.actionItems.forEach(item => {
        if (!item) return;
        const li = document.createElement('li');
        li.textContent = item;
        ul.appendChild(li);
      });
      body.appendChild(ul);
    }
    card.appendChild(body);
    wrap.appendChild(card);
    dom.chatMessages.appendChild(wrap);
    // Scroll to bottom
    dom.chatWindow.scrollTop = dom.chatWindow.scrollHeight;
  }

  /** 버튼 로딩 토글 */
  function setLoading(btn, loading, defaultHtml) {
    if (!btn) return;
    btn.disabled = loading;
    const spinner = btn.querySelector(".spinner-border");
    if (loading) {
      spinner?.classList.remove("d-none");
    } else {
      spinner?.classList.add("d-none");
      if (defaultHtml) btn.innerHTML = defaultHtml;
    }
  }

  /** 사이드바 상태 표시 */
  function showStatus(msg, type = "info", persist = false) {
    if (!dom.adminStatus) return;
    dom.adminStatus.innerHTML = msg;
    const color = {
      info: "bg-info-subtle text-info-emphasis",
      success: "bg-success-subtle text-success-emphasis",
      warning: "bg-warning-subtle text-warning-emphasis",
      danger: "bg-danger-subtle text-danger-emphasis",
    }[type];
    dom.adminStatus.className = `p-2 rounded ${color}`;
    if (!persist) {
      setTimeout(() => {
        if (dom.adminStatus.innerHTML === msg) {
          dom.adminStatus.innerHTML = adminStatusInitialHtml;
          dom.adminStatus.className = "mt-auto pt-2 text-muted";
        }
      }, 4000);
    }
  }

  /**
   * 문자열의 특수 문자를 HTML 엔티티로 변환합니다. 파일명이나 프롬프트를
   * 메시지에 출력할 때 XSS를 방지하기 위해 사용합니다.
   * @param {string} str 입력 문자열
   * @returns {string} 이스케이프된 문자열
   */
  function escapeHtml(str) {
    if (typeof str !== 'string') return '';
    return str.replace(/[&<>"']/g, (ch) => {
      const map = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
      return map[ch] || ch;
    });
  }

  /**
   * 선택된 파일 UI를 갱신합니다. 파일이 선택되어 있으면 래퍼를 표시하고
   * 파일명을 보여줍니다. 선택이 해제되면 래퍼를 숨깁니다.
   */
  function updateSelectedFileUI() {
    if (!dom.selectedFileWrap || !dom.selectedFileName) return;
    if (state.selectedFile) {
      dom.selectedFileWrap.classList.remove('d-none');
      dom.selectedFileName.innerText = state.selectedFile.name;
    } else {
      dom.selectedFileWrap.classList.add('d-none');
      dom.selectedFileName.innerText = '선택된 파일 없음';
    }
  }

  /**
   * 파일 선택을 초기화합니다. 상태와 input 값을 모두 지웁니다.
   */
  function clearSelectedFile() {
    state.selectedFile = null;
    if (dom.fileInput) dom.fileInput.value = '';
    updateSelectedFileUI();
  }

  /* ================================================================
   * 역할 추론 및 TTS 지원 유틸리티
   *
   * To ensure the screen reader (TTS) reads back only the most recent
   * assistant answer and not intermediary search traces, we annotate
   * each rendered message with a data-role attribute.  When loading
   * historical messages this role is inferred from server hints or
   * simple heuristics.  We also persist the turn number of the last
   * read answer in localStorage so that it can be restored after
   * reloading the page.
   *
   * These helpers are defined here to avoid polluting the global scope.
   */

  /**
   * Infer the semantic role of a message.  When the server provides a
   * roleTag or role_tag property this is used directly.  Otherwise the
   * content and type are inspected to determine whether the message
   * represents a search trace or a final answer.  By default
   * anything that is not explicitly a trace is considered an answer.
   *
   * @param {Object} m The message object returned from the server
   * @returns {string} 'answer' or 'trace'
   */
  function inferRoleFromMessage(m) {
    if (!m) return 'answer';
    // explicit server-provided tags (case insensitive)
    const tag = (m.roleTag ?? m.role_tag ?? '').toString().toLowerCase();
    if (tag === 'answer' || tag === 'trace') {
      return tag;
    }
    // explicit type flags
    if (m.type === 'SEARCH_TRACE' || m.isTrace === true) return 'trace';
    // inspect the message text/content
    const txt = (m.content ?? m.text ?? '').toString();
    try {
      if (typeof txt === 'string') {
        // search traces are often wrapped in a dedicated class or prefixed with an emoji
        if (txt.includes('class="search-trace"') || txt.trim().startsWith('🔎')) {
          return 'trace';
        }
      }
    } catch {
      // swallow errors from toString
    }
    return 'answer';
  }

  /**
   * Locate the last assistant answer element in the DOM.  This query
   * searches for assistant message bubbles annotated with
   * data-role="answer" and excludes any nodes that have been marked
   * with data-tts-ignore.  If multiple candidates are found and they
   * carry a data-turn attribute, the one with the highest turn number
   * is selected.  Otherwise the last element in document order is
   * returned.  If no element matches the query this function returns
   * null.
   *
   * @returns {HTMLElement|null} the DOM node containing the last answer
   */
  function findLastAssistantAnswerEl() {
    const answers = Array.from(document.querySelectorAll('.msg.msg-assistant[data-role="answer"]:not([data-tts-ignore])'));
    if (answers.length === 0) return null;
    // determine if any have a valid numeric turn
    const mapped = answers.map(el => {
      const t = Number(el.dataset.turn || '0');
      return { el, turn: isNaN(t) ? 0 : t };
    });
    mapped.sort((a, b) => a.turn - b.turn);
    const last = mapped[mapped.length - 1];
    return last.el || answers[answers.length - 1];
  }

  /**
   * Cache the turn identifier of the last assistant answer for the
   * provided session.  When the session is reloaded this value is used
   * to prioritise the previously read answer.  If the supplied element
   * does not include a data-turn attribute the cache is not updated.
   *
   * @param {HTMLElement} el The message bubble element to persist
   * @param {string|number} sessionId The current chat session identifier
   */
  function onAssistantAnswerRendered(el, sessionId) {
    if (!el || !sessionId) return;
    const turn = el.dataset.turn;
    if (!turn) return;
    try {
      localStorage.setItem(`lastAnswerTurn:${sessionId}`, String(turn));
    } catch {
      // ignore storage failures (e.g. private mode)
    }
  }

  /**
   * Restore the previously cached last assistant answer for a session.
   * This function reads the cached turn number from localStorage and
   * returns the matching DOM element if present.  If the cache is
   * missing or the element cannot be found, null is returned.
   *
   * @param {string|number} sessionId The current chat session identifier
   * @returns {HTMLElement|null} The restored element, if any
   */
  function restoreLastAnswerEl(sessionId) {
    if (!sessionId) return null;
    try {
      const turn = localStorage.getItem(`lastAnswerTurn:${sessionId}`);
      if (!turn) return null;
      const selector = `.msg.msg-assistant[data-role="answer"][data-turn="${turn}"]`;
      const el = document.querySelector(selector);
      return el || null;
    } catch {
      return null;
    }
  }

/* 4-1. 세션 목록 조회 & 렌더 */
async function fetchSessions() {
  if (!dom.sessionList) return;

  let sessions = [];
  try {
    sessions = await apiCall("/api/chat/sessions");
  } catch (e) {
    showStatus("세션 목록 로드 실패", "danger", true);
    return;               // 실패 시 종료
  }

  dom.sessionList.innerHTML = "";

  // ▲ (새 대화) 더미 항목 처리
if (!sessions.length && state.currentSessionId === null) {
    dom.sessionList.innerHTML =
      `<li class="session-item">
         <a href="#" class="active d-block px-3 py-2 text-truncate">
           <em class="text-muted">(새 대화)</em>
         </a>
       </li>`;
  }

  // 실존 세션 렌더
  sessions.forEach((s) => {
    const li = document.createElement("li");
    li.className =
      "session-item d-flex justify-content-between align-items-center";

    const a = document.createElement("a");
    a.href           = "#";
    a.dataset.sessionId = s.id;
    a.textContent    = s.title;
    a.className      =
      "flex-grow-1 text-truncate pe-2 " +
      (s.id === state.currentSessionId ? "active" : "");

    const del = document.createElement("button");
    del.className      = "btn btn-sm btn-outline-danger delete-session-btn";
    del.dataset.sessionId = s.id;
    del.innerHTML      = '<i class="bi bi-trash"></i>';
    del.setAttribute("aria-label", `'${s.title}' 대화 삭제`);

    li.append(a, del);
    dom.sessionList.appendChild(li);
  });
}

  /**
   * Attach to an existing in‑flight run by issuing a streaming request with the
   * attach flag set.  This delegates to {@link streamChat} by adding a
   * temporary `attach` property onto the payload.  The loaderId is omitted
   * because we do not need to render intermediate status bubbles when
   * rejoining a running conversation.
   *
   * @param {Object} payload A minimal payload containing at least a
   *                         sessionId identifying the chat to attach to.
   */
  async function streamChatWithAttach(payload) {
    try {
      // Clone the payload and set the attach flag.  Passing undefined as
      // loaderId causes streamChat() to skip status bubble creation.
      await streamChat({ ...(payload || {}), attach: true }, undefined);
    } catch (e) {
      console.warn('Failed to attach to existing chat stream', e);
    }
  }



  async function loadSession(id) {
    if (state.isLoading || !id || id === state.currentSessionId) return;
    setLoading(dom.sendBtn, true, sendBtnHtml);
    try {
      const data = await apiCall(`/api/chat/sessions/${id}`);
      dom.chatMessages.innerHTML = "";
      const msgs = data.messages || [];
      const lastAssistIdx = msgs.map(x => (x.role || '').toLowerCase()).lastIndexOf('assistant');
      msgs.forEach((m, idx) => {
        const modelForThis = (idx === lastAssistIdx) ? (data.modelUsed || undefined) : undefined;
        // Render each message; supply model only for the last assistant message
        renderMessage({ role: m.role, content: m.content, model: modelForThis });
        // Annotate the most recently inserted message with semantic hints
        try {
          const wrap = dom.chatMessages?.lastElementChild;
          if (wrap) {
            // find the bubble element inside the wrapper (assistant/user/system)
            const bubble = wrap.querySelector('.msg');
            const isAssistant = bubble?.classList.contains('msg-assistant');
            const roleTag = inferRoleFromMessage(m);
            const turnVal = m.turnId ?? m.turn ?? m.turn_id;
            if (bubble && isAssistant) {
              // Set the computed role on the bubble to aid TTS queries
              bubble.dataset.role = roleTag;
              if (turnVal !== undefined && turnVal !== null) {
                bubble.dataset.turn = String(turnVal);
              }
              if (roleTag === 'trace') {
                // Mark search traces so they are skipped by TTS
                bubble.dataset.ttsIgnore = '1';
                wrap.dataset.ttsIgnore = '1';
                wrap.classList.add('msg-trace');
              }
            }
          }
        } catch {
          // ignore failures during annotation
        }
        state.chatHistory.push({ role: m.role, content: m.content });
        const role = (m.role || '').toUpperCase();
        if (role === 'ASSISTANT' && FEEDBACK_ENABLED) {
          attachFeedbackControls(m.content ?? '');
        }
      });
      state.currentSessionId = id;
      await fetchSessions();
      // Restore persisted model badge for this session
      restoreModelBadgeFromStorage(id);
    } catch (e) {
      renderMessage({ role: "SYSTEM", content: `대화 기록 로드 실패: ${e.message}` });
    } finally {
      setLoading(dom.sendBtn, false, sendBtnHtml);
    }
  }

  /* --------------------------------------------------
   * 5. 설정 관리 (GPT 상세 / 기본 모델)
   * -------------------------------------------------- */
  function hydrateModels() {
    if (!dom.defaultModelSelect || !window.initialData) return;
    const { models = [], currentModel } = window.initialData;
    dom.defaultModelSelect.innerHTML = "";
    if (!models.length) {
      const opt = document.createElement("option");
      opt.textContent = "사용 가능한 모델 없음";
      opt.disabled = true;
      dom.defaultModelSelect.appendChild(opt);
      dom.saveModelBtn.disabled = true;
      return;
    }
    models.forEach(({ modelId }) => {
      const opt = document.createElement("option");
      opt.value = modelId;
      opt.textContent = modelId;
      if (modelId === currentModel) opt.selected = true;
            // model-strategy.js가 후보를 수집할 수 있도록 힌트
            opt.dataset.modelId = modelId;
      dom.defaultModelSelect.appendChild(opt);
    });
  }

function collectSettings() {
  const settings = {
    systemPrompt: dom.systemPromptInput?.value ?? "",
    useRag: dom.useRag?.checked,
    useAdaptiveTranslator: dom.useAdaptiveTranslator?.checked,
    autoTranslate: dom.autoTranslate?.checked,
    usePolish: dom.usePolish?.checked,
    useHistory: dom.useHistory?.checked,
  };
  for (const key in dom.sliders) {
    const slider = dom.sliders[key];
    if (slider?.el) settings[key] = +slider.el.value;
  }
   /* UI 슬라이더 → DTO 필드명(webTopK) 매핑 */
    settings.webTopK = +dom.sliders.searchTopK.el.value;
    // Persist web search settings so that they can be restored on reload.
    settings.searchMode = dom.searchModeSelect?.value;
    settings.officialSourcesOnly = dom.officialSourcesOnly?.checked;
    settings.webProviders = Array.from(dom.webProvidersSelect?.selectedOptions || []).map(opt => opt.value).join(',');
    settings.precisionSearch = !!dom.precisionToggle?.checked;
    settings.precisionTopK = (dom.precisionToggle?.checked
        ? (parseInt(dom.sliders?.searchTopK?.el?.value, 10) || 10)
        : '');
  return settings;
}
  async function loadSettings() {
    try {
      const s = await apiCall("/api/settings");
      if (dom.systemPromptInput) dom.systemPromptInput.value = s.SYSTEM_PROMPT ?? "";
       // ▼▼▼ [수정] 서버에서 받은 값으로 체크박스를 설정하는 로직을 추가합니다. ▼▼▼
            // s.USE_RAG가 true이면 dom.useRag.checked도 true가 됩니다.
            dom.useRag.checked = s.USE_RAG === true || s.USE_RAG === 'true';
            dom.useAdaptiveTranslator.checked = s.USE_ADAPTIVE_TRANSLATOR === true || s.USE_ADAPTIVE_TRANSLATOR === 'true';
            dom.autoTranslate.checked = s.AUTO_TRANSLATE === true || s.AUTO_TRANSLATE === 'true';
            dom.usePolish.checked = s.USE_POLISH === true || s.USE_POLISH === 'true';
            dom.useHistory.checked = s.USE_HISTORY === true || s.USE_HISTORY === 'true';
            // ▲▲▲ [수정 끝] ▲▲▲
      for (const key in dom.sliders) {
        const slider = dom.sliders[key];
        if (!slider?.el) continue;
     const settingKey = {
       maxMemoryTokens: "MAX_MEMORY_TOKENS",
       maxRagTokens:    "MAX_RAG_TOKENS",
     }[key] ?? key.replace(/([A-Z])/g, "_$1").toUpperCase();
        const val = s[settingKey] ?? slider.el.defaultValue;
        slider.el.value = val;
        slider.val.textContent = val;
      }
            /* (NEW) searchTopK 슬라이더 값 바인딩 */
            const k = s.WEB_TOP_K ?? dom.sliders.searchTopK.el.defaultValue;
            dom.sliders.searchTopK.el.value = k;
            dom.sliders.searchTopK.val.textContent = k;

      // 검색 옵션 복원
      if (dom.searchModeSelect && s.SEARCH_MODE) dom.searchModeSelect.value = s.SEARCH_MODE;
      if (dom.officialSourcesOnly) dom.officialSourcesOnly.checked = (s.OFFICIAL_SOURCES_ONLY === true || s.OFFICIAL_SOURCES_ONLY === 'true');
      if (dom.webProvidersSelect && s.WEB_PROVIDERS) {
         const vals = String(s.WEB_PROVIDERS).split(',').map(v => v.trim());
         [...dom.webProvidersSelect.options].forEach(o => o.selected = vals.includes(o.value));
      }
      if (dom.precisionToggle && s.PRECISION_SEARCH != null) dom.precisionToggle.checked = (s.PRECISION_SEARCH === true || s.PRECISION_SEARCH === 'true');
      if (dom.sliders?.searchTopK?.el && s.PRECISION_TOP_K) dom.sliders.searchTopK.el.value = +s.PRECISION_TOP_K;
    } catch (e) {
      showStatus("GPT 설정 로드 실패", "danger");
    }
  }

  async function saveSettings() {
    setLoading(dom.saveSettingsBtn, true, saveSettingsBtnHtml);
    try {
    const snake = obj => Object.fromEntries(
      Object.entries(obj).map(([k,v]) => [
        k.replace(/([A-Z])/g,"_$1").toUpperCase(), v
      ]));
    await apiCall("/api/settings", { method: "POST", body: snake(collectSettings()) });
      showStatus("GPT 설정 저장 완료", "success");
    } catch (e) {
      showStatus(`설정 저장 실패: ${e.message}`, "danger", true);
    } finally {
      setLoading(dom.saveSettingsBtn, false, saveSettingsBtnHtml);
    }
  }

  async function saveDefaultModel() {
    const selected = dom.defaultModelSelect?.value;
    if (!selected) return showStatus("저장할 모델을 선택하세요.", "warning");

    setLoading(dom.saveModelBtn, true, saveModelBtnHtml);
    showStatus(`'${selected}' 모델을 기본값으로 저장 중...`, "info", true);
    try {
      const data = await apiCall("/api/settings/model", {
        method: "POST",
        body: { model: selected },
      });
      showStatus(data.message || `✅ 기본 모델이 '${selected}'(으)로 변경되었습니다.`, "success");
      window.initialData.currentModel = selected;
            // 프런트 모델 전략에도 반영
            localStorage.setItem("models.active", selected);
            localStorage.setItem("models.strategy", "manual");
            document.getElementById('activeModelText').textContent = selected;
            document.getElementById('modelStrategySelect').value = 'manual';
            window.__modelStrategy?.getActiveModel?.(); // warm
    } catch (e) {
      showStatus(`모델 저장 실패: ${e.message}`, "danger", true);
    } finally {
      setLoading(dom.saveModelBtn, false, saveModelBtnHtml);
    }
  }

// chat.js의 sendMessage 함수

  async function sendMessage() {
    const text = dom.messageInput.value.trim();
    if (!text || state.isLoading) return;
    // Apply a location bias for DW 아카데미 related queries when appropriate
    const biasedText = applyLocationBiasIfNeeded(text);

    renderMessage({ role: "USER", content: text });
    if (dom.useHistory?.checked) {
      state.chatHistory.push({ role: "USER", content: text });
    }
    dom.messageInput.value = "";

    // ===== 파일 첨부 처리 =====
    // 사용자가 메시지를 전송할 때 첨부 파일이 선택되어 있으면
    // 시스템 메시지로 업로드 완료를 알리고 상태를 초기화합니다.
    if (state.selectedFile) {
      renderMessage({
        role: "SYSTEM",
        content: `<i class="bi bi-paperclip me-1"></i>파일 '<strong>${escapeHtml(state.selectedFile.name)}</strong>'이 업로드 되었습니다.`
      });
      clearSelectedFile();
    }

    // ===== /imagine 명령 처리 (API 호출) =====
    // '/imagine'으로 시작하는 입력은 별도의 이미지 생성 API를 호출합니다.
    if (/^\/imagine(?:\s|$)/i.test(text)) {
      state.isLoading = true;
      setLoading(dom.sendBtn, true, sendBtnHtml);
      dom.messageInput.disabled = true;
      const loaderId = `loader-${Date.now()}`;
      renderMessage({
        role: "ASSISTANT",
        content: `<div id="${loaderId}"><div class="spinner-border spinner-border-sm me-2" role="status"><span class="visually-hidden">Loading...</span></div>이미지를 생성 중입니다…</div>`
      });
      if (dom.stopBtn) {
        dom.stopBtn.style.display = "none";
      }
      const prompt = text.replace(/^\/imagine\s*/i, "").trim();
      try {
        const res = await fetch("/api/image-plugin/generate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ prompt, count: 1, size: "1024x1024" })
        });
        if (!res.ok) throw new Error(`이미지 API 실패 (${res.status})`);
        const data = await res.json();
        const url  = data?.imageUrls?.[0];
        const bubble = document.getElementById(loaderId)?.closest(".msg");
        if (bubble) {
          if (!url) {
            const reason = data?.error || data?.reason || '이미지 생성 실패(자세한 원인은 서버 로그 참조)';
            bubble.innerHTML = `<span class="text-danger">${escapeHtml(reason)}</span>`;
          } else {
            bubble.innerHTML = `<figure class="m-0"><img src="${encodeURI(url)}" class="img-fluid rounded"><figcaption class="small text-muted mt-1">"${escapeHtml(prompt)}"</figcaption></figure>`;
          }
        }
        if (dom.useHistory?.checked) {
          state.chatHistory.push({ role: "ASSISTANT", content: `[image] ${prompt}` });
        }
      } catch (e) {
        const bubble = document.getElementById(loaderId)?.closest(".msg");
        if (bubble) {
          bubble.innerHTML = `<span class="text-danger">이미지 생성 중 오류: ${escapeHtml(String(e.message || e))}</span>`;
        }
      } finally {
        state.isLoading = false;
        setLoading(dom.sendBtn, false, sendBtnHtml);
        dom.messageInput.disabled = false;
        dom.messageInput.focus();
      }
      return;
    }
    // ===== /imagine 명령 처리 =====
    // /imagine로 시작하는 입력은 백엔드 호출 없이 프론트에서 이미지 생성을 시뮬레이션합니다.
    // (dev only) image simulator executes only when the feature flag is enabled
    const imaginePattern = /^\/imagine(?:\s|$)/i;
    if (FeatureFlags?.imageSimulator === true && imaginePattern.test(text)) {
      state.isLoading = true;
      setLoading(dom.sendBtn, true, sendBtnHtml);
      dom.messageInput.disabled = true;
      // 이미지 생성 로딩 버블 출력
      const loaderId = `loader-${Date.now()}`;
      renderMessage({
        role: "ASSISTANT",
        content: `<div id="${loaderId}">\n` +
                 `  <div class="spinner-border spinner-border-sm me-2" role="status">\n` +
                 `    <span class="visually-hidden">Loading...</span>\n` +
                 `  </div>\n` +
                 `  이미지를 생성 중입니다…\n` +
                 `</div>`
      });
      // 스트리밍 취소 버튼은 이미지 생성에서 사용하지 않음
      if (dom.stopBtn) {
        dom.stopBtn.style.display = 'none';
      }
      // 입력에서 프롬프트(명령어 이후 부분)를 추출하고 HTML 이스케이프
      const prompt = text.replace(/^\/imagine\s*/i, '').trim();
      const safePrompt = escapeHtml(prompt);
      // 0.8~1.6초 사이 무작위 지연을 두고 이미지로 교체
      const delay = 800 + Math.random() * 800;
      setTimeout(() => {
        const loaderEl = document.getElementById(loaderId);
        if (loaderEl) {
          const bubble = loaderEl.closest('.msg');
          if (bubble) {
            bubble.innerHTML = `<figure class="m-0">\n` +
                               `  <img src="https://picsum.photos/300?rand=${Date.now()}" alt="generated image" class="img-fluid rounded">\n` +
                               `  <figcaption class="small text-muted mt-1">"${safePrompt}" (샘플)</figcaption>\n` +
                               `</figure>`;
          }
        }
        // 히스토리 옵션이 켜진 경우 [image] [프롬프트] 형태로 기록
        if (dom.useHistory?.checked) {
          state.chatHistory.push({ role: 'ASSISTANT', content: `[image] ${prompt}` });
        }
        state.isLoading = false;
        setLoading(dom.sendBtn, false, sendBtnHtml);
        dom.messageInput.disabled = false;
        dom.messageInput.focus();
      }, delay);
      return;
    }

    state.isLoading = true;
    setLoading(dom.sendBtn, true, sendBtnHtml);
    dom.messageInput.disabled = true;

    // Reset thought process panel for a new request
    if (dom.agentThoughtProcess) {
      dom.agentThoughtProcess.innerHTML = '';
      dom.agentThoughtProcess.style.display = 'none';
    }
    // Always create a loader bubble so that the first assistant response replaces it rather than
    // appending a duplicate.  This resolves the duplicate UI bug on a fresh session.  The loader
    // displays a spinner and a generic message while the answer is being prepared.
    let loaderId = `loader-${Date.now()}`;
    renderMessage({
        role: "ASSISTANT",
        content: `<div id="${loaderId}">
                    <div class="spinner-border spinner-border-sm me-2" role="status">
                      <span class="visually-hidden">Loading...</span>
                    </div>
                    생성중입니다…
                  </div>`
    });
    // Show the stop button while the AI is generating a response
    if (dom.stopBtn) {
        dom.stopBtn.style.display = '';
    }

    // ▲▲▲ [1. 로딩 메시지 추가 끝] ▲▲▲


    try {
// chat.js  (sendMessage 안)
const payload = {
  attachmentIds: (state.pendingAttachments || []).slice(),
  message: biasedText,
  sessionId: state.currentSessionId,
  useRag: dom.useRag?.checked,
  // 웹 검색: RAG 켜짐 && 모드가 OFF가 아닐 때만 true
  useWebSearch: !!(dom.useRag?.checked && (dom.searchModeSelect?.value !== 'OFF')),
  useAdaptive: dom.useAdaptiveTranslator?.checked,
  autoTranslate: dom.autoTranslate?.checked,
        // 모델 전략: 수동 선택 시 헤더/바디에 주입되며, 없으면 백엔드 기본
        model: (window.__modelStrategy?.getActiveModel?.() || window.initialData.currentModel),
 polish: dom.usePolish?.checked,     // ← 추가
  history: dom.useHistory?.checked ? state.chatHistory.slice(0, -1) : [],
  ...collectSettings(),
    maxMemoryTokens: dom.sliders.maxMemoryTokens.el.value, // 💡 추가
    maxRagTokens:    dom.sliders.maxRagTokens.el.value,    // 💡 추가
        /* (NEW) 웹 검색 개수 */
        webTopK:         dom.sliders.searchTopK.el.value,
  // 이해 요약 플래그: 사용자가 토글을 켠 경우 true
  understandingEnabled: dom.understandingToggle?.checked,

  // (NEW) GPT Web Search preferences
  searchMode: dom.searchModeSelect?.value,
  officialSourcesOnly: dom.officialSourcesOnly?.checked,
  webProviders: Array.from(dom.webProvidersSelect?.selectedOptions || []).map(opt => opt.value),
  // 정밀 검색 플래그 & 스캔할 상위 URL 개수
  precisionSearch: !!dom.precisionToggle?.checked,
  precisionTopK: (dom.precisionToggle?.checked
      ? (parseInt(dom.sliders?.searchTopK?.el?.value, 10) || 10)
      : undefined),
  // (NEW) 실시간 Gemini 연동 학습 플래그
  geminiLearning: !!dom.useGeminiLearning?.checked
};

         // ▶ 웹 검색/RAG 사용 시: 스트리밍으로 진행 상태와 토큰 표시
         if (dom.useRag?.checked) {
           await streamChat(payload, loaderId);
           return;
         }

         // ▶ 그 외: 기존 동기식 호출 유지
         const res = await apiCall("/api/chat", {
           method: "POST",
           body: payload,
         });

    const loader = document.getElementById(loaderId);
 const answer = res.content;
        const model = res.modelUsed;

        if (loader) {
          // 로더가 있다면, 로더가 포함된 채팅 버블(.msg)을 찾아 내용을 교체합니다.
          const messageBubble = loader.closest('.msg');
          if (messageBubble) {
                    const raw = String(answer ?? "");
            const isHtml = /<[^>]+>/.test(raw);
                    messageBubble.innerHTML = isHtml ? raw : raw.replace(/\n/g, "<br>");

              // 모델 정보가 있다면 버블 위에 배지를 부착하고 영구 저장합니다.
              if (model) {
                  upsertModelBadge(messageBubble.parentElement, model);
                  persistModelBadge(res.sessionId || state.currentSessionId, model);
              }
              // 레거시 본문 속의 model: 라인을 제거합니다.
              stripModelPrefix(messageBubble);
              // Annotate this final assistant answer so that the TTS logic can
              // correctly identify it.  We assign a data-role of "answer"
              // and a synthetic turn number based on the current history
              // length (plus one) if none was supplied by the server.
              try {
                messageBubble.dataset.role = 'answer';
                if (!messageBubble.dataset.turn) {
                  const syntheticTurn = state.chatHistory?.length ? (state.chatHistory.length + 1) : 1;
                  messageBubble.dataset.turn = String(syntheticTurn);
                }
                onAssistantAnswerRendered(messageBubble, res.sessionId || state.currentSessionId);
              } catch {
                // ignore annotation failures
              }
          }
        } else {
          // 만약 로더를 찾지 못하는 예외 상황에는 그냥 새 메시지를 추가합니다. (Fallback)
          renderMessage({ role: "ASSISTANT", content: answer, model: undefined });
          // 배지 및 영구 저장을 수행합니다.
          if (model) {
            const wrappers = dom.chatMessages?.querySelectorAll('.msg-container.msg-assistant-container');
            const lastWrap = wrappers?.[wrappers.length - 1];
            if (lastWrap) {
              upsertModelBadge(lastWrap, model);
              persistModelBadge(res.sessionId || state.currentSessionId, model);
            }
          }
          // Annotate the newly rendered assistant bubble so that the TTS
          // logic can find it.  We pick the last assistant bubble
          // appended to the chat and assign a role of "answer" and a
          // synthetic turn if none exists.
          try {
            const wrappers = dom.chatMessages?.querySelectorAll('.msg-container.msg-assistant-container');
            const lastWrap = wrappers?.[wrappers.length - 1];
            const bubble = lastWrap?.querySelector('.msg.msg-assistant');
            if (bubble) {
              bubble.dataset.role = 'answer';
              if (!bubble.dataset.turn) {
                const syntheticTurn = state.chatHistory?.length ? (state.chatHistory.length + 1) : 1;
                bubble.dataset.turn = String(syntheticTurn);
              }
              onAssistantAnswerRendered(bubble, res.sessionId || state.currentSessionId);
            }
          } catch {
            // ignore annotation failures
          }
          if (FEEDBACK_ENABLED) attachFeedbackControls(answer);
        }

        // 채팅 히스토리에 실제 답변을 저장합니다.
        if (dom.useHistory?.checked) {
          state.chatHistory.push({ role: "ASSISTANT", content: answer });
        }
        // ▲▲▲ [2. 로딩 메시지 교체 끝] ▲▲▲

      // 세션 ID 최초 생성·체크
      if (!state.currentSessionId && res.sessionId) {
        state.currentSessionId = res.sessionId;
        await fetchSessions();
      }
    } catch (e) {
      renderMessage({ role: "SYSTEM", content: `[오류] ${e.message}` });
    } finally {
      state.isLoading = false;
      setLoading(dom.sendBtn, false, sendBtnHtml);
       // ▼▼▼ [수정] 이 부분을 추가하세요 ▼▼▼
            dom.messageInput.disabled = false;
            // ▲▲▲ [수정 끝] ▲▲▲
      dom.messageInput.focus();
      // Hide stop button and reset cancel flag when the request completes
      if (dom.stopBtn) dom.stopBtn.style.display = 'none';
      state.isCancelled = false;
      state.pendingAttachments = [];
    }
   }
 /* ========================================================== */
 async function streamChat(payload, loaderId) {
   let res;
  try {
    // Determine the appropriate endpoint based on the presence of the
    // `attach` flag on the payload.  When attaching to an existing run
    // the flag instructs the server to return a replay of buffered events
    // instead of starting a new generation.  Remove the flag from the
    // body prior to sending the request.
    let url = "/api/chat/stream";
    let reqBody = payload;
    if (payload && payload.attach) {
      url = "/api/chat/stream?attach=true";
      // clone the payload to avoid mutating the original object
      reqBody = { ...payload };
      delete reqBody.attach;
    }
    res = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        [CSRF.header]: CSRF.token,
      },
      body: JSON.stringify(reqBody),
      credentials: "same-origin",
    });
  } catch (err) {
     // Network failure or streaming not supported – fall back to synchronous chat
     await finalizeFromSync(payload, loaderId);
     return;
   }
   if (!res || !res.ok || !res.body) {
     // Server responded with an error status or body missing – fall back
     await finalizeFromSync(payload, loaderId);
     return;
   }

   const decoder = new TextDecoder("utf-8");
   const reader = res.body.getReader();
   let buf = "";

   const loader = document.getElementById(loaderId);
   const bubble = loader?.closest(".msg"); // 로더가 들어있는 말풍선
   const statusEl = loader;                // 상태 텍스트 교체용

    const flushEvent = (evtName, dataStr) => {
     if (!evtName || !dataStr) return;
     let payload = {};
     try { payload = JSON.parse(dataStr); } catch {}
     const type = payload.type || evtName;

    // If the user has cancelled the current streaming request, ignore all non‑final
    // events to prevent residual tokens from rendering.  Final and error events
    // are still processed so that the UI can clean up correctly.
    if (state.isCancelled && type !== "final" && type !== "error") {
      return;
    }

     if (type === "status" && statusEl) {
       statusEl.innerHTML =
         `<div class="spinner-border spinner-border-sm me-2" role="status"><span class="visually-hidden">Loading...</span></div>${payload.data || ""}`;
     }
     // When we receive a trace event, append the search details to a
     // collapsible panel within the current turn wrapper.  Only one
     // <details> element is created per turn and subsequent traces are
     // appended as panels.  This prevents duplicate assistant bubbles
     // being added for each trace chunk.
     if (type === "trace" && bubble) {
       const wrap = bubble.parentElement;
       // Find or create the <details> container for this turn.
       let det = wrap.querySelector('[data-role="trace"]');
       if (!det) {
         det = document.createElement("details");
         det.dataset.role = "trace";
         // Mark the trace container so that TTS skips it entirely
         det.dataset.ttsIgnore = '1';
         const summary = document.createElement("summary");
         summary.innerHTML = `<i class="bi bi-search-heart me-1"></i>검색 과정`;
         det.appendChild(summary);
         wrap.appendChild(det);
       }
       // Append this trace HTML as a separate panel inside the details.
       const panel = document.createElement("div");
       panel.innerHTML = payload.html || "";
       det.appendChild(panel);
     }
     if (type === "token" && bubble) {
       const current = bubble.innerHTML.replace(/<div id=".*?">[\s\S]*?<\/div>/, "");
       const chunk = String(payload.data || "").replace(/\n/g, "<br>");
       bubble.innerHTML = current + chunk;
       dom.chatWindow.scrollTop = dom.chatWindow.scrollHeight;
     }

    // Handle intermediate thought updates by appending them to the thought process panel
    if (type === "thought") {
       const msg = payload.data || payload.message || "";
       if (dom.agentThoughtProcess) {
         // Ensure panel is visible when thoughts arrive
         dom.agentThoughtProcess.style.display = '';
         const div = document.createElement("div");
         div.textContent = msg;
         dom.agentThoughtProcess.appendChild(div);
         // Auto‑scroll to the bottom
         dom.agentThoughtProcess.scrollTop = dom.agentThoughtProcess.scrollHeight;
       }
    }
     if (type === "understanding") {
       // Parse and render structured summary card
       try {
         const jsonStr = payload.data || payload.summary || '';
         const summary = jsonStr ? JSON.parse(jsonStr) : null;
         if (summary) {
           renderUnderstandingCard(summary);
         }
       } catch (e) {
         console.error('Failed to parse understanding summary', e);
       }
     }
     if (type === "final") {
     const model = payload.modelUsed;
     const sid   = payload.sessionId;
     if (statusEl) statusEl.remove();
     // Add or update the model badge on the current turn wrapper.  We
     // ensure idempotent behaviour by reusing an existing label if
     // present; otherwise a new one is created.
     if (bubble && model) {
         upsertModelBadge(bubble.parentElement, model);
         persistModelBadge(sid, model);
     }
     // Prefer server HTML over plain text when rendering the final
     // assistant response.  After setting the HTML, strip any legacy
     // 'model:' prefix on the first line to avoid showing it twice in
     // the bubble.  The removal is anchored to the start of the
     // document to prevent accidental removal of legitimate content.
     const finalHtml = payload.html || payload.contentHtml;
     if (bubble && finalHtml) {
         bubble.innerHTML = finalHtml;
     }
     stripModelPrefix(bubble);
     // Annotate this final assistant answer bubble with a data-role and
     // synthetic turn if none is present.  Persist the last read
     // answer so that re‑loading the page will not cause search
     // panels to be read back.
     if (bubble) {
         try {
             bubble.dataset.role = 'answer';
             if (!bubble.dataset.turn) {
                 const syntheticTurn = state.chatHistory?.length ? (state.chatHistory.length + 1) : 1;
                 bubble.dataset.turn = String(syntheticTurn);
             }
             onAssistantAnswerRendered(bubble, sid || state.currentSessionId);
         } catch {
             // ignore errors during annotation
         }
     }
     const answerText = (payload.content ?? (bubble?.innerText || "")).trim();
     if (dom.useHistory?.checked) {
         state.chatHistory.push({ role: "ASSISTANT", content: answerText });
     }
     if (!state.currentSessionId && sid) {
         state.currentSessionId = sid;
         fetchSessions();
     }
     // Persist the last session id so that the session can be restored on
     // page reload.  Guard with try/catch to tolerate storage quota errors.
     try {
       if (state.currentSessionId) {
         localStorage.setItem(LAST_SESSION_KEY, String(state.currentSessionId));
       }
     } catch {}
     if (FEEDBACK_ENABLED) attachFeedbackControls(answerText);

     // When the final answer arrives, hide the stop button and reset cancellation state
     if (dom.stopBtn) dom.stopBtn.style.display = "none";
     state.isCancelled = false;
      state.pendingAttachments = [];
 }
     if (type === "error") {
       if (statusEl) statusEl.remove();
       if (bubble) bubble.innerHTML = (payload.data || "스트리밍 오류");
       renderMessage({ role: "SYSTEM", content: payload.data || "스트리밍 오류" });

        // On error, hide the stop button and reset cancellation state
        if (dom.stopBtn) dom.stopBtn.style.display = "none";
        state.isCancelled = false;
      state.pendingAttachments = [];
     }
   };

   let evt = "", data = "";
   while (true) {
     const { value, done } = await reader.read();
     if (done) {
       if (evt || data) flushEvent(evt, data);
       break;
     }
     buf += decoder.decode(value, { stream: true });
     let idx;
     while ((idx = buf.indexOf("\n\n")) >= 0) {
       const raw = buf.slice(0, idx); // 이벤트 블록
       buf = buf.slice(idx + 2);
       evt = ""; data = "";
       raw.split("\n").forEach(line => {
         if (line.startsWith("event:")) evt = line.slice(6).trim();
         else if (line.startsWith("data:")) data += (line.slice(5).trim() + "\n");
       });
       data = data.trim();
       flushEvent(evt, data);
     }
   }
 }

  /* 답변 피드백 버튼 */
  async function sendFeedback(message, rating) {
      try {
          await apiCall("/api/chat/feedback", {
              method: "POST",
              body: { sessionId: state.currentSessionId, message, rating }
          });
          showStatus("피드백 반영 완료", "success");
      } catch (e) {
          showStatus(`피드백 실패: ${e.message}`, "danger", true);
      }
  }
  function attachFeedbackControls(answerText) {
      // Determine the wrapper for the message we want to attach feedback to.
      // We always target the most recent assistant message container (last child
      // of chatMessages).  If there is no message container, do nothing.
      const wrap = dom.chatMessages?.lastElementChild;
      if (!wrap) return;
      // Remove any duplicate feedback bars that may already exist within this
      // wrapper.  If multiple bars are found, remove all but the first.
      const existingBars = wrap.querySelectorAll('[data-role="feedback-bar"]');
      if (existingBars.length > 0) {
        existingBars.forEach((el, idx) => { if (idx > 0) el.remove(); });
        // If at least one bar already exists after cleanup, do not create another.
        return;
      }
      // Create the feedback bar container
      const bar = document.createElement("div");
      bar.dataset.role = "feedback-bar";
      bar.className = "d-flex gap-2 ms-3 mt-1";
      // Create independent like/dislike buttons without duplicate IDs
      const likeBtn = document.createElement("button");
      likeBtn.className = "btn btn-sm btn-outline-success";
      likeBtn.innerHTML = `<i class="bi bi-hand-thumbs-up"></i>`;
      const dislikeBtn = document.createElement("button");
      dislikeBtn.className = "btn btn-sm btn-outline-danger";
      dislikeBtn.innerHTML = `<i class="bi bi-hand-thumbs-down"></i>`;
      bar.appendChild(likeBtn);
      bar.appendChild(dislikeBtn);
      wrap.appendChild(bar);
      // Attach event handlers to send feedback when clicked.  Capture the current
      // answer text via closure to avoid referencing stale values if this
      // function is called again.
      likeBtn.addEventListener("click", () => sendFeedback(answerText, "POSITIVE"));
      dislikeBtn.addEventListener("click", () => sendFeedback(answerText, "NEGATIVE"));
  }

  /* --------------------------------------------------
   * Stop generation functionality
   * -------------------------------------------------- */
  /**
   * Cancel the current streaming response generation.  This function sets a
   * cancellation flag so that incoming SSE events are ignored, hides the
   * stop button and notifies the server to abort any in‑flight tasks for
   * the current session.  If no generation is in progress this function
   * simply returns without making a request.
   */
  async function cancelGeneration() {
    if (!state.isLoading) return;
    state.isCancelled = true;
    if (dom.stopBtn) dom.stopBtn.style.display = 'none';
    try {
      await apiCall('/api/chat/cancel', {
        method: 'POST',
        body: { sessionId: state.currentSessionId }
      });
    } catch (err) {
      // swallow errors
    }
  }

  /* --------------------------------------------------
   * 7. 관리자 기능(임베딩 재학습 / 미세 조정)
   * -------------------------------------------------- */
  async function trainModel() {
    if (!confirm("정말로 임베딩 재학습을 시작하시겠습니까? 기존 데이터는 삭제됩니다.")) return;
    showStatus("임베딩 재학습 요청 중...", "info", true);
    try {
      const r = await apiCall("/api/admin/train", { method: "POST" });
      showStatus(`✅ ${r.message}`, "success", true);
    } catch (e) {
      showStatus(`훈련 시작 실패: ${e.message}`, "danger", true);
    }
  }

  async function startFineTune() {
    showStatus("미세 조정 작업 시작 요청 중...", "info", true);
    try {
      const r = await apiCall("/api/admin/fine-tune", { method: "POST" });
      dom.jobIdInput.value = r.jobId || "";
      showStatus(`✅ 미세 조정 작업 시작됨 (Job ID: ${r.jobId})`, "success", true);
    } catch (e) {
      showStatus(`미세 조정 실패: ${e.message}`, "danger", true);
    }
  }

  async function checkFineTuneStatus() {
    const jobId = dom.jobIdInput.value.trim();
    if (!jobId) return showStatus("확인할 Job ID를 입력하세요.", "warning");
    showStatus(`'${jobId}' 상태 확인 중...`, "info", true);
    try {
      const r = await apiCall(`/api/admin/fine-tune/status?jobId=${jobId}`);
      const { status, fineTunedModel, trainedTokens } = r;
      let msg = `상태: ${status}`;
      if (fineTunedModel) msg += `, 모델: ${fineTunedModel}`;
      if (trainedTokens) msg += `, 학습 토큰: ${trainedTokens}`;
      showStatus(msg, "success", true);
    } catch (e) {
      showStatus(`상태 확인 실패: ${e.message}`, "danger", true);
    }
  }

  /* --------------------------------------------------
   * 8. 초기화
   * -------------------------------------------------- */
  function init() {
    if (!dom.sendBtn || !dom.messageInput) return console.error("필수 요소 누락: sendBtn / messageInput");

    sendBtnHtml = dom.sendBtn.innerHTML;
    saveModelBtnHtml = dom.saveModelBtn?.innerHTML;
    saveSettingsBtnHtml = dom.saveSettingsBtn?.innerHTML;
    adminStatusInitialHtml = dom.adminStatus?.innerHTML ?? "준비 완료.";

      // 설정
        // ▼▼▼ [수정] 체크박스 자동 저장을 위한 리스너들을 추가합니다. ▼▼▼
        const addAutoSaveListener = (element) => {
            if (element) {
                element.addEventListener('change', saveSettings);
            }
        };
        addAutoSaveListener(dom.useRag);
        addAutoSaveListener(dom.useAdaptiveTranslator);
        addAutoSaveListener(dom.autoTranslate);
        addAutoSaveListener(dom.usePolish);
        addAutoSaveListener(dom.useHistory);

        // 이해 요약 토글: 로컬스토리지 복원 및 변경 시 저장
        if (dom.understandingToggle) {
            const saved = localStorage.getItem(UNDERSTANDING_TOGGLE_KEY);
            if (saved !== null) {
                dom.understandingToggle.checked = (saved === 'true');
            }
            dom.understandingToggle.addEventListener('change', () => {
                localStorage.setItem(UNDERSTANDING_TOGGLE_KEY, dom.understandingToggle.checked ? 'true' : 'false');
            });
        }

     // 채팅
      dom.sendBtn.addEventListener("click", sendMessage);

    // ---- 첨부 파일 관련 이벤트 바인딩 ----
    // UI를 초기화합니다. 기존 선택된 파일이 있으면 표시합니다.
    updateSelectedFileUI();
    // 클릭 시 숨겨진 파일 input을 트리거
    if (dom.attachBtn) {
      dom.attachBtn.addEventListener('click', () => {
        dom.fileInput?.click();
      });
    }
    // 파일이 선택되면 상태를 업데이트하고 UI를 갱신
    if (dom.fileInput) {
      dom.fileInput.addEventListener('change', (e) => {
        const file = e.target?.files && e.target.files[0];
        state.selectedFile = file || null;
        updateSelectedFileUI();
      });
    }
    // 선택 취소(X) 버튼 처리
    if (dom.clearFileBtn) {
      dom.clearFileBtn.addEventListener('click', (ev) => {
        ev.preventDefault();
        clearSelectedFile();
      });
    }

    // 드래그 앤 드롭 업로드 영역 처리
    // dropZone 요소가 존재하면 dragover, dragleave, drop 이벤트를 바인딩한다.
    // dragover 이벤트에서 preventDefault를 호출해야 drop이 허용된다.
    // 드래그 중에는 배경색을 밝게 변경하여 사용자가 올바른 영역에 위치했음을 알려준다.
    const dropZone = $("dropZone");
    if (dropZone) {
      const highlight = () => dropZone.classList.add('bg-light');
      const unhighlight = () => dropZone.classList.remove('bg-light');
      // Drag over: allow drop and highlight
      dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        highlight();
      });
      dropZone.addEventListener('dragenter', (e) => {
        e.preventDefault();
        highlight();
      });
      // Drag leave: remove highlight
      dropZone.addEventListener('dragleave', (e) => {
        e.preventDefault();
        unhighlight();
      });
      // Drop: upload files
      dropZone.addEventListener('drop', async (e) => {
        e.preventDefault();
        unhighlight();
        const files = e.dataTransfer?.files;
        if (!files || files.length === 0) return;
        const fd = new FormData();
        for (const f of files) {
          fd.append('files', f);
        }
        try {
          // Include the current sessionId when uploading attachments.  This
          // allows the server to associate uploaded files with the active
          // session so that the RAG chain can retrieve them later.
          const sessionQuery = encodeURIComponent(state.currentSessionId || '');
          const res = await fetch(`/api/attachments/upload?sessionId=${sessionQuery}`, {
            method: 'POST',
            body: fd,
          });
          if (!res.ok) {
            renderMessage({ role: 'SYSTEM', content: '파일 업로드 실패' });
            return;
          }
          const list = await res.json();
          if (!Array.isArray(state.pendingAttachments)) state.pendingAttachments = [];
          if (Array.isArray(list)) {
            list.forEach((att) => {
              // 각 업로드된 파일에 대해 시스템 메시지를 출력합니다.
              const fileName = att?.name || (typeof att === 'object' ? att.name : '파일');
              renderMessage({
                role: 'SYSTEM',
                content: `<i class="bi bi-paperclip me-1"></i>파일 '<strong>${escapeHtml(String(fileName))}</strong>'이 업로드 되었습니다.`
              });
              // 다음 /api/chat 요청에 첨부되도록 ID를 대기 큐에 적재합니다.
              if (att && att.id && !state.pendingAttachments.includes(att.id)) {
                state.pendingAttachments.push(att.id);
              }
            });
          }
        } catch (err) {
          renderMessage({ role: 'SYSTEM', content: `파일 업로드 실패: ${err?.message || err}` });
        }
      });
    }

    // Stop generation button cancels the current response stream
    dom.stopBtn?.addEventListener('click', cancelGeneration);

    // ---------------------------------------------------------------------
    // 위치 기반 기능: 토글, 위치 전송 버튼, 빠른 질의 버튼
    //
    // 위치 기능은 사용자가 명시적으로 켜야만 활성화됩니다.  토글을 ON으로
    // 변경하면 서버에 consent 요청을 보낸 후 브라우저의 Geolocation API를
    // 통해 위치를 주기적으로 업데이트합니다.  토글을 OFF로 변경하면
    // watch를 해제하고 더 이상 이벤트를 보내지 않습니다.  “내 위치 전송”
    // 버튼을 누르면 현재 위치를 즉시 전송합니다.  세 가지 퀵 액션
    // 버튼(.qa 클래스)은 data-q 속성에 저장된 텍스트를 입력창에 넣고
    // sendMessage()를 호출하여 손쉽게 위치 관련 질문을 수행합니다.

    // 토글 이벤트: consent on/off 및 위치 watch 관리
    if (dom.locToggle) {
      dom.locToggle.addEventListener('change', async (e) => {
        const on = e.target.checked;
        try {
          await apiCall(`/api/location/consent/${on ? 'on' : 'off'}`, {
            method: 'POST'
          });
        } catch (err) {
          console.warn('Failed to toggle location consent', err?.message || err);
        }
        // 로컬 프리퍼런스 반영
        savePrefs({ locationEnabled: on });
        // Start or stop watching based on the toggle state
        if (on) {
          // Begin watching: store the watch ID so we can clear it later
          state.geoWatchId = startWatch(postLocation);
        } else {
          // Clear existing watch if present
          if (state.geoWatchId != null && typeof navigator !== 'undefined' && navigator.geolocation) {
            navigator.geolocation.clearWatch(state.geoWatchId);
          }
          state.geoWatchId = null;
        }
      });
    }

    // 위치 전송 버튼: 현재 위치를 즉시 가져와 전송
    if (dom.sendLocBtn) {
      dom.sendLocBtn.addEventListener('click', (e) => {
        e.preventDefault();
        if (typeof navigator === 'undefined' || !navigator.geolocation) {
          console.warn('Geolocation API not available');
          return;
        }
        navigator.geolocation.getCurrentPosition((pos) => {
          postLocation({
            latitude: pos.coords.latitude,
            longitude: pos.coords.longitude,
            accuracy: pos.coords.accuracy,
            timestampMs: Date.now(),
            source: 'manual'
          });
        }, (err) => {
          console.warn('Failed to fetch current position', err);
        }, { enableHighAccuracy: true, maximumAge: 10000, timeout: 10000 });
      });
    }

    // 퀵 액션 버튼: data-q 속성의 질문을 전송
    document.querySelectorAll('.qa').forEach((btn) => {
      btn.addEventListener('click', () => {
        const q = btn.getAttribute('data-q') || btn.dataset.q;
        if (q) {
          dom.messageInput.value = q;
          sendMessage();
        }
      });
    });

      // Gemini 도움말 버튼 이벤트
      {
        const helpBtn = document.getElementById('sendBtnHelp');
        const helpPopover = document.getElementById('helpPopover');
        if (helpBtn && helpPopover) {
          helpBtn.addEventListener('click', async () => {
            // 클릭 즉시 로딩 메시지 표시
            helpPopover.textContent = 'AI 설명 생성 중...';
            const payload = {
  attachmentIds: (state.pendingAttachments || []).slice(),
              contextType: 'ui',
              contextData: {
                elementId: 'send-button',
                description: '사용자가 입력한 메시지를 챗봇에게 전송하는 버튼입니다.'
              }
            };
            try {
              const res = await apiCall('/api/v1/help/context', { method: 'POST', body: payload });
              if (typeof res === 'string') {
                helpPopover.textContent = res;
              } else if (res && (typeof res.content === 'string' || typeof res.message === 'string')) {
                helpPopover.textContent = res.content ?? res.message;
              } else {
                helpPopover.textContent = String(res ?? '');
              }
            } catch (err) {
              helpPopover.textContent = '설명을 가져오는 데 실패했습니다.';
            }
          });
        }

    // ===== TTS (브라우저 → 서버 폴백) =====
    const ttsBtn = document.getElementById("btn-tts");
    if (ttsBtn) {
      ttsBtn.addEventListener("click", async () => {
        // Locate the most recent assistant answer, preferring the
        // previously read turn if available.  Skip any trace panels.
        const sessionId = state.currentSessionId;
        let el = null;
        try {
          el = restoreLastAnswerEl(sessionId) || findLastAssistantAnswerEl();
        } catch {
          el = findLastAssistantAnswerEl();
        }
        if (!el) {
          // No answer bubble found; nothing to read
          return;
        }
        const text = (el.innerText || '').trim();
        if (!text) return;
  const isIOS = /iP(hone|ad|od)/.test(navigator.userAgent)
          || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
        const isAndroid = /Android/i.test(navigator.userAgent);
        const isMobile  = isIOS || isAndroid;

        // CSRF 토큰을 메타 또는 쿠키(XSRF-TOKEN)에서 읽어오는 안전 헬퍼
        const readCsrf = () => {
          const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
          const metaTok = document.querySelector('meta[name="_csrf"]')?.content || '';
          const cookieTok = (() => {
            const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
            return m ? decodeURIComponent(m[1]) : '';
          })();
          return { header, token: metaTok || cookieTok || '' };
        };
        const csrf = readCsrf();

        const playBlob = async (blob) => {
          const audio = document.createElement('audio');
          audio.autoplay = true;
          audio.playsInline = true;
          audio.src = URL.createObjectURL(blob);
          try { await audio.play(); } catch (e) { console.warn('Audio.play blocked', e); }
        };

        const postServerTts = async () => {
          const res = await fetch('/api/audio/tts', {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
              'Content-Type': 'text/plain',
              ...(csrf.token ? { [csrf.header]: csrf.token } : {})
            },
            body: text
          });
          if (!res.ok) throw new Error('POST_TTS_' + res.status);
          await playBlob(await res.blob());
          return true;
        };

        // 예전 백엔드와 호환(“옛날엔 잘됨” 경로): GET은 CSRF 미적용
        const getServerTts = async () => {
          const url = '/api/audio/tts?text=' + encodeURIComponent(text);
          const res = await fetch(url, { method: 'GET', credentials: 'same-origin' });
          if (!res.ok) throw new Error('GET_TTS_' + res.status);
          await playBlob(await res.blob());
          return true;
        };

        const tryServerTts = async () => {
          try { return await postServerTts(); }
          catch (e) {
            // 인증/CSRF/미디어 타입 문제면 GET 재시도
            const s = String(e?.message || '');
            if (s.includes('401') || s.includes('403') || s.includes('415')) {
              try { return await getServerTts(); } catch (_) { /* fallthrough */ }
            }
            return false;
          }
        };

                // ========== ① 모바일(갤럭시/아이폰): 서버 TTS 우선 ==========
                     if (isMobile) {
                       const ok = await tryServerTts();
                       if (ok) return;
                       // 서버 실패 시에만 Web Speech 시도
                     }

             // 데스크톱 우선: Web Speech → 실패 시 서버

             if (!("speechSynthesis" in window)) {
                    const ok = await tryServerTts(); if (!ok) console.warn('TTS: server failed');
                    return;
                  }

                  // Web Speech: 안드로이드에서 보이스 로딩 지연 대응
                  const ensureVoices = () => new Promise(resolve => {
                    const v = window.speechSynthesis.getVoices();
                    if (v && v.length) return resolve(v);
                    const timer = setTimeout(() => resolve(window.speechSynthesis.getVoices() || []), 700);
                    window.speechSynthesis.addEventListener('voiceschanged', () => {
                      clearTimeout(timer);
                      resolve(window.speechSynthesis.getVoices() || []);
                    }, { once: true });
                  });

                  try {
                    const utter = new SpeechSynthesisUtterance(text);
                    const voices = await ensureVoices();
                   const ko = voices.find(v => (v.lang || '').toLowerCase().startsWith('ko'));
                   if (ko) { utter.voice = ko; utter.lang = ko.lang; } else { utter.lang = 'ko-KR'; }
                     utter.rate = 1.0; utter.volume = 1.0;
                   window.speechSynthesis.cancel();
                   if (window.speechSynthesis.paused) window.speechSynthesis.resume();
                   window.speechSynthesis.speak(utter);
                           // 1200ms 안에 speaking 아니면 실패로 보고 서버 폴백
                           await new Promise(r => setTimeout(r, 1200));
                        if (!window.speechSynthesis.speaking) {
                          const ok = await tryServerTts(); if (!ok) console.warn('TTS: server failed after web');
                        }
               } catch (e) {
                const ok = await tryServerTts(); if (!ok) console.warn('TTS: server failed after exception', e);
               }
      });
    }

    // ===== 마이크(STT) (Web Speech → MediaRecorder + 서버 폴백) =====
    const micBtn = document.getElementById("btn-mic");
    if (micBtn) {
      micBtn.addEventListener("click", async () => {
        const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (SR) {
          const r = new SR();
          r.lang = 'ko-KR';
          r.interimResults = false;
          r.maxAlternatives = 1;
          r.onresult = (e) => {
            const resultText = e.results[0][0].transcript;
            const inputEl = document.getElementById('messageInput');
            if (inputEl) inputEl.value = resultText;
          };
          r.start();
          return;
        }
        try {
          const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
          const rec = new MediaRecorder(stream);
          const chunks = [];
          rec.ondataavailable = (ev) => chunks.push(ev.data);
          rec.onstop = async () => {
            const blob = new Blob(chunks, { type: 'audio/webm' });
            const fd = new FormData();
            fd.append('file', blob, 'speech.webm');
            try {
              const res = await fetch('/api/audio/stt', { method: 'POST', body: fd });
              const text = await res.text();
              const inputEl = document.getElementById('messageInput');
              if (inputEl) inputEl.value = text;
            } catch (err) {
              console.warn('STT request failed', err);
            }
          };
          rec.start();
          setTimeout(() => rec.stop(), 5000);
        } catch (err) {
          console.warn('mic failed', err);
        }
      });
    }
      }

   // ✅ 수정 후: 일반적인 채팅 방식 (Enter=전송) 으로 복원
   dom.messageInput.addEventListener("keydown", (e) => {
     if (e.key === "Enter" && !e.shiftKey) {
       e.preventDefault(); // 기본 줄바꿈 동작 방지
       sendMessage();
     }
     // Shift+Enter는 기본 동작(줄바꿈)을 그대로 허용
   });



// 삭제 기능이 포함된 리스너 하나만 남깁니다.
// 세션
  dom.newChatBtn?.addEventListener("click", newSession);
  dom.sessionList?.addEventListener("click", (e) => {
    const delBtn = e.target.closest(".delete-session-btn[data-session-id]");
    const link   = e.target.closest("a[data-session-id]");

    if (delBtn) {
      e.preventDefault();
      e.stopPropagation();
      deleteSession(Number(delBtn.dataset.sessionId));
    } else if (link) {
      e.preventDefault();
      loadSession(Number(link.dataset.sessionId));
    }
  });



/* ===== 꼭 다시 넣어야 하는 함수 ===== */
async function deleteSession(id) {
  if (!confirm("정말로 삭제하시겠습니까?")) return;
  try {
    await apiCall(`/api/chat/sessions/${id}`, { method: "DELETE" });
    showStatus("삭제 완료", "success");
    if (state.currentSessionId === id) newSession(); // 현재 세션을 지웠다면 초기화
    await fetchSessions();
  } catch (e) {
    showStatus(`삭제 실패: ${e.message}`, "danger", true);
  }
}

  /**
   * Fallback handler when streaming is not supported.  This method performs a
   * synchronous chat request to /api/chat and finalises the UI for the current
   * turn.  It mirrors the behaviour of the non‑RAG branch in sendMessage(),
   * replacing the loader bubble with the final answer, attaching a model badge,
   * persisting session information and appending feedback controls.
   *
   * @param {Object} payload The chat request payload
   * @param {string} loaderId The DOM id of the temporary loader element
   */
  async function finalizeFromSync(payload, loaderId) {
    try {
      const res = await apiCall("/api/chat", {
        method: "POST",
        body: payload,
      });
      const loader = document.getElementById(loaderId);
      const answer = res && res.content;
      const model = res && res.modelUsed;
      // Replace the loader bubble contents with the final answer
      if (loader) {
        const messageBubble = loader.closest(".msg");
        if (messageBubble) {
          const raw = String(answer ?? "");
          const isHtml = /<[^>]+>/.test(raw);
          messageBubble.innerHTML = isHtml ? raw : raw.replace(/\n/g, "<br>");
          // attach model badge and persist association
          if (model) {
            upsertModelBadge(messageBubble.parentElement, model);
            persistModelBadge(res.sessionId || state.currentSessionId, model);
          }
          // Remove any legacy model: prefix
          stripModelPrefix(messageBubble);
        }
      } else {
        // Fallback: add a new assistant message bubble
        renderMessage({ role: "ASSISTANT", content: answer, model: undefined });
        if (model) {
          const wrappers = dom.chatMessages?.querySelectorAll('.msg-container.msg-assistant-container');
          const lastWrap = wrappers?.[wrappers.length - 1];
          if (lastWrap) {
            upsertModelBadge(lastWrap, model);
            persistModelBadge(res.sessionId || state.currentSessionId, model);
          }
        }
      }
      // Append answer to history if enabled
      if (dom.useHistory?.checked) {
        state.chatHistory.push({ role: "ASSISTANT", content: String(answer ?? "").trim() });
      }
      // Initialise session id if absent
      if (!state.currentSessionId && res && res.sessionId) {
        state.currentSessionId = res.sessionId;
        await fetchSessions();
      }
      // Attach feedback controls
      if (FEEDBACK_ENABLED) {
        attachFeedbackControls(String(answer ?? "").trim());
      }
    } catch (e) {
      // On error, show a system error message
      renderMessage({ role: "SYSTEM", content: `[오류] ${e.message}` });
    } finally {
      // Regardless of outcome, hide the stop button and reset cancellation state
      if (dom.stopBtn) dom.stopBtn.style.display = "none";
      state.isCancelled = false;
      state.pendingAttachments = [];
    }
  }

  /**
   * 위치 이벤트를 백엔드로 전송합니다.  서버는 현재 사용자의 위치 수집
   * 권한을 검사하고, 권한이 꺼져 있으면 412(Precondition Failed)를
   * 반환합니다. 오류는 콘솔에 기록되고 사용자에게는 표시하지 않습니다.
   *
   * @param {{latitude: number, longitude: number, accuracy: number, timestampMs: number, source: string}} payload
   */
  async function postLocation(payload) {
    try {
      await apiCall('/api/location/events', {
        method: 'POST',
        body: payload
      });
    } catch (err) {
      // 위치 전송 실패는 조용히 무시하고 콘솔에 기록합니다.
      console.warn('Location post failed', err?.message || err);
    }
  }

function newSession() {
  state.currentSessionId = null;
  state.chatHistory = [];
  dom.chatMessages.innerHTML = "";
  fetchSessions();                // (새 대화) 더미 갱신
  dom.messageInput.focus();
}

    // 설정
    dom.saveSettingsBtn?.addEventListener("click", saveSettings);
    dom.saveModelBtn?.addEventListener("click", saveDefaultModel);
 for (const key in dom.sliders) {
   const slider = dom.sliders[key];
   // ❷ 새 콜백
   slider.el?.addEventListener("input", () => {
     slider.val.textContent = slider.el.value;
     /* searchTopK 슬라이더는 즉시 저장(선택) */
     if (key === "searchTopK") {
       saveSettings();   // 서버 보관(있는 경우)
       if (typeof saveUIPrefs === 'function') saveUIPrefs(); // 프론트 저장
     }
   });
 }                  // ⬅️ ③ for-loop 닫기

    // ✅ UI 변경 시 즉시 저장
    const bindPref = el => el && el.addEventListener('change', () => {
      if (typeof saveUIPrefs === 'function') saveUIPrefs();
    });
    bindPref(dom.precisionToggle);
    bindPref(dom.officialSourcesOnly);
    bindPref(dom.searchModeSelect);
    bindPref(dom.webProvidersSelect);
    bindPref(dom.locToggle);
    bindPref(dom.useGeminiLearning);
    // The precision search slider uses 'input' instead of 'change'
    if (dom.sliders?.searchTopK?.el) {
      dom.sliders.searchTopK.el.addEventListener('input', () => {
        if (typeof saveUIPrefs === 'function') saveUIPrefs();
      });
    }
    // 멀티 셀렉트: 공급자 변경 시 저장 (중복 방지를 위해 별도 핸들러)
    if (dom.webProvidersSelect) {
      dom.webProvidersSelect.addEventListener('change', () => {
        if (typeof saveUIPrefs === 'function') saveUIPrefs();
      });
    }


    // 관리자
    dom.trainBtn?.addEventListener("click", trainModel);
    dom.startFineTuneBtn?.addEventListener("click", startFineTune);
    dom.checkFineTuneBtn?.addEventListener("click", checkFineTuneStatus);

    // 모바일 사이드바
    dom.sideOpen?.addEventListener("click", () => dom.sideNav?.classList.add("show"));
    dom.sideClose?.addEventListener("click", () => dom.sideNav?.classList.remove("show"));

    // 로그인/로그아웃 이벤트 바인딩
    {
      // 로그인 트리거 클릭 시 오버레이 표시. 기존 a href 동작을 막는다.
      const loginTrigger = document.getElementById('login-popover-trigger');
      if (loginTrigger) {
        loginTrigger.addEventListener('click', (e) => {
          e.preventDefault();
          showLoginOverlay();
        });
      }
          // {스터프4} 적용: 로그아웃 → POST /logout → 항상 새로고침
        document.getElementById('logoutLink')?.addEventListener('click', async (e) => {
          e.preventDefault();
          try {
            await fetch('/logout', {
              method: 'POST',
              credentials: 'same-origin',
              headers: { [CSRF.header]: CSRF.token, 'X-Requested-With': 'XMLHttpRequest' }
            });
          } finally {
            // 프런트 상태도 비워주고, BFCache 영향 없도록 하드 네비게이션
            try { localStorage.clear(); sessionStorage.clear(); } catch {}
            window.location.replace('/login?logout');
          }
        });
    }

    // 초기 데이터 로드
    hydrateModels();
    // 1) localStorage 프리퍼런스 → UI 복원
    (function rehydrateUIPrefs() {
      const p = loadPrefs();
      if (typeof applyPrefsToUI === 'function') applyPrefsToUI(p);
      // 위치가 켜져 있다면 서버 consent와 watch를 재동기화
      if (p && p.locationEnabled) {
        try {
          apiCall('/api/location/consent/on', { method: 'POST' });
        } catch {
          // ignore errors
        }
        state.geoWatchId = startWatch(postLocation);
      }
    })();
    // 2) 서버 설정/세션 로드 이후 세션 시작
    Promise.all([loadSettings(), fetchSessions()]).then(async () => {
      // Attempt to restore the last session id from localStorage.  When
      // present load the session history and, if it is still running, attach
      // to the in‑flight stream.  Otherwise start a fresh session.
      try {
        const lastSid = localStorage.getItem(LAST_SESSION_KEY);
        if (lastSid) {
          await loadSession(+lastSid);
          try {
            const st = await apiCall(`/api/chat/state?sessionId=${lastSid}`);
            if (st && st.running) {
              const payload = { sessionId: +lastSid };
              await streamChatWithAttach(payload);
            }
          } catch {
            /* ignore state query failures */
          }
        } else {
          newSession();
        }
      } catch {
        // On any error while restoring the previous session simply start a new one
        newSession();
      }
      state.isInitialLoad = false;
      console.log("[chat.js] 초기화 완료");
    });
  }

  document.addEventListener("DOMContentLoaded", init);
})();