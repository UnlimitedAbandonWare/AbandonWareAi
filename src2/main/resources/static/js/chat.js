/**
 * chat.js – AI 서비스 통합 Front-End (Unified Version)
 * 경로: src/main/resources/static/js/chat.js
 * -----------------------------------------------------------------------
 * @version 2025-06-28
 * @description
 * - 분리되어 있던 두 chat.js 파일의 모든 기능 통합.
 * - 기능 단위(상태, DOM, 헬퍼, 세션, 설정, 채팅 등)로 코드 구조 재정리.
 * - 모델 저장 API는 '/api/settings/model'로 통일하고 프론트 상태 동기화 로직 적용.
 * - UI 피드백(로딩, 상태 메시지) 로직을 일관성 있게 개선.
 */

// fetch-wrapper.js에서 apiCall 함수를 가져옵니다.
// 해당 파일이 없다면, 일반 fetch를 사용하도록 수정해야 합니다.
import { apiCall } from "./fetch-wrapper.js";

(() => {
  "use strict";

  /* --------------------------------------------------
   * 0. CSRF 헤더/토큰 (전역 상수)
   * -------------------------------------------------- */
  const CSRF_HEADER = document.querySelector('meta[name="_csrf_header"]')?.content;
  const CSRF_TOKEN = document.querySelector('meta[name="_csrf"]')?.content;


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
    // 세션 관련
    newChatBtn: $("newChatBtn"),
    sessionList: $("sessionList"),
    // 상단 옵션 토글
    useAdaptiveTranslator: $("useAdaptiveTranslator"),
    autoTranslate: $("autoTranslate"),
    useHistory: $("useHistory"),
    // GPT 상세 설정
    advWrap: $("advWrap"),
    saveSettingsBtn: $("saveSettingsBtn"),
    systemPromptInput: $("systemPromptInput"),
    sliders: {
      temperature: { el: $("temperatureSlider"), val: $("temperatureValue") },
      topP: { el: $("topPSlider"), val: $("topPValue") },
      frequencyPenalty: { el: $("freqPenaltySlider"), val: $("freqPenaltyValue") },
      presencePenalty: { el: $("presPenaltySlider"), val: $("presPenaltyValue") },
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

  // 버튼 초기 HTML 저장 (로딩 상태 변경 시 사용)
  let sendBtnHtml, saveModelBtnHtml, saveSettingsBtnHtml, adminStatusInitialHtml;


  /* --------------------------------------------------
   * 3. UI 헬퍼 함수
   * -------------------------------------------------- */

  /**
   * 채팅 메시지를 화면에 렌더링합니다. (모델 이름 표시 기능 포함)
   * @param {object} { role, content, model }
   */
  function renderMessage({ role, content, model }) {
    if (!dom.chatMessages) return;

    const wrap = document.createElement("div");
    wrap.className = `msg-container msg-${role.toLowerCase()}-container`;

    // 시스템 메시지는 다른 스타일 적용
    if (role.toUpperCase() === 'SYSTEM') {
        const systemMsg = document.createElement("div");
        systemMsg.className = "text-center text-muted small my-2";
        systemMsg.innerHTML = content;
        dom.chatMessages.appendChild(systemMsg);
    } else {
        // 어시스턴트 답변일 경우, 사용된 모델 이름 표시
        if (role.toUpperCase() === 'ASSISTANT' && model) {
          const modelLabel = document.createElement("small");
          modelLabel.className = "text-muted d-block mb-1 ms-3";
          modelLabel.innerHTML = `<i class="bi bi-robot me-1"></i>model: <strong>${model}</strong>`;
          wrap.appendChild(modelLabel);
        }
        const bubble = document.createElement("div");
        bubble.className = `msg msg-${role.toLowerCase()}`;
        //
        bubble.innerHTML = String(content ?? "").replace(/\n/g, "<br>");
        wrap.appendChild(bubble);
        dom.chatMessages.appendChild(wrap);
    }
    dom.chatWindow.scrollTop = dom.chatWindow.scrollHeight;
  }

  /**
   * 버튼의 로딩 상태를 설정합니다.
   * @param {HTMLElement} btn - 대상 버튼
   * @param {boolean} loading - 로딩 상태 여부
   * @param {string} [defaultHtml] - 로딩이 끝난 후 복원할 HTML
   */
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

  /**
   * 사이드바 하단에 상태 메시지를 표시합니다.
   * @param {string} msg - 표시할 메시지
   * @param {'info'|'success'|'warning'|'danger'} [type='info'] - 메시지 타입
   * @param {boolean} [persist=false] - 메시지를 계속 표시할지 여부
   */
  function showStatus(msg, type = "info", persist = false) {
    if (!dom.adminStatus) return;
    dom.adminStatus.innerHTML = msg;
    // Bootstrap 5.3+ sublte color classes
    const colorClass = {
        info: 'bg-info-subtle text-info-emphasis',
        success: 'bg-success-subtle text-success-emphasis',
        warning: 'bg-warning-subtle text-warning-emphasis',
        danger: 'bg-danger-subtle text-danger-emphasis'
    }[type];
    dom.adminStatus.className = `p-2 rounded ${colorClass}`;

    if (!persist) {
      setTimeout(() => {
        if (dom.adminStatus.innerHTML === msg) {
          dom.adminStatus.innerHTML = adminStatusInitialHtml;
          dom.adminStatus.className = "mt-auto pt-2 text-muted";
        }
      }, 4000);
    }
  }


  /* --------------------------------------------------
   * 4. 세션 관리 (새 대화, 목록 로드, 특정 대화 로드)
   * -------------------------------------------------- */
  async function newSession() {
    state.currentSessionId = null;
    state.chatHistory = [];
    dom.chatMessages.innerHTML = "";
    renderMessage({ role: "SYSTEM", content: "새 대화를 시작합니다. 무엇을 도와드릴까요?" });
    dom.messageInput?.focus();
    dom.sessionList?.querySelector("a.active")?.classList.remove("active");
  }

  async function fetchSessions() {
    if (!dom.sessionList) return;
    try {
      const sessions = await apiCall("/api/chat/sessions");
      dom.sessionList.innerHTML = "";
      (sessions || []).forEach((s) => {
        const li = document.createElement("li");
        li.className = "session-item";
        const a = document.createElement("a");
        a.href = "#";
        a.className = s.id === state.currentSessionId ? "active" : "";
        a.textContent = s.title;
        a.dataset.sessionId = s.id;
        li.appendChild(a);
        dom.sessionList.appendChild(li);
      });
    } catch (e) {
      showStatus("세션 목록 로드 실패", "danger", true);
    }
  }

  async function loadSession(id) {
    if (state.isLoading || !id || id === state.currentSessionId) return;
    setLoading(dom.sendBtn, true, sendBtnHtml);
    try {
      const data = await apiCall(`/api/chat/sessions/${id}`);
      dom.chatMessages.innerHTML = "";
      state.chatHistory = [];
      (data.messages || []).forEach((m) => {
        renderMessage({ role: m.role, content: m.content, model: null });
        state.chatHistory.push({ role: m.role, content: m.content });
      });
      state.currentSessionId = id;
      await fetchSessions(); // 활성 세션 표시 업데이트
    } catch (e) {
      renderMessage({ role: "SYSTEM", content: `대화 기록 로드 실패: ${e.message}` });
    } finally {
      setLoading(dom.sendBtn, false, sendBtnHtml);
    }
  }


  /* --------------------------------------------------
   * 5. 설정 관리 (GPT 상세 설정 및 기본 모델)
   * -------------------------------------------------- */

  /**
   * 페이지 로드 시 백엔드에서 모델 목록을 받아 드롭다운을 채웁니다.
   */
  function hydrateModels() {
    if (!dom.defaultModelSelect || !window.initialData) return;
    const { models = [], currentModel = null } = window.initialData;
    dom.defaultModelSelect.innerHTML = "";

    if (!models.length) {
      const opt = document.createElement("option");
      opt.textContent = "사용 가능한 모델 없음";
      opt.disabled = true;
      dom.defaultModelSelect.appendChild(opt);
      dom.saveModelBtn.disabled = true;
      return;
    }

    // `models` 배열이 객체의 배열이라고 가정 ({modelId: '...'})
    models.forEach((m) => {
      const opt = document.createElement("option");
      opt.value = m.modelId;
      opt.textContent = m.modelId;
      if (m.modelId === currentModel) opt.selected = true;
      dom.defaultModelSelect.appendChild(opt);
    });
  }

  /**
   * 현재 슬라이더와 입력 필드의 값들을 객체로 수집합니다.
   */
  function collectSettings() {
    const settings = { systemPrompt: dom.systemPromptInput?.value ?? "" };
    for (const key in dom.sliders) {
      const slider = dom.sliders[key];
      if (slider?.el) {
        settings[key] = +slider.el.value;
      }
    }
    return settings;
  }

  /**
   * 백엔드에서 GPT 설정을 로드하여 UI에 반영합니다.
   */
  async function loadSettings() {
    try {
      const settings = await apiCall("/api/settings");
      if (dom.systemPromptInput) dom.systemPromptInput.value = settings.SYSTEM_PROMPT ?? "";
      for (const key in dom.sliders) {
        const slider = dom.sliders[key];
        if (!slider?.el) continue;
        const settingKey = key.replace(/([A-Z])/g, "_$1").toUpperCase();
        const value = settings[settingKey] ?? slider.el.defaultValue;
        slider.el.value = value;
        slider.val.textContent = value;
      }
    } catch (e) {
      showStatus("GPT 설정 로드 실패", "danger");
    }
  }

  /**
   * 현재 UI의 GPT 설정을 백엔드에 저장합니다.
   */
  async function saveSettings() {
    setLoading(dom.saveSettingsBtn, true, saveSettingsBtnHtml);
    try {
      await apiCall("/api/settings", { method: "POST", body: collectSettings() });
      showStatus("GPT 설정 저장 완료", "success");
    } catch (e) {
      showStatus(`설정 저장 실패: ${e.message}`, "danger", true);
    } finally {
      setLoading(dom.saveSettingsBtn, false, saveSettingsBtnHtml);
    }
  }

  /**
   * 선택된 기본 모델을 백엔드에 저장합니다.
   */
  async function saveDefaultModel() {
    const selectedModel = dom.defaultModelSelect?.value;
    if (!selectedModel) {
      showStatus("저장할 모델을 선택하세요.", "warning");
      return;
    }

    setLoading(dom.saveModelBtn, true, saveModelBtnHtml);
    showStatus(`'${selectedModel}' 모델을 기본값으로 저장 중...`, "info", true);

    try {
        const headers = {
            'Content-Type': 'application/json',
            ...(CSRF_HEADER && CSRF_TOKEN && { [CSRF_HEADER]: CSRF_TOKEN })
        };
        const response = await fetch('/api/settings/model', {
            method: 'POST',
            headers: headers,
            body: JSON.stringify({ model: selectedModel })
        });
        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.message || `서버 오류: ${response.status}`);
        }

        showStatus(data.message || `✅ 기본 모델이 '${selectedModel}'(으)로 변경되었습니다.`, "success");
        window.initialData.currentModel = selectedModel; // 프론트엔드 상태 동기화

    } catch (error) {
        showStatus(`모델 저장 실패: ${error.message}`, "danger", true);
        console.error('Failed to save default model:', error);
    } finally {
        setLoading(dom.saveModelBtn, false, saveModelBtnHtml);
    }
  }


  /* --------------------------------------------------
   * 6. 채팅 메시지 전송
   * -------------------------------------------------- */
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

    try {
      const payload = {
        message: text,
        sessionId: state.currentSessionId,
        useAdaptive: dom.useAdaptiveTranslator?.checked,
        autoTranslate: dom.autoTranslate?.checked,
        history: dom.useHistory?.checked ? state.chatHistory.slice(0, -1) : [],
        ...collectSettings(),
      };

      const res = await apiCall("/api/chat", { method: "POST", body: payload });

      renderMessage({ role: "ASSISTANT", content: res.content, model: res.model });
      if (dom.useHistory?.checked) {
          state.chatHistory.push({ role: "ASSISTANT", content: res.content });
      }

      if (!state.currentSessionId && res.sessionId) {
        state.currentSessionId = res.sessionId;
        await fetchSessions();
      }
    } catch (e) {
      renderMessage({ role: "SYSTEM", content: `[오류] ${e.message}` });
    } finally {
      state.isLoading = false;
      setLoading(dom.sendBtn, false, sendBtnHtml);
      dom.messageInput.focus();
    }
  }


  /* --------------------------------------------------
   * 7. 초기화 (이벤트 리스너 바인딩)
   * -------------------------------------------------- */
  function init() {
    // 필수 요소가 없으면 초기화 중단
    if (!dom.sendBtn || !dom.messageInput) {
        console.error("채팅 필수 요소(sendBtn, messageInput)가 없어 초기화를 중단합니다.");
        return;
    }

    // 버튼의 원래 HTML 저장
    sendBtnHtml = dom.sendBtn.innerHTML;
    saveModelBtnHtml = dom.saveModelBtn?.innerHTML;
    saveSettingsBtnHtml = dom.saveSettingsBtn?.innerHTML;
    adminStatusInitialHtml = dom.adminStatus?.innerHTML ?? "준비 완료.";

    // 이벤트 리스너 연결
    dom.sendBtn.addEventListener("click", sendMessage);
    dom.messageInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.isComposing && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
      }
    });

    dom.newChatBtn?.addEventListener("click", newSession);
    dom.sessionList?.addEventListener("click", (e) => {
      const anchor = e.target.closest("a[data-session-id]");
      if (anchor) {
        e.preventDefault();
        loadSession(Number(anchor.dataset.sessionId));
      }
    });

    dom.saveSettingsBtn?.addEventListener("click", saveSettings);
    dom.saveModelBtn?.addEventListener("click", saveDefaultModel);

    // 슬라이더 값 변경 시 텍스트 업데이트
    for (const key in dom.sliders) {
        const slider = dom.sliders[key];
        slider.el?.addEventListener("input", () => (slider.val.textContent = slider.el.value));
    }

    // 모바일 사이드바 토글
    dom.sideOpen?.addEventListener("click", () => dom.sideNav?.classList.add("show"));
    dom.sideClose?.addEventListener("click", () => dom.sideNav?.classList.remove("show"));

    // 초기 데이터 로드 및 UI 구성
    hydrateModels(); // 모델 목록 채우기
    Promise.all([loadSettings(), fetchSessions()]).then(() => {
      newSession(); // 새 대화로 시작
      state.isInitialLoad = false;
      console.log("[chat.js] 모든 기능이 통합되고 성공적으로 초기화되었습니다.");
    });
  }

  // DOM이 완전히 로드된 후 초기화 함수 실행
  document.addEventListener("DOMContentLoaded", init);

})();