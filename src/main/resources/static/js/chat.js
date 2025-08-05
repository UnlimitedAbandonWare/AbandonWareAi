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

(() => {
  "use strict";

  /* --------------------------------------------------
   * 1. 전역 상태 (Global State)
   * -------------------------------------------------- */
  const state = {
    currentSessionId: null,
    chatHistory: [],
    isLoading: false,
    isInitialLoad: true,
  };

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
  };

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
      const hasTrace = typeof content === "string" && content.includes('class="search-trace"');
      if (hasTrace) {
        // 검색과정 패널은 assistant 버블처럼 렌더
        const label = document.createElement("small");
        label.className = "text-muted d-block mb-1 ms-3";
        label.innerHTML = `<i class="bi bi-search-heart me-1"></i>검색 과정`;
        wrap.appendChild(label);

        const bubble = document.createElement("div");
        bubble.className = "msg msg-assistant";
        const raw = String(content ?? "");
        bubble.innerHTML = raw; // HTML 그대로
        wrap.appendChild(bubble);
        dom.chatMessages.appendChild(wrap);
      } else {
        const systemMsg = document.createElement("div");
        systemMsg.className = "text-center text-muted small my-2";
        systemMsg.innerHTML = content;
        dom.chatMessages.appendChild(systemMsg);
      }
    } else {
      if (role.toUpperCase() === "ASSISTANT" && model) {
        const label = document.createElement("small");
        label.className = "text-muted d-block mb-1 ms-3";
        label.innerHTML = `<i class=\"bi bi-robot me-1\"></i>model: <strong>${model}</strong>`;
        wrap.appendChild(label);
      }
      const bubble = document.createElement("div");
      bubble.className = `msg msg-${role.toLowerCase()}`;
      const raw = String(content ?? "");
      const isHtml = /<[^>]+>/.test(raw);   // 답변이 HTML 조각이면 줄바꿈 치환 금지
      bubble.innerHTML = isHtml ? raw : raw.replace(/\n/g, "<br>");
      wrap.appendChild(bubble);
      dom.chatMessages.appendChild(wrap);
    }
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

/* 4-2. 세션 목록-클릭 델리게이트 (패치 ② 포함) */
dom.sessionList?.addEventListener("click", (e) => {
  const delBtn   = e.target.closest(".delete-session-btn[data-session-id]");
  const link     = e.target.closest("a[data-session-id]");

  if (delBtn) {                      // 🔥 삭제
    e.preventDefault();
    e.stopPropagation();             // ← 링크 활성화 방지
    deleteSession(Number(delBtn.dataset.sessionId));
    return;
  }
  if (link) {                        // 📑 열기
    e.preventDefault();
    loadSession(Number(link.dataset.sessionId));
  }
});


  async function loadSession(id) {
    if (state.isLoading || !id || id === state.currentSessionId) return;
    setLoading(dom.sendBtn, true, sendBtnHtml);
    try {
      const data = await apiCall(`/api/chat/sessions/${id}`);
      dom.chatMessages.innerHTML = "";
      state.chatHistory = [];
      (data.messages || []).forEach((m) => {
        renderMessage({ role: m.role, content: m.content, model: m.modelUsed });
        state.chatHistory.push({ role: m.role, content: m.content });
      });
      state.currentSessionId = id;
      await fetchSessions();
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

    renderMessage({ role: "USER", content: text });
    if (dom.useHistory?.checked) {
      state.chatHistory.push({ role: "USER", content: text });
    }
    dom.messageInput.value = "";

    state.isLoading = true;
    setLoading(dom.sendBtn, true, sendBtnHtml);
    dom.messageInput.disabled = true;

    // ▼▼▼ [수정] 이 부분을 수정하세요 ▼▼▼
    let loaderId = null; // loaderId를 if문 밖에서 선언
if (dom.useRag?.checked) { // 웹 검색이 켜져 있을 때만 실행
        loaderId = `loader-${Date.now()}`;
        renderMessage({
            role: "ASSISTANT",
            content: `<div id="${loaderId}">
                        <div class="spinner-border spinner-border-sm me-2" role="status">
                          <span class="visually-hidden">Loading...</span>
                        </div>
                        검색중입니다…
                      </div>`
        });
    }
    // ▲▲▲ [수정 끝] ▲▲▲

    // ▲▲▲ [1. 로딩 메시지 추가 끝] ▲▲▲


    try {
// chat.js  (sendMessage 안)
const payload = {
  message: text,
  sessionId: state.currentSessionId,
  useRag: dom.useRag?.checked,
  useAdaptive: dom.useAdaptiveTranslator?.checked,
  autoTranslate: dom.autoTranslate?.checked,
 model: window.initialData.currentModel,   // ★ 추가
 polish: dom.usePolish?.checked,     // ← 추가
  history: dom.useHistory?.checked ? state.chatHistory.slice(0, -1) : [],
  ...collectSettings(),
    maxMemoryTokens: dom.sliders.maxMemoryTokens.el.value, // 💡 추가
    maxRagTokens:    dom.sliders.maxRagTokens.el.value,    // 💡 추가
        /* (NEW) 웹 검색 개수 */
        webTopK:         dom.sliders.searchTopK.el.value,
};

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
                    const isHtml = /<[^+>]>/.test(raw);
                    messageBubble.innerHTML = isHtml ? raw : raw.replace(/\n/g, "<br>");

              // 모델 정보가 있다면 버블 위에 추가합니다.
              if(model) {
                  const modelLabel = document.createElement("small");
                  modelLabel.className = "text-muted d-block mb-1 ms-3";
                  modelLabel.innerHTML = `<i class="bi bi-robot me-1"></i>model: <strong>${model}</strong>`;
                  messageBubble.parentElement.prepend(modelLabel);
              }
          }
        } else {
          // 만약 로더를 찾지 못하는 예외 상황에는 그냥 새 메시지를 추가합니다. (Fallback)
          renderMessage({ role: "ASSISTANT", content: answer, model: model });
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

     // 채팅
      dom.sendBtn.addEventListener("click", sendMessage);

      // ▼▼▼ [수정] 기존 keydown 리스너를 아래 코드로 전체 교체 ▼▼▼
      dom.messageInput.addEventListener("keydown", (e) => {
        // Shift 키와 함께 Enter를 누르면 메시지 전송
        if (e.key === "Enter" && e.shiftKey) {
          e.preventDefault(); // 기본 동작(줄바꿈) 방지
          sendMessage();
          return;
        }

        // (중요) 그냥 Enter만 누를 경우, 아무것도 하지 않고 기본 동작(줄바꿈)이 일어나도록 둡니다.
        // chat.js에 다른 Enter 관련 리스너가 있을 수 있으므로, 여기서 이벤트 전파를 막아 충돌을 방지합니다.
        if (e.key === "Enter" && !e.shiftKey) {
           e.stopPropagation();
        }
      }, true); // 이벤트 캡처링 단계에서 먼저 실행하여 다른 리스너보다 우선권을 갖도록 true 옵션을 추가합니다.
      // ▲▲▲ [수정 끝] ▲▲▲



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
        if (key === "searchTopK") saveSettings();
    });
 }                  // ⬅️ ③ for-loop 닫기


    // 관리자
    dom.trainBtn?.addEventListener("click", trainModel);
    dom.startFineTuneBtn?.addEventListener("click", startFineTune);
    dom.checkFineTuneBtn?.addEventListener("click", checkFineTuneStatus);

    // 모바일 사이드바
    dom.sideOpen?.addEventListener("click", () => dom.sideNav?.classList.add("show"));
    dom.sideClose?.addEventListener("click", () => dom.sideNav?.classList.remove("show"));

    // 초기 데이터 로드
    hydrateModels();
    Promise.all([loadSettings(), fetchSessions()]).then(() => {
      newSession();
      state.isInitialLoad = false;
      console.log("[chat.js] 초기화 완료");
    });
  }

  document.addEventListener("DOMContentLoaded", init);
})();