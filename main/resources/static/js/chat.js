const $ = (id) => document.getElementById(id);
const CHAT_UI_HEARTBEAT_API = '/api/chat/ui-heartbeat';
const PIPELINE_HEALTH_API = '/agent/db-context/pipeline-health';

const dom = {
  form: $("chatForm"),
  messageInput: $("messageInput"),
  sendBtn: $("sendBtn"),
  stopBtn: $("stopBtn"),
  attachBtn: $("attachBtn"),
  attachInput: $("attachInput"),
  attachList: $("attachList"),
  micBtn: $("micBtn"),
  newChatBtn: $("newChatBtn"),
  modelSelect: $("modelSelect"),
  modelSelectionMode: $("modelSelectionMode"),
  executionMode: $("executionModeSelect"),
  searchModeSelect: $("searchModeSelect"),
  useRag: $("useRagToggle"),
  googleSearchRescue: $("googleSearchRescueToggle"),
  chatMessages: $("chatWindow"),
  coreStatusRail: $("coreStatusRail"),
  streamStatus: $("streamStatus"),
  modelStatus: $("modelStatus"),
  searchStatus: $("searchStatus"),
  ragStatus: $("ragStatus"),
  traceStatus: $("traceStatus"),
  qualityStatus: $("qualityStatus"),
  healthStatus: $("healthStatus"),
  decisionRibbon: $("decisionRibbon"),
  responseSettingsSummary: $("responseSettingsSummary"),
  diagnosticsSummaryControl: $("diagnosticsSummaryControl"),
  diagnosticsSummary: $("diagnosticsSummary"),
  debugHeartbeatBar: $("debugHeartbeatBar"),
  currentModelBadge: document.querySelector("[data-current-model]")
};

const debugHeartbeatSummaryState = {
  liveStatus: "WARN",
  waitStatus: "unknown",
  timeoutStatus: "unknown",
  cancelStatus: "unknown",
  nextAction: "none"
};

const currentTurnHealthOverlay = {
  kind: 'none',
  status: 'OK',
  streamStatus: 'unknown',
  streamContext: 'none'
};

const CHAT_TRANSITION_DEBUG_EVENT = "awx:chat-transition";
const CHAT_TRANSITION_DEBUG_MAX_RECORDS = 40;
const CHAT_TRANSITION_DEBUG_KINDS = new Set([
  "reset",
  "evidence",
  "diagnostics",
  "terminal",
  "late-event",
  "decision.route",
  "decision.context",
  "decision.retrieve",
  "decision.evidence",
  "decision.answer",
  "decision.recover"
]);
const CHAT_TRANSITION_DEBUG_STATES = new Set([
  "not-observed",
  "idle",
  "citation-backed",
  "partial-citation",
  "retrieved-uncited",
  "external-unavailable",
  "observed-active",
  "observed-complete",
  "observed-degraded",
  "observed-cancelled",
  "pending",
  "responding",
  "attention",
  "complete",
  "stopped",
  "blocked"
]);
const CHAT_TRANSITION_DEBUG_REASONS = new Set([
  ...CHAT_TRANSITION_DEBUG_STATES,
  "new-turn",
  "new-chat",
  "reload",
  "evidence-state",
  "decision-state",
  "diagnostics-state",
  "stream-stopped",
  "server-cancel",
  "client-cancel",
  "abort-error",
  "terminal-latch",
  "inactive-stream-target",
  "session-restore"
]);
const CHAT_TRANSITION_DEBUG_RESET_REASONS = new Set([
  "none",
  "new-turn",
  "new-chat",
  "reload",
  "session-restore"
]);
const chatTransitionDebugState = {
  turnId: "turn:not-observed",
  startedAtMs: 0,
  records: [],
  lastByKind: Object.create(null),
  lastSignatureByKind: Object.create(null)
};
let chatTransitionDebugSearchCache = null;
let chatTransitionDebugEnabledCache = false;

function isChatTransitionDebugEnabled() {
  let search = "";
  try {
    search = String(window.location?.search || "");
    if (search === chatTransitionDebugSearchCache) return chatTransitionDebugEnabledCache;
    const value = new URLSearchParams(search).get("debug");
    chatTransitionDebugEnabledCache = ["1", "true", "on"].includes(String(value || "").trim().toLowerCase());
  } catch {
    chatTransitionDebugEnabledCache = false;
  }
  chatTransitionDebugSearchCache = search;
  return chatTransitionDebugEnabledCache;
}

function boundedChatTransitionTurnId(value) {
  const text = String(value || "").trim().toLowerCase();
  return /^(?:turn|session):[a-z0-9][a-z0-9._-]{0,39}$/.test(text)
    ? text
    : "turn:not-observed";
}

function boundedChatTransitionValue(value, allowed, fallback) {
  const text = String(value || "").trim().toLowerCase();
  return allowed.has(text) ? text : fallback;
}

function emitChatTransitionDebug(record) {
  try {
    document.dispatchEvent(new CustomEvent(CHAT_TRANSITION_DEBUG_EVENT, { detail: record }));
  } catch {
    // Debug observers must never interfere with the chat lifecycle.
  }
  try {
    console.debug("[AWX][chat-transition]", record);
  } catch {
    // Console instrumentation is optional and fail-soft by contract.
  }
}

function recordChatTransitionDebug({
  kind = "",
  to = "not-observed",
  reasonCode = "not-observed",
  terminalLatch = false,
  lateEventBlocked = false,
  resetReason = "none"
} = {}) {
  if (!isChatTransitionDebugEnabled()) return null;
  try {
    const boundedKind = boundedChatTransitionValue(kind, CHAT_TRANSITION_DEBUG_KINDS, "");
    if (!boundedKind) return null;
    const boundedTo = boundedChatTransitionValue(to, CHAT_TRANSITION_DEBUG_STATES, "not-observed");
    const boundedReason = boundedChatTransitionValue(reasonCode, CHAT_TRANSITION_DEBUG_REASONS, "not-observed");
    const boundedReset = boundedChatTransitionValue(resetReason, CHAT_TRANSITION_DEBUG_RESET_REASONS, "none");
    const from = chatTransitionDebugState.lastByKind[boundedKind] || "not-observed";
    const signature = [boundedTo, boundedReason, terminalLatch === true, lateEventBlocked === true, boundedReset].join("|");
    if (chatTransitionDebugState.lastSignatureByKind[boundedKind] === signature) return null;

    const startedAtMs = Number(chatTransitionDebugState.startedAtMs || 0);
    const record = Object.freeze({
      turnId: chatTransitionDebugState.turnId,
      kind: boundedKind,
      from,
      to: boundedTo,
      reasonCode: boundedReason,
      terminalLatch: terminalLatch === true,
      lateEventBlocked: lateEventBlocked === true,
      resetReason: boundedReset,
      timestampMs: Math.max(0, Math.floor(Date.now())),
      elapsedMs: startedAtMs > 0 ? Math.max(0, Math.round(nowMs() - startedAtMs)) : 0
    });
    chatTransitionDebugState.lastByKind[boundedKind] = boundedTo;
    chatTransitionDebugState.lastSignatureByKind[boundedKind] = signature;
    chatTransitionDebugState.records.push(record);
    if (chatTransitionDebugState.records.length > CHAT_TRANSITION_DEBUG_MAX_RECORDS) {
      chatTransitionDebugState.records.splice(0, chatTransitionDebugState.records.length - CHAT_TRANSITION_DEBUG_MAX_RECORDS);
    }
    emitChatTransitionDebug(record);
    return record;
  } catch {
    return null;
  }
}

function beginChatTransitionDebugTurn(turnId, resetReason = "new-turn") {
  if (!isChatTransitionDebugEnabled()) return null;
  try {
    chatTransitionDebugState.turnId = boundedChatTransitionTurnId(turnId);
    chatTransitionDebugState.startedAtMs = nowMs();
    chatTransitionDebugState.lastByKind = Object.create(null);
    chatTransitionDebugState.lastSignatureByKind = Object.create(null);
    return recordChatTransitionDebug({
      kind: "reset",
      to: "idle",
      reasonCode: resetReason,
      resetReason
    });
  } catch {
    return null;
  }
}

let lastServerHealth = {
  status: 'WARN',
  detail: 'live:WARN core:WARN ui:WARN model:UNKNOWN answer:WARN proof:SUPPORTING external:SUPPORTING'
};

const CSRF = {
  token: document.querySelector('meta[name="_csrf"]')?.content || "",
  header: document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN"
};

const IMAGE_JOB_MODULE_IMPORT_CONTRACT = "import { renderImageJobCard, updateImageJobCard, attachImageJobDebug, attachImageJobConfigDebug }";
const IMAGE_JOB_STILL_RUNNING_CHECK_STATUS_OR_MANIFEST = "IMAGE_JOB_STILL_RUNNING_CHECK_STATUS_OR_MANIFEST";
const assistantReasoningStates = new WeakMap();
let imageJobUiModule = null;
let streamController = null;
let streamCancelRequested = false;
let streamRenderSuppressed = false;
let activeStreamHeartbeatTimer = null;
let streamCancelInFlight = null;
let sendMessageInFlight = false;
let activeSessionId = null;
let activeRunToken = null;
let activeRunGeneration = 0;
let activeStreamAssistant = null;
let assistantMessageSequence = 0;
let thoughtEventCount = 0;
let understandingEventCount = 0;
let lastAssistantModelFallback = null;
let currentTurnRecoveryState = "";
let lastChatUsageDetail = "usage:unknown";
let latestStreamQueryRewriteHeartbeat = null;
let restoredSessionHydrated = false;
let restoredSessionHydrationGeneration = 0;
let restoredRunResumeInFlight = null;
let pendingStopBeforeToken = null;
const state = { currentSessionId: null, latestEvidenceRailItems: [] };
const MAX_PENDING_ATTACHMENTS = 32;
const MIC_PCM_MAX_BYTES = 320000;
const pendingAttachments = [];
let micRecording = null;
let micUnavailable = false;
let localControlOverrideActive = false;
// 복원 중 렌더링과 사용자 명시 변경을 구분하는 hydration 단계.
// READY 이전에는 복원이 persist를 트리거하지 않고, 사용자 이벤트만 저장된다.
const ControlHydrationPhase = Object.freeze({
  BOOTSTRAPPING: "BOOTSTRAPPING",
  RESTORING_GLOBAL: "RESTORING_GLOBAL",
  RESTORING_SESSION: "RESTORING_SESSION",
  READY: "READY"
});
let controlHydrationPhase = ControlHydrationPhase.BOOTSTRAPPING;
function markControlHydrationReady() {
  if (controlHydrationPhase !== ControlHydrationPhase.READY) {
    controlHydrationPhase = ControlHydrationPhase.READY;
  }
}
const UNKNOWN_ANSWER_MODES = new Set(["unknown", "none", "null", "undefined", "-"]);
const ANSWER_COMPLETE_MODES = new Set(["CHAT", "RAG", "HISTORY_CURRENT_TURN", "HISTORY_RECENT", "DIRECT_LITERAL"]);
const STREAM_STALE_WAIT_MS = 60000;
const STREAM_SERVER_MODEL_BUDGET_MS = 90000;
const STREAM_SERVER_WEB_BUDGET_MS = 600000;
const STREAM_SERVER_EVIDENCE_BUDGET_MS = 120000;
const SERVER_CANCEL_TIMEOUT_MS = 1500;
const FINAL_ACK_TIMEOUT_MS = 1500;
const READY_ACK_TIMEOUT_MS = 4000;
const MAX_SSE_EVENT_UTF8_BYTES = 131072;
const SAFE_STREAM_EVENT_NAME = /^[A-Za-z0-9._-]{1,64}$/;
const CURRENT_SESSION_STORAGE_KEY = "chat.currentSessionId";
const ACTIVE_RUN_STORAGE_KEY = "chat.activeRun";
const CONTROL_SETTINGS_STORAGE_KEY = "chat.controlSettings";
const SESSION_LIST_LIMIT = 12;
let sessionListRefreshGeneration = 0;
let sessionListRefreshInFlight = null;
let sessionListRefreshPendingReason = null;
let sessionSelectionGeneration = 0;

function streamProtocolError(code) {
  const error = new Error("stream_failed");
  error.name = "StreamProtocolError";
  error.streamFailureCode = code;
  return error;
}

async function readBoundedFailureBody(response, maxChars = 1200) {
  const contentType = String(response?.headers?.get?.("content-type") || "").toLowerCase();
  const reader = response?.body?.getReader?.();
  if (!reader) return { text: "", contentType, truncated: false };

  const decoder = new TextDecoder();
  const limit = Math.max(0, Math.floor(Number(maxChars) || 0));
  let text = "";
  let truncated = false;

  while (text.length < limit) {
    const next = await reader.read();
    if (next.done) break;
    const bytes = next.value instanceof Uint8Array ? next.value : new Uint8Array(next.value || []);
    for (let index = 0; index < bytes.length; index += 1) {
      const decoded = decoder.decode(bytes.subarray(index, index + 1), { stream: true });
      if (decoded.length > limit - text.length) {
        truncated = true;
        break;
      }
      text += decoded;
      if (text.length >= limit) {
        truncated = true;
        break;
      }
    }
    if (truncated) break;
  }

  if (truncated) {
    try {
      await reader.cancel();
    } catch {
      // Cancellation is best-effort; failure bodies never become public diagnostics.
    }
  } else {
    const tail = decoder.decode();
    if (tail.length <= limit - text.length) text += tail;
  }
  return { text, contentType, truncated };
}

function chatFailureMeta(failureKind, retryable, status, serverCode, nextAction, retryAt = null) {
  return Object.freeze({
    failureKind,
    retryable: retryable === true,
    status: Number.isInteger(status) ? status : null,
    serverCode: serverCode || null,
    nextAction,
    ...(Number.isFinite(retryAt) ? { retryAt } : {})
  });
}

const CHAT_FAILURE_BODY_CODES = new Set([
  "backend_unavailable",
  "forbidden",
  "session_forbidden",
  "backend_timeout", "auth_required", "csrf_invalid", "rate_limited", "quota_exceeded",
  "provider_not_configured", "provider_unauthorized", "model_unavailable", "protocol_unsupported",
  "local_model_store_unavailable", "local_capacity_exceeded", "gpu_device_lost",
  "model_circuit_open", "model_request_invalid", "request_cancelled",
  "public_request_deadline_exhausted", "request_deadline_exhausted",
  "chat_rate_limited", "chat_admission_exceeded", "chat_model_budget_exceeded",
  "chat_retrieval_budget_exceeded", "chat_provider_budget_exceeded", "public_body_executor_saturated",
  "chat_admission_unavailable", "chat_plan_budget_unavailable", "session_quota_exceeded",
  "idempotency_duplicate", "idempotency_payload_mismatch"
]);
const CHAT_FAILURE_BODY_CODE_KEYS = ["reasonCode", "code", "error", "errorCode", "reason", "content"];
const CHAT_FAILURE_STREAM_CODES = new Set(CHAT_FAILURE_BODY_CODES);
const CHAT_FAILURE_PROTOCOL_CODES = new Set(["sse_event_too_large", "malformed_terminal_event", "unexpected_content_type", "stream_incomplete"]);
let chatAccessState = "checking";
let chatModelCatalogReady = !document.getElementById("modelBrowser");

function boundedFailureCode(input = {}) {
  if (input.truncated === true) return null;
  const boundedText = String(input.boundedText || "");
  const contentType = String(input.contentType || "").toLowerCase();
  if (contentType.includes("json")) {
    let parsed;
    try {
      parsed = JSON.parse(boundedText);
    } catch {
      return null;
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
    const candidates = [];
    // Explicit machine fields are authoritative. Prose and structured error
    // descriptions must not invalidate them; conflicting categorical aliases do.
    for (const key of ["reasonCode", "code", "errorCode"]) {
      if (!Object.prototype.hasOwnProperty.call(parsed, key)) continue;
      if (typeof parsed[key] !== "string") return null;
      candidates.push(parsed[key].trim().toLowerCase());
    }
    for (const key of ["error", "reason", "content"]) {
      if (typeof parsed[key] !== "string") continue;
      const code = parsed[key].trim().toLowerCase();
      if (CHAT_FAILURE_BODY_CODES.has(code)) candidates.push(code);
    }
    if (!candidates.length || !candidates.every(code => code === candidates[0])) return null;
    return CHAT_FAILURE_BODY_CODES.has(candidates[0]) ? candidates[0] : null;
  }
  const candidate = boundedText.trim();
  return CHAT_FAILURE_BODY_CODES.has(candidate) ? candidate : null;
}

function sseFailureCode(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  const candidates = [];
  for (const key of ["code", "data"]) {
    if (!Object.prototype.hasOwnProperty.call(payload, key)) continue;
    if (typeof payload[key] !== "string") return null;
    candidates.push(payload[key]);
  }
  if (candidates.length === 0 || !CHAT_FAILURE_STREAM_CODES.has(candidates[0])) return null;
  return candidates.every((candidate) => candidate === candidates[0]) ? candidates[0] : null;
}

function chatRetryAt(value, now = Date.now()) {
  const text = String(value || "").trim();
  if (!text || text.length > 128) return null;
  // HTTP-date or nonnegative delta-seconds (RFC 9110, 10.2.3).
  const seconds = /^\d+$/.test(text) ? Number(text) : null;
  if (seconds !== null) {
    const deadline = now + seconds * 1000;
    return Number.isSafeInteger(deadline) ? deadline : null;
  }
  if (!/^(?:[A-Z][a-z]{2}, |[A-Z][a-z]+, |[A-Z][a-z]{2} [A-Z][a-z]{2} )/.test(text)) return null;
  const deadline = Date.parse(text);
  return Number.isFinite(deadline) ? Math.max(now, deadline) : null;
}

function classifyChatFailure(input = {}) {
  const hasStatus = input.status !== null && input.status !== undefined && input.status !== "";
  const status = hasStatus && Number.isInteger(Number(input.status)) ? Number(input.status) : null;
  const bodyCode = boundedFailureCode(input);
  if (input.error?.name === "TypeError") {
    return chatFailureMeta("network_error", true, null, null, "check_connection");
  }
  const streamCode = CHAT_FAILURE_STREAM_CODES.has(input.streamCode) ? input.streamCode : null;
  const code = streamCode || bodyCode;
  if (code === "auth_required" || (status === 401 && !code?.startsWith("provider_"))) {
    return chatFailureMeta("login_required", false, status, code, "sign_in");
  }
  if (code === "quota_exceeded" || code === "session_quota_exceeded") return chatFailureMeta("quota_exceeded", false, status, code, "check_quota");
  if (code === "chat_admission_exceeded") return chatFailureMeta("concurrency_limited", false, status, code, "check_run");
  if (code === "chat_model_budget_exceeded") return chatFailureMeta("model_budget_exceeded", false, status, code, "adjust_model_settings");
  if (code === "chat_retrieval_budget_exceeded" || code === "chat_provider_budget_exceeded") {
    return chatFailureMeta(code === "chat_retrieval_budget_exceeded" ? "retrieval_budget_exceeded" : "provider_budget_exceeded", false, status, code, "adjust_search_settings");
  }
  if (["public_body_executor_saturated", "chat_admission_unavailable", "chat_plan_budget_unavailable"].includes(code)) {
    return chatFailureMeta("service_unavailable", false, status, code, "check_service");
  }
  if (code === "idempotency_duplicate" || code === "idempotency_payload_mismatch") {
    return chatFailureMeta("run_unresolved", false, status, code, "check_run");
  }
  if (code === "rate_limited" || code === "chat_rate_limited") {
    return chatFailureMeta("rate_limited", true, status, code, "wait_then_retry", chatRetryAt(input.retryAfter));
  }
  if (status === 401 && code === "provider_unauthorized") {
    return chatFailureMeta(code, false, status, code, "choose_model");
  }
  if (["backend_unavailable", "provider_not_configured", "provider_unauthorized",
    "local_model_store_unavailable", "local_capacity_exceeded", "gpu_device_lost",
    "model_circuit_open", "model_request_invalid"].includes(code)) {
    return chatFailureMeta(code, false, status, code, "check_service");
  }
  if (["backend_timeout", "public_request_deadline_exhausted", "request_deadline_exhausted"].includes(code)) {
    return chatFailureMeta("timeout", false, status, code, "check_run");
  }
  if (code === "request_cancelled") return chatFailureMeta("cancelled", false, status, code, "check_run");
  if (["model_unavailable", "protocol_unsupported"].includes(code)) {
    return chatFailureMeta("model_unavailable", false, status, code, "choose_model");
  }
  if (code === "csrf_invalid") return chatFailureMeta("access_denied", false, status, code, "refresh_session");
  if (status === 429) return chatFailureMeta("rate_limited", true, status, code, "wait_then_retry", chatRetryAt(input.retryAfter));
  if (streamCode === "backend_timeout") {
    return chatFailureMeta("timeout", true, null, streamCode, "retry");
  }
  if (streamCode === "session_forbidden") {
    return chatFailureMeta("access_denied", false, null, streamCode, "choose_or_start_session");
  }
  const protocolCode = CHAT_FAILURE_PROTOCOL_CODES.has(input.protocolCode) ? input.protocolCode : null;
  if (protocolCode) {
    return chatFailureMeta("stream_failed", false, null, protocolCode, "check_run");
  }
  if (status === 503) {
    return chatFailureMeta(
      "service_unavailable",
      true,
      status,
      bodyCode === "backend_unavailable" ? bodyCode : null,
      "retry"
    );
  }
  if (status === 401 || status === 403) {
    const sessionDenied = bodyCode === "session_forbidden";
    return chatFailureMeta(
      "access_denied",
      false,
      status,
      sessionDenied || bodyCode === "forbidden" ? bodyCode : null,
      sessionDenied ? "choose_or_start_session" : "check_access"
    );
  }
  if (status === 504) {
    return chatFailureMeta(
      "timeout",
      true,
      status,
      bodyCode === "backend_timeout" ? bodyCode : null,
      "retry"
    );
  }
  return chatFailureMeta("stream_failed", false, status, code, "check_run");
}

function chatFailureMessage(meta = {}) {
  if (meta.serverCode === "local_model_store_unavailable") return "로컬 모델 파일을 읽을 수 없습니다. 서비스 상태에서 모델 저장소와 드라이브 접근 상태를 확인해 주세요. 질문은 보관됩니다.";
  if (meta.serverCode === "local_capacity_exceeded") return "로컬 모델 실행에 필요한 메모리가 부족합니다. 실행 중인 작업과 자원 상태를 확인해 주세요.";
  if (meta.serverCode === "gpu_device_lost") return "로컬 GPU에 접근할 수 없습니다. 서비스와 장치 상태를 확인해 주세요.";
  if (["provider_unauthorized", "provider_not_configured"].includes(meta.serverCode)) return "모델 공급자의 연결 및 인증 설정을 확인해 주세요. 질문은 보관됩니다.";
  if (meta.serverCode === "model_circuit_open") return "앞선 실패로 모델 호출이 잠시 차단되어 있습니다. 서비스 상태와 기존 실행을 확인해 주세요.";
  if (meta.serverCode === "model_request_invalid") return "모델이 요청 설정을 받아들이지 않았습니다. 서비스 상태에서 요청 형식과 지원 옵션을 확인해 주세요.";
  if (meta.serverCode === "backend_unavailable") return "모델 서비스에 연결할 수 없거나 실행이 실패했습니다. 서비스 상태를 확인해 주세요. 질문은 보관됩니다.";
  if (meta.failureKind === "cancelled") return "요청이 취소되었습니다. 수신한 내용과 질문은 유지됩니다.";
  if (meta.nextAction === "sign_in") return "로그인이 필요합니다. 작성한 질문은 보관됩니다.";
  if (meta.nextAction === "choose_or_start_session") return "이 대화에 접근할 수 없습니다. 내 대화를 선택하거나 새 대화를 시작해 주세요.";
  if (meta.nextAction === "refresh_session") return "보안 세션을 새로 확인해야 합니다. 작성한 질문을 보관한 뒤 새로고침해 주세요.";
  if (meta.nextAction === "check_access") return "접근 권한을 확인해 주세요. 모델을 변경해도 해결되지 않는 요청입니다.";
  if (meta.failureKind === "rate_limited") return "일정 시간의 요청 한도에 도달했습니다. 안내된 대기 후 다시 시도해 주세요.";
  if (meta.failureKind === "concurrency_limited") return "동시에 실행 중인 요청이 많습니다. 기존 실행 상태를 확인해 주세요. 질문은 보관됩니다.";
  if (meta.failureKind === "model_budget_exceeded") return "모델 문맥 또는 출력 토큰 예산을 초과했습니다. 모델 설정과 질문 길이를 확인해 주세요.";
  if (meta.failureKind === "retrieval_budget_exceeded" || meta.failureKind === "provider_budget_exceeded") return "검색 또는 외부 호출 예상 예산을 초과했습니다. 검색 설정을 확인해 주세요. 운영자 설정의 검색 깊이·확장 수·topK도 확인이 필요할 수 있습니다.";
  if (meta.failureKind === "admission_unknown") return "요청이 거절됐지만 제한 원인이 확인되지 않았습니다. 질문을 보관하고 서비스 상태를 확인해 주세요.";
  if (meta.failureKind === "run_unresolved") return "이미 접수된 요청의 완료 여부를 확인해야 합니다. 기존 실행 상태를 확인해 주세요.";
  if (meta.failureKind === "quota_exceeded") return "사용 한도에 도달했습니다. 운영자에게 한도 설정을 확인해 주세요.";
  if (meta.failureKind === "model_unavailable") return "선택한 모델의 연결 또는 지원 상태를 확인해 주세요. 다른 사용 가능한 모델을 선택할 수 있습니다.";
  if (meta.failureKind === "service_unavailable") return "서버가 일시적으로 응답할 수 없습니다. 질문은 입력창에 유지됩니다.";
  if (meta.failureKind === "timeout") return "응답 대기 시간이 초과되었습니다. 질문과 수신한 내용은 유지됩니다.";
  if (meta.failureKind === "network_error") return "연결이 끊겼습니다. 수신한 답변은 유지됩니다. 연결 상태를 확인해 주세요.";
  return "응답이 완료되지 않았습니다. 수신한 내용과 질문은 유지됩니다. 대화 기록에서 완료 여부를 확인해 주세요.";
}

function preserveComposerDraft() {
  try { sessionStorage.setItem("chat.recoveryDraft", dom.messageInput?.value || ""); } catch { /* storage can be disabled */ }
}

function renderChatFailureNotice(assistant, meta, recovery = null) {
  const partial = assistant?.dataset?.pendingPlaceholder !== "client-wait" && Boolean(assistant?.dataset?.ariaText?.trim());
  if (!partial) setMessageContent(assistant, "assistant", chatFailureMessage(meta), "error");
  const notice = document.createElement("div");
  notice.className = "chat-failure-notice";
  notice.setAttribute("role", "status");
  if (partial) {
    const message = document.createElement("p");
    message.textContent = chatFailureMessage(meta);
    notice.appendChild(message);
  }
  const action = document.createElement(meta.nextAction === "sign_in" ? "a" : "button");
  action.className = "ghost";
  if (meta.nextAction === "sign_in") {
    action.href = "/login";
    action.textContent = "로그인";
    action.addEventListener("click", preserveComposerDraft);
  } else {
    action.type = "button";
    const newSession = meta.nextAction === "choose_or_start_session";
    const chooseModel = meta.nextAction === "choose_model" || meta.nextAction === "adjust_model_settings";
    const chooseSearch = meta.nextAction === "adjust_search_settings";
    const checkService = meta.nextAction === "check_service" || meta.nextAction === "check_quota";
    // Only this pre-generation admission rejection certifies that a new run is safe.
    const retry = meta.serverCode === "chat_rate_limited" && meta.status === 429 && recovery;
    action.textContent = newSession ? "새 대화" : chooseModel ? "모델 설정 확인" : chooseSearch ? "검색 설정 확인"
      : checkService ? (dom.debugHeartbeatBar ? "서비스 상태 확인" : "서버 연결 확인") : retry ? "다시 시도" : meta.nextAction === "refresh_session" ? "새로고침" : "기존 실행 확인";
    if (retry && Number.isFinite(meta.retryAt)) {
      const updateWait = () => {
        const seconds = Math.max(0, Math.ceil((meta.retryAt - Date.now()) / 1000));
        action.disabled = seconds > 0;
        action.textContent = seconds > 0 ? `${seconds}초 후 다시 시도` : "다시 시도";
        if (seconds > 0 && action.isConnected) window.setTimeout(updateWait, Math.min(1000, meta.retryAt - Date.now()));
      };
      window.setTimeout(updateWait, 0);
    }
    action.addEventListener("click", async () => {
      if (newSession) {
        const draft = dom.messageInput?.value || "";
        startNewChatSession();
        if (dom.messageInput) dom.messageInput.value = draft;
        syncSendButtonState();
      } else if (chooseModel || chooseSearch) {
        const control = chooseSearch ? dom.searchModeSelect : dom.modelSelect;
        const settings = control?.closest?.("details");
        if (settings) settings.open = true;
        if (chooseModel && document.getElementById("modelBrowserTrigger")) document.getElementById("modelBrowserTrigger").click();
        else control?.focus();
      } else if (retry) {
        if (Date.now() < (meta.retryAt || 0) || action.disabled || sendMessageInFlight || restoredRunResumeInFlight) return;
        if (!chatModelCatalogReady || chatAccessState === "login_required" || chatAccessState === "forbidden") {
          dom.messageInput?.focus();
          return;
        }
        if (state.currentSessionId !== recovery.sessionId || dom.messageInput?.value !== recovery.draftText) {
          dom.messageInput?.focus();
          return;
        }
        action.disabled = true;
        await sendMessage();
      } else if (meta.nextAction === "refresh_session") {
        preserveComposerDraft();
        window.location.reload();
      } else if (checkService) {
        const diagnostics = dom.diagnosticsSummaryControl?.closest?.("details");
        if (diagnostics) diagnostics.open = true;
        if (dom.debugHeartbeatBar) {
          await refreshDebugHeartbeat();
        } else {
          const result = document.createElement("p");
          result.textContent = "서버 연결을 확인하고 있습니다.";
          notice.appendChild(result);
          action.disabled = true;
          const controller = new AbortController();
          const timeout = window.setTimeout(() => controller.abort(), 5000);
          try {
            const response = await fetch(CHAT_UI_HEARTBEAT_API, { method: "HEAD", cache: "no-store", signal: controller.signal });
            result.textContent = response.ok
              ? "서버 연결 응답을 확인했습니다. 생성 한도와 실행 상태는 운영자 확인이 필요합니다. 질문은 보관됩니다."
              : response.status === 401 || response.status === 403
                ? "서버 진단은 운영자 권한이 필요합니다. 질문은 보관됩니다."
                : "서버 상태를 확인하지 못했습니다. 질문을 보관하고 잠시 후 연결을 확인해 주세요.";
          } catch {
            result.textContent = "서버에 연결하지 못했습니다. 질문은 입력창에 보관됩니다.";
          } finally {
            window.clearTimeout(timeout);
            action.disabled = false;
          }
        }
      } else {
        const current = strictBackendSessionId(state.currentSessionId);
        if (current !== null && !sendMessageInFlight && !restoredRunResumeInFlight) await selectSessionCandidate(current);
        else await refreshSessionList("failure-review");
      }
    });
  }
  notice.appendChild(action);
  assistant?.appendChild(notice);
  if (meta.failureKind === "login_required") chatAccessState = "login_required";
}

function chatFailureError(meta) {
  const error = new Error("chat_failure");
  error.chatFailure = meta;
  return error;
}

function applyChatFailureState(assistant, meta) {
  if (!assistant || !meta) return;
  assistant.dataset.failureKind = meta.failureKind;
  assistant.dataset.failureRetryable = String(meta.retryable === true);
  if (Number.isInteger(meta.status)) assistant.dataset.failureStatus = String(meta.status);
  else delete assistant.dataset.failureStatus;
  if (meta.serverCode) assistant.dataset.failureCode = meta.serverCode;
  else delete assistant.dataset.failureCode;
  assistant.dataset.failureNextAction = meta.nextAction;
  assistant.setAttribute(
    "aria-label",
    chatFailureMessage(meta)
  );
}

function safeUnknownEventName(value) {
  const text = String(value || "");
  return SAFE_STREAM_EVENT_NAME.test(text) ? text : "unknown";
}

function sseFieldValue(line, field) {
  let value = line.slice(field.length + 1);
  if (value.startsWith(" ")) value = value.slice(1);
  return value;
}

function createSseEventParser({
  maxEventUtf8Bytes = MAX_SSE_EVENT_UTF8_BYTES,
  onEvent = null
} = {}) {
  const decoder = new TextDecoder();
  const byteCounter = new TextEncoder();
  const dataFieldPrefix = "data:";
  const byteLimit = Number.isFinite(Number(maxEventUtf8Bytes))
    ? Math.max(0, Math.floor(Number(maxEventUtf8Bytes)))
    : MAX_SSE_EVENT_UTF8_BYTES;
  let lineBuffer = "";
  let pendingCr = false;
  let eventType = "message";
  let dataLines = [];
  let eventUtf8Bytes = 0;
  let currentLineKind = "candidate";
  let currentDataValueStarted = false;
  let currentDataValueUtf8Bytes = 0;
  let stopped = false;

  function dispatch(out) {
    let event = null;
    if (dataLines.length > 0) {
      event = { type: eventType || "message", data: dataLines.join("\n") };
      out.push(event);
    }
    eventType = "message";
    dataLines = [];
    eventUtf8Bytes = 0;
    if (event && typeof onEvent === "function" && onEvent(event) === false) stopped = true;
  }

  function consumeLine(line, out) {
    if (line === "") {
      dispatch(out);
      return;
    }
    if (line.startsWith(":")) return;
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? "" : sseFieldValue(line, field);
    if (field === "event") {
      eventType = value;
      return;
    }
    if (field !== "data") return;
    const separatorBytes = dataLines.length > 0 ? 1 : 0;
    const nextBytes = eventUtf8Bytes + separatorBytes + byteCounter.encode(value).byteLength;
    if (nextBytes > byteLimit) throw streamProtocolError("sse_event_too_large");
    eventUtf8Bytes = nextBytes;
    dataLines.push(value);
  }

  function resetCurrentLineTracking() {
    currentLineKind = "candidate";
    currentDataValueStarted = false;
    currentDataValueUtf8Bytes = 0;
  }

  function appendLineCharacter(current) {
    if (currentLineKind === "candidate") {
      const expected = dataFieldPrefix[lineBuffer.length];
      currentLineKind = current === expected
        ? (lineBuffer.length + 1 === dataFieldPrefix.length ? "data" : "candidate")
        : "other";
      lineBuffer += current;
      return;
    }
    if (currentLineKind !== "data") {
      lineBuffer += current;
      return;
    }
    if (!currentDataValueStarted) {
      currentDataValueStarted = true;
      if (current === " ") {
        lineBuffer += current;
        return;
      }
    }
    const nextBytes = eventUtf8Bytes +
      (dataLines.length > 0 ? 1 : 0) +
      currentDataValueUtf8Bytes +
      byteCounter.encode(current).byteLength;
    if (nextBytes > byteLimit) throw streamProtocolError("sse_event_too_large");
    currentDataValueUtf8Bytes = nextBytes - eventUtf8Bytes - (dataLines.length > 0 ? 1 : 0);
    lineBuffer += current;
  }

  function consumeText(text, out) {
    if (stopped) return;
    for (const current of text) {
      if (pendingCr) {
        pendingCr = false;
        if (current === "\n") continue;
      }
      if (current === "\r") {
        consumeLine(lineBuffer, out);
        lineBuffer = "";
        resetCurrentLineTracking();
        pendingCr = true;
      } else if (current === "\n") {
        consumeLine(lineBuffer, out);
        lineBuffer = "";
        resetCurrentLineTracking();
      } else {
        appendLineCharacter(current);
      }
      if (stopped) break;
    }
  }

  return {
    push(chunk) {
      if (stopped) return [];
      const out = [];
      consumeText(decoder.decode(chunk, { stream: true }), out);
      return out;
    },
    finish() {
      if (stopped) return [];
      const out = [];
      consumeText(decoder.decode(), out);
      lineBuffer = "";
      pendingCr = false;
      eventType = "message";
      dataLines = [];
      eventUtf8Bytes = 0;
      resetCurrentLineTracking();
      return out;
    }
  };
}

function decodeSseEvent(event) {
  const eventType = event?.type || "message";
  try {
    const payload = JSON.parse(event?.data || "");
    return {
      effectiveType: payload?.type || eventType,
      payload,
      malformedTerminal: false
    };
  } catch {
    const terminal = eventType === "final" || eventType === "error" || eventType === "stream_failed";
    return {
      effectiveType: terminal ? "stream_failed" : eventType,
      payload: terminal
        ? { type: "stream_failed", code: "malformed_terminal_event" }
        : { type: eventType, data: event?.data || "" },
      malformedTerminal: terminal
    };
  }
}

function streamClientDeadlineError(elapsedMs) {
  const error = new Error("stream_client_deadline");
  error.name = "StreamClientDeadlineError";
  error.elapsedMs = Math.max(0, Math.round(Number(elapsedMs) || 0));
  return error;
}

function normalizeSessionIdValue(raw) {
  const numeric = Number(raw);
  return Number.isSafeInteger(numeric) && numeric > 0 ? numeric : null;
}

function strictBackendSessionId(value) {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0
    ? value
    : null;
}

function invalidatePendingSessionSelectionForTranscriptOwnership() {
  sessionSelectionGeneration += 1;
}

function normalizeRunToken(raw) {
  const token = String(raw ?? "").trim();
  if (!token || token.length > 128) return null;
  return /^[A-Za-z0-9._:-]+$/.test(token) ? token : null;
}

function runTokenFromPayload(payload) {
  return normalizeRunToken(payload?.runToken ?? payload?.run_token ?? payload?.data);
}

function rememberActiveRunIdentity(sessionId, runToken) {
  const sid = normalizeSessionIdValue(sessionId);
  const token = normalizeRunToken(runToken);
  if (!sid || !token) return false;
  const changed = activeSessionId !== sid || activeRunToken !== token;
  activeSessionId = sid;
  activeRunToken = token;
  if (changed) {
    activeRunGeneration += 1;
    invalidatePendingSessionSelectionForTranscriptOwnership();
  }
  try {
    window.sessionStorage?.setItem(ACTIVE_RUN_STORAGE_KEY, JSON.stringify({ sessionId: sid, runToken: token }));
  } catch {
    // storage is optional for reload continuity
  }
  syncSessionSelectionCapability();
  return true;
}

function restoreActiveRunIdentity() {
  try {
    const parsed = JSON.parse(window.sessionStorage?.getItem(ACTIVE_RUN_STORAGE_KEY) || "null");
    return rememberActiveRunIdentity(parsed?.sessionId, parsed?.runToken)
      ? { sessionId: activeSessionId, runToken: activeRunToken }
      : null;
  } catch {
    return null;
  }
}

function clearActiveRunIdentity() {
  const changed = activeSessionId != null || activeRunToken != null;
  activeSessionId = null;
  activeRunToken = null;
  if (changed) activeRunGeneration += 1;
  try {
    window.sessionStorage?.removeItem(ACTIVE_RUN_STORAGE_KEY);
  } catch {
    // storage is optional for reload continuity
  }
  syncSessionSelectionCapability();
}

function activeRunIdentitySnapshot() {
  const sessionId = normalizeSessionIdValue(activeSessionId);
  const runToken = normalizeRunToken(activeRunToken);
  return sessionId && runToken
    ? { sessionId, runToken, generation: activeRunGeneration }
    : null;
}

function sameActiveRunIdentity(expected) {
  return Boolean(expected) &&
    expected.generation === activeRunGeneration &&
    expected.sessionId === normalizeSessionIdValue(activeSessionId) &&
    expected.runToken === normalizeRunToken(activeRunToken);
}

function clearActiveRunIdentityIfMatch(expected) {
  if (!sameActiveRunIdentity(expected)) return false;
  clearActiveRunIdentity();
  return true;
}

function rememberCurrentSessionId(sessionId) {
  const sid = normalizeSessionIdValue(sessionId);
  if (!sid) return null;
  state.currentSessionId = sid;
  try {
    window.sessionStorage?.setItem(CURRENT_SESSION_STORAGE_KEY, String(sid));
  } catch {
    // storage is optional for reload continuity
  }
  return sid;
}

function restoreCurrentSessionId() {
  try {
    return rememberCurrentSessionId(window.sessionStorage?.getItem(CURRENT_SESSION_STORAGE_KEY));
  } catch {
    return null;
  }
}

function forgetCurrentSessionId(sessionId = null) {
  const expectedSid = normalizeSessionIdValue(sessionId);
  const currentSid = sessionIdFromPayload({ sessionId: state.currentSessionId });
  let storedSid = null;
  try {
    storedSid = normalizeSessionIdValue(window.sessionStorage?.getItem(CURRENT_SESSION_STORAGE_KEY));
  } catch {
    storedSid = null;
  }
  if (expectedSid && currentSid && currentSid !== expectedSid) return false;
  if (expectedSid && storedSid && storedSid !== expectedSid) return false;
  state.currentSessionId = null;
  try {
    if (!expectedSid || !storedSid || storedSid === expectedSid) {
      window.sessionStorage?.removeItem(CURRENT_SESSION_STORAGE_KEY);
    }
  } catch {
    // storage is optional for reload continuity
  }
  syncSelectedSessionRow(null);
  return true;
}

function clearSessionModeDiagnostics(sessionId = null) {
  const list = document.querySelector("[data-session-mode-list]");
  if (!list) return false;
  const sid = normalizeSessionIdValue(sessionId);
  if (sid) {
    const rows = Array.from(list.querySelectorAll("[data-session-mode-row]"))
      .filter((row) => row.dataset.sessionModeSessionId === String(sid));
    rows.forEach((row) => row.remove());
    return rows.length > 0;
  }
  const rows = Array.from(list.querySelectorAll("[data-session-mode-row]"));
  rows.forEach((row) => row.remove());
  return rows.length > 0;
}

function sessionListContainer() {
  return document.querySelector("[data-session-mode-list]");
}

function removeSessionListOwnedRows(list) {
  if (!list) return;
  ["[data-session-list-row]", "[data-session-list-state]", "[data-session-selection-state]"].forEach((selector) => {
    Array.from(list.querySelectorAll(selector)).forEach((row) => row.remove());
  });
}

function fixedSessionListStateText(state) {
  if (state === "loading") return "대화 기록을 불러오는 중입니다.";
  if (state === "empty") return "첫 대화를 시작해 보세요.";
  if (state === "session_list_login_required") return "대화 기록을 보려면 로그인이 필요합니다.";
  if (state === "session_list_invalid_response") return "Session history response is unavailable.";
  if (state === "session_list_forbidden") return "Session history is unavailable.";
  return "Session history is temporarily unavailable.";
}

function syncSelectedSessionRow(sessionId = state.currentSessionId) {
  const list = sessionListContainer();
  if (!list) return;
  const selectedId = strictBackendSessionId(sessionId);
  Array.from(list.querySelectorAll("[data-session-list-row]")).forEach((row) => {
    const selected = selectedId !== null && row.dataset.sessionId === String(selectedId);
    row.dataset.sessionSelected = selected ? "true" : "false";
    row.setAttribute("aria-pressed", selected ? "true" : "false");
  });
}

function sessionSelectionBusy() {
  return Boolean(activeRunIdentitySnapshot() || activeStreamAssistant || pendingStopBeforeToken?.unresolved);
}

function syncSessionSelectionCapability() {
  const list = sessionListContainer();
  if (!list) return false;
  const disabled = sessionSelectionBusy();
  Array.from(list.querySelectorAll("[data-session-list-row]")).forEach((row) => {
    row.disabled = disabled;
    row.setAttribute("aria-disabled", disabled ? "true" : "false");
  });
  return disabled;
}

function renderSessionList({ rows = [], state: requestedState = "ready", retryable = false } = {}) {
  const list = sessionListContainer();
  if (!list) return [];
  removeSessionListOwnedRows(list);

  const seenSessionIds = new Set();
  const validRows = (Array.isArray(rows) ? rows : [])
    .map((row) => ({ row, id: strictBackendSessionId(row?.id) }))
    .filter((entry) => {
      if (entry.id === null || seenSessionIds.has(entry.id)) return false;
      seenSessionIds.add(entry.id);
      return true;
    })
    .slice(0, SESSION_LIST_LIMIT);
  const stateName = requestedState === "ready" && validRows.length === 0 ? "empty" : String(requestedState || "ready");
  list.dataset.sessionListState = stateName;
  list.dataset.sessionListRetryable = retryable === true ? "true" : "false";

  if (stateName !== "ready") {
    const stateRow = document.createElement("div");
    stateRow.dataset.sessionListState = "true";
    stateRow.dataset.sessionListStateCode = stateName;
    stateRow.textContent = fixedSessionListStateText(stateName);
    stateRow.setAttribute("aria-label", stateRow.textContent);
    list.appendChild(stateRow);
    syncSessionSelectionCapability();
    return [];
  }

  const rendered = validRows.map(({ row, id }) => {
    const button = document.createElement("button");
    const title = safeDebugCockpitDetail(row?.title || `Session ${id}`);
    const mode = safeDebugCockpitDetail(answerModeRailText(row?.answerMode) || row?.answerMode || "");
    const trace = row?.lastTraceTurnId == null
      ? ""
      : safeDebugCockpitDetail(traceRailLabel(row.lastTraceTurnId));
    button.type = "button";
    button.setAttribute("type", "button");
    button.dataset.sessionListRow = "true";
    button.dataset.sessionId = String(id);
    button.dataset.sessionAnswerMode = mode;
    button.dataset.sessionTraceTurnId = trace;
    button.textContent = title;
    button.setAttribute("aria-label", button.textContent);
    button.addEventListener("click", () => {
      void selectSessionCandidate(id);
    });
    list.appendChild(button);
    return button;
  });
  syncSelectedSessionRow();
  syncSessionSelectionCapability();
  return rendered;
}

function setSessionSelectionNotice(message = "") {
  const list = sessionListContainer();
  if (!list) return;
  Array.from(list.querySelectorAll("[data-session-selection-state]")).forEach((row) => row.remove());
  if (!message) return;
  const notice = document.createElement("div");
  notice.dataset.sessionSelectionState = "true";
  notice.setAttribute("role", "status");
  notice.textContent = message;
  list.appendChild(notice);
}

function refreshSessionList(reason = "manual") {
  if (sessionListRefreshInFlight) {
    if (reason === "session" || reason === "final") {
      // A response captured before this mutation cannot satisfy its refresh.
      sessionListRefreshPendingReason = reason;
      sessionListRefreshGeneration += 1;
    }
    return sessionListRefreshInFlight;
  }
  const generation = ++sessionListRefreshGeneration;
  renderSessionList({ state: "loading", retryable: false });
  const currentSessionId = strictBackendSessionId(state.currentSessionId);
  const refreshTask = (async () => {
    try {
      const response = await apiCall("/api/chat/sessions", {
        method: "GET",
        cache: "no-store",
        headers: withChatCorrelationHeaders({}, { sessionId: currentSessionId || 0 })
      });
      let payload;
      try {
        payload = await response.json();
      } catch {
        if (generation === sessionListRefreshGeneration) {
          renderSessionList({ state: "session_list_invalid_response", retryable: true });
        }
        return false;
      }
      if (generation !== sessionListRefreshGeneration) return false;
      if (!Array.isArray(payload)) {
        renderSessionList({ state: "session_list_invalid_response", retryable: true });
        return false;
      }
      renderSessionList({ rows: payload, state: "ready", retryable: false });
      chatAccessState = "ready";
      syncSendButtonState();
      return true;
    } catch (error) {
      if (generation !== sessionListRefreshGeneration) return false;
      if (error?.status === 401) {
        chatAccessState = "login_required";
        renderSessionList({ state: "session_list_login_required", retryable: false });
      } else if (error?.status === 403) {
        chatAccessState = "forbidden";
        renderSessionList({ state: "session_list_forbidden", retryable: false });
      } else if (error?.status === 503) {
        renderSessionList({ state: "session_list_unavailable", retryable: true });
      } else {
        renderSessionList({ state: "session_list_network_error", retryable: true });
      }
      syncSendButtonState();
      return false;
    } finally {
      if (sessionListRefreshInFlight === refreshTask) {
        sessionListRefreshInFlight = null;
        const pendingReason = sessionListRefreshPendingReason;
        sessionListRefreshPendingReason = null;
        if (pendingReason) return refreshSessionList(pendingReason);
      }
    }
  })();
  sessionListRefreshInFlight = refreshTask;
  return refreshTask;
}

const TURN_TRACE_SNAPSHOT_ID = /^[A-Za-z0-9_.:-]{1,160}$/;
const TURN_TRACE_FIELD_KEY = /^[A-Za-z0-9_.:-]{1,80}$/;
const TURN_TRACE_FIELD_VALUE = /^[\x20-\x7E]{0,160}$/;
const TURN_TRACE_MAX_ENTRIES = 64;
const TURN_TRACE_MAX_FIELDS = 20; // Existing 16 fields plus four observed overview scalars.

function normalizeMessageTurnId(raw) {
  const numeric = Number(raw);
  return Number.isSafeInteger(numeric) && numeric > 0 ? numeric : null;
}

function validateTurnTraces(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const entry of raw) {
    if (!entry || typeof entry !== "object") continue;
    const turnId = normalizeMessageTurnId(entry.turnId);
    const snapshotId = typeof entry.snapshotId === "string" ? entry.snapshotId : "";
    if (turnId === null || !TURN_TRACE_SNAPSHOT_ID.test(snapshotId)) continue;
    const src = entry.fields && typeof entry.fields === "object" && !Array.isArray(entry.fields)
      ? entry.fields : {};
    const keys = Object.keys(src);
    if (keys.length > TURN_TRACE_MAX_FIELDS) continue;
    const fields = {};
    let invalid = false;
    for (const key of keys) {
      const value = src[key];
      const safeValue = key === 'observedModel'
        ? typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._/:+\-]{0,199}$/.test(value) && !/^(sk-|AIza|eyJ)/.test(value)
        : typeof value === 'string' && TURN_TRACE_FIELD_VALUE.test(value);
      if (!TURN_TRACE_FIELD_KEY.test(key) || !safeValue) {
        invalid = true;
        break;
      }
      fields[key] = value;
    }
    if (invalid) continue;
    out.push({ turnId, snapshotId, fields });
    if (out.length >= TURN_TRACE_MAX_ENTRIES) break;
  }
  return out;
}

function turnTracesByTurnId(turnTraces) {
  const map = new Map();
  for (const trace of Array.isArray(turnTraces) ? turnTraces : []) {
    if (trace && normalizeMessageTurnId(trace.turnId) !== null) map.set(trace.turnId, trace);
  }
  return map;
}

function renderRestoredTurnTrace(bubble, trace) {
  if (!bubble || !trace) return null;
  const snapshotId = typeof trace.snapshotId === "string" ? trace.snapshotId : "";
  if (!TURN_TRACE_SNAPSHOT_ID.test(snapshotId)) return null;
  return window.AwxChatTraceUi?.restore(bubble, trace) || null;
}

function chatTraceRequestUrl(url) {
  return window.AwxChatTraceUi?.withDebugQuery(url) || url;
}

function validateSessionDetail(candidateId, detail) {
  const expectedId = strictBackendSessionId(candidateId);
  const detailId = strictBackendSessionId(detail?.id);
  if (expectedId === null || detailId !== expectedId || detail?.found === false) return null;
  if (!Array.isArray(detail?.messages)) return null;
  if (Object.prototype.hasOwnProperty.call(detail, "settings") &&
      (detail.settings === null || typeof detail.settings !== "object" || Array.isArray(detail.settings))) {
    return null;
  }
  const messages = detail.messages
    .filter((message) => {
      const role = String(message?.role || "").toLowerCase();
      return (role === "user" || role === "assistant") && typeof message?.content === "string";
    })
    .map((message) => ({
      role: String(message.role).toLowerCase(),
      content: message.content,
      turnId: normalizeMessageTurnId(message.turnId ?? message.id),
      executionMode: String(message.role).toLowerCase() === "assistant" ? message.executionMode : undefined,
      evidence: String(message.role).toLowerCase() === "assistant" && Array.isArray(message.evidence)
        ? message.evidence : undefined
    }));
  const turnTraces = validateTurnTraces(detail.turnTraces);
  return { ...detail, id: expectedId, messages, turnTraces, settings: detail.settings || {} };
}

function sessionListRowMetadata(candidateId) {
  const id = strictBackendSessionId(candidateId);
  if (id === null) return {};
  const row = Array.from(sessionListContainer()?.querySelectorAll("[data-session-list-row]") || [])
    .find((candidate) => candidate.dataset.sessionId === String(id));
  return row ? {
    answerMode: row.dataset.sessionAnswerMode || null,
    traceTurnId: row.dataset.sessionTraceTurnId || null
  } : {};
}

let visibleTraceRefreshGeneration = 0;

async function refreshVisibleTurnTraces() {
  const refreshGeneration = ++visibleTraceRefreshGeneration;
  const hydrationGeneration = restoredSessionHydrationGeneration;
  const sid = normalizeSessionIdValue(state.currentSessionId) || restoreCurrentSessionId();
  if (!sid || !window.AwxChatTraceUi?.enabled() || !dom.chatMessages) return;
  try {
    const response = await apiCall(chatTraceRequestUrl(`/api/chat/sessions/${sid}?restoreProbe=true`));
    const detail = await response.json();
    if (refreshGeneration !== visibleTraceRefreshGeneration ||
        hydrationGeneration !== restoredSessionHydrationGeneration ||
        normalizeSessionIdValue(state.currentSessionId) !== sid ||
        !window.AwxChatTraceUi?.enabled()) return;
    const validated = validateSessionDetail(sid, detail);
    if (!validated) return;
    const byTurn = turnTracesByTurnId(validated.turnTraces);
    for (const bubble of dom.chatMessages.querySelectorAll(".message.assistant[data-turn-id]")) {
      const turnTrace = byTurn.get(normalizeMessageTurnId(bubble.dataset.turnId));
      if (turnTrace && bubble.isConnected) renderRestoredTurnTrace(bubble, turnTrace);
    }
  } catch (_) {
    // Trace restoration is diagnostic only; the transcript remains available.
  }
}

document.addEventListener("change", event => {
  if (!event.target?.matches?.("[data-chat-trace-toggle]")) return;
  if (window.AwxChatTraceUi?.enabled()) void refreshVisibleTurnTraces();
  else visibleTraceRefreshGeneration += 1;
});

async function selectSessionCandidate(candidateId) {
  const id = strictBackendSessionId(candidateId);
  if (id === null || sessionSelectionBusy()) return false;
  const generation = ++sessionSelectionGeneration;
  const selectionSnapshot = {
    currentSessionId: normalizeSessionIdValue(state.currentSessionId),
    hydrationGeneration: restoredSessionHydrationGeneration
  };
  const listMetadata = sessionListRowMetadata(id);
  setSessionSelectionNotice();
  try {
    const response = await apiCall(chatTraceRequestUrl(`/api/chat/sessions/${id}`), {
      method: "GET",
      headers: withChatCorrelationHeaders({}, { sessionId: id })
    });
    const detail = await response.json();
    const validated = validateSessionDetail(id, detail);
    if (generation !== sessionSelectionGeneration || sessionSelectionBusy()) return false;
    if (selectionSnapshot.currentSessionId !== normalizeSessionIdValue(state.currentSessionId) ||
        selectionSnapshot.hydrationGeneration !== restoredSessionHydrationGeneration) return false;
    if (!validated) {
      setSessionSelectionNotice("대화 내용을 불러오지 못했습니다. 목록에서 다시 선택해 주세요.");
      return false;
    }

    sessionListRefreshGeneration += 1;
    restoredSessionHydrationGeneration += 1;
    restoredSessionHydrated = true;
    localControlOverrideActive = false;
    if (strictBackendSessionId(state.currentSessionId) !== id && dom.googleSearchRescue) {
      delete dom.googleSearchRescue.dataset.rescueExplicit;
      dom.googleSearchRescue.checked = dom.googleSearchRescue.dataset.rescueInherited === "true";
    }
    clearActiveRunIdentity();
    rememberCurrentSessionId(id);
    clearSessionModeDiagnostics();
    if (dom.chatMessages) {
      clearSelectionEntropyTrace(dom.chatMessages);
      window.AwxChatTraceUi?.dispose(dom.chatMessages);
      if (typeof dom.chatMessages.replaceChildren === "function") dom.chatMessages.replaceChildren();
      else dom.chatMessages.textContent = "";
    }
    const restoredTurnTraces = turnTracesByTurnId(validated.turnTraces);
    validated.messages.forEach((message) => {
      const node = appendMessage(message.role, message.content);
      if (message.turnId != null) node.dataset.turnId = String(message.turnId);
      if (message.role === "assistant") {
        renderExecutionModeReceipt({ executionMode: message.executionMode }, node);
        node.dataset.sessionId = String(id);
        if (Array.isArray(message.evidence) && message.evidence.length) {
          renderEvidenceRail(message.evidence, node, { answerText: message.content });
        }
        try {
          window.ChatEvidenceGraph?.restore(node, { sessionId: id, turnId: message.turnId,
            evidence: message.evidence, answerText: message.content });
        } catch { /* Optional answer view. */ }
      }
      const turnTrace = restoredTurnTraces.get(message.turnId);
      if (turnTrace) renderRestoredTurnTrace(node, turnTrace);
    });
    applyRestoredSessionSettings(validated, { source: "session-list", persist: false });
    restoreSessionModeBadge(id, { ...validated, ...listMetadata });
    syncSelectedSessionRow(id);
    syncSessionSelectionCapability();
    dispatchBrainStateSignal("session", { sessionId: id });
    return true;
  } catch (error) {
    if (generation !== sessionSelectionGeneration || sessionSelectionBusy() ||
        selectionSnapshot.currentSessionId !== normalizeSessionIdValue(state.currentSessionId) ||
        selectionSnapshot.hydrationGeneration !== restoredSessionHydrationGeneration) return false;
    const message = error?.status === 401
      ? "대화를 불러오려면 로그인이 필요합니다."
      : error?.status === 403
        ? "이 기기 또는 계정에서 접근할 수 없는 대화입니다."
        : error?.status === 404
          ? "이 대화를 찾을 수 없습니다. 목록을 새로고침해 주세요."
          : "대화를 불러오지 못했습니다. 잠시 후 다시 선택해 주세요.";
    setSessionSelectionNotice(message);
    return false;
  }
}

function currentControlSettings(source = "") {
  const settings = {
    model: dom.modelSelect?.value || "",
    modelSelectionMode: dom.modelSelectionMode?.value || "strict",
    executionMode: dom.executionMode?.value || "AUTO",
    searchMode: dom.searchModeSelect?.value || "AUTO",
    useRag: dom.useRag?.checked !== false
  };
  if (source) settings.source = source;
  const rescue = dom.googleSearchRescue;
  if (rescue?.dataset.rescueExplicit === "true") {
    settings.googleSearchRescueEnabled = rescue.checked === true;
    if (rescue.dataset.rescueOwnerScopeId) settings.googleSearchRescueOwnerScopeId = rescue.dataset.rescueOwnerScopeId;
    settings.googleSearchRescueSessionId = window.sessionStorage?.getItem(CURRENT_SESSION_STORAGE_KEY) || null;
  }
  return settings;
}

function persistControlSettings(source = "") {
  try {
    window.sessionStorage?.setItem(CONTROL_SETTINGS_STORAGE_KEY, JSON.stringify(currentControlSettings(source)));
  } catch {
    // storage is optional for reload continuity
  }
}

function storedControlSettings() {
  try {
    const raw = window.sessionStorage?.getItem(CONTROL_SETTINGS_STORAGE_KEY);
    if (!raw) return null;
    const settings = JSON.parse(raw);
    return settings && typeof settings === "object" ? settings : null;
  } catch {
    return null;
  }
}

function restoreStoredControlSettings() {
  const settings = storedControlSettings();
  if (!settings) return false;
  localControlOverrideActive = settings.source === "user";
  const model = String(restoredSessionSetting(settings, "model", "modelId") ?? "").trim();
  if (model && dom.modelSelect
      && !Array.from(dom.modelSelect.options || []).some((option) => option.value === model)) {
    // Preserve the selected identity until the asynchronous catalog verifies availability.
    const pending = document.createElement("option");
    pending.value = model;
    pending.textContent = model + " · 사용 가능 여부 확인 필요";
    pending.disabled = true;
    pending.selected = true;
    dom.modelSelect.prepend(pending);
  }
  applyRestoredSessionSettings({ settings }, { source: "stored-controls", persist: false });
  return true;
}

function defaultSelectOption(select) {
  if (!select) return null;
  return Array.from(select.options || []).find((option) => option.defaultSelected)
    || Array.from(select.options || [])[0]
    || null;
}

function resetControlSettingsToDefaults() {
  const defaultModel = defaultSelectOption(dom.modelSelect);
  if (defaultModel) dom.modelSelect.value = defaultModel.value;
  const defaultSelectionMode = defaultSelectOption(dom.modelSelectionMode);
  if (defaultSelectionMode) dom.modelSelectionMode.value = defaultSelectionMode.value;
  const defaultExecutionMode = defaultSelectOption(dom.executionMode);
  if (defaultExecutionMode) dom.executionMode.value = defaultExecutionMode.value;
  const defaultSearchMode = defaultSelectOption(dom.searchModeSelect);
  if (defaultSearchMode) dom.searchModeSelect.value = defaultSearchMode.value;
  if (dom.useRag) dom.useRag.checked = dom.useRag.defaultChecked !== false;
  if (dom.googleSearchRescue) {
    dom.googleSearchRescue.checked = dom.googleSearchRescue.defaultChecked === true;
    delete dom.googleSearchRescue.dataset.rescueExplicit;
  }
}

function isSmokeProofSession() {
  const search = String(window.location?.search || "");
  return /(?:^\?|&)codexSmoke(?:=|&|$)/.test(search)
    || /(?:^\?|&)awxSmoke(?:=|&|$)/.test(search)
    || /(?:^\?|&)proofMode=browser(?:&|$)/.test(search);
}

function applySmokeProofControlDefaults() {
  if (!isSmokeProofSession() || !dom.searchModeSelect || !selectCanUseValue(dom.searchModeSelect, "OFF")) {
    return false;
  }
  dom.searchModeSelect.value = "OFF";
  dom.searchModeSelect.dataset.smokeProofDefault = "local-only";
  return true;
}

function streamAbortError() {
  const error = new Error("aborted");
  error.name = "AbortError";
  return error;
}

function streamTranscriptCleanupInProgress() {
  const controllerActive = Boolean(streamController && streamController.signal && !streamController.signal.aborted);
  return Boolean(activeStreamAssistant || controllerActive || dom.messageInput?.disabled);
}

function chatDescendants(root) {
  const nodes = [];
  const stack = Array.from(root?.children || []);
  while (stack.length) {
    const node = stack.shift();
    if (!node) continue;
    nodes.push(node);
    stack.push(...Array.from(node.children || []));
  }
  return nodes;
}

function hasCompletedSessionlessTranscript(root = dom.chatMessages) {
  return chatDescendants(root).some((node) => {
    const text = String(node.textContent || "").trim();
    const className = String(node.className || "");
    const speaker = node.dataset?.speaker || node.dataset?.messageRole || "";
    const stateValue = node.dataset?.state || "";
    if (node.dataset?.imageJobDebug || className.includes("image-job-card")) return true;
    return speaker === "assistant" && stateValue !== "pending" && Boolean(text);
  });
}

function clearOrphanedSessionlessTranscript() {
  if (streamTranscriptCleanupInProgress()) return false;
  const sid = sessionIdFromPayload({ sessionId: state.currentSessionId }) || restoreCurrentSessionId();
  const hasMessages = Boolean(dom.chatMessages && (dom.chatMessages.children?.length || 0) > 0);
  const hasSessionModeRows = Boolean(document.querySelector("[data-session-mode-list] [data-session-mode-row]"));
  if (sid || (!hasMessages && !hasSessionModeRows)) return false;
  if (hasCompletedSessionlessTranscript()) return false;
  if (dom.chatMessages) {
    window.AwxChatTraceUi?.dispose(dom.chatMessages);
    if (typeof dom.chatMessages.replaceChildren === "function") {
      dom.chatMessages.replaceChildren();
    } else {
      dom.chatMessages.textContent = "";
    }
    dom.chatMessages.textContent = "";
  }
  clearSessionModeDiagnostics();
  restoredSessionHydrated = false;
  return true;
}

function startNewChatSession() {
  if (dom.messageInput?.disabled || pendingStopBeforeToken?.unresolved) {
    setStatusRailValue(dom.traceStatus, "stop response first");
    return false;
  }
  beginChatTransitionDebugTurn("turn:not-observed", "new-chat");
  restoredSessionHydrationGeneration += 1;
  forgetCurrentSessionId();
  if (dom.googleSearchRescue) {
    dom.googleSearchRescue.checked = dom.googleSearchRescue.defaultChecked === true;
    delete dom.googleSearchRescue.dataset.rescueExplicit;
    delete dom.googleSearchRescue.dataset.rescueOwnerScopeId;
  }
  persistControlSettings();
  pendingAttachments.length = 0;
  renderAttachChips();
  clearSessionModeDiagnostics();
  if (dom.chatMessages) {
    clearSelectionEntropyTrace(dom.chatMessages);
    window.AwxChatTraceUi?.dispose(dom.chatMessages);
    if (typeof dom.chatMessages.replaceChildren === "function") {
      dom.chatMessages.replaceChildren();
    } else {
      dom.chatMessages.textContent = "";
    }
  }
  restoredSessionHydrated = false;
  clearActiveRunIdentity();
  activeStreamAssistant = null;
  syncSessionSelectionCapability();
  resetCurrentTurnHealthOverlay();
  currentTurnRecoveryState = "";
  renderDecisionRibbon(presentObservedDecision({
    streamStatus: "idle",
    healthOverlay: currentTurnHealthOverlay
  }));
  renderPrimaryDiagnostic(selectPrimaryDiagnostic({
    healthOverlay: currentTurnHealthOverlay,
    streamStatus: "idle",
    isStopAvailable: false
  }));
  if (dom.messageInput) {
    dom.messageInput.value = "";
  }
  syncComposerDraftState();
  setStatusRailValue(dom.streamStatus, "idle");
  setStatusRailValue(dom.traceStatus, "new chat");
  setCoreStatus("idle", "idle");
  setComposerBusy(false);
  dispatchBrainStateSignal('session', {});
  return true;
}

function restoredSessionSetting(settings, ...keys) {
  if (!settings || typeof settings !== "object") return null;
  for (const key of keys) {
    if (!Object.prototype.hasOwnProperty.call(settings, key)) continue;
    const value = settings[key];
    if (value == null) continue;
    if (typeof value === "string" && !value.trim()) continue;
    return value;
  }
  return null;
}

function restoredSessionBoolean(value) {
  if (typeof value === "boolean") return value;
  if (value == null) return null;
  const text = String(value).trim().toLowerCase();
  if (text === "true") return true;
  if (text === "false") return false;
  return null;
}

function selectCanUseValue(select, value) {
  const text = String(value ?? "").trim();
  if (!select || !text) return false;
  const options = Array.from(select.options || []);
  return options.length === 0 || options.some((option) => String(option.value) === text);
}

function applyRestoredSessionSettings(detail = {}, options = {}) {
  const settings = detail?.settings && typeof detail.settings === "object" ? detail.settings : {};
  // Stored browser drafts require the bridge's fresh owner check; session detail is server-authorized.
  if (options.source !== "stored-controls" && dom.googleSearchRescue
      && typeof settings.googleSearchRescueEnabled === "boolean"
      && dom.googleSearchRescue.dataset.rescueExplicit !== "true") {
    dom.googleSearchRescue.checked = settings.googleSearchRescueEnabled;
  }
  if (localControlOverrideActive && options.source !== "stored-controls") {
    syncControlStatus({ persist: false });
    return false;
  }
  const restoredModel = restoredSessionSetting(settings, "model", "modelId");
  if (dom.modelSelectionMode && ["preferred", "strict", "auto"].includes(settings.modelSelectionMode)) {
    dom.modelSelectionMode.value = settings.modelSelectionMode;
  }
  let modelApplied = false;
  if (restoredModel && dom.modelSelect && selectCanUseValue(dom.modelSelect, restoredModel)) {
    dom.modelSelect.value = String(restoredModel).trim();
    modelApplied = true;
  }

  const restoredSearchMode = restoredSessionSetting(settings, "searchMode", "search_mode");
  const restoredExecutionMode = restoredSessionSetting(settings, "executionMode", "execution_mode");
  if (dom.executionMode && ["AUTO", "STRIKE", "SELF_ASK"].includes(restoredExecutionMode)) {
    dom.executionMode.value = restoredExecutionMode;
  }
  const searchMode = restoredSearchMode == null ? null : String(restoredSearchMode).trim().toUpperCase();
  if (searchMode && dom.searchModeSelect && selectCanUseValue(dom.searchModeSelect, searchMode)) {
    dom.searchModeSelect.value = searchMode;
  }

  const restoredUseRag = restoredSessionBoolean(restoredSessionSetting(settings, "useRag", "use_rag"));
  if (restoredUseRag !== null && dom.useRag) {
    dom.useRag.checked = restoredUseRag;
  }

  // 복원 자체는 렌더링만 한다 — persist는 명시 opt-in이며 현재 호출자는 모두 false다.
  syncControlStatus({ persist: options.persist === true });
  markControlHydrationReady();
  if (!modelApplied && detail?.modelUsed) {
    setStatusRailValue(dom.modelStatus, `이전 응답 ${modelStatusRailValue(detail.modelUsed)}`);
  }
  return true;
}

function applyRestoredTerminalStoppedState(detail = {}, renderedMessages = []) {
  const currentId = strictBackendSessionId(state.currentSessionId);
  const detailId = strictBackendSessionId(detail?.id);
  const terminal = Array.isArray(renderedMessages) && renderedMessages.length
    ? renderedMessages[renderedMessages.length - 1]
    : null;
  if (
    currentId === null ||
    detailId !== currentId ||
    terminal?.dataset?.speaker !== "assistant" ||
    terminal?.dataset?.state !== "stopped"
  ) {
    return false;
  }

  syncCurrentTurnHealthOverlay("stopped", "session-restore", { streamStopped: true });
  renderDecisionRibbon(presentObservedDecision({
    streamStatus: "stopped",
    healthOverlay: currentTurnHealthOverlay
  }));
  renderPrimaryDiagnostic(selectPrimaryDiagnostic({
    healthOverlay: currentTurnHealthOverlay,
    streamStatus: "stopped",
    isStopAvailable: false
  }));
  return true;
}

function focusRestoredComposerIfDocumentOwned() {
  const input = dom.messageInput;
  if (!input || input.disabled) return false;
  const activeElement = document.activeElement;
  if (activeElement === input) return true;
  if (activeElement &&
      activeElement !== document.body &&
      activeElement !== document.documentElement) return false;
  input.focus();
  return document.activeElement === input;
}

async function hydrateRestoredSessionTranscript() {
  const sid = sessionIdFromPayload({ sessionId: state.currentSessionId }) || restoreCurrentSessionId();
  const hydrationMessageCount = dom.chatMessages?.children?.length || 0;
  const hasExistingMessages = hydrationMessageCount > 0;
  if (!sid || restoredSessionHydrated) {
    markControlHydrationReady();
    return false;
  }
  beginChatTransitionDebugTurn(`session:${sid}`, "reload");
  const hydrationGeneration = restoredSessionHydrationGeneration;
  restoredSessionHydrated = true;
  let hydrationCompleted = false;
  try {
    const response = await apiCall(chatTraceRequestUrl(`/api/chat/sessions/${sid}?restoreProbe=true`));
    if (hydrationGeneration !== restoredSessionHydrationGeneration ||
        normalizeSessionIdValue(state.currentSessionId) !== sid ||
        (dom.chatMessages?.children?.length || 0) !== hydrationMessageCount) return false;
    const detail = await response.json();
    if (hydrationGeneration !== restoredSessionHydrationGeneration ||
        normalizeSessionIdValue(state.currentSessionId) !== sid ||
        (dom.chatMessages?.children?.length || 0) !== hydrationMessageCount) return false;
    if (detail?.found === false) {
      clearSessionModeDiagnostics(sid);
      forgetCurrentSessionId(sid);
      clearActiveRunIdentity();
      clearOrphanedSessionlessTranscript();
      setStatusRailValue(dom.traceStatus, "session restore unavailable");
      return false;
    }
    const validated = validateSessionDetail(sid, detail);
    if (!validated) {
      setStatusRailValue(dom.traceStatus, "session restore unavailable");
      return false;
    }
    rememberCurrentSessionId(sid);
    const messages = validated.messages;
    if (!hasExistingMessages) {
      clearSelectionEntropyTrace(dom.chatMessages);
      const renderedMessages = [];
      const restoredTurnTraces = turnTracesByTurnId(validated.turnTraces);
      for (const message of messages) {
        const node = appendMessage(message.role, message.content);
        if (message.turnId != null) node.dataset.turnId = String(message.turnId);
        if (message.role === "assistant") {
          renderExecutionModeReceipt({ executionMode: message.executionMode }, node);
          node.dataset.sessionId = String(sid);
          if (Array.isArray(message.evidence) && message.evidence.length) {
            renderEvidenceRail(message.evidence, node, { answerText: message.content });
          }
          try {
            window.ChatEvidenceGraph?.restore(node, { sessionId: sid, turnId: message.turnId,
              evidence: message.evidence, answerText: message.content });
          } catch { /* Optional answer view. */ }
        }
        const turnTrace = restoredTurnTraces.get(message.turnId);
        if (turnTrace) renderRestoredTurnTrace(node, turnTrace);
        renderedMessages.push(node);
      }
      applyRestoredTerminalStoppedState(validated, renderedMessages);
      if (window.AwxChatTraceUi?.enabled() && validated.turnTraces.length === 0) {
        void refreshVisibleTurnTraces();
      }
    }
    applyRestoredSessionSettings(validated);
    restoreSessionModeBadge(sid, validated);
    void resumeStoredRunIfNeeded(sid);
    const restoredTranscript = !hasExistingMessages && messages.length > 0;
    if (restoredTranscript) focusRestoredComposerIfDocumentOwned();
    hydrationCompleted = true;
    return restoredTranscript;
  } catch (error) {
    if (hydrationGeneration !== restoredSessionHydrationGeneration ||
        normalizeSessionIdValue(state.currentSessionId) !== sid ||
        (dom.chatMessages?.children?.length || 0) !== hydrationMessageCount) return false;
    if (isHttp403(error) || error?.status === 404) {
      clearSessionModeDiagnostics(sid);
      forgetCurrentSessionId(sid);
      clearActiveRunIdentity();
      clearOrphanedSessionlessTranscript();
    }
    setStatusRailValue(dom.traceStatus, "session restore unavailable");
    return false;
  } finally {
    if (!hydrationCompleted && hydrationGeneration === restoredSessionHydrationGeneration
        && normalizeSessionIdValue(state.currentSessionId) === sid) {
      restoredSessionHydrated = false;
    }
    markControlHydrationReady();
  }
}

async function resumeStoredRunIfNeeded(expectedSessionId) {
  if (restoredRunResumeInFlight) return restoredRunResumeInFlight;
  const restored = activeSessionId && activeRunToken
    ? { sessionId: activeSessionId, runToken: activeRunToken }
    : restoreActiveRunIdentity();
  const sid = normalizeSessionIdValue(expectedSessionId);
  if (restored && sid && restored.sessionId !== sid) {
    clearActiveRunIdentityIfMatch(activeRunIdentitySnapshot());
    return false;
  }
  if (!restored || !sid || activeStreamAssistant) return false;
  const expectedRun = activeRunIdentitySnapshot();
  if (!expectedRun || expectedRun.sessionId !== sid) return false;

  const resumeTask = (async () => {
    let resumeAssistant = null;
    if (dom.messageInput) dom.messageInput.disabled = true;
    if (dom.sendBtn) dom.sendBtn.disabled = true;
    if (dom.stopBtn) dom.stopBtn.disabled = false;
    setComposerBusy(true);
    try {
      const response = await apiCall(chatTraceRequestUrl(`/api/chat/state?sessionId=${sid}`), {
        headers: withChatCorrelationHeaders({}, expectedRun)
      });
      if (!sameActiveRunIdentity(expectedRun)) return false;
      const runState = await response.json();
      if (!sameActiveRunIdentity(expectedRun)) return false;
      if (runState?.persisted === true) {
        const restoredAnswer = typeof runState?.lastAssistant === "string"
          ? runState.lastAssistant.trim()
          : "";
        if (runState?.currentRun === true && restoredAnswer) {
          const acknowledged = await acknowledgeExactRun(
            expectedRun.sessionId, expectedRun.runToken, "recovery");
          if (acknowledged) clearActiveRunIdentityIfMatch(expectedRun);
          setCoreStatus("done", acknowledged ? "restored final acknowledged" : "restored final pending ack");
          return true;
        }
        clearActiveRunIdentityIfMatch(expectedRun);
        return false;
      }
      const status = String(runState?.runStatus || "").toLowerCase();
      const replayableStatus = status === "running" || status === "committing" ||
        status === "cancelling" || status === "done" || status === "cancelled";
      if (runState?.attachable !== true || !replayableStatus) {
        clearActiveRunIdentityIfMatch(expectedRun);
        return false;
      }

      const loaderId = `assistant-resume-${Date.now()}-${++assistantMessageSequence}`;
      resumeAssistant = appendMessage("assistant", "");
      resumeAssistant.id = loaderId;
      activeStreamAssistant = resumeAssistant;
      invalidatePendingSessionSelectionForTranscriptOwnership();
      syncSessionSelectionCapability();
      setCoreStatus("streaming", "attaching exact run");
      await streamChat({
        message: "resume",
        question: "resume",
        sessionId: sid,
        attach: true,
        runToken: expectedRun.runToken
      }, loaderId);
      return true;
    } catch (error) {
      if (activeStreamAssistant === resumeAssistant && resumeAssistant?.dataset?.state === "pending") {
        if (error?.name === "AbortError" && streamCancelRequested) markAssistantStreamStopped(resumeAssistant);
        else renderChatFailureNotice(resumeAssistant, error?.chatFailure || classifyChatFailure({ error }));
      }
      if (sameActiveRunIdentity(expectedRun)) {
        setStatusRailValue(dom.traceStatus, "exact run resume unavailable");
      }
      return false;
    } finally {
      if (dom.messageInput) dom.messageInput.disabled = false;
      if (dom.sendBtn) dom.sendBtn.disabled = false;
      if (dom.stopBtn) dom.stopBtn.disabled = true;
      setComposerBusy(false);
      if (activeStreamAssistant === resumeAssistant) activeStreamAssistant = null;
      syncSessionSelectionCapability();
    }
  })();
  restoredRunResumeInFlight = resumeTask;
  try {
    return await resumeTask;
  } finally {
    if (restoredRunResumeInFlight === resumeTask) restoredRunResumeInFlight = null;
  }
}

async function recoverExactRunAfterTransportLoss(expectedRun, loaderId) {
  if (!sameActiveRunIdentity(expectedRun)) return false;
  const response = await apiCall(chatTraceRequestUrl(`/api/chat/state?sessionId=${expectedRun.sessionId}`), {
    headers: withChatCorrelationHeaders({}, expectedRun)
  });
  if (!sameActiveRunIdentity(expectedRun)) return false;
  const runState = await response.json();
  if (!sameActiveRunIdentity(expectedRun)) return false;
  if (runState?.persisted === true) {
    const restoredAnswer = typeof runState?.lastAssistant === "string"
      ? runState.lastAssistant.trim()
      : "";
    if (runState?.currentRun !== true || !restoredAnswer) {
      clearActiveRunIdentityIfMatch(expectedRun);
      return false;
    }
    const recoveredAssistant = document.getElementById(loaderId);
    if (recoveredAssistant) {
      clearAssistantPendingPlaceholder(recoveredAssistant);
      setMessageContent(recoveredAssistant, "assistant", restoredAnswer);
      try { window.ChatEvidenceGraph?.restore(recoveredAssistant); } catch { /* No persisted provenance. */ }
    }
    const acknowledged = await acknowledgeExactRun(
      expectedRun.sessionId, expectedRun.runToken, "recovery");
    if (acknowledged) clearActiveRunIdentityIfMatch(expectedRun);
    setCoreStatus("done", acknowledged ? "recovered final acknowledged" : "recovered final pending ack");
    return true;
  }
  const status = String(runState?.runStatus || "").toLowerCase();
  const replayableStatus = status === "running" || status === "committing" ||
    status === "cancelling" || status === "done" || status === "cancelled";
  if (runState?.attachable !== true || !replayableStatus) {
    clearActiveRunIdentityIfMatch(expectedRun);
    return false;
  }
  const replayAssistant = document.getElementById(loaderId);
  if (replayAssistant) {
    clearAssistantPendingPlaceholder(replayAssistant);
    if (typeof replayAssistant.replaceChildren === "function") replayAssistant.replaceChildren();
    else replayAssistant.textContent = "";
    if (replayAssistant.dataset) replayAssistant.dataset.ariaText = "";
  }
  setCoreStatus("streaming", "recovering exact run");
  await streamChat({
    message: "resume",
    question: "resume",
    sessionId: expectedRun.sessionId,
    attach: true,
    runToken: expectedRun.runToken
  }, loaderId, { exactRecoveryAttempt: true });
  return true;
}

function reconcileRestoredSessionTranscript() {
  if (clearOrphanedSessionlessTranscript()) {
    // hydrate 경로로 진입하지 않으므로 여기서 READY로 닫아야 사용자 변경 persist가 풀린다.
    markControlHydrationReady();
    return true;
  }
  void hydrateRestoredSessionTranscript();
  return false;
}

function scheduleRestoredSessionTranscriptReconcile() {
  [0, 100, 500, 1500].forEach((delayMs) => {
    window.setTimeout(reconcileRestoredSessionTranscript, delayMs);
  });
}

function withCsrfHeaders(headers = {}) {
  return {
    ...headers,
    ...(CSRF.token ? { [CSRF.header]: CSRF.token } : {})
  };
}

function copyHeaders(headers = {}) {
  const out = {};
  if (headers && typeof headers.forEach === "function") {
    headers.forEach((value, key) => {
      out[key] = value;
    });
    return out;
  }
  return { ...headers };
}

function hasHeader(headers, name) {
  const expected = String(name).toLowerCase();
  return Object.keys(headers || {}).some((key) => String(key).toLowerCase() === expected);
}

function makeRequestId() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID();
  return `req-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function withChatCorrelationHeaders(headers = {}, options = {}) {
  const out = copyHeaders(headers);
  if (!hasHeader(out, "x-request-id")) out["x-request-id"] = makeRequestId();
  const sid = normalizeSessionIdValue(options.sessionId ?? state.currentSessionId);
  if (sid && !hasHeader(out, "x-session-id")) out["x-session-id"] = String(sid);
  const runToken = normalizeRunToken(options.runToken);
  if (runToken && !hasHeader(out, "x-chat-run-token")) out["x-chat-run-token"] = runToken;
  return withCsrfHeaders(out);
}

function responseHeader(response, name) {
  try {
    return response?.headers?.get?.(name) || "";
  } catch {
    return "";
  }
}

function applyChatResponseHeaders(response, options = {}) {
  const sid = normalizeSessionIdValue(responseHeader(response, "x-session-id"));
  if (sid && options.allowSessionIdentity !== false) {
    activeSessionId = sid;
    activeRunToken = null;
    rememberCurrentSessionId(sid);
    dispatchBrainStateSignal("session", { sessionId: sid });
  }
  const modelUsed = safeDebugCockpitDetail(responseHeader(response, "x-model-used"));
  if (modelUsed && modelUsed !== "-") {
    state.responseModelUsed = modelUsed;
    setStatusRailValue(dom.modelStatus, `적용 ${modelStatusRailValue(modelUsed)}`);
  }
  const ragUsed = responseHeader(response, "x-rag-used");
  if (ragUsed) {
    setStatusRailValue(dom.ragStatus, `적용 ${/^(true|1|yes|on)$/i.test(String(ragUsed).trim()) ? "ON" : "OFF"}`);
  }
  const traceSnapshotId = safeDebugCockpitDetail(responseHeader(response, "x-trace-snapshot-id"));
  const requestId = safeDebugCockpitDetail(responseHeader(response, "x-request-id"));
  const rawTraceLabel = traceSnapshotId && traceSnapshotId !== "-" ? traceSnapshotId : requestId;
  const traceLabel = traceRailLabel(rawTraceLabel);
  if (traceLabel && traceLabel !== "-") {
    state.responseTraceId = traceLabel;
    setStatusRailValue(dom.traceStatus, traceLabel);
  }
}

function setText(node, value) {
  if (node) node.textContent = String(value ?? "");
}

function ensureDebugCellTextSeparators(cell) {
  if (!cell) return;
  const parts = Array.from(cell.querySelectorAll("strong, span, small"));
  for (const part of parts.slice(1)) {
    const previous = part.previousSibling;
    if (previous?.nodeType === Node.TEXT_NODE) {
      if (!/\s$/.test(previous.textContent || "")) {
        previous.textContent = `${previous.textContent} `;
      }
    } else {
      part.before(document.createTextNode("\n"));
    }
  }
}

const normalizedAnswerMode = (mode) => {
  const raw = String(mode ?? "").trim();
  if (!raw) return null;
  if (UNKNOWN_ANSWER_MODES.has(raw.toLowerCase())) return null;
  return raw;
};

const finalAnswerMode = (payload, model) => {
  const explicit = normalizedAnswerMode(payload?.answerMode || payload?.answer_mode || payload?.mode);
  if (explicit) return explicit;
  const modelLower = String(model || "").toLowerCase();
  if (modelLower.includes("ui-mode:local")) return "ui-mode:local:evidence";
  if (modelLower.includes("history:fallback:current-turn")) return "HISTORY_CURRENT_TURN";
  if (modelLower.includes("history:fallback:recent")) return "HISTORY_RECENT";
  if (modelLower.includes("direct:literal")) return "DIRECT_LITERAL";
  if (modelLower.includes("fallback:evidence")) return "FALLBACK_EVIDENCE";
  if (modelLower.includes("fallback-local") || modelLower.includes("fallback:local")) return "FALLBACK_LOCAL";
  if (modelLower.includes("fallback")) return "FALLBACK";
  const ragUsed = payload.ragUsed ?? payload.rag_used;
  return ragUsed === true ? "rag" : ragUsed === false ? "chat" : null;
};

const answerModeLabel = (mode) => {
  const upper = String(mode || "").toUpperCase();
  if (upper === "HISTORY_CURRENT_TURN") {
    return { text: "current turn memory", status: "OK" };
  }
  if (upper === "HISTORY_RECENT") {
    return { text: "recent history", status: "OK" };
  }
  if (upper === "DIRECT_LITERAL") {
    return { text: "direct literal", status: "OK" };
  }
  if (upper.startsWith("UI-MODE:LOCAL")) {
    return { text: "ui mode: local evidence", status: "OK" };
  }
  if (upper === "FALLBACK_LOCAL") {
    return { text: "fallback: local", status: "WARN" };
  }
  if (upper === "FALLBACK_EVIDENCE") {
    return { text: "fallback: evidence", status: "WARN" };
  }
  if (upper === "FALLBACK") {
    return { text: "fallback", status: "WARN" };
  }
  return { text: mode || "unknown", status: mode ? "OK" : "WARN" };
};

const answerModeRailText = (mode) => {
  const normalized = normalizedAnswerMode(mode);
  if (!normalized) return null;
  return answerModeLabel(normalized)?.text || normalized;
};

const successfulAnswerMode = (mode) => {
  const normalized = normalizedAnswerMode(mode);
  if (!normalized) return false;
  const upper = String(normalized).toUpperCase();
  return ANSWER_COMPLETE_MODES.has(upper) || upper.startsWith("UI-MODE:LOCAL");
};

const streamStatusRailValue = (partial = {}) =>
  partial.streamContext || answerModeRailText(partial.answerMode || partial.streamStatus) || partial.streamStatus || "-";

const modelStatusRailValue = (model, mode) => {
  const modelText = String(model || "").trim();
  const normalized = normalizedAnswerMode(mode) || finalAnswerMode({}, model);
  const upper = String(normalized || "").toUpperCase();
  if (upper === "HISTORY_CURRENT_TURN" || upper === "HISTORY_RECENT") return answerModeRailText(normalized);
  if (upper === "DIRECT_LITERAL") return answerModeRailText(normalized);
  if (upper.startsWith("UI-MODE:LOCAL")) {
    const modelMarker = String(finalAnswerMode({}, modelText) || modelText).toUpperCase();
    if (modelMarker.startsWith("UI-MODE:LOCAL")) return dom.modelSelect?.value || "-";
    return modelText || dom.modelSelect?.value || "-";
  }
  if (upper === "FALLBACK_EVIDENCE") return answerModeRailText(normalized);
  return modelText || "-";
};

const searchModeRailValue = (mode) => {
  const upper = String(mode || "AUTO").trim().toUpperCase();
  if (upper === "FORCE_LIGHT") return "LIGHT";
  if (upper === "FORCE_DEEP") return "DEEP";
  return upper || "AUTO";
};

const modelFallbackDiagnostic = (model, mode) => {
  const normalized = normalizedAnswerMode(mode) || finalAnswerMode({}, model);
  return normalized && String(normalized).startsWith("FALLBACK") ? answerModeLabel(normalized).text : null;
};

function fallbackEvidenceDiagnosticDetail(mode, pipeline = {}, evidence = [], model = "") {
  const normalized = normalizedAnswerMode(mode) || finalAnswerMode({}, model);
  const safeEvidenceCount = Array.isArray(evidence) ? evidence.length : 0;
  const failureClass = safeDebugCockpitDetail(pipeline.failureClass || pipeline.failure_class || "none");
  const disabledReason = safeDebugCockpitDetail(
    pipeline.disabledReason || pipeline.disabled_reason || pipeline.reason || "none"
  );
  const route = safeDebugCockpitDetail(pipeline.route || normalized || "fallback");
  const nextAction = compactNextActionText(
    pipeline.nextAction || pipeline.next_action || pipeline.recommendedAction || pipeline.recommended_action || "observe"
  );
  return `mode:${normalized || "fallback"} evidence:${safeEvidenceCount} failure:${failureClass} disabled:${disabledReason} route:${route} next:${nextAction}`;
}

function renderFallbackEvidenceDiagnostic(target, mode, pipeline = {}, evidence = [], model = "") {
  if (shouldSuppressMessageDiagnostics(target)) return null;
  const normalized = normalizedAnswerMode(mode) || finalAnswerMode({}, model);
  if (String(normalized || "").toUpperCase() !== "FALLBACK_EVIDENCE") return;
  const safeEvidenceCount = Array.isArray(evidence) ? evidence.length : 0;
  const detail = fallbackEvidenceDiagnosticDetail(mode, pipeline, evidence, model);
  const card = document.createElement("div");
  card.className = "message-debug-fx fallback-evidence-diagnostic";
  card.dataset.role = "fallback-evidence-diagnostic";
  card.dataset.answerMode = normalized;
  card.dataset.evidenceCount = String(safeEvidenceCount);
  card.textContent = `Fallback evidence: ${detail}`;
  card.setAttribute("aria-label", `Fallback evidence: ${detail}`);
  markChatDiagnosticNode(card);
  target?.appendChild(card);
  scrollChatToLatestAssistant();
}

const DIRECT_LITERAL_DIAGNOSTIC_SELECTOR = [
  ".message-debug-fx",
  "[data-role=\"plan-mode\"]",
  "[data-role=\"transformer-core-rail\"]",
  "[data-role=\"trace-signal-detail\"]",
  "[data-role=\"score-delta-detail\"]",
  ".evidence-rail",
  "[data-answer-mode-badge]"
].join(",");

function isDirectLiteralMode(mode, model) {
  const normalized = normalizedAnswerMode(mode) || finalAnswerMode({}, model);
  if (String(normalized || "").toUpperCase() === "DIRECT_LITERAL") return true;
  const compact = String(mode || model || "").trim().toLowerCase().replace(/[\s:_-]+/g, "");
  return compact === "directliteral";
}

function suppressDirectLiteralDiagnostics(bubble, mode, model) {
  if (!bubble || !isDirectLiteralMode(mode, model)) return false;
  bubble.dataset.directLiteralDiagnostics = "suppressed";
  let node = bubble.nextElementSibling;
  while (node && !node.classList?.contains("message")) {
    const next = node.nextElementSibling;
    if (node.matches?.(DIRECT_LITERAL_DIAGNOSTIC_SELECTOR)) {
      node.remove();
    }
    node = next;
  }
  return true;
}

function isLocalUiMode(mode, model) {
  const normalized = normalizedAnswerMode(mode) || finalAnswerMode({}, model);
  return String(normalized || "").toUpperCase().startsWith("UI-MODE:LOCAL");
}

function suppressLocalUiModeDiagnostics(bubble, mode, model) {
  if (!bubble || !isLocalUiMode(mode, model)) return false;
  bubble.dataset.localUiModeDiagnostics = "suppressed";
  let node = bubble.nextElementSibling;
  while (node && !node.classList?.contains("message")) {
    const next = node.nextElementSibling;
    if (node.matches?.(DIRECT_LITERAL_DIAGNOSTIC_SELECTOR)) {
      node.remove();
    }
    node = next;
  }
  return true;
}

function isAnswerOnlyInstruction(text) {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  if (!value) return false;
  const answerOnly = /답변만|대답만|최종\s*답변만|answer\s*only|only\s+the\s+answer|final\s+answer\s+only/i.test(value);
  const noDiagnostics = /디버그\s*(?:설명|출력|정보)?\s*없이|진단\s*(?:설명|출력|정보)?\s*없이|without\s+(?:debug|diagnostic|trace)|no\s+(?:debug|diagnostic|trace)/i.test(value);
  return answerOnly && noDiagnostics;
}

function suppressAnswerOnlyDiagnostics(bubble, userInstruction) {
  if (!bubble || !isAnswerOnlyInstruction(userInstruction)) return false;
  bubble.dataset.answerOnlyDiagnostics = "suppressed";
  let node = bubble.nextElementSibling;
  while (node && !node.classList?.contains("message")) {
    const next = node.nextElementSibling;
    if (node.matches?.(DIRECT_LITERAL_DIAGNOSTIC_SELECTOR)) {
      node.remove();
    }
    node = next;
  }
  return true;
}

function messageText(node) {
  return String(node?.innerText || node?.textContent || "").trim();
}

function isCompactExternalProofAnswer(bubble, mode, model) {
  const normalized = normalizedAnswerMode(mode) || finalAnswerMode({}, model);
  if (String(normalized || "").toUpperCase() !== "FALLBACK_EVIDENCE") return false;
  if (!String(model || "").toLowerCase().includes("agent-debug")) return false;
  const text = messageText(bubble).replace(/\s+/g, " ");
  return text.includes("- Browser:")
    && text.includes("- Computer:")
    && text.includes("- Supabase: evidence_needed")
    && text.includes("DB verification not claimed");
}

function suppressCompactExternalProofDiagnostics(bubble, mode, model) {
  if (!bubble || !isCompactExternalProofAnswer(bubble, mode, model)) return false;
  bubble.dataset.compactExternalProofDiagnostics = "suppressed";
  let node = bubble.nextElementSibling;
  while (node && !node.classList?.contains("message")) {
    const next = node.nextElementSibling;
    if (node.matches?.(DIRECT_LITERAL_DIAGNOSTIC_SELECTOR)) {
      node.remove();
    }
    node = next;
  }
  return true;
}

function shouldSuppressMessageDiagnostics(target) {
  const anchor = latestMessageDiagnosticAnchor(target);
  return anchor?.dataset?.directLiteralDiagnostics === "suppressed"
    || anchor?.dataset?.compactExternalProofDiagnostics === "suppressed"
    || anchor?.dataset?.localUiModeDiagnostics === "suppressed"
    || anchor?.dataset?.answerOnlyDiagnostics === "suppressed";
}

function latestMessageDiagnosticAnchor(target) {
  if (!target) return null;
  if (target.classList?.contains("message")) return target;
  const messages = Array.from(target.querySelectorAll?.(".message") || []);
  return messages.length ? messages[messages.length - 1] : null;
}

function clearDirectLiteralDiagnosticsSuppression() {
  dom.chatMessages?.querySelectorAll?.("[data-direct-literal-diagnostics]").forEach((node) => {
    delete node.dataset.directLiteralDiagnostics;
  });
  dom.chatMessages?.querySelectorAll?.("[data-compact-external-proof-diagnostics]").forEach((node) => {
    delete node.dataset.compactExternalProofDiagnostics;
  });
  dom.chatMessages?.querySelectorAll?.("[data-local-ui-mode-diagnostics]").forEach((node) => {
    delete node.dataset.localUiModeDiagnostics;
  });
  dom.chatMessages?.querySelectorAll?.("[data-answer-only-diagnostics]").forEach((node) => {
    delete node.dataset.answerOnlyDiagnostics;
  });
}

const isGenericModelDiagnostic = (value) =>
  !value || ["Model", "Model: pending", "pending"].includes(String(value));

const defaultModelWaitReason = (reason) =>
  /waiting[_-]for[_-]default[_-]model|default[_-]model[_-]wait/i.test(String(reason || ""))
    ? "waiting_for_default_model"
    : null;

const statusContext = (signal) => {
  const safeSignal = signal || {};
  const statusLabel = safeSignal.message || safeSignal.code || safeSignal.phase || "status";
  return `${statusLabel} remaining:${safeSignal.remainingMs ?? "-"} took:${safeSignal.tookMs ?? "-"} cancelled:${safeSignal.cancelled === true ? "yes" : "no"}`;
};

function deriveTransformerModelDiagnostic(transformerBadges = {}, model, mode, meta = {}) {
  let transformerModelDiagnosticVisible = false;
  let transformerModelDiagnosticLabel = null;
  if (transformerBadges.model) transformerModelDiagnosticVisible = true;
  const finalModelDiagnostic = modelFallbackDiagnostic(model, mode);
  if (finalModelDiagnostic) {
    transformerModelDiagnosticLabel = finalModelDiagnostic;
  }
  if (meta?.status === "final" && transformerModelDiagnosticLabel && isGenericModelDiagnostic(transformerBadges.model)) {
    transformerBadges.model = transformerModelDiagnosticLabel;
    transformerBadges.modelBadge = `Model: ${transformerModelDiagnosticLabel}`;
  }
  return {
    model: finalModelDiagnostic || (transformerModelDiagnosticVisible ? undefined : model),
    ...(finalModelDiagnostic ? { modelActive: true, modelBadge: `Model: ${finalModelDiagnostic}` } : {}),
  };
}

function deriveSupabaseTransformerBadge(blocks = []) {
  const findBlock = (name) => blocks.find((block) => block?.name === name || block?.type === name || block?.id === name);
  const blockReason = (block) => block?.reason || block?.status || block?.code || null;
  const supabaseBlock = findBlock("supabase");
  const supabaseReason = supabaseBlock ? blockReason(supabaseBlock) : null;
  return {
    supabaseActive: Boolean(supabaseBlock),
    supabaseBadge: supabaseBlock ? `Supabase: ${supabaseReason || "pending"}` : "Supabase"
  };
}

function deriveTransformerBadges(blocks = []) {
  const findBlock = (name) => blocks.find((block) => block?.name === name || block?.type === name || block?.id === name);
  const blockReason = (block) => block?.reason || block?.status || block?.code || null;
  const modelBlock = findBlock("model") || findBlock("llm") || null;
  const recoverBlock = findBlock("recover") || findBlock("recovery") || null;
  const answerModeBlock = findBlock("answerMode") || findBlock("route") || {};
  const modelReason = modelBlock ? blockReason(modelBlock) : null;
  const modelStatus = modelBlock?.status || modelBlock?.code || modelReason;
  const answerMode = answerModeBlock.answerMode || answerModeBlock.mode || blockReason(answerModeBlock);
  const modelNeedsReview = Boolean(modelBlock && /warn|error|timeout|disabled/i.test(String(modelStatus || modelReason || "")));
  const transformerContext = answerModeBlock.context || modelReason || null;
  const recoveryState = observedRecoveryCode(recoverBlock ? blockReason(recoverBlock) : modelReason)
    || observedRecoveryCode(modelReason);
  return {
    ...deriveSupabaseTransformerBadge(blocks),
    answerMode,
    streamStatus: answerModeBlock.status || answerModeBlock.phase || null,
    streamContext: transformerContext,
    model: modelReason,
    modelActive: Boolean(modelBlock),
    modelBadge: modelBlock ? `Model: ${modelStatus || "pending"}` : "Model",
    modelNeedsReview,
    recoveryState
  };
}

function queryRewriteTransformerBlock(blocks = []) {
  return blocks.find((block) => {
    const id = String(block?.id || "").toLowerCase();
    const name = String(block?.name || "").toLowerCase();
    const type = String(block?.type || "").toLowerCase();
    const label = String(block?.label || "").toLowerCase();
    return id === "rewrite" || id === "queryrewrite" || name === "rewrite" || type === "rewrite" || label === "query rewrite";
  }) || null;
}

function queryRewriteReasonCount(reason, key) {
  const escaped = String(key).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = String(reason || "").match(new RegExp(`(?:^|_)${escaped}:(\\d+)`, "i"));
  return match ? match[1] : "";
}

function queryRewriteReasonToken(reason, key) {
  const escaped = String(key).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = String(reason || "").match(new RegExp(`(?:^|_)${escaped}:([A-Za-z0-9_.:-]{1,32})`, "i"));
  return match ? match[1] : "";
}

function queryRewriteTemperatureHintTokens(queryRewrite = {}) {
  const hints = Array.isArray(queryRewrite.variantLaneTemperatureHints)
    ? queryRewrite.variantLaneTemperatureHints
    : [];
  return hints
    .map((hint) => {
      const match = String(hint || "").match(/^\d+:([A-Za-z0-9_.:-]+)@([0-9]+(?:\.[0-9]+)?)#[A-Fa-f0-9]{12}$/);
      return match ? `${match[1]}@${match[2]}` : "";
    })
    .filter(Boolean)
    .slice(0, 3)
    .join("|");
}

function promoteQueryRewriteTransformerToHeartbeat(blocks = []) {
  const block = queryRewriteTransformerBlock(blocks);
  if (!block) return false;
  const rawStatus = safeDebugCockpitDetail(block.status || block.code || "observed");
  const status = /^(done|ok|complete)$/i.test(rawStatus) ? "OK" : "WARN";
  const reason = String(block.reason || "");
  const parts = [`status:${rawStatus || "observed"}`];
  [
    ["models", "models"],
    ["asgn", "asgn"],
    ["titles", "titles"],
    ["supers", "supers"],
    ["branches", "branches"],
    ["axes", "axes"],
    ["padded", "padded"]
  ].forEach(([key, label]) => {
    const count = queryRewriteReasonCount(reason, key);
    if (count) parts.push(`${label}:${count}`);
  });
  [
    ["lanes", "lanes"],
    ["temp", "temp"],
    ["profile", "profile"]
  ].forEach(([key, label]) => {
    const token = queryRewriteReasonToken(reason, key);
    if (token) parts.push(`${label}:${token}`);
  });
  const detail = `enabled:yes reason:stream_transformer ${parts.join(" ")}`;
  latestStreamQueryRewriteHeartbeat = { status, detail };
  setDebugHeartbeatField('qtx', status, detail);
  return true;
}

function upsertAnswerModeBadge(target, mode) {
  if (!target || !mode) return;
  let badgeEl = target.querySelector?.("[data-answer-mode-badge]");
  if (!badgeEl) {
    badgeEl = document.createElement("small");
    badgeEl.dataset.answerModeBadge = "true";
    markChatDiagnosticNode(badgeEl);
    target.appendChild(badgeEl);
  }
  const label = safeDebugCockpitDetail(answerModeRailText(mode) || String(mode));
  badgeEl.className = "message-debug-fx answer-mode-badge";
  badgeEl.dataset.answerMode = String(mode);
  badgeEl.textContent = `Answer mode: ${label}`;
  badgeEl.setAttribute("aria-label", `Answer mode: ${label}`);
}

function persistAnswerModeBadge(sessionId, mode) {
  if (!sessionId || !mode) return;
  try {
    window.sessionStorage?.setItem(`answerMode:${sessionId}`, String(mode));
  } catch {
    // storage is optional for this diagnostic badge
  }
}

function persistSessionModeTraceTurn(sessionId, traceTurnId) {
  const sid = normalizeSessionIdValue(sessionId);
  if (!sid || traceTurnId === undefined || traceTurnId === null || traceTurnId === "") return;
  try {
    window.sessionStorage?.setItem(`answerModeTraceTurn:${sid}`, String(traceTurnId));
  } catch {
    // storage is optional for this diagnostic badge
  }
}

function restoredAnswerModeBadge(sessionId, detail = {}) {
  const explicit = normalizedAnswerMode(
    restoredSessionSetting(detail, "answerMode", "answer_mode", "mode", "lastAnswerMode", "last_answer_mode")
      || restoredSessionSetting(detail?.settings, "answerMode", "answer_mode", "mode", "lastAnswerMode", "last_answer_mode")
  );
  if (explicit) return explicit;
  try {
    return normalizedAnswerMode(window.sessionStorage?.getItem(`answerMode:${sessionId}`));
  } catch {
    return null;
  }
}

function restoredSessionModeTraceTurn(sessionId, detail = {}) {
  const sid = normalizeSessionIdValue(sessionId);
  if (!sid) return null;
  const explicit = restoredSessionSetting(detail, "traceTurnId", "trace_turn_id", "lastTraceTurnId", "last_trace_turn_id")
    || restoredSessionSetting(detail?.settings, "traceTurnId", "trace_turn_id", "lastTraceTurnId", "last_trace_turn_id");
  if (explicit) return explicit;
  try {
    return window.sessionStorage?.getItem(`answerModeTraceTurn:${sid}`);
  } catch {
    return null;
  }
}

function restoreSessionModeBadge(sessionId, detail = {}) {
  const sid = normalizeSessionIdValue(sessionId);
  if (!sid) return;
  const mode = restoredAnswerModeBadge(sid, detail);
  if (!mode) return;
  const traceTurnId = restoredSessionModeTraceTurn(sid, detail);
  upsertSessionModeBadgeInList(sid, mode, traceTurnId);
}

function upsertSessionModeBadgeInList(sessionId, mode, traceTurnId) {
  const sid = normalizeSessionIdValue(sessionId);
  if (!sid || !mode) return;
  const sidKey = String(sid);
  const list = document.querySelector("[data-session-mode-list]");
  if (!list) return;
  let row = Array.from(list.querySelectorAll("[data-session-mode-row]"))
    .find((candidate) => candidate.dataset.sessionModeSessionId === sidKey);
  if (!row) {
    row = document.createElement("div");
    row.dataset.sessionModeRow = "true";
    row.dataset.sessionModeSessionId = sidKey;
    list.appendChild(row);
  }
  const modeText = answerModeRailText(mode) || mode;
  row.dataset.sessionMode = String(mode);
  row.dataset.sessionModeTraceTurnId = String(traceTurnId || "-");
  row.textContent = `${sessionTraceLabel(sid)} ${modeText} ${traceTurnId || "-"}`;
  persistSessionModeTraceTurn(sid, traceTurnId);
}

const sessionTraceLabel = (sessionId) => {
  const numeric = Number(sessionId);
  return Number.isSafeInteger(numeric) && numeric > 0 ? `session:${numeric}` : "session:pending";
};

const traceRailLabel = (traceTurn) => {
  const raw = String(traceTurn ?? "").trim();
  if (!raw) return "";
  if (/^(session|trace|request):/i.test(raw)) return raw;
  if (/^\d+$/.test(raw)) return `trace:${raw}`;
  return raw;
};

const nowMs = () => (typeof performance !== "undefined" && performance.now ? performance.now() : Date.now());

function syncFinalContext(tookMs, syncMode) {
  const parts = [];
  const safeMs = Math.max(0, Math.round(Number(tookMs) || 0));
  parts.push(`sync-took:${safeMs}ms`);
  if (safeMs > 2000) parts.push("slow-response");
  if (syncMode) parts.push(`mode:${answerModeRailText(syncMode) || syncMode}`);
  return parts.join(" ");
}

function setStatusRailValue(node, value) {
  if (!node) return;
  const safeValue = String(value ?? "");
  node.textContent = safeValue;
  node.title = safeValue;
  const pill = node.parentElement;
  const label = (pill?.querySelector("strong")?.textContent || "").trim().replace(/:\s*$/, "");
  if (pill && label) {
    const summary = `${label}: ${safeValue || "-"}`;
    pill.setAttribute("aria-label", summary);
    pill.title = summary;
  }
}

function hasStatusRailValue(value) {
  return value !== undefined && value !== null && String(value).trim() !== "";
}

function compactHealthRailDetail(detail) {
  const text = String(detail ?? "");
  const turnState = text.match(/\bstate:(responding|pending|stopped|attention)\b/i)?.[1]?.toLowerCase();
  const stateOf = (name) => {
    const match = text.match(new RegExp(`\\b${name}:(OK|WARN|UNKNOWN|SUPPORTING)\\b`, "i"));
    return match ? match[1].toUpperCase() : "WARN";
  };
  const live = stateOf("live");
  const proof = stateOf("proof");
  const external = stateOf("external");
  const core = stateOf("core");
  const ui = stateOf("ui");
  const model = stateOf("model");
  const answer = stateOf("answer");
  const liveText = turnState === 'responding'
    ? 'Responding'
    : turnState === 'pending'
      ? 'Response pending'
      : turnState === 'stopped'
        ? 'Response stopped'
        : turnState === 'attention'
          ? 'Needs attention'
          : model === 'UNKNOWN'
            ? 'Model proof unavailable'
            : live === 'OK'
              ? 'Live OK'
              : model === 'WARN'
                ? 'Model needs attention'
                : answer === 'WARN'
                  ? 'Answer needs attention'
                  : core === 'WARN'
                    ? 'Core needs attention'
                    : ui === 'WARN'
                      ? 'UI needs attention'
                      : 'Live needs attention';
  const proofText = proof === "OK" && external === "OK" ? "external proof OK" : proof === "SUPPORTING" || external === "SUPPORTING" ? "external proof supporting" : "external proof needed";
  return `${liveText} / ${proofText}`;
}

function setDebugCellAccessible(cell, title, status, detail, value) {
  if (!cell) return;
  const safeTitle = safeDebugCockpitDetail(title || "Status");
  const safeStatus = status === "OK" ? "OK" : "WARN";
  const safeDetail = safeDebugCockpitDetail(detail || "");
  const compactDetail = compactVisibleDebugDetail(safeTitle, detail || "");
  const rawValue = value == null || value === "" ? "" : safeDebugCockpitDetail(value);
  const safeValue = rawValue === safeStatus ? "" : rawValue;
  const ariaTail = [safeValue, compactDetail].filter(Boolean).join(" - ");
  const titleTail = [safeValue, safeDetail].filter(Boolean).join(" - ");
  const ariaSummary = ariaTail ? `${safeTitle}: ${safeStatus} - ${ariaTail}` : `${safeTitle}: ${safeStatus}`;
  const titleSummary = titleTail ? `${safeTitle}: ${safeStatus} - ${titleTail}` : `${safeTitle}: ${safeStatus}`;
  cell.setAttribute("aria-label", ariaSummary);
  cell.title = titleSummary;
}

function safeDebugCockpitDetail(value) {
  return String(value ?? "-")
    .replace(/https?:\/\/\S+/gi, '[url]')
    .replace(/[A-Za-z]:\\[^\s]+/g, '[path]')
    .replace(/sk-[A-Za-z0-9_-]{12,}/gi, '[secret]')
    .replace(/AIza[0-9A-Za-z_-]{12,}/g, '[secret]')
    .replace(/gsk_[A-Za-z0-9]{12,}/gi, '[secret]')
    .replace(/pcsk_[A-Za-z0-9_-]{12,}/gi, '[secret]')
    .replace(/authorization\s*[:=]\s*(?:Bearer\s+)?\S+/gi, 'auth-header=[redacted]')
    .replace(/\b(api[_-]?key|client[_-]?secret|owner[_-]?token|token)\s*[:=]\s*\S+/gi, '$1=[redacted]')
    .replace(/project[_-]?ref[:=][A-Za-z0-9_-]+/gi, 'project_ref:[redacted]')
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[email]')
    .slice(0, 180);
}

function providerStatusToken(value, fallback = "na") {
  const text = safeDebugCockpitDetail(value == null || value === "" ? fallback : value)
    .replace(/[^A-Za-z0-9_.:-]/g, "_")
    .slice(0, 40);
  return text || fallback;
}

function providerStatusSearchSummary(rows = []) {
  const safeRows = (Array.isArray(rows) ? rows : [])
    .filter((row) => row && typeof row === "object")
    .slice(0, 6);
  if (!safeRows.length) return "none";
  const providers = safeRows.map((row) => providerStatusToken(row.provider)).join("|");
  const routes = safeRows.map((row) => `${providerStatusToken(row.provider)}:${providerStatusToken(row.route)}`).join("|");
  const models = safeRows
    .filter((row) => !["not_applicable", "unavailable"].includes(String(row.model || "")))
    .map((row) => `${providerStatusToken(row.provider)}:${providerStatusToken(row.model)}`)
    .join("|") || "na";
  const enabled = safeRows.filter((row) => row.enabled === true).length;
  const credentials = safeRows.filter((row) => row.credentialPresent === true).length;
  const attempts = safeRows.reduce((sum, row) => sum + Math.max(0, Number(row.attemptCount) || 0), 0);
  const latencyMs = safeRows.reduce((maximum, row) => Math.max(maximum, Math.max(0, Number(row.latencyMs) || 0)), 0);
  const cacheHits = safeRows.filter((row) => row.cacheHit === true).length;
  const perProvider = (field, fallback) => safeRows
    .map(row => `${providerStatusToken(row.provider)}:${providerStatusToken(row[field], fallback)}`).join("|");
  const observedCode = perProvider("statusCode", "not_observed");
  const quota = perProvider("quotaDecision", "not_observed");
  const fallback = perProvider("fallbackReason", "not_observed");
  const error = perProvider("errorClass", "not_observed");
  return `p:${providers} r:${routes} m:${models} en:${enabled}/${safeRows.length} cred:${credentials}/${safeRows.length} try:${attempts} code:${providerStatusToken(observedCode)} ms:${latencyMs} cache:${cacheHits} q:${providerStatusToken(quota)} fb:${providerStatusToken(fallback)} err:${providerStatusToken(error)}`;
}

function safeHeartbeatText(value, fallback = "-") {
  return safeDebugCockpitDetail(value ?? fallback);
}

function compactProtectedOperatorDetail(value) {
  return safeDebugCockpitDetail(value)
    .replace(/admin_pipeline_not_exposed/g, "auth disabled test mode")
    .replace(/heartbeat_json_available/g, "heartbeat available");
}

function compactNextActionText(value) {
  return safeDebugCockpitDetail(String(value ?? "")
    .replace(/\bbrowser_use:/g, "browser:")
    .replace(/\bsupabase_apply:/g, "supabase:")
    .replace(/\bbrowser_use\//g, "browser:")
    .replace(/\bsupabase_apply\//g, "supabase:")
    .replace(/\/evidence_needed/g, "")
    .replace(/open_public_80_443_then_rerun_browser_public_domain_ui_smoke/g, "public browser proof")
    .replace(/rerun_browser_public_domain_ui_smoke/g, "public browser proof")
    .replace(/run_browser_ui_smoke/g, "browser UI proof")
    .replace(/run_readonly_supabase_context_probe/g, "Supabase readonly probe")
    .replace(/set_SUPABASE_PROJECT_REF/g, "set project ref")
    .replace(/complete_supabase_mcp_oauth_flow/g, "complete OAuth")
    .replace(/install_supabase_cli_or_use_mcp_execute_sql/g, "CLI or MCP SQL")
    .replace(/run_supabase_cli_help_discovery/g, "CLI help")
    .replace(/computer_use_supporting_evidence_current/g, "Computer proof current")
    .replace(/source:browser_use/g, "source:browser")
    .replace(/decision:evidence_needed/g, "decision:evidence needed"));
}

function compactExternalEvidenceDetail(name, detail) {
  if (!["supabase", "browser", "computer", "harmony"].includes(name)) {
    return safeDebugCockpitDetail(detail);
  }
  return compactNextActionText(detail)
    .replace(/supabase_project_scope_or_auth_unverified/g, "project/auth unverified")
    .replace(/Supabase readonly probe/g, "readonly probe")
    .replace(/browser_ui_smoke_stale/g, "browser proof stale")
    .replace(/computer_use_smoke_stale/g, "computer proof stale")
    .replace(/run_browser_local_ui_smoke/g, "refresh browser proof")
    .replace(/run_computer_use_lightweight_smoke/g, "refresh computer proof")
    .replace(/countOnly:true/g, "count-only:true")
    .replace(/browser_ui_smoke_not_persisted_to_runtime/g, "runtime proof pending")
    .replace(/Computer proof current/g, "proof current")
    .replace(/dom_ready/g, "dom ready")
    .replace(/json_rendered/g, "json rendered");
}

function compactModelDebugDetail(detail) {
  const safe = safeDebugCockpitDetail(detail);
  if (/model_unavailable|local_safe|fallback_local/i.test(safe)) {
    const lengthMatch = safe.match(/\blen:(\d+)/i);
    const lengthText = lengthMatch ? ` len:${lengthMatch[1]}` : "";
    const reason = /model_unavailable/i.test(safe)
      ? "model_unavailable"
      : /fallback_local/i.test(safe)
        ? "fallback_local"
        : "local_safe";
    const modelAnswerMatch = safe.match(/\bmodel:(OK|WARN)\s+answer:(OK|WARN)\b/i);
    const modelAnswerText = modelAnswerMatch
      ? `model:${modelAnswerMatch[1].toUpperCase()} answer:${modelAnswerMatch[2].toUpperCase()} `
      : "";
    return `${modelAnswerText}local safe fallback reason:${reason}${lengthText} next:inspect/start local model route`;
  }
  return safe
    .replace(/model_unavailable/g, "model unavailable")
    .replace(/local_safe/g, "local safe")
    .replace(/fallback_local/g, "fallback local")
    .replace(/inspect_model_route_or_start_local_llm/g, "inspect/start local model route");
}

function chatUsageSummary(chatUsage = {}) {
  if (chatUsage?.schemaVersion !== "awx.chat-usage.v1"
      || chatUsage?.captureEnabled !== true
      || chatUsage?.observed !== true) {
    return "usage:unknown";
  }
  const count = (value) => {
    const numeric = Number(value);
    return Number.isFinite(numeric) && numeric >= 0 ? Math.trunc(numeric) : 0;
  };
  const compactCount = (value) => {
    const numeric = count(value);
    if (numeric < 1_000_000) return String(numeric);
    return numeric.toExponential(2).replace("e+", "e");
  };
  const model = chatUsage.modelInvocations || {};
  const expansion = chatUsage.answerExpansion || {};
  const successfulCapState = String(model.lastSuccessfulCapState || "").toLowerCase();
  const numericCap = Number(model.lastSuccessfulConfiguredCap);
  const cap = successfulCapState === "omitted"
    ? "omitted"
    : successfulCapState === "provider_default_unknown"
      ? "unknown"
      : Number.isFinite(numericCap) && numericCap > 0 ? Math.trunc(numericCap) : "unknown";
  const rejected = [
    expansion.rejectedNumeric,
    expansion.rejectedNoEvidence,
    expansion.rejectedTooShort,
    expansion.rejectedEmpty
  ].reduce((sum, value) => sum + count(value), 0);
  const rejectionRate = expansion.rejectionRate == null || expansion.rejectionRate === ""
    ? Number.NaN
    : Number(expansion.rejectionRate);
  const rejectionRateLabel = Number.isFinite(rejectionRate)
    ? `${Math.round(Math.max(0, Math.min(1, rejectionRate)) * 100)}%`
    : "n/a";
  const providerTokens = count(model.providerUsageObservedAttemptCount) > 0
      && model.providerOutputTokensComplete === true
    ? compactCount(model.providerOutputTokens)
    : "n/a";
  const rejectedProviderTokens = expansion.rejectedProviderOutputTokensComplete === true
    ? compactCount(expansion.rejectedProviderOutputTokens)
    : "n/a";
  const capStateCounts = model.capStateCounts || {};
  const providerUnknownAttempts = capStateCounts.provider_default_unknown == null
    ? count(model.unknownAttemptCount)
    : count(capStateCounts.provider_default_unknown);
  const rejectionRateDenominator = count(expansion.rejectionRateDenominator);
  return safeDebugCockpitDetail([
    "usage(proc)",
    `calls:${compactCount(model.attempts)}/${compactCount(model.responseReceived)}`,
    `cap:${cap}`,
    `unknown:${compactCount(providerUnknownAttempts)}`,
    `tokens:${providerTokens}`,
    `expand:${compactCount(expansion.invocations)}`,
    `accepted:${compactCount(expansion.accepted)}`,
    `rejected:${compactCount(rejected)}`,
    `rejRate:${rejectionRateLabel}${Number.isFinite(rejectionRate) ? `/${compactCount(rejectionRateDenominator)}` : ""}`,
    `skip:${compactCount(expansion.skippedBeforeModel)}`,
    `rejectTok:${rejectedProviderTokens}`
  ].join(" "));
}

function setCoreStatus(status, detail) {
  if (dom.coreStatusRail) dom.coreStatusRail.dataset.coreStatus = status;
  setStatusRailValue(dom.streamStatus, answerModeRailText(detail) || detail || status);
}

function setCurrentModelBadge(model) {
  const value = String(model || "-").trim() || "-";
  if (!dom.currentModelBadge) return;
  dom.currentModelBadge.textContent = value;
  dom.currentModelBadge.title = `Current model: ${value}`;
  dom.currentModelBadge.setAttribute("aria-label", `Current model: ${value}`);
}

function syncControlStatus(options = {}) {
  const selectedModel = dom.modelSelect?.value || "-";
  setStatusRailValue(dom.modelStatus, `요청 ${selectedModel}`);
  setCurrentModelBadge(selectedModel);
  setStatusRailValue(dom.searchStatus, `요청 ${searchModeRailValue(dom.searchModeSelect?.value)}`);
  const ragState = dom.useRag?.checked === false ? "OFF" : "ON";
  setStatusRailValue(dom.ragStatus, `요청 ${ragState}`);
  if (dom.responseSettingsSummary) {
    const executionLabel = {AUTO:"자동", STRIKE:"빠르게", SELF_ASK:"추가 검색"}[dom.executionMode?.value || "AUTO"];
    dom.responseSettingsSummary.textContent = `요청 모델 ${selectedModel} | 선택 방식 ${dom.modelSelectionMode?.value || "strict"} | 전략 ${executionLabel} | 검색 ${searchModeRailValue(dom.searchModeSelect?.value)} | RAG ${ragState}`;
  }
  if (dom.useRag) {
    const label = `RAG: ${ragState}`;
    dom.useRag.setAttribute("aria-label", "Use RAG context");
    dom.useRag.title = label;
  }
  const applyingDefault = dom.modelSelect?.dataset.applyingDefault === "true";
  if (applyingDefault) localControlOverrideActive = false;
  if (options.persist !== false || applyingDefault) persistControlSettings(applyingDefault ? "catalog-default" : options.source || "");
}

function handleControlChange() {
  const ready = controlHydrationPhase === ControlHydrationPhase.READY;
  if (ready) {
    localControlOverrideActive = true;
  }
  syncControlStatus({ source: "user", persist: ready });
}

function syncSendButtonState(options = {}) {
  syncChatAccessNotice();
  if (!dom.sendBtn) return;
  const busy = options.busy ?? dom.messageInput?.disabled === true;
  const hasDraft = Boolean(dom.messageInput?.value?.trim());
  dom.sendBtn.disabled = busy || pendingStopBeforeToken?.unresolved === true || !hasDraft || !chatModelCatalogReady || chatAccessState === "login_required" || chatAccessState === "forbidden";
}

function hasComposerDraftRailToRefresh() {
  const coreStatus = String(dom.coreStatusRail?.dataset?.coreStatus || "");
  const streamStatus = String(dom.streamStatus?.textContent || "");
  const traceStatus = String(dom.traceStatus?.textContent || "");
  return /^(?:done|stopped|fallback|error)$/i.test(coreStatus)
    || /(?:server cancel|local stop|local detach|cancelled|image plugin disabled|auth disabled test mode|fallback|new chat)/i.test(`${streamStatus} ${traceStatus}`);
}

function markComposerDraftReady() {
  if (!dom.messageInput?.value?.trim() || dom.messageInput.disabled || !hasComposerDraftRailToRefresh()) return;
  setCoreStatus("idle", "draft");
  updateOrchestrationSignalBar({
    streamStatus: "draft",
    traceTurn: "ready"
  });
}

function clearEmptyComposerDraftRail() {
  if (dom.messageInput?.value?.trim()) return;
  if (String(dom.coreStatusRail?.dataset?.coreStatus || "") !== "idle") return;
  if (String(dom.streamStatus?.textContent || "") !== "draft") return;
  setCoreStatus("idle", "idle");
  setStatusRailValue(dom.traceStatus, "ready");
}

function syncComposerDraftState() {
  syncSendButtonState();
  clearEmptyComposerDraftRail();
  markComposerDraftReady();
}

function setComposerBusy(isBusy) {
  dom.chatMessages?.setAttribute("aria-busy", isBusy ? "true" : "false");
  if (dom.sendBtn) {
    const sendLabel = isBusy ? "질문 전송 중" : "질문 전송";
    dom.sendBtn.setAttribute("aria-label", sendLabel);
    dom.sendBtn.title = sendLabel;
    dom.sendBtn.textContent = isBusy ? "…" : "↑";
    syncSendButtonState({ busy: isBusy });
  }
  if (dom.stopBtn) {
    const stopLabel = isBusy ? "답변 생성 중지" : "답변 중지";
    dom.stopBtn.setAttribute("aria-label", stopLabel);
    dom.stopBtn.title = stopLabel;
    dom.stopBtn.textContent = "중지";
    dom.stopBtn.hidden = !isBusy;
    dom.stopBtn.style.display = isBusy ? "" : "none";
  }
}

function scrollChatToBottom() {
  if (!dom.chatMessages) return;
  dom.chatMessages.scrollTop = dom.chatMessages.scrollHeight;
}

function scrollChatToLatestAssistant() {
  if (!dom.chatMessages) return;
  const children = Array.from(dom.chatMessages.children || []);
  const latestAssistant = children.reverse().find((child) => child?.dataset?.speaker === "assistant");
  if (!latestAssistant || !Number.isFinite(latestAssistant.offsetTop)) {
    scrollChatToBottom();
    return;
  }
  const maxScroll = Math.max(0, (dom.chatMessages.scrollHeight || 0) - (dom.chatMessages.clientHeight || 0));
  const containerOffset = Number.isFinite(dom.chatMessages.offsetTop) ? dom.chatMessages.offsetTop : 0;
  const targetTop = Math.max(0, latestAssistant.offsetTop - containerOffset - 8);
  dom.chatMessages.scrollTop = Math.max(0, Math.min(targetTop, maxScroll || targetTop));
}

function messageAriaLabel(role, text) {
  const speaker = role === "user" ? "User" : role === "assistant" ? "Assistant" : "Message";
  const safeText = safeDebugCockpitDetail(text || "");
  if (role === "assistant" && !safeText) return "Assistant response pending";
  return safeText ? `${speaker}: ${safeText}` : speaker;
}

function rememberMessageAriaSource(node, text) {
  if (!node?.dataset) return String(text || "");
  const source = String(text || "");
  node.dataset.ariaText = source.slice(0, 240);
  return node.dataset.ariaText;
}

function appendMessageAriaSource(node, text) {
  if (!node?.dataset) return String(text || "");
  const prior = node.dataset.ariaText || "";
  node.dataset.ariaText = `${prior}${String(text || "")}`.slice(0, 240);
  return node.dataset.ariaText;
}

function looksLikeMojibake(value) {
  const text = String(value || "");
  return /[\u00c2\u00c3\ufffd]{2,}|(?:\u00ec|\u00ed|\u00eb|\u00ea)[\x80-\xBF]/.test(text);
}

function renderAnswerIntegrityWarning(target, reason = "output.integrity") {
  const warning = document.createElement("small");
  warning.className = "answer-integrity-warning";
  warning.dataset.reason = reason;
  warning.textContent = "output integrity warning";
  markChatDiagnosticNode(warning);
  (target?.parentElement || target)?.appendChild(warning);
}

function markChatDiagnosticNode(node) {
  if (!node) return node;
  node.dataset.liveRegion = "excluded";
  node.setAttribute("aria-hidden", "true");
  node.setAttribute("role", "presentation");
  return node;
}

function defaultMessageState(role, text, state) {
  if (state) return state;
  const value = String(text || "").trim();
  if (role === "assistant" && value === "Response stopped") return "stopped";
  if (role === "assistant" && !value) return "pending";
  return "ready";
}

function setMessageContent(node, role, text, state) {
  if (!node) return;
  clearAssistantPendingPlaceholder(node);
  const content = String(text ?? "");
  const cleanText = role === "assistant" ? stripAssistantReasoningBlocks(text) : content;
  node.textContent = cleanText;
  if (role === "assistant") delete node.dataset.pendingPlaceholder;
  node.setAttribute("data-testid", "chat-message");
  node.dataset.messageRole = role;
  node.dataset.speaker = role;
  node.dataset.state = defaultMessageState(role, cleanText, state);
  if (role === "assistant" && node.dataset.state !== "pending" && node.dataset.waitMs != null) {
    delete node.dataset.waitMs;
    if (String(node.title || "").startsWith("Response still pending (")) node.title = "";
  }
  const ariaText = rememberMessageAriaSource(node, cleanText);
  node.setAttribute("role", "article");
  node.setAttribute("aria-label", messageAriaLabel(role, ariaText));
  if (role === "assistant") {
    reflectAssistantModelFallback(cleanText);
    if (looksLikeMojibake(cleanText)) {
      renderAnswerIntegrityWarning(node, "output.integrity");
    }
  }
}

function isAssistantStreamStopped(node) {
  if (!node) return false;
  return node.dataset?.streamCancelState === "stopped"
    || (node.dataset?.speaker === "assistant" && node.dataset?.state === "stopped");
}

function markAssistantStreamStopped(node) {
  if (!node) return false;
  clearSelectionEntropyTrace(node);
  setMessageContent(node, "assistant", "Response stopped", "stopped");
  node.dataset.streamCancelState = "stopped";
  try { window.ChatEvidenceGraph?.invalidate(node, "cancelled"); } catch { /* Preserve the stop latch. */ }
  recordChatTransitionDebug({
    kind: "terminal",
    to: "stopped",
    reasonCode: "stream-stopped",
    terminalLatch: true
  });
  return true;
}

function isLocalSafeFallbackAssistantText(text) {
  const value = String(text || "");
  if (/model_unavailable|fallback[_ -]?local|local[_ -]?safe|local safe response|model response.*unavailable|\uAE30\uBCF8\s*\uBAA8\uB378\s*\uC751\uB2F5.*\uC548\uC815\uC801\uC73C\uB85C\s*\uC0DD\uC131\uB418\uC9C0\s*\uC54A\uC544|\uB85C\uCEEC\s*\uC548\uC804\s*\uC751\uB2F5|\u6E72\uACEC\uB0AF.*\uF98F\u2464\uB73D.*\u6FD\uC49E\uBE84|\u6E72\uACEC\uB0AF.*\uF98F\u2464\uB73D.*\uB369\uC823/i.test(value)) return true;
  return /기본 모델 응답.*안정적으로 생성되지 않아|로컬 안전 응답|local safe response|model response.*unavailable/i.test(value);
}

function reflectAssistantModelFallback(text) {
  if (!isLocalSafeFallbackAssistantText(text)) return;
  const length = String(text || "").length;
  const operatorDetail = "llm:model_unavailable next:inspect_model_route_or_start_local_llm score:100";
  lastAssistantModelFallback = {
    modelDetail: `route:fallback delivery:local_safe wait:none hits:1 len:${length} ${operatorDetail}`,
    answerDetail: `mode:fallback_local guard:no fallback:local_safe docs:0 ${operatorDetail}`,
    matrixDetail: `model:WARN answer:WARN live:WARN ${operatorDetail}`
  };
  applyAssistantModelFallbackDebug();
}

function applyAssistantModelFallbackDebug() {
  if (!lastAssistantModelFallback) return;
  setDebugHeartbeatField("model", "WARN", lastAssistantModelFallback.modelDetail);
  setDebugHeartbeatField("answer", "WARN", `${lastChatUsageDetail} ${lastAssistantModelFallback.answerDetail}`);
  setDebugMatrixCell("model-answer", "WARN", "Model/Answer", lastAssistantModelFallback.matrixDetail);
}

function clearAssistantModelFallbackDebugOnSuccessfulAnswer(partial = {}, liveStatus = 'OK') {
  if (!successfulAnswerMode(partial.streamStatus) && !successfulAnswerMode(partial.answerMode)) return false;
  const answerMode = normalizedAnswerMode(partial.answerMode || partial.streamStatus) || "CHAT";
  const modelName = partial.model || dom.modelSelect?.value || "current";
  const recovered = Boolean(observedRecoveryCode(partial.recoveryState || currentTurnRecoveryState));
  const modelDetail = `model:${modelName} mode:${answerMode} fallback:${recovered ? "local_device_recovered" : "cleared"}`;
  const answerDetail = `mode:${answerMode} guard:no fallback:${recovered ? "local_device" : "none"} docs:n/a`;
  const matrixDetail = `model:OK answer:OK live:${liveStatus || 'OK'}${recovered ? " recover:observed" : ""}`;
  lastAssistantModelFallback = null;
  setDebugHeartbeatField("model", "OK", modelDetail);
  setDebugHeartbeatField("answer", "OK", `${lastChatUsageDetail} ${answerDetail}`);
  setDebugMatrixCell("model-answer", "OK", "Model/Answer", matrixDetail);
  return true;
}

function currentHeartbeatStatus(name, fallback = "OK") {
  const card = dom.debugHeartbeatBar?.querySelector(`[data-debug-heartbeat-field="${name}"]`);
  return statusOf({ status: card?.dataset?.status || fallback }, fallback);
}

function applyDirectLiteralRetrievalBypassDebug(partial = {}) {
  if (!isDirectLiteralMode(partial.answerMode || partial.streamStatus, partial.model || partial.modelBadge)) return false;
  const detail = "bypassed:DIRECT_LITERAL source:live_final";
  const traceStatus = currentHeartbeatStatus("trace", "OK");
  const searchTraceStatus = traceStatus === "WARN" ? "WARN" : "OK";
  const searchTraceDetail = `search:OK trace:${traceStatus} ${detail}`;
  setDebugHeartbeatField("search", "OK", detail);
  setDebugHeartbeatField("ladder", "OK", detail);
  setDebugMatrixCell("search-trace", searchTraceStatus, "Search/Trace", searchTraceDetail);
  setDebugCockpitCell("search", searchTraceStatus, "Search", searchTraceDetail);
  setDebugFlowStep("search", searchTraceStatus, "Search", "OK", searchTraceDetail);
  return true;
}

function clearRetrievalWarmupDebugOnSuccessfulAnswer(partial = {}) {
  const answerMode = normalizedAnswerMode(partial.answerMode || partial.streamStatus);
  if (!successfulAnswerMode(answerMode)) return false;
  if (isDirectLiteralMode(answerMode, partial.model || partial.modelBadge)) return false;
  const detail = `answer:${answerMode} source:live_final`;
  const traceStatus = currentHeartbeatStatus("trace", "OK");
  const searchTraceStatus = traceStatus === "WARN" ? "WARN" : "OK";
  const searchTraceDetail = `search:OK trace:${traceStatus} ${detail}`;
  setDebugHeartbeatField("search", "OK", detail);
  setDebugHeartbeatField("ladder", "OK", detail);
  setDebugMatrixCell("search-trace", searchTraceStatus, "Search/Trace", searchTraceDetail);
  setDebugCockpitCell("search", searchTraceStatus, "Search", searchTraceDetail);
  setDebugFlowStep("search", searchTraceStatus, "Search", "OK", searchTraceDetail);
  return true;
}

function rememberVisibleTurnEvidence(answerMode, evidenceCount) {
  const normalized = normalizedAnswerMode(answerMode);
  const count = Number(evidenceCount || 0);
  if (!normalized || !successfulAnswerMode(normalized) || count <= 0) return false;
  state.latestVisibleTurnEvidence = {
    answerMode: normalized,
    evidenceCount: count,
    updatedAt: Date.now()
  };
  return true;
}

function visibleTurnEvidenceDebugSummary(answerOutput = {}) {
  const latest = state.latestVisibleTurnEvidence || {};
  const answerMode = normalizedAnswerMode(latest.answerMode);
  const evidenceCount = Number(latest.evidenceCount || 0);
  if (!answerMode || !successfulAnswerMode(answerMode) || evidenceCount <= 0) return null;
  const heartbeatMode = normalizedAnswerMode(answerOutput.answerMode || answerOutput.mode);
  const heartbeatDocs = Number(answerOutput.evidenceDocs || 0);
  if (heartbeatMode && heartbeatMode !== answerMode && heartbeatDocs > 0) return null;
  return { answerMode, evidenceCount };
}

function applyVisibleTurnEvidenceDebug(data = {}) {
  const summary = visibleTurnEvidenceDebugSummary(data.answerOutput || {});
  if (!summary) return false;
  const detail = `answer:${summary.answerMode} evidence:${summary.evidenceCount} source:visible_turn`;
  const answerDetail = `mode:${summary.answerMode} guard:no fallback:none docs:${summary.evidenceCount} source:visible_turn`;
  const traceStatus = currentHeartbeatStatus("trace", "OK");
  const searchTraceStatus = traceStatus === "WARN" ? "WARN" : "OK";
  const searchTraceDetail = `search:OK trace:${traceStatus} ${detail}`;
  setDebugHeartbeatField("search", "OK", detail);
  setDebugHeartbeatField("ladder", "OK", detail);
  setDebugHeartbeatField("answer", "OK", `${lastChatUsageDetail} ${answerDetail}`);
  setDebugMatrixCell("search-trace", searchTraceStatus, "Search/Trace", searchTraceDetail);
  setDebugCockpitCell("search", searchTraceStatus, "Search", searchTraceDetail);
  setDebugFlowStep("search", searchTraceStatus, "Search", "OK", searchTraceDetail);
  return true;
}

function isIdleRetrievalWarmupState(answerOutput = {}, modelRuntime = {}, providerRuntime = {}, failSoftLadder = {}, visibleAnswerMode = "", heartbeatAnswerMode = "") {
  const visibleMode = String(visibleAnswerMode || "").trim().toLowerCase();
  const explicitMode = normalizedAnswerMode(answerOutput.answerMode || answerOutput.mode || heartbeatAnswerMode || "");
  const noAnswerAttempt = !explicitMode
    && !answerOutput.emptyAnswerGuardTriggered
    && !answerOutput.blankBaseFallback
    && !answerOutput.emptyAnswerFallback
    && Number(answerOutput.evidenceDocs || 0) === 0;
  const idleRail = !visibleMode || /^(idle|ready|none|-)$/.test(visibleMode);
  const noModelAttempt = !modelRuntime.route
    && !modelRuntime.finalDeliveryExpected
    && !modelRuntime.fastBailTimeout
    && !modelRuntime.waiting
    && !modelRuntime.localLlmOperatorAction;
  const noProviderFailure = Number(providerRuntime.awaitTimeoutCount || 0) === 0
    && Number(providerRuntime.cancelSuppressedCount || 0) === 0;
  const noLadderOutput = Number(failSoftLadder.outCount || 0) === 0
    && Number(failSoftLadder.tracePoolSize || 0) === 0
    && !failSoftLadder.rescueMergeUsed
    && !failSoftLadder.starvationFallbackTrigger;
  const ladderIdleReason = failSoftLadder.poolSafeEmpty === true
    || String(failSoftLadder.reason || "").toLowerCase() === "trace_not_observed";
  return noAnswerAttempt && idleRail && noModelAttempt && noProviderFailure && noLadderOutput && ladderIdleReason;
}

function assistantWaitLabel(node) {
  const completed = {
    ready: '검색 완료', empty: '검색 종료 · 자료 미확보',
    partial: '검색 종료 · 일부 자료만 확보', skipped: '검색 생략'
  }[node.dataset.waitSearchOutcome];
  const phase = node.dataset.waitPhase;
  const current = phase === 'generating' ? '답변 작성 중'
    : phase === 'searching' ? '검색 중'
    : phase === 'attaching' ? '이전 응답에 다시 연결 중' : 'Planning';
  return completed ? `${completed} · ${current}` : current;
}

function markAssistantClientWait(node, elapsedMs) {
  if (!node || isAssistantStreamStopped(node) || node.dataset.waitStopped === 'true') return;
  if (node.dataset.state && node.dataset.state !== 'pending') return;
  const safeElapsedMs = Math.max(0, Math.round(Number(elapsedMs) || 0));
  let row = node.querySelector?.('[data-wait-progress]');
  if (!row) {
    if (node.dataset.pendingPlaceholder === 'client-wait') node.textContent = '';
    else if (String(node.textContent || '').trim()) return;
    row = document.createElement('div');
    row.className = 'chat-wait-progress'; row.dataset.waitProgress = 'true';
    const spinner = document.createElement('span');
    spinner.className = 'chat-wait-spinner'; spinner.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span'); label.dataset.waitLabel = 'true';
    label.setAttribute('role', 'status'); label.setAttribute('aria-live', 'polite');
    label.setAttribute('aria-atomic', 'true');
    const elapsed = document.createElement('span'); elapsed.dataset.waitElapsed = 'true';
    elapsed.className = 'chat-wait-elapsed'; elapsed.setAttribute('aria-hidden', 'true');
    elapsed.title = '클라이언트에서 응답을 기다린 시간';
    row.append(spinner, label, elapsed); node.appendChild(row);
    node.dataset.waitProgress = 'true'; node.dataset.pendingPlaceholder = 'client-wait';
  }
  node.dataset.speaker = 'assistant'; node.dataset.state = 'pending';
  node.dataset.waitMs = String(safeElapsedMs);
  const label = row.querySelector('[data-wait-label]');
  const text = assistantWaitLabel(node);
  if (label.textContent !== text) label.textContent = text;
  const elapsed = row.querySelector('[data-wait-elapsed]');
  const elapsedText = `대기 ${Math.floor(safeElapsedMs / 1000)}초`;
  if (elapsed.textContent !== elapsedText) elapsed.textContent = elapsedText;
  node.setAttribute('role', 'article');
  node.setAttribute('aria-label', text);
  node.title = safeElapsedMs >= STREAM_STALE_WAIT_MS
    ? '응답을 기다리고 있습니다. 중단할 수 있습니다.' : '';
}

function updateAssistantWaitProgress(node, signal = {}) {
  if (!node || node.dataset.state !== 'pending' || node.dataset.waitStopped === 'true'
      || isAssistantStreamStopped(node)) return;
  const code = String(signal.code || '').toLowerCase();
  if (code === 'answer_generation_started') node.dataset.waitPhase = 'generating';
  else if (code === 'web_search_running' && !node.dataset.waitSearchOutcome
      && node.dataset.waitPhase !== 'generating') node.dataset.waitPhase = 'searching';
  else if (/^retrieval_completed_(ready|empty|partial|skipped)$/.test(code)) {
    if (!node.dataset.waitSearchOutcome) node.dataset.waitSearchOutcome = code.slice('retrieval_completed_'.length);
    if (node.dataset.waitPhase !== 'generating') node.dataset.waitPhase = 'preparing';
  }
  markAssistantClientWait(node, node.dataset.waitMs || 0);
}

function clearAssistantPendingPlaceholder(node) {
  if (!node) return;
  node.querySelector?.('[data-wait-progress]')?.remove();
  if (node.dataset.pendingPlaceholder === 'client-wait' && !node.dataset.waitProgress) node.textContent = '';
  delete node.dataset.pendingPlaceholder; delete node.dataset.waitProgress;
  delete node.dataset.waitMs; delete node.dataset.waitPhase; delete node.dataset.waitSearchOutcome;
  node.title = '';
}


function clearActiveStreamHeartbeat(timerId = activeStreamHeartbeatTimer) {
  if (timerId != null && typeof window.clearInterval === "function") {
    window.clearInterval(timerId);
  }
  if (activeStreamHeartbeatTimer === timerId) {
    activeStreamHeartbeatTimer = null;
  }
}

function appendMessage(role, text) {
  const node = document.createElement("div");
  node.className = `message ${role}`;
  setMessageContent(node, role, text);
  if (role === "assistant" && node.dataset.state === "pending") markAssistantClientWait(node, 0);
  dom.chatMessages.appendChild(node);
  scrollChatToBottom();
  return node;
}

function sessionIdFromPayload(payload) {
  const raw = payload?.sessionId ?? payload?.session_id;
  return normalizeSessionIdValue(raw);
}

function stripAssistantReasoningBlocks(text) {
  return filterAssistantReasoningChunk(text, null, true).trim();
}

function filterAssistantReasoningChunk(chunk, assistant, final = false) {
  // A resumed connection retains its bubble; a new answer gets independent state.
  let filter = assistant && assistantReasoningStates.get(assistant);
  if (!filter) {
    filter = { pending: "", tags: [] };
    if (assistant) assistantReasoningStates.set(assistant, filter);
  }
  const text = filter.pending + String(chunk || "");
  filter.pending = "";
  const markers = ["<think>", "<reasoning>", "</think>", "</reasoning>"];
  let visible = "";
  for (let index = 0; index < text.length;) {
    if (text[index] === "<") {
      const marker = markers.find(value => text.slice(index, index + value.length).toLowerCase() === value);
      if (marker) {
        const closing = marker[1] === "/";
        const tag = marker.slice(closing ? 2 : 1, -1);
        if (!closing) filter.tags.push(tag);
        else if (filter.tags[filter.tags.length - 1] === tag) filter.tags.pop();
        index += marker.length;
        continue;
      }
      if (!final && text.length - index < 13 && markers.some(value => value.startsWith(text.slice(index).toLowerCase()))) {
        filter.pending = text.slice(index);
        break;
      }
    }
    if (filter.tags.length === 0) visible += text[index];
    index += 1;
  }
  if (final && assistant) assistantReasoningStates.delete(assistant);
  return visible;
}

function appendTextWithBreaks(bubble, text) {
  if (!bubble) return;
  if (isAssistantStreamStopped(bubble)) return;
  const safeText = String(text || "");
  if (!safeText) return;
  safeText.split(/\r?\n/).forEach((part, index) => {
    if (index > 0) bubble.appendChild(document.createElement("br"));
    bubble.appendChild(document.createTextNode(part));
  });
  const ariaText = appendMessageAriaSource(bubble, safeText);
  bubble.dataset.speaker = "assistant";
  bubble.dataset.state = "ready";
  delete bubble.dataset.waitMs;
  if (String(bubble.title || "").startsWith("Response still pending (")) bubble.title = "";
  bubble.setAttribute("role", "article");
  bubble.setAttribute("aria-label", messageAriaLabel("assistant", ariaText));
  reflectAssistantModelFallback(ariaText || bubble.textContent || "");
  if (looksLikeMojibake(bubble.textContent || "")) {
    renderAnswerIntegrityWarning(bubble, "output.integrity");
  }
}

function replaceWithSanitizedHtml(bubble, html) {
  const cleanHtml = stripAssistantReasoningBlocks(html);
  return window.AwxChatTraceUi?.upsert(bubble, { html: cleanHtml }) || null;
}

function appendSanitizedTraceBlock(bubble, rawHtml) {
  const cleanHtml = stripAssistantReasoningBlocks(rawHtml);
  replaceWithSanitizedHtml(bubble, cleanHtml);
}

function renderTraceHtml(payload = {}, bubble) {
  const holder = bubble;
  replaceWithSanitizedHtml(holder, payload.html || "");
}

function selectionEntropySignal(payload = {}) {
  const raw = payload && typeof payload.selectionEntropySignal === "object"
    && payload.selectionEntropySignal !== null
    && !Array.isArray(payload.selectionEntropySignal)
    ? payload.selectionEntropySignal
    : null;
  if (!raw) return null;

  const modes = new Set(["standard", "replay"]);
  const coherenceValues = new Set([
    "not_requested", "accepted", "matched", "partial", "failed", "forbidden", "invalid"
  ]);
  const reasonValues = new Set([
    "",
    "selection_entropy_replay_forbidden",
    "selection_entropy_replay_invalid",
    "selection_entropy_algorithm_unsupported",
    "selection_entropy_replay_init_failed",
    "selection_entropy_context_missing",
    "selection_entropy_coordinate_invalid",
    "selection_entropy_stable_key_missing",
    "selection_entropy_derivation_invalid",
    "selection_entropy_candidate_drift",
    "selection_entropy_decision_cap_reached"
  ]);
  const count = (value) => Number.isInteger(value) && value >= 0 && value <= 10000
    ? value
    : null;
  const mode = modes.has(raw.mode) ? raw.mode : null;
  const coherenceStatus = coherenceValues.has(raw.coherenceStatus)
    ? raw.coherenceStatus
    : null;
  const replayReferenceSource = raw.replayReference == null ? "" : raw.replayReference;
  const replayReference = typeof replayReferenceSource === "string"
    && /^[a-f0-9]{12}$/.test(replayReferenceSource)
    ? replayReferenceSource
    : "";
  const decisionDigest = typeof raw.decisionDigest === "string"
    && /^[a-f0-9]{64}$/.test(raw.decisionDigest)
    ? raw.decisionDigest
    : "";
  const decisionCount = count(raw.decisionCount);
  const drawCount = count(raw.drawCount);
  const stableTieBreakCount = count(raw.stableTieBreakCount);
  const candidateDriftCount = count(raw.candidateDriftCount);
  const routerDrawCount = count(raw.routerDrawCount);
  const strategyDrawCount = count(raw.strategyDrawCount);
  const ensembleDrawCount = count(raw.ensembleDrawCount);
  const reasonCode = typeof raw.reasonCode === "string" && reasonValues.has(raw.reasonCode)
    ? raw.reasonCode
    : null;
  const validCounts = decisionCount !== null
    && drawCount !== null
    && stableTieBreakCount !== null
    && candidateDriftCount !== null
    && routerDrawCount !== null
    && strategyDrawCount !== null
    && ensembleDrawCount !== null;
  const standardValid = mode !== "standard" || (
    raw.replayAccepted === false
    && coherenceStatus === "not_requested"
    && replayReferenceSource === ""
    && reasonCode === ""
  );
  const replayValid = mode !== "replay" || (
    raw.replayAccepted === true
    && coherenceStatus !== "not_requested"
    && replayReference.length === 12
  );
  if (raw.schema !== "awx.selection-entropy.v1"
      || !mode
      || !coherenceStatus
      || raw.algorithmVersion !== "selection-entropy-v1"
      || typeof raw.replayAccepted !== "boolean"
      || typeof raw.completionOrderDeterministic !== "boolean"
      || !decisionDigest
      || reasonCode === null
      || !validCounts
      || !standardValid
      || !replayValid) {
    return null;
  }
  return Object.freeze({
    mode,
    replayAccepted: raw.replayAccepted,
    coherenceStatus,
    replayReference,
    algorithmVersion: raw.algorithmVersion,
    decisionDigest,
    decisionCount,
    drawCount,
    stableTieBreakCount,
    candidateDriftCount,
    routerDrawCount,
    strategyDrawCount,
    ensembleDrawCount,
    completionOrderDeterministic: raw.completionOrderDeterministic,
    reasonCode
  });
}

function selectionEntropyReasonLabel(reasonCode) {
  switch (reasonCode) {
    case "selection_entropy_replay_forbidden": return "Replay forbidden";
    case "selection_entropy_replay_invalid": return "Replay invalid";
    case "selection_entropy_algorithm_unsupported": return "Algorithm unsupported";
    case "selection_entropy_replay_init_failed": return "Replay initialization failed";
    case "selection_entropy_context_missing": return "Context missing";
    case "selection_entropy_coordinate_invalid": return "Coordinate invalid";
    case "selection_entropy_stable_key_missing": return "Stable key missing";
    case "selection_entropy_derivation_invalid": return "Derivation invalid";
    case "selection_entropy_candidate_drift": return "Candidate drift";
    case "selection_entropy_decision_cap_reached": return "Decision cap reached";
    default: return "";
  }
}

function selectionEntropyCoherenceLabel(status) {
  switch (status) {
    case "not_requested": return "Not requested";
    case "accepted": return "Accepted";
    case "matched": return "Matched";
    case "partial": return "Partial";
    case "failed": return "Failed";
    case "forbidden": return "Forbidden";
    case "invalid": return "Invalid";
    default: return "";
  }
}

function appendSelectionEntropyRow(list, label, value) {
  const term = document.createElement("dt");
  const description = document.createElement("dd");
  term.textContent = label;
  description.textContent = String(value);
  list.appendChild(term);
  list.appendChild(description);
}

function renderSelectionEntropyTrace(payload, bubble) {
  if (!bubble) return null;
  const diagnosticsEnabled = isChatTransitionDebugEnabled();
  if (!diagnosticsEnabled) {
    clearSelectionEntropyTrace(bubble);
    return null;
  }
  const signal = selectionEntropySignal(payload);
  if (!signal) {
    clearSelectionEntropyTrace(bubble);
    return null;
  }
  const card = document.createElement("section");
  card.className = "trace-card selection-entropy-card";
  card.dataset.selectionEntropyCard = "true";
  card.dataset.status = signal.coherenceStatus;
  card.setAttribute("aria-label", `Selection replay: ${selectionEntropyCoherenceLabel(signal.coherenceStatus)}`);
  const heading = document.createElement("h3");
  heading.textContent = "Selection replay";
  const list = document.createElement("dl");
  appendSelectionEntropyRow(list, "Mode", signal.mode === "replay" ? "Replay" : "Standard");
  appendSelectionEntropyRow(list, "Replay status", signal.replayAccepted ? "Accepted" : "Not requested");
  appendSelectionEntropyRow(list, "Coherence", selectionEntropyCoherenceLabel(signal.coherenceStatus));
  if (signal.replayReference) {
    appendSelectionEntropyRow(list, "Replay reference", signal.replayReference);
  }
  appendSelectionEntropyRow(list, "Algorithm", signal.algorithmVersion);
  appendSelectionEntropyRow(list, "Decisions", signal.decisionCount);
  appendSelectionEntropyRow(list, "Draws", signal.drawCount);
  appendSelectionEntropyRow(list, "Stable ties", signal.stableTieBreakCount);
  appendSelectionEntropyRow(list, "Router draws", signal.routerDrawCount);
  appendSelectionEntropyRow(list, "Strategy draws", signal.strategyDrawCount);
  appendSelectionEntropyRow(list, "Ensemble draws", signal.ensembleDrawCount);
  appendSelectionEntropyRow(list, "Candidate drift", signal.candidateDriftCount);
  appendSelectionEntropyRow(list, "Completion order",
    signal.completionOrderDeterministic ? "Deterministic" : "Not deterministic");
  const reasonLabel = selectionEntropyReasonLabel(signal.reasonCode);
  if (reasonLabel) {
    appendSelectionEntropyRow(list, "Reason", reasonLabel);
  }
  card.appendChild(heading);
  card.appendChild(list);
  const existing = bubble.querySelector?.("[data-selection-entropy-card]");
  if (existing) existing.replaceWith(card);
  else bubble.appendChild(card);
  return card;
}

function clearSelectionEntropyTrace(scope) {
  const root = scope || dom.chatMessages;
  if (!root) return 0;
  const cards = Array.from(root.querySelectorAll?.("[data-selection-entropy-card]") || []);
  if (root.dataset?.selectionEntropyCard !== undefined) cards.unshift(root);
  const uniqueCards = cards.filter((card, index) => cards.indexOf(card) === index);
  uniqueCards.forEach((card) => card.remove?.());
  return uniqueCards.length;
}

function appendTraceSignalLabel(detail, label, value) {
  if (value == null || value === "") return;
  const row = document.createElement("span");
  const traceLabel = document.createElement("strong");
  const traceValue = document.createElement("small");
  const safeValue = safeDebugCockpitDetail(value);
  traceLabel.textContent = `${label}:`;
  traceValue.textContent = ` ${safeValue}`;
  row.replaceChildren(traceLabel, traceValue);
  detail.appendChild(row);
}

function renderExecutionModeReceipt(pipeline = {}, assistant) {
  const receipt = pipeline?.executionMode;
  const labels = {AUTO: "자동", STRIKE: "빠름", SELF_ASK: "추가 확인"};
  if (!assistant?.dataset || !receipt || !labels[receipt.requested] || !labels[receipt.effective]) return;
  const reasons = {
    "base-retrieval": "기본 검색", "user-strike": "빠른 경로 선택", "user-self-ask": "추가 확인 선택",
    "evidence-gap": "근거 보완", "safety-gate": "안전 제한", "search-off": "검색 꺼짐",
    "deadline-or-cancel": "시간 제한 또는 취소", "evidence-sufficient": "추가 확인 생략",
    "simple-query": "기본 경로", "global-disabled": "추가 확인 비활성", "cheap-search-mode": "간단 검색",
    "search-budget": "검색 예산 제한"
  };
  let summary = assistant.querySelector?.("[data-execution-mode-receipt]");
  if (!summary) {
    summary = document.createElement("small");
    summary.dataset.executionModeReceipt = "true";
    const meta = document.createElement("div");
    meta.dataset.role = "execution-mode-meta";
    meta.style.display = "block";
    meta.style.marginTop = "0.5rem";
    meta.style.borderTop = "1px solid var(--border-color, #d1d5db)";
    meta.style.paddingTop = "0.4rem";
    markChatDiagnosticNode(meta);
    meta.appendChild(summary);
    assistant.appendChild(meta);
  }
  const parts = [`전략: ${labels[receipt.requested]} → ${labels[receipt.effective]}`];
  if (Number.isInteger(receipt.queryCount) && receipt.queryCount >= 0 && receipt.queryCount <= 3) parts.push(`검색 질의 ${receipt.queryCount}개`);
  if (Number.isInteger(receipt.httpAttempts) && receipt.httpAttempts >= 0 && receipt.httpAttempts <= 6) parts.push(`검색 전송 ${receipt.httpAttempts}회`);
  if (reasons[receipt.reason]) parts.push(reasons[receipt.reason]);
  summary.textContent = parts.join(" · ");
  assistant.dataset.executionModeRequested = receipt.requested;
  assistant.dataset.executionModeEffective = receipt.effective;
}

const renderTraceSignalDetail = (signal, pipeline, assistant) => {
  if (!assistant || shouldSuppressMessageDiagnostics(assistant)) return null;
  const detail = document.createElement("div");
  detail.dataset.role = "trace-signal-detail";
  markChatDiagnosticNode(detail);
  appendTraceSignalLabel(detail, "trace", signal.traceIdHash || pipeline.traceTurnId);
  appendTraceSignalLabel(detail, "request", signal.requestIdHash);
  appendTraceSignalLabel(detail, "session", signal.sessionIdHash);
  appendTraceSignalLabel(detail, "events", signal.eventCount);
  appendTraceSignalLabel(detail, "failure", signal.failureClass || pipeline.failureClass);
  appendTraceSignalLabel(detail, "reason", signal.reasonCode || pipeline.disabledReason);
  const search = pipeline.agentWebSearch;
  if (search && ["OK", "FAIL_SOFT", "SKIPPED"].includes(search.status)) {
    appendTraceSignalLabel(detail, "web search", search.status);
    appendTraceSignalLabel(detail, "search reason", search.reasonCode);
    appendTraceSignalLabel(detail, "search results", search.returnedCount);
  }
  (Array.isArray(search?.providers) ? search.providers : []).slice(0, 32).forEach(row => {
    if (!["naver", "brave", "serpapi", "tavily"].includes(row?.provider)) return;
    const provider = row.provider;
    appendTraceSignalLabel(detail, `${provider} status`, row.outcome);
    appendTraceSignalLabel(detail, `${provider} HTTP`, row.httpStatus);
    appendTraceSignalLabel(detail, `${provider} reason`, row.failureReason);
    appendTraceSignalLabel(detail, `${provider} results`, row.returnedCount);
    appendTraceSignalLabel(detail, `${provider} freshness`, row.freshness);
    appendTraceSignalLabel(detail, `${provider} masked by`, row.maskedBy);
    appendTraceSignalLabel(detail, `${provider} recovered at`, row.recoveredAt);
  });
  Object.entries(signal.stageCounts || {}).forEach(([stage, count]) => {
    appendTraceSignalLabel(detail, stage, count);
  });
  return window.AwxChatTraceUi?.upsertDiagnostic(assistant, "trace", detail) || null;
};

const signalLabel = (value) => safeDebugCockpitDetail(value == null || value === "" ? "-" : value);

const scoreDeltaContext = (signal) => {
  const parts = [];
  if (signal?.stage) parts.push(`stage:${signalLabel(signal.stage)}`);
  if (signal?.guard) parts.push(`guard:${signalLabel(signal.guard)}`);
  if (signal?.clampName) parts.push(`clamp:${signalLabel(signal.clampName)}`);
  if (signal?.eventId != null && signal?.eventId !== "") parts.push(`event:${signalLabel(signal.eventId)}`);
  return parts.join(" ");
};

function isSupportedScoreDeltaNumber(value) {
  if (typeof value === "number") return Number.isFinite(value);
  if (typeof value !== "string" || value.trim() === "") return false;
  return Number.isFinite(Number(value));
}

function appendScoreDeltaLabel(detail, label, value) {
  if (value == null || value === "") return;
  const row = document.createElement("span");
  const key = document.createElement("strong");
  const val = document.createElement("small");
  key.textContent = `${label}:`;
  val.textContent = ` ${signalLabel(value)}`;
  row.appendChild(key);
  row.appendChild(val);
  detail.appendChild(row);
}

const renderScoreDeltaDetail = (signal, assistant) => {
  if (!assistant || shouldSuppressMessageDiagnostics(assistant)) return null;
  if (!signal) return null;
  const numericFields = [
    ["scoreDelta", signal.scoreDelta],
    ["dropRatio", signal.dropRatio],
    ["maxDrawdown", signal.maxDrawdown],
    ["expectedDelta", signal.expectedDelta],
    ["rawScoreDelta", signal.rawScoreDelta]
  ].filter(([, value]) => isSupportedScoreDeltaNumber(value));
  const labelFields = [
    ["clampName", signal.clampName],
    ["stage", signal.stage],
    ["guard", signal.guard],
    ["eventId", signal.eventId]
  ].filter(([, value]) => value != null && value !== "");
  if (numericFields.length === 0 && labelFields.length === 0) return null;
  const detail = document.createElement("div");
  detail.dataset.role = "score-delta-detail";
  markChatDiagnosticNode(detail);
  numericFields.forEach(([label, value]) => appendScoreDeltaLabel(detail, label, value));
  labelFields.forEach(([label, value]) => appendScoreDeltaLabel(detail, label, value));
  const eventKey = signal.eventId != null && signal.eventId !== ""
    ? String(signal.eventId)
    : JSON.stringify([...numericFields, ...labelFields]);
  return window.AwxChatTraceUi?.upsertDiagnostic(assistant, "score", detail, eventKey) || null;
};

function debugFxSignal(payload = {}) {
  return payload.debugFxSignal || payload.debug_fx_signal || payload.debugFx || payload.debug_fx || payload;
}

function debugFxLabels(payload = {}) {
  const signal = debugFxSignal(payload);
  const labels = signal?.labels || payload.labels || {};
  return labels && typeof labels === "object" ? labels : {};
}

function debugFxLabel(labels, key) {
  if (!labels || !Object.prototype.hasOwnProperty.call(labels, key)) return "";
  const value = safeDebugCockpitDetail(labels[key]);
  return value === "-" ? "" : value;
}

function debugFxBoundaryPhase(value) {
  const phase = safeDebugCockpitDetail(value);
  return phase === "pre_llm" || phase === "final" ? phase : "";
}

function pushDebugFxPart(parts, label, value) {
  if (value) parts.push(`${label} ${value}`);
}

function chatHarmonyDebugNextActionFallback(labels, debugAiNextAction) {
  if (debugAiNextAction) return debugAiNextAction;
  const harmonyDecision = debugFxLabel(labels, "chatHarmonyDecision");
  const harmonyDegraded = debugFxLabel(labels, "chatHarmonyDegraded");
  if (harmonyDecision !== "smooth_chat" || harmonyDegraded === "true") return "";
  const matrixDecision = debugFxLabel(labels, "debugAiMatrixDecision");
  if (matrixDecision === "mitigate_now") return "mitigate_debug_ai_hot_chunk";
  if (matrixDecision === "investigate_hot_chunk") return "investigate_debug_ai_hot_chunk";
  return "continue_observing_chat_harmony";
}

function effectiveLocalLlmDebugAction(labels = {}) {
  const failureClass = debugFxLabel(labels, "localLlmFailureClass");
  const nextAction = debugFxLabel(labels, "localLlmNextAction");
  const upstreamFailureClass = debugFxLabel(labels, "localLlmUpstreamFailureClass");
  const upstreamNextAction = debugFxLabel(labels, "localLlmUpstreamNextAction");
  const upstreamStatus = debugFxLabel(labels, "localLlmUpstreamStatus");
  const upstreamActive = Boolean(upstreamFailureClass && upstreamFailureClass !== "none");
  return {
    failureClass: upstreamActive ? upstreamFailureClass : failureClass,
    nextAction: upstreamActive ? (upstreamNextAction || nextAction) : nextAction,
    upstreamStatus: upstreamActive ? upstreamStatus : ""
  };
}

function debugFxSummary(payload = {}) {
  const signal = debugFxSignal(payload);
  const labels = debugFxLabels(payload);
  const parts = [];
  const harmonyDegraded = debugFxLabel(labels, "chatHarmonyDegraded");
  const harmonyDecision = debugFxLabel(labels, "chatHarmonyDecision");
  const harmonyReason = debugFxLabel(labels, "chatHarmonyReason");
  const matrixCount = debugFxLabel(labels, "debugAiMatrixCount");
  const matrixChunks = debugFxLabel(labels, "debugAiMatrixChunkCount");
  const matrixDecision = debugFxLabel(labels, "debugAiMatrixDecision");
  const stageBoundaryStage = debugFxLabel(labels, "stageBoundaryStage");
  const stageBoundaryFailureClass = debugFxLabel(labels, "stageBoundaryFailureClass");
  const stageBoundaryReason = debugFxLabel(labels, "stageBoundaryReason");
  const stageBoundaryPhase = debugFxBoundaryPhase(signal?.phase);
  const verificationStatus = debugFxLabel(labels, "verificationStatus");
  const verificationFailureClass = debugFxLabel(labels, "verificationFailureClass");
  const verificationReason = debugFxLabel(labels, "verificationReason");
  const traceMemoryRouteDecision = debugFxLabel(labels, "traceMemoryRouteDecision");
  const traceMemoryVirtualCheckpointKey = debugFxLabel(labels, "traceMemoryVirtualCheckpointKey");
  const traceMemoryVirtualCheckpointStage = debugFxLabel(labels, "traceMemoryVirtualCheckpointStage");
  const traceMemoryVirtualCheckpointPhase = debugFxLabel(labels, "traceMemoryVirtualCheckpointPhase");
  const traceMemoryCfvmOffered = debugFxLabel(labels, "traceMemoryCfvmOffered");
  const traceMemoryCfvmPatternId = debugFxLabel(labels, "traceMemoryCfvmPatternId");
  const traceMemoryCheckpointSource = debugFxLabel(labels, "traceMemoryCheckpointSource");
  const traceMemoryVirtualCheckpointStageObserved = debugFxLabel(labels, "traceMemoryVirtualCheckpointStageObserved");
  const contextInjectedHistory = debugFxLabel(labels, "promptContextInjectedHistory");
  const contextInjectedHistoryChars = debugFxLabel(labels, "promptContextInjectedHistoryChars");
  const contextInjectedLastAssistant = debugFxLabel(labels, "promptContextInjectedLastAssistant");
  const contextInjectedMemory = debugFxLabel(labels, "promptContextInjectedMemory");
  const contextInjectedDelivered = debugFxLabel(labels, "promptContextInjectedDelivered");
  const supabaseStatus = debugFxLabel(labels, "supabaseStatus");
  const supabaseNeeded = debugFxLabel(labels, "supabaseEvidenceNeeded");
  const agentDbStatus = debugFxLabel(labels, "agentDbContextStatus");
  const agentDbReason = debugFxLabel(labels, "agentDbContextReason");
  const browserStatus = debugFxLabel(labels, "browserStatus");
  const computerStatus = debugFxLabel(labels, "computerUseStatus");
  const nativeRoute = debugFxLabel(labels, "ollamaNativeRoute");
  const nativeGpuMode = debugFxLabel(labels, "ollamaNativeGpuMode");
  const nativeNumGpu = debugFxLabel(labels, "ollamaNativeNumGpu");
  const nativePromptLength = debugFxLabel(labels, "ollamaNativePromptLength");
  const nativeMaxTokens = debugFxLabel(labels, "ollamaNativeMaxTokens");
  const workerTermination = debugFxLabel(labels, "llmTimeoutWorkerTermination");
  const debugAiNextAction = debugFxLabel(labels, "debugAiNextAction");
  const localLlmAction = effectiveLocalLlmDebugAction(labels);
  const localLlmFailureClass = localLlmAction.failureClass;
  const localLlmNextAction = localLlmAction.nextAction;
  const localLlmNeedsAction = localLlmNextAction && localLlmFailureClass && localLlmFailureClass !== "none";
  const nextAction = localLlmNeedsAction ? localLlmNextAction : (chatHarmonyDebugNextActionFallback(labels, debugAiNextAction)
    || debugFxLabel(labels, "agentDbContextNextAction")
    || debugFxLabel(labels, "supabaseNextAction"));

  const harmonyState = harmonyDegraded ? `degraded:${harmonyDegraded}` : harmonyDecision;
  const harmonyDetail = [
    harmonyState,
    harmonyState === harmonyDecision ? null : harmonyDecision,
    harmonyReason
  ].filter(Boolean).join(" ");
  pushDebugFxPart(parts, "Harmony", harmonyDetail);
  if (matrixCount || matrixChunks || matrixDecision) {
    pushDebugFxPart(parts, "Matrix", `${matrixCount || "0"}/${matrixChunks || "0"} ${matrixDecision || "observe"}`.trim());
  }
  const mlaLabel = stageBoundaryPhase === "pre_llm"
    ? "MLA PRE"
    : (stageBoundaryPhase === "final" ? "MLA FINAL" : "MLA");
  pushDebugFxPart(parts, mlaLabel, [
    stageBoundaryStage,
    stageBoundaryFailureClass,
    stageBoundaryReason
  ].filter(Boolean).join(" "));
  pushDebugFxPart(parts, "Verification", [
    verificationStatus,
    verificationFailureClass,
    verificationReason
  ].filter(Boolean).join(" "));
  pushDebugFxPart(parts, "Trace Memory", [
    traceMemoryRouteDecision,
    traceMemoryVirtualCheckpointStage
      ? `stage:${traceMemoryVirtualCheckpointStage}${traceMemoryVirtualCheckpointStageObserved === "false" ? "(synthetic)" : ""}`
      : "",
    traceMemoryVirtualCheckpointPhase ? `phase:${traceMemoryVirtualCheckpointPhase}` : "",
    traceMemoryVirtualCheckpointKey ? `vkey:${traceMemoryVirtualCheckpointKey}` : "",
    traceMemoryCheckpointSource ? `src:${traceMemoryCheckpointSource}` : "",
    traceMemoryCfvmOffered ? `cfvm:${traceMemoryCfvmOffered}` : "",
    traceMemoryCfvmPatternId ? `pattern:${traceMemoryCfvmPatternId}` : ""
  ].filter(Boolean).join(" "));
  // 세션 대화 컨텍스트가 조립→프롬프트 전달까지 실제로 이어졌는지 단계별로 표기한다.
  pushDebugFxPart(parts, "Context", [
    contextInjectedHistory ? `hist:${contextInjectedHistory}` : "",
    contextInjectedHistoryChars ? `chars:${contextInjectedHistoryChars}` : "",
    contextInjectedLastAssistant ? `last:${contextInjectedLastAssistant}` : "",
    contextInjectedMemory ? `mem:${contextInjectedMemory}` : "",
    contextInjectedDelivered ? `delivered:${contextInjectedDelivered}` : ""
  ].filter(Boolean).join(" "));
  pushDebugFxPart(parts, "Supabase", [supabaseStatus, supabaseNeeded].filter(Boolean).join(" "));
  pushDebugFxPart(parts, "Agent DB", [agentDbStatus, agentDbReason].filter(Boolean).join(" "));
  pushDebugFxPart(parts, "Native", [
    nativeRoute ? `route:${nativeRoute === "true" ? "native" : "compat"}` : "",
    nativeGpuMode,
    nativeNumGpu ? `numGpu:${nativeNumGpu}` : "",
    nativePromptLength ? `promptChars:${nativePromptLength}` : "",
    nativeMaxTokens ? `maxTokens:${nativeMaxTokens}` : "",
    workerTermination ? `executorExit:${workerTermination}` : ""
  ].filter(Boolean).join(" "));
  pushDebugFxPart(parts, "Local LLM", [
    localLlmFailureClass,
    localLlmAction.upstreamStatus ? `status:${localLlmAction.upstreamStatus}` : "",
    localLlmNeedsAction ? `next:${localLlmNextAction}` : ""
  ].filter(Boolean).join(" "));
  pushDebugFxPart(parts, "Browser", browserStatus);
  pushDebugFxPart(parts, "Computer", computerStatus);
  pushDebugFxPart(parts, "Next", nextAction);
  return parts.join(" | ");
}

function promoteLocalLlmDebugFxToHeartbeat(labels = {}) {
  const localLlmAction = effectiveLocalLlmDebugAction(labels);
  const localLlmFailureClass = localLlmAction.failureClass;
  const localLlmNextAction = localLlmAction.nextAction;
  if (!localLlmNextAction || !localLlmFailureClass || localLlmFailureClass === "none") return false;
  const detail = `llm:${localLlmFailureClass} next:${localLlmNextAction}`;
  debugHeartbeatSummaryState.liveStatus = "WARN";
  debugHeartbeatSummaryState.nextAction = localLlmNextAction;
  setDebugHeartbeatField('model', 'WARN', detail);
  setDebugMatrixCell('model-answer', 'WARN', 'Model/Answer', detail);
  refreshDebugHeartbeatSummary();
  return true;
}

function pruneStaleLocalLlmDebugFxTraces(target, labels = {}) {
  const localLlmFailureClass = debugFxLabel(labels, "localLlmFailureClass");
  if (localLlmFailureClass === "none") {
    target?.querySelectorAll?.('[data-role="debug-fx"]').forEach((node) => {
      const text = node?.textContent || "";
      if (text.includes("Local LLM model_blank") || text.includes("inspect_ollama_runtime_capacity")) {
        node.remove();
      }
    });
  }
}

function applyChatHarmonyRailStatus(holder, finalStatus) {
  if (!holder || (finalStatus !== "done" && finalStatus !== "warn")) return false;
  const harmonyRow = holder?.querySelector?.('[data-block-id="harmony"]');
  const status = harmonyRow?.querySelector?.("small");
  if (!harmonyRow || !status) return false;
  harmonyRow.dataset.status = finalStatus;
  status.textContent = finalStatus;
  return true;
}

function reconcileChatHarmonyRailFromDebugFx(payload = {}, bubble) {
  const labels = debugFxLabels(payload);
  const decision = debugFxLabel(labels, "chatHarmonyDecision").toLowerCase();
  const degraded = debugFxLabel(labels, "chatHarmonyDegraded").toLowerCase();
  const reason = debugFxLabel(labels, "chatHarmonyReason").toLowerCase();
  if (!decision || (degraded !== "true" && degraded !== "false")) return false;
  const finalStatus = decision === "smooth_chat" && degraded === "false"
    && reason !== "answer_shape_respected" ? "done" : "warn";
  if (bubble) bubble.__chatHarmonyFinalStatus = finalStatus;
  const target = bubble?.parentElement || bubble || dom.chatMessages;
  const holder = bubble?.__transformerCoreRail || target?.querySelector?.('[data-role="transformer-core-rail"]');
  return applyChatHarmonyRailStatus(holder, finalStatus);
}

function renderDebugFxTrace(payload, bubble) {
  if (shouldSuppressMessageDiagnostics(bubble?.parentElement || bubble)) return;
  const labels = debugFxLabels(payload);
  promoteLocalLlmDebugFxToHeartbeat(labels);
  const summary = debugFxSummary(payload);
  if (!summary) return;
  const signal = debugFxSignal(payload);
  const phase = safeDebugCockpitDetail(signal?.phase || "");
  const boundaryPhase = debugFxBoundaryPhase(phase);
  const code = safeDebugCockpitDetail(signal?.code || "");
  const prefix = [phase === "-" ? "" : phase, code === "-" ? "" : code].filter(Boolean).join("/");
  const target = bubble?.parentElement || bubble || dom.chatMessages;
  pruneStaleLocalLlmDebugFxTraces(target, labels);
  const trace = document.createElement("div");
  trace.className = "message-debug-fx";
  trace.dataset.role = "debug-fx";
  if (boundaryPhase && debugFxLabel(labels, "stageBoundaryStage")) {
    trace.dataset.debugFxPhase = boundaryPhase;
  }
  trace.textContent = prefix ? `Debug FX ${prefix}: ${summary}` : `Debug FX: ${summary}`;
  trace.setAttribute("aria-label", `Debug FX: ${summary}`);
  markChatDiagnosticNode(trace);
  target?.appendChild(trace);
}

function handleDebugFxSignal(payload = {}) {
  const signal = payload.debugFxSignal || payload.debug_fx || {};
  const labels = signal.labels || {};
  const labelEntries = Object.entries(labels).filter(([key, value]) => key && value != null);
  if (!labelEntries.length) return;
  const card = document.createElement("div");
  card.className = "message-debug-fx";
  markChatDiagnosticNode(card);
  const labelsHolder = document.createElement("div");
  labelsHolder.dataset.role = "debug-fx-labels";
  labelEntries.slice(0, 8).forEach(([key, value]) => {
    const label = document.createElement("span");
    const labelKey = document.createElement("strong");
    const labelValue = document.createElement("small");
    labelKey.textContent = `${key}:`;
    labelValue.textContent = String(value ?? "-");
    label.replaceChildren(labelKey, labelValue);
    labelsHolder.appendChild(label);
  });
  card.appendChild(labelsHolder);
  return card;
}

function httpDebugSummary(dbg = {}) {
  return `requestIdHash=${dbg.requestIdHash}`;
}

function evidenceValue(item, keys) {
  if (item == null) return "";
  if (typeof item === "string") return keys.includes("snippet") || keys.includes("text") ? item : "";
  if (typeof item !== "object") return "";
  for (const key of keys) {
    const value = item[key];
    if (value == null) continue;
    const text = String(value).trim();
    if (text) return text;
  }
  return "";
}

function evidenceHost(item) {
  const rawUrl = evidenceValue(item, ["url", "sourceUrl", "source_url", "source", "link", "href", "canonical", "permalink"]);
  if (!rawUrl) return "";
  try {
    return new URL(rawUrl).hostname.replace(/^www\./i, "");
  } catch {
    return safeDebugCockpitDetail(rawUrl);
  }
}

function evidenceUrl(item) {
  const rawUrl = evidenceValue(item, ["url", "sourceUrl", "source_url", "link", "href", "canonical", "permalink", "source"]);
  if (!rawUrl) return "";
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return "";
    parsed.username = "";
    parsed.password = "";
    return parsed.href;
  } catch {
    return "";
  }
}

function evidenceMarker(item) {
  return safeDebugCockpitDetail(evidenceValue(item, ["marker", "citation", "citationMarker", "citation_marker"]));
}

function citedEvidenceMarkersFromText(text) {
  const markers = new Set();
  String(text || "").replace(/\[([A-Z]\d+)]/gi, (_, marker) => {
    markers.add(String(marker || "").toUpperCase());
    return "";
  });
  return markers;
}

function evidenceAnswerTextIndex(item, text) {
  const haystack = String(text || "").toLowerCase();
  if (!haystack.trim()) return Number.POSITIVE_INFINITY;
  const candidates = [
    evidenceUrl(item),
    evidenceValue(item, ["url", "sourceUrl", "source_url", "link", "href", "canonical", "permalink", "source"]),
    evidenceValue(item, ["title", "name", "label"]),
    evidenceHost(item)
  ];
  let best = Number.POSITIVE_INFINITY;
  for (const candidate of candidates) {
    const raw = String(candidate || "").trim();
    if (raw.length < 4) continue;
    const variants = new Set([raw, raw.replace(/\/$/, "")]);
    for (const variant of variants) {
      const normalized = variant.toLowerCase();
      if (!normalized) continue;
      const index = haystack.indexOf(normalized);
      if (index >= 0 && index < best) best = index;
    }
  }
  return best;
}

function prioritizedEvidenceItems(items, context = {}) {
  const answerText = context.answerText || context.text || "";
  const citedMarkers = context.citedMarkers instanceof Set
    ? context.citedMarkers
    : citedEvidenceMarkersFromText(answerText);
  const ranked = items
    .map((item, index) => ({
      item,
      index,
      cited: citedMarkers.has(evidenceMarker(item).toUpperCase()),
      answerIndex: evidenceAnswerTextIndex(item, answerText)
    }));
  const hasCited = citedMarkers.size > 0;
  const hasAnswerOrder = ranked.some((entry) => Number.isFinite(entry.answerIndex));
  if (!hasCited && !hasAnswerOrder) return items;
  return ranked
    .sort((left, right) =>
      Number(right.cited) - Number(left.cited) ||
      left.answerIndex - right.answerIndex ||
      left.index - right.index
    )
    .map((entry) => entry.item);
}

function evidenceCitationState(items, context = {}) {
  const hasAnswerText = Object.prototype.hasOwnProperty.call(context, "answerText")
    || Object.prototype.hasOwnProperty.call(context, "text");
  if (!hasAnswerText || !Array.isArray(items) || !items.length) return "";
  const markerItems = items
    .map((item) => evidenceMarker(item).toUpperCase())
    .filter(Boolean);
  if (!markerItems.length) return "";
  const citedMarkers = context.citedMarkers instanceof Set
    ? context.citedMarkers
    : citedEvidenceMarkersFromText(context.answerText || context.text || "");
  const citedEvidenceCount = markerItems.filter((marker) => citedMarkers.has(marker)).length;
  if (citedEvidenceCount === 0) return "searched-but-uncited";
  if (citedEvidenceCount < markerItems.length) return "partially-cited";
  return "cited";
}

function presentEvidenceQuality({
  citationState = "",
  evidenceCount = 0,
  externalUnavailable = false
} = {}) {
  const count = Math.max(0, Number(evidenceCount) || 0);
  const citation = String(citationState || "");

  if (citation === "cited") {
    return {
      code: "citation-backed",
      visibleLabel: "인용 확인됨",
      accessibleLabel: "근거 품질: 인용 확인됨",
      tone: "ok",
      evidenceCount: count,
      detail: "",
      sourceBasis: "citation-state:cited"
    };
  }
  if (citation === "partially-cited") {
    return {
      code: "partial-citation",
      visibleLabel: "부분 인용",
      accessibleLabel: "근거 품질: 부분 인용",
      tone: "warn",
      evidenceCount: count,
      detail: "",
      sourceBasis: "citation-state:partially-cited"
    };
  }
  if (citation === "searched-but-uncited") {
    return {
      code: "retrieved-uncited",
      visibleLabel: "검색됨 · 미인용",
      accessibleLabel: "근거 품질: 검색됨, 미인용",
      tone: "warn",
      evidenceCount: count,
      detail: "",
      sourceBasis: "citation-state:searched-but-uncited"
    };
  }
  if (externalUnavailable === true) {
    return {
      code: "external-unavailable",
      visibleLabel: "외부 근거 사용 불가",
      accessibleLabel: "근거 품질: 외부 근거 사용 불가",
      tone: "warn",
      evidenceCount: count,
      detail: "",
      sourceBasis: "pipeline.disabledReason"
    };
  }
  return {
    code: "not-observed",
    visibleLabel: "근거 상태 관측 안 됨",
    accessibleLabel: "근거 품질: 근거 상태 관측 안 됨",
    tone: "neutral",
    evidenceCount: count,
    detail: "",
    sourceBasis: "no-authoritative-mapping"
  };
}

function hasExplicitExternalUnavailable(pipeline = {}) {
  if (!pipeline || typeof pipeline !== "object" || Array.isArray(pipeline)) return false;
  return Boolean(String(pipeline.disabledReason || pipeline.disabled_reason || "").trim());
}

function appendEvidenceQualityBadge(holder, quality) {
  const badge = document.createElement("span");
  badge.className = "evidence-quality-badge";
  badge.dataset.role = "evidence-quality";
  badge.dataset.evidenceQuality = quality.code;
  badge.dataset.tone = quality.tone;
  badge.textContent = quality.visibleLabel;
  badge.setAttribute("aria-label", quality.accessibleLabel);
  holder.appendChild(badge);
  return badge;
}

const DECISION_STATE_LABELS = Object.freeze({
  "observed-active": "Active",
  "observed-complete": "Observed",
  "observed-degraded": "Degraded",
  "observed-cancelled": "Cancelled",
  "not-observed": "Not observed"
});

const OBSERVED_RECOVERY_CODES = new Set([
  "primary_failed_fallback_done",
  "fallback_done_primary_not_observed",
  "fallback_done_primary_delivery_not_observed"
]);

function observedRecoveryCode(value) {
  const code = String(value || "").trim().toLowerCase();
  return OBSERVED_RECOVERY_CODES.has(code) ? code : "";
}

function presentObservedDecision({
  route = "",
  citationState = "",
  answerMode = "",
  streamStatus = "",
  recoveryState = "",
  healthOverlay = currentTurnHealthOverlay
} = {}) {
  const kind = String(healthOverlay?.kind || "none");
  const citation = String(citationState || "");
  const mode = normalizedAnswerMode(answerMode || streamStatus);
  const reset = /^(idle|connecting)$/i.test(String(streamStatus || ""));
  const stopped = kind === "stopped";
  const attention = kind === "attention";
  const pending = kind === "pending";
  const responding = kind === "responding";
  const complete = !stopped && !attention && successfulAnswerMode(mode);
  const recovered = observedRecoveryCode(recoveryState);

  const stage = (code, label, state, sourceBasis, shouldUpdate) => ({
    code,
    label,
    state,
    sourceBasis,
    shouldUpdate: Boolean(shouldUpdate)
  });

  return {
    stages: [
      stage("route", "Route", route ? "observed-complete" : "not-observed",
        route ? "pipeline.route" : "no-current-route-field", reset || Boolean(route)),
      stage("context", "Context", "not-observed",
        "no-structured-current-turn-context", reset),
      stage("retrieve", "Retrieve", "not-observed",
        "no-structured-current-turn-retrieval", reset),
      stage("evidence", "Evidence",
        citation === "cited"
          ? "observed-complete"
          : ["partially-cited", "searched-but-uncited"].includes(citation)
            ? "observed-degraded"
            : "not-observed",
        citation ? "evidenceCitationState" : "no-citation-state",
        reset || Boolean(citation)),
      stage("answer", "Answer",
        stopped
          ? "observed-cancelled"
          : attention
            ? "observed-degraded"
            : pending || responding
              ? "observed-active"
              : complete
                ? "observed-complete"
                : "not-observed",
        "currentTurnHealthOverlay+answerMode",
        reset || kind !== "none" || Boolean(answerMode)),
      stage("recover", "Recover",
        stopped
          ? "observed-cancelled"
          : attention
            ? "observed-degraded"
            : recovered
              ? "observed-complete"
              : "not-observed",
        stopped || attention
          ? "currentTurnHealthOverlay.kind"
          : recovered
            ? "model-attempt-summary"
            : "no-observed-recovery-state",
        reset || stopped || attention || Boolean(recovered))
    ]
  };
}

function renderDecisionRibbon(presentation = {}) {
  const root = dom.decisionRibbon;
  if (!root) return null;

  const stages = new Map(
    (Array.isArray(presentation.stages) ? presentation.stages : [])
      .map((stage) => [String(stage.code || ""), stage])
  );
  const announced = [];

  root.querySelectorAll("[data-decision-stage]").forEach((node) => {
    const code = String(node.dataset.decisionStage || "");
    const stage = stages.get(code);
    if (!stage || stage.shouldUpdate !== true) {
      const currentLabel = node.querySelector("[data-decision-state-label]")?.textContent || "Not observed";
      announced.push((stage?.label || code) + ": " + currentLabel);
      return;
    }

    const state = Object.prototype.hasOwnProperty.call(DECISION_STATE_LABELS, stage.state)
      ? stage.state
      : "not-observed";
    const label = DECISION_STATE_LABELS[state];
    node.dataset.state = state;
    const valueNode = node.querySelector("[data-decision-state-label]");
    if (valueNode) valueNode.textContent = label;
    node.setAttribute("aria-label", stage.label + ": " + label.toLowerCase());
    recordChatTransitionDebug({
      kind: "decision." + code,
      to: state,
      reasonCode: "decision-state",
      terminalLatch: state === "observed-cancelled"
    });
    announced.push(stage.label + ": " + label);
  });

  root.setAttribute("aria-label", "Observed decision: " + announced.join(", "));
  return root;
}

function selectPrimaryDiagnostic({
  healthOverlay = currentTurnHealthOverlay,
  answerMode = "",
  streamStatus = "",
  evidenceQualityCode = "",
  isStopAvailable = false
} = {}) {
  const kind = String(healthOverlay?.kind || "none");
  const mode = normalizedAnswerMode(answerMode || streamStatus);
  const evidenceCode = String(evidenceQualityCode || "");
  const noneAction = Object.freeze({
    code: "none",
    visibleLabel: "",
    accessibleLabel: "",
    sourceBasis: "no-proven-safe-control"
  });
  const stopAction = isStopAvailable
    ? Object.freeze({
        code: "stop",
        visibleLabel: "Stop available",
        accessibleLabel: "Stop the current response",
        sourceBasis: "existing-stop-control"
      })
    : noneAction;

  if (kind === "stopped") {
    return {
      code: "stopped",
      status: "OK",
      title: "Response stopped",
      summaryText: "Response stopped · user cancelled",
      action: noneAction,
      supportingFacts: [{ code: "user-cancelled", sourceBasis: "currentTurnHealthOverlay.kind" }]
    };
  }
  if (kind === "attention") {
    return {
      code: "attention",
      status: "WARN",
      title: "Needs attention",
      summaryText: "Needs attention · current turn",
      action: noneAction,
      supportingFacts: [{ code: "current-turn-attention", sourceBasis: "currentTurnHealthOverlay.kind" }]
    };
  }
  if (kind === "pending" || kind === "responding") {
    return {
      code: kind,
      status: kind === "pending" ? "WARN" : "OK",
      title: kind === "pending" ? "Response pending" : "Responding",
      summaryText: (kind === "pending" ? "Response pending" : "Responding")
        + " · current turn"
        + (isStopAvailable ? " · Stop available" : ""),
      action: stopAction,
      supportingFacts: [{ code: "current-turn-" + kind, sourceBasis: "currentTurnHealthOverlay.kind" }]
    };
  }
  if (String(mode || "").toUpperCase() === "FALLBACK_LOCAL") {
    return {
      code: "model-unavailable",
      status: "WARN",
      title: "Model route unavailable",
      summaryText: "Model route unavailable · local safe response",
      action: noneAction,
      supportingFacts: [{ code: "model-unavailable", sourceBasis: "answerMode" }]
    };
  }
  if (evidenceCode === "external-unavailable") {
    return {
      code: evidenceCode,
      status: "WARN",
      title: "External evidence unavailable",
      summaryText: "External evidence unavailable · current answer",
      action: noneAction,
      supportingFacts: [{ code: evidenceCode, sourceBasis: "pipeline.disabledReason" }]
    };
  }
  if (evidenceCode === "partial-citation" || evidenceCode === "retrieved-uncited") {
    const partial = evidenceCode === "partial-citation";
    return {
      code: evidenceCode,
      status: "WARN",
      title: partial ? "Evidence partly cited" : "Retrieved evidence is uncited",
      summaryText: (partial ? "Evidence partly cited" : "Retrieved evidence is uncited") + " · current answer",
      action: noneAction,
      supportingFacts: [{ code: evidenceCode, sourceBasis: "evidenceCitationState" }]
    };
  }
  if (successfulAnswerMode(mode)) {
    return {
      code: "complete",
      status: "OK",
      title: "Response complete",
      summaryText: "Response complete · answer mode observed",
      action: noneAction,
      supportingFacts: [{ code: "answer-mode-observed", sourceBasis: "answerMode" }]
    };
  }
  return {
    code: "not-observed",
    status: "UNKNOWN",
    title: "State not observed",
    summaryText: "State not observed",
    action: noneAction,
    supportingFacts: []
  };
}

function setDiagnosticsSummaryPresentation(status, title, code) {
  if (!dom.diagnosticsSummary) return null;
  const boundedStatus = String(status || "UNKNOWN");
  const boundedTitle = String(title || "State not observed");
  dom.diagnosticsSummary.dataset.status = boundedStatus;
  if (code !== undefined) {
    dom.diagnosticsSummary.dataset.diagnosticCode = String(code || "not-observed");
  }
  dom.diagnosticsSummary.textContent = boundedTitle;
  dom.diagnosticsSummary.removeAttribute("aria-label");
  dom.diagnosticsSummaryControl?.setAttribute("aria-label", "Diagnostics: " + boundedTitle);
  return dom.diagnosticsSummary;
}

function renderPrimaryDiagnostic(presentation = {}) {
  const code = String(presentation.code || "not-observed");
  const status = String(presentation.status || "UNKNOWN");
  const title = String(presentation.summaryText || presentation.title || "State not observed");
  const rendered = setDiagnosticsSummaryPresentation(status, title, code);
  recordChatTransitionDebug({
    kind: "diagnostics",
    to: code,
    reasonCode: code,
    terminalLatch: code === "stopped"
  });
  return rendered;
}

function evidenceTitle(item, index) {
  if (typeof item === "string") return `Evidence #${index + 1}`;
  const host = evidenceHost(item);
  const marker = evidenceMarker(item);
  const label = evidenceValue(item, ["title", "source", "provider", "name", "label"]);
  if (marker && host && (!label || /^https?:\/\//i.test(String(label).trim()))) {
    return `${marker} ${host}`;
  }
  return safeDebugCockpitDetail(
    label ||
      marker ||
      host ||
      `Evidence #${index + 1}`
  );
}

function evidenceSnippet(item) {
  return safeDebugCockpitDetail(evidenceValue(item, ["snippet", "text", "content", "summary", "description", "evidence"]));
}

function isPlaceholderEvidenceText(value) {
  const normalized = String(value ?? "").trim().toLowerCase();
  return !normalized ||
    normalized === "-" ||
    normalized === "[url]" ||
    normalized === "source:[url]" ||
    normalized === "metadata only" ||
    normalized.startsWith("metadata only ") ||
    normalized.startsWith("url based ") ||
    normalized.startsWith("url fallback ");
}

function hasUsefulEvidenceLabel(item) {
  if (item == null || typeof item !== "object") return false;
  const marker = evidenceMarker(item);
  if (marker && (evidenceHost(item) || evidenceValue(item, ["filePath", "file_path", "path"]))) {
    return true;
  }
  const labels = [
    evidenceValue(item, ["title", "name", "label"]),
    evidenceValue(item, ["source", "provider", "type", "kind"])
  ];
  return labels.some((value) => {
    const raw = String(value || "").trim();
    if (!raw || /^https?:\/\//i.test(raw)) return false;
    return !isPlaceholderEvidenceText(safeDebugCockpitDetail(raw));
  });
}

function isEmptyEvidenceMetadata(item) {
  return item != null && typeof item === "object" && Object.keys(item).length === 0;
}

function isRenderableEvidenceItem(item) {
  if (!isPlaceholderEvidenceText(evidenceSnippet(item))) return true;
  return hasUsefulEvidenceLabel(item) || isEmptyEvidenceMetadata(item);
}

function emptyEvidenceState(context = {}) {
  const mode =
    normalizedAnswerMode(context.answerMode || context.answer_mode || context.mode) ||
    finalAnswerMode({}, context.model || context.modelUsed || context.model_used);
  const model = String(context.model || context.modelUsed || context.model_used || "").toLowerCase();
  if (String(mode || "").toUpperCase() === "HISTORY_CURRENT_TURN") {
    return {
      context: "current-turn-memory",
      text: "Current turn memory - no external evidence",
      label: "current turn memory, no external evidence"
    };
  }
  if (String(mode || "").toUpperCase() === "HISTORY_RECENT") {
    return {
      context: "history-fallback",
      text: "Recent history - no external evidence",
      label: "recent history fallback, no external evidence"
    };
  }
  if (String(mode || "").toUpperCase() === "FALLBACK_LOCAL") {
    return {
      context: "local-fallback",
      text: "Local fallback - no external evidence",
      label: "local fallback, no external evidence"
    };
  }
  if (model.includes("history:fallback")) {
    return {
      context: "history-fallback",
      text: "Recent history - no external evidence",
      label: "recent history fallback, no external evidence"
    };
  }
  return {
    context: "no-external-evidence",
    text: "No external evidence",
    label: "no external evidence"
  };
}

function evidenceRailSignature(items, emptyState) {
  const contextKey = items.length === 0 ? (emptyState?.context || "empty") : "items";
  const itemKey = items.slice(0, 8).map((item, index) => {
    if (typeof item === "string") return `text:${index}`;
    const title = evidenceTitle(item, index);
    const host = evidenceHost(item);
    const source = evidenceValue(item, ["source", "provider", "type", "kind"]);
    return [title, host, source].map((value) => safeDebugCockpitDetail(value || "")).join("|");
  }).join("||");
  return `${items.length}:${contextKey}:${itemKey}`;
}

function latestEvidenceRail(target) {
  const children = Array.from(target?.children || []);
  for (let i = children.length - 1; i >= 0; i -= 1) {
    if (children[i]?.matches?.(".evidence-rail[data-role=\"evidence\"]")) return children[i];
  }
  return null;
}

function mountEvidenceRail(holder, target, items) {
  const existingRail = latestEvidenceRail(target);
  const existingCount = Number(existingRail?.dataset?.count || "0");
  if (existingRail && existingCount > 0 && items.length === 0) {
    return existingRail;
  }
  if (existingRail && (existingRail.dataset.signature === holder.dataset.signature || existingCount === 0)) {
    existingRail.replaceWith(holder);
    scrollChatToLatestAssistant();
    return holder;
  }
  target?.appendChild(holder);
  scrollChatToLatestAssistant();
  return holder;
}

function renderEvidenceRail(evidence, target, context = {}) {
  if (shouldSuppressMessageDiagnostics(target)) return null;
  const incomingItems = Array.isArray(evidence) ? evidence : [];
  const hasAnswerText = Object.prototype.hasOwnProperty.call(context, "answerText")
    || Object.prototype.hasOwnProperty.call(context, "text");
  const answerText = context.answerText || context.text || "";
  const cachedSourceItems = !incomingItems.length && hasAnswerText && Array.isArray(target?.__awxEvidenceRailItems)
    ? target.__awxEvidenceRailItems
    : !incomingItems.length && hasAnswerText && Array.isArray(state.latestEvidenceRailItems)
      ? state.latestEvidenceRailItems
    : [];
  const cachedItems = cachedSourceItems.some((item) => Number.isFinite(evidenceAnswerTextIndex(item, answerText)))
    ? cachedSourceItems
    : [];
  const items = incomingItems.length ? incomingItems : cachedItems;
  const renderableItems = prioritizedEvidenceItems(
    items.filter((item) => isRenderableEvidenceItem(item)),
    context
  );
  if (items.length && target && typeof target === "object") {
    target.__awxEvidenceRailItems = items.slice(0, 20);
    state.latestEvidenceRailItems = items.slice(0, 20);
  }
  const emptyState = renderableItems.length === 0 ? emptyEvidenceState(context) : null;
  const citationState = evidenceCitationState(renderableItems, context);
  const pipeline = context.pipeline && typeof context.pipeline === "object" ? context.pipeline : {};
  const evidenceQuality = presentEvidenceQuality({
    citationState,
    evidenceCount: renderableItems.length,
    externalUnavailable: hasExplicitExternalUnavailable(pipeline)
  });
  recordChatTransitionDebug({
    kind: "evidence",
    to: evidenceQuality.code,
    reasonCode: evidenceQuality.code,
    terminalLatch: currentTurnHealthOverlay.kind === "stopped"
  });
  renderPrimaryDiagnostic(selectPrimaryDiagnostic({
    healthOverlay: currentTurnHealthOverlay,
    answerMode: context.answerMode || context.answer_mode || context.mode || "",
    evidenceQualityCode: evidenceQuality.code,
    isStopAvailable: Boolean(
      dom.stopBtn &&
      dom.stopBtn.hidden === false &&
      dom.stopBtn.style.display !== "none" &&
      dom.stopBtn.disabled === false
    )
  }));
  renderDecisionRibbon(presentObservedDecision({
    route: String(pipeline.route || ""),
    citationState,
    answerMode: context.answerMode || "",
    streamStatus: context.streamStatus || "",
    recoveryState: currentTurnRecoveryState,
    healthOverlay: currentTurnHealthOverlay
  }));
  rememberVisibleTurnEvidence(
    context.answerMode || context.answer_mode || context.mode,
    renderableItems.length
  );
  const holder = document.createElement("div");
  holder.className = "evidence-rail";
  holder.dataset.role = "evidence";
  holder.dataset.count = String(renderableItems.length);
  holder.dataset.evidenceQuality = evidenceQuality.code;
  if (items.length !== renderableItems.length) holder.dataset.sourceCount = String(items.length);
  holder.dataset.signature = evidenceRailSignature(renderableItems, emptyState);
  if (citationState) holder.dataset.citationState = citationState;
  markChatDiagnosticNode(holder);
  if (emptyState) holder.dataset.context = emptyState.context;
  const citationLabel = citationState === "searched-but-uncited"
    ? "searched but uncited"
    : citationState === "partially-cited"
      ? "partially cited"
      : citationState === "cited"
        ? "cited"
        : "";
  holder.setAttribute(
    "aria-label",
    emptyState
      ? `Evidence: ${renderableItems.length} item${renderableItems.length === 1 ? "" : "s"} (${emptyState.label})`
      : `Evidence: ${renderableItems.length} item${renderableItems.length === 1 ? "" : "s"}${citationLabel ? ` (${citationLabel})` : ""}`
  );

  const heading = document.createElement("strong");
  heading.textContent = renderableItems.length === 0 ? `Evidence ${renderableItems.length}:` : `Evidence ${renderableItems.length}`;
  holder.appendChild(heading);
  appendEvidenceQualityBadge(holder, evidenceQuality);

  const list = document.createElement("div");
  list.className = "evidence-list";
  holder.appendChild(list);

  if (renderableItems.length === 0) {
    const empty = document.createElement("small");
    empty.className = "evidence-more";
    empty.textContent = ` ${emptyState.text}`;
    holder.appendChild(empty);
    return mountEvidenceRail(holder, target, renderableItems);
  }

  renderableItems.slice(0, 3).forEach((item, index) => {
    const card = document.createElement("div");
    card.className = "evidence-chip";
    card.dataset.rank = String(index + 1);

    const title = evidenceTitle(item, index);
    const host = evidenceHost(item);
    const rawSource = evidenceValue(item, ["source", "provider", "type", "kind"]);
    const meta = safeDebugCockpitDetail(
      host ? `source:${host}` : rawSource && rawSource !== title ? `source:${rawSource}` : "source:local"
    );
    const rawSnippet = evidenceSnippet(item);
    const snippet = isPlaceholderEvidenceText(rawSnippet) ? "" : rawSnippet;
    const summary = [title, meta, snippet].filter(Boolean).join(" - ");

    const titleNode = document.createElement("span");
    titleNode.textContent = title;
    const sourceUrl = evidenceUrl(item);
    if (sourceUrl) {
      const link = document.createElement("a");
      link.className = "evidence-link";
      link.href = sourceUrl;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.setAttribute("href", sourceUrl);
      link.setAttribute("target", "_blank");
      link.setAttribute("rel", "noopener noreferrer");
      link.textContent = title;
      link.setAttribute("aria-label", `Open evidence source: ${title}`);
      card.appendChild(link);
    } else {
      card.appendChild(titleNode);
    }
    const metaNode = document.createElement("small");
    metaNode.textContent = meta;
    card.appendChild(metaNode);
    if (snippet) {
      const snippetNode = document.createElement("em");
      snippetNode.textContent = snippet;
      card.appendChild(snippetNode);
    }
    card.title = summary;
    card.setAttribute("aria-label", `Evidence ${index + 1}: ${summary}`);
    list.appendChild(card);
  });

  if (renderableItems.length > 3) {
    const more = document.createElement("small");
    more.className = "evidence-more";
    more.textContent = `+${renderableItems.length - 3} more`;
    holder.appendChild(more);
  }
  if (citationState === "searched-but-uncited") {
    const citation = document.createElement("small");
    citation.className = "evidence-more";
    citation.textContent = " searched, not cited";
    holder.appendChild(citation);
  }
  return mountEvidenceRail(holder, target, renderableItems);
}

function refreshEvidenceRailFromAnswerText(target, text, context = {}) {
  const answerText = String(text || "");
  if (!answerText.trim() || !Array.isArray(state.latestEvidenceRailItems) || !state.latestEvidenceRailItems.length) {
    return null;
  }
  const hasReferencedEvidence = state.latestEvidenceRailItems
    .some((item) => Number.isFinite(evidenceAnswerTextIndex(item, answerText)));
  if (!hasReferencedEvidence) return null;
  return renderEvidenceRail([], target || dom.chatMessages, {
    ...context,
    answerText
  });
}

function presentContextUsage(usage = {}) {
  const count = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
  const input = count(usage?.inputTokens);
  const limit = usage?.limitSource === "model_spec_snapshot" && count(usage?.contextLimitTokens) > 0
    ? usage.contextLimitTokens : null;
  const percent = input !== null && limit !== null ? Math.min(100, Math.round(input / limit * 100)) : null;
  const format = value => value.toLocaleString("en-US");
  const contextText = input === null ? "입력 컨텍스트 관측 안 됨 · 한도 확인 안 됨"
    : limit === null ? `입력 ${format(input)} 토큰 추정 · 한도 확인 안 됨`
      : `입력 ${format(input)} / ${format(limit)} 토큰 · 추정 ${percent}% · 관측된 모델 한도`;
  const before = count(usage?.memoryBeforeChars);
  const after = count(usage?.memoryAfterChars);
  const reasons = {disabled:"압축 꺼짐",empty_input:"압축할 기억 없음",below_threshold:"압축 기준 미도달",
    all_lines_dropped:"안전한 기억 줄 없음",exception_original_returned:"압축 실패 · 원문 유지",
    overflow:"길이 기준 압축",history_context_contamination:"내부 흔적 정리",overflow_and_contamination:"길이 압축 · 내부 흔적 정리"};
  let compressionText = reasons[usage?.memoryCompressionReason] || "기억 압축 관측 안 됨";
  if (before !== null && after !== null) compressionText += ` · ${format(before)} → ${format(after)} 문자`;
  if (usage?.memoryCompressionActivated === true && usage?.memoryCompressionReason !== "exception_original_returned") {
    compressionText += usage.memoryIncluded === true ? " · 프롬프트에 포함"
      : usage.memoryIncluded === false ? " · 프롬프트 미포함" : " · 전달 확인 안 됨";
  }
  return {percent,contextText,compressionText};
}

function renderContextUsage(pipeline = {}, reset = false) {
  const gauge = $("contextGauge");
  if (!gauge || (!reset && !pipeline?.contextUsage && pipeline?.finalContextCount == null)) return;
  const view = presentContextUsage(reset ? {} : pipeline.contextUsage);
  $("contextUsageText").textContent = view.contextText;
  $("memoryCompressionText").textContent = view.compressionText;
  $("contextPercent").textContent = view.percent === null ? "?" : `${view.percent}%`;
  gauge.style.setProperty("--context-percent", `${view.percent ?? 0}%`);
  gauge.dataset.known = String(view.percent !== null);
  gauge.setAttribute("aria-label", view.contextText);
  if (view.percent === null) gauge.removeAttribute("aria-valuenow");
  else gauge.setAttribute("aria-valuenow", String(view.percent));
  const evidence = Number.isSafeInteger(pipeline?.finalContextCount) && pipeline.finalContextCount >= 0
    ? `근거 ${pipeline.finalContextCount}건${pipeline.finalContextCountSource === "web_vector_estimate" ? " · 추정" : " · 관측"}`
    : "근거 수 관측 안 됨";
  $("contextEvidenceText").textContent = reset ? "근거 수 관측 대기" : evidence;
}

function initializeContextSummary() {
  const panel = $("contextSummary");
  if (!panel) return;
  const key = "chat.contextSummary.collapsed";
  try { panel.open = localStorage.getItem(key) !== "true"; } catch { /* optional UI preference */ }
  let lastOpen = panel.open;
  panel.addEventListener("toggle", () => {
    if (panel.open === lastOpen) return;
    lastOpen = panel.open;
    try { localStorage.setItem(key, String(!panel.open)); } catch { /* optional UI preference */ }
  });
}

function updateOrchestrationSignalBar(partial = {}) {
  renderContextUsage(partial.pipelineSnapshot, /^(idle|connecting)$/i.test(String(partial.streamStatus || "")));
  if (/^(idle|connecting)$/i.test(String(partial.streamStatus || ""))) {
    currentTurnRecoveryState = "";
  }
  if (Object.prototype.hasOwnProperty.call(partial, "recoveryState")) {
    const observedRecovery = observedRecoveryCode(partial.recoveryState);
    if (observedRecovery) currentTurnRecoveryState = observedRecovery;
  }
  if (Object.prototype.hasOwnProperty.call(partial, "streamStatus")) {
    const nextStreamValue = streamStatusRailValue(partial);
    if (hasStatusRailValue(nextStreamValue) && nextStreamValue !== "-") {
      setStatusRailValue(dom.streamStatus, nextStreamValue);
    }
  }
  if (Object.prototype.hasOwnProperty.call(partial, "model")) {
    if (hasStatusRailValue(partial.model)) {
      setStatusRailValue(dom.modelStatus, `응답 ${modelStatusRailValue(partial.model, partial.answerMode || partial.streamStatus)}`);
    }
  }
  if (Object.prototype.hasOwnProperty.call(partial, "traceTurnId")) {
    setStatusRailValue(dom.traceStatus, traceRailLabel(partial.traceTurnId) || "ready");
  }
  if (Object.prototype.hasOwnProperty.call(partial, "traceTurn")) {
    setStatusRailValue(dom.traceStatus, traceRailLabel(partial.traceTurn) || "ready");
  }
  renderLiveDebugHeartbeat({
    streamStatus: partial.streamStatus,
    streamContext: partial.streamContext,
    answerMode: partial.answerMode,
    model: partial.model,
    modelBadge: partial.modelBadge,
    recoveryState: currentTurnRecoveryState
  });
  renderDecisionRibbon(presentObservedDecision({
    route: partial.pipelineSnapshot && typeof partial.pipelineSnapshot === "object"
      ? String(partial.pipelineSnapshot.route || "")
      : "",
    answerMode: partial.answerMode || "",
    streamStatus: partial.streamStatus || "",
    recoveryState: currentTurnRecoveryState,
    healthOverlay: currentTurnHealthOverlay
  }));
}

function findOverview(data, name) {
  return (Array.isArray(data?.debugOverview) ? data.debugOverview : [])
    .find((item) => item?.name === name) || {};
}

function findExternalEvidence(data, service) {
  return (Array.isArray(data?.externalEvidence) ? data.externalEvidence : [])
    .find((item) => item?.service === service) || {};
}

function statusOf(row, fallback = "WARN") {
  const status = String(row?.status || fallback).toUpperCase();
  return status === "OK" ? "OK" : "WARN";
}

function modelRuntimeStatus(modelRuntime, assistantModelFallback) {
  if (assistantModelFallback) return "WARN";
  const explicitStatus = String(modelRuntime?.status || "").toUpperCase();
  if (["OK", "WARN", "UNKNOWN"].includes(explicitStatus)) return explicitStatus;
  return modelRuntime?.finalDeliveryExpected || modelRuntime?.fastBailTimeout || modelRuntime?.waiting
    ? "WARN"
    : "UNKNOWN";
}

function externalEvidenceDetail(row, fallbackDetail = "scope:unknown needed:none next:none") {
  const scope = row?.evidenceScope || row?.projectScopeStatus || row?.browserSurface || "unknown";
  const needed = row?.evidenceNeeded || row?.disabledReason || "none";
  const next = row?.nextAction || "none";
  if (!row || (!row.evidenceScope && !row.evidenceNeeded && !row.nextAction)) {
    return fallbackDetail;
  }
  const statusParts = [];
  if (row.projectRefEnvStatus) statusParts.push(`projectScope:${row.projectRefEnvStatus}`);
  if (row.authEnvStatus) statusParts.push(`auth:${row.authEnvStatus}`);
  if (row.mcpConfigStatus) statusParts.push(`mcp:${row.mcpConfigStatus}`);
  if (row.probeFileStatus) statusParts.push(`probe:${row.probeFileStatus}`);
  if (row.smokeFileStatus) statusParts.push(`smoke:${row.smokeFileStatus}`);
  if (row.stale !== undefined && row.stale !== null) statusParts.push(`stale:${row.stale}`);
  if (row.appCount !== undefined && row.appCount !== null) statusParts.push(`apps:${row.appCount}`);
  const windowCount = row.targetableWindowCount ?? row.windowCount;
  if (windowCount !== undefined && windowCount !== null) statusParts.push(`windows:${windowCount}`);
  if (row.countOnly !== undefined && row.countOnly !== null) statusParts.push(`countOnly:${row.countOnly}`);
  const statusDetail = statusParts.length ? ` ${statusParts.join(' ')}` : '';
  return `scope:${scope} needed:${needed}${statusDetail} next:${next}`;
}

function compactActionSource(source) {
  const safe = safeDebugCockpitDetail(source || "unknown");
  if (safe === "browser_use") return "browser";
  if (safe === "supabase_apply" || safe === "supabase_smoke") return "supabase";
  if (safe === "computer_use") return "computer";
  return safe;
}

function normalizedNextAction(value) {
  const action = String(value ?? "").trim();
  return /^(|none|null|n\/a|unknown)$/i.test(action) ? "" : action;
}

function isClearedLocalLlmOperatorAction(operatorAction = {}) {
  const failureClass = String(operatorAction.failureClass || "").trim().toLowerCase();
  const triggerReason = String(operatorAction.triggerReason || "").trim().toLowerCase();
  return failureClass === "none"
    && !normalizedNextAction(operatorAction.nextAction)
    && (operatorAction.triggered === false || ["native_success", "recent_local_model_success"].includes(triggerReason));
}

function primaryExternalNextAction(goalNextRow, supabaseRow) {
  if (!goalNextRow && !supabaseRow) return "";
  const goalAction = normalizedNextAction(goalNextRow.nextAction) || normalizedNextAction(goalNextRow.firstAction);
  if (goalAction) return goalAction;
  if (isDesktopLocalReady(goalNextRow) && isReadOnlySupabaseProbeAction(supabaseRow)) {
    return "";
  }
  return normalizedNextAction(supabaseRow.nextAction);
}

function topActionQueueText(goalNextRow) {
  const actions = Array.isArray(goalNextRow?.topActions) ? goalNextRow.topActions : [];
  return actions
    .slice(0, 3)
    .map((item) => {
      const source = compactActionSource(item?.source);
      const action = compactNextActionText(item?.action || "");
      return action ? `${source}:${action}` : "";
    })
    .filter(Boolean)
    .join(" | ");
}

function isDesktopLocalReady(goalNextRow = {}) {
  const localReady = goalNextRow.localReady === true || goalNextRow.sourceHealthExit === 0;
  const completionReady = goalNextRow.completionReady === true || goalNextRow.completionAuditExit === 0;
  return localReady && completionReady && goalNextRow.localPatchJustified !== false;
}

function isReadOnlySupabaseProbeAction(supabaseRow = {}) {
  const nextAction = String(supabaseRow.nextAction || "");
  const scope = String(supabaseRow.evidenceScope || supabaseRow.projectScopeStatus || "");
  const needed = String(supabaseRow.evidenceNeeded || "");
  return /supabase_context_probe|readonly_supabase_context_probe|read_only_supabase/i.test(nextAction)
    && (/read[-_ ]?only/i.test(scope) || /project.*auth|auth.*project|unverified/i.test(needed));
}

function isOptionalSupabaseProofRow(row = {}) {
  if (!row || Object.keys(row).length === 0) return true;
  const scope = String(row.evidenceScope || row.projectScopeStatus || "");
  const needed = String(row.evidenceNeeded || "");
  return statusOf(row) === 'OK'
    || isReadOnlySupabaseProbeAction(row)
    || (/read[-_ ]?only/i.test(scope) && /project.*auth|auth.*project|unverified/i.test(needed));
}

function isOptionalBrowserProofRow(row = {}) {
  if (!row || Object.keys(row).length === 0) return true;
  const scope = String(row.evidenceScope || row.browserSurface || "");
  const needed = String(row.evidenceNeeded || "");
  const action = String(row.nextAction || "");
  return statusOf(row) === 'OK'
    || /local[-_ ]?ui[-_ ]?proof|iab/i.test(scope)
    || /^browser_ui_smoke_/i.test(needed)
    || /^run_browser_/i.test(action);
}

function isOptionalComputerProofRow(row = {}) {
  if (!row || Object.keys(row).length === 0) return true;
  const scope = String(row.evidenceScope || "");
  const needed = String(row.evidenceNeeded || "");
  const action = String(row.nextAction || "");
  return statusOf(row) === 'OK'
    || /gui[-_ ]?supporting[-_ ]?only/i.test(scope)
    || /^computer_use_smoke_/i.test(needed)
    || /^run_computer_use_/i.test(action);
}

function isStaleExternalEvidenceRow(row = {}) {
  const needed = String(row?.evidenceNeeded || "");
  return row?.stale === true || /(?:^|[_ -])stale(?:$|[_ -])/i.test(needed);
}

function isSupportingPatchDropEvidence(row = {}) {
  const evidenceNeeded = String(row?.evidenceNeeded || "");
  return (row?.activeTopLevelPatchCount ?? 0) === 0
    && /^(?:patchdrop_report_only_pending|nested_patchdrop_reference_not_apply_candidate)$/i.test(evidenceNeeded);
}

function isExplicitProducerEvidenceAction(value) {
  const action = normalizedNextAction(value);
  return /patch[_-]?drop|producer|macmini|notebook|noether|dispatch[_-]?packet|source[_-]?lease/i.test(action);
}

function isBlockingPatchDropEvidence(row = {}) {
  const evidenceNeeded = String(row?.evidenceNeeded || "");
  return (row?.activeTopLevelPatchCount ?? 0) > 0 || evidenceNeeded === 'patchdrop_root_missing';
}

function isDemandDrivenExternalEvidenceOnly(goalNextRow = {}, supabaseRow = {}, browserRow = {},
                                            computerRow = {}, patchDropRow = {}, noetherRow = {},
                                            producerEvidenceRows = []) {
  if (goalNextRow.localPatchJustified === false || goalNextRow.externalInputGateStatus === 'external_input_needed') {
    return false;
  }
  const goalAction = normalizedNextAction(goalNextRow.nextAction) || normalizedNextAction(goalNextRow.firstAction);
  if (goalAction && !isReadOnlySupabaseProbeAction({ ...supabaseRow, nextAction: goalAction })) {
    return false;
  }
  if (isBlockingPatchDropEvidence(patchDropRow)) {
    return false;
  }
  return isOptionalSupabaseProofRow(supabaseRow)
    && isOptionalBrowserProofRow(browserRow)
    && isOptionalComputerProofRow(computerRow);
}

function localBrowserProof(row) {
  const scope = row?.evidenceScope || row?.browserSurface || "local-ui-proof";
  if (scope !== "local-ui-proof" && scope !== "local_ui_proof") {
    return null;
  }
  if (row?.stale === true) {
    return null;
  }
  const needed = String(row?.evidenceNeeded || "none");
  if (needed !== "none" && needed !== "browser_ui_smoke_not_persisted_to_runtime") {
    return null;
  }
  return `scope:local-ui-proof proof:dom_ready heartbeat:json_rendered server:${row?.evidenceNeeded || "none"}`;
}

function compactHeartbeatDetail(name, detail) {
  const safe = safeDebugCockpitDetail(detail);
  if (name === "action") {
    return compactProtectedOperatorDetail(compactNextActionText(safe));
  }
  if (name === "model" || name === "answer") {
    return compactProtectedOperatorDetail(compactModelDebugDetail(safe));
  }
  if (["supabase", "browser", "computer", "harmony", "traceMemory"].includes(name)) {
    return compactProtectedOperatorDetail(compactExternalEvidenceDetail(name, safe));
  }
  return compactProtectedOperatorDetail(compactExternalEvidenceDetail(name, safe));
}

function compactVisibleDebugDetail(title, detail) {
  const safeTitle = String(title || "");
  if (safeTitle === "Action" || safeTitle === "Next") {
    return compactProtectedOperatorDetail(compactNextActionText(detail));
  }
  const evidenceName = safeTitle.toLowerCase();
  if (["supabase", "browser", "computer"].includes(evidenceName)) {
    return compactProtectedOperatorDetail(compactExternalEvidenceDetail(evidenceName, detail));
  }
  if (evidenceName === "model" || evidenceName === "answer" || evidenceName === "model/answer") {
    return compactProtectedOperatorDetail(compactModelDebugDetail(detail));
  }
  return compactProtectedOperatorDetail(safeDebugCockpitDetail(detail));
}

function compactOrchSignalText(name, status, detail) {
  const safe = safeDebugCockpitDetail(detail);
  if (name === "model") {
    return safe.includes("wait:none") && status === "OK" ? "Model live" : "Model check";
  }
  if (name === "dpp") {
    return safe.includes("lane:present") ? "DPP lane present" : "DPP lane missing";
  }
  if (name === "cfvm") {
    const lanes = safe.match(/lanes:(\d+\/\d+)/);
    return lanes ? `CFVM ${lanes[1]}` : "CFVM lanes";
  }
  if (name === "supabase") {
    const compact = compactHeartbeatDetail("supabase", safe);
    if (compact.includes("needed:none")) return "Supabase ready";
    if (compact.includes("project/auth unverified")) return "Supabase project/auth unverified";
    return "Supabase evidence needed";
  }
  return `${name} ${status === "OK" ? "OK" : "check"}`;
}

function setDebugHeartbeatField(name, status, detail) {
  const card = dom.debugHeartbeatBar?.querySelector(`[data-debug-heartbeat-field="${name}"]`);
  if (!card) return;
  card.dataset.status = status === "OK" ? "OK" : "WARN";
  const fullDetail = safeDebugCockpitDetail(detail);
  const compactDetail = compactHeartbeatDetail(name, detail);
  const detailTitle = ["supabase", "browser", "computer", "harmony", "traceMemory", "action"].includes(name)
    ? fullDetail
    : (compactDetail || fullDetail);
  const label = card.querySelector("strong, span")?.textContent?.trim() || name;
  const summary = compactDetail
    ? `${label}: ${card.dataset.status} - ${compactDetail}`
    : `${label}: ${card.dataset.status}`;
  card.setAttribute("aria-label", summary);
  card.title = detailTitle;
  const small = card.querySelector("small");
  if (small) {
    if (["supabase", "browser", "computer", "action", "harmony", "traceMemory"].includes(name)) {
      small.textContent = compactDetail || safeDebugCockpitDetail(detail);
    } else {
      small.textContent = compactDetail || safeDebugCockpitDetail(detail);
    }
    if (name === "computer" && small.textContent && !small.textContent.startsWith(" ")) {
      small.textContent = ` ${small.textContent}`;
    }
    small.title = detailTitle;
  }
  ensureDebugCellTextSeparators(card);
}

function setOrchSignalBadge(name, status, detail) {
  const badge = document.querySelector(`[data-orch-badge="${name}"]`);
  if (!badge) return;
  badge.dataset.status = status === "OK" ? "ok" : "warn";
  const badgeTitle = name === "model" ? "Model" : name;
  const badgeDetail = detail || status || name;
  badge.title = name === "supabase"
    ? safeDebugCockpitDetail(badgeDetail)
    : compactVisibleDebugDetail(badgeTitle, badgeDetail);
  badge.textContent = compactOrchSignalText(name, status, detail || status || name);
}

function setStatusRailHealth(status, detail) {
  if (!dom.healthStatus) return;
  dom.healthStatus.dataset.status = status === "OK" ? "ok" : "warn";
  const rawDetail = safeDebugCockpitDetail(detail);
  const compactDetail = compactHealthRailDetail(detail);
  dom.healthStatus.textContent = compactDetail;
  dom.healthStatus.title = rawDetail;
  const pill = dom.healthStatus.parentElement;
  if (pill) {
    const label = pill.querySelector("strong")?.textContent?.trim() || "Health";
    pill.setAttribute("aria-label", `${label}: ${compactDetail}`);
    pill.title = `${label}: ${compactDetail} (raw: ${rawDetail})`;
  }
}

function healthEvidenceState(name) {
  const match = String(lastServerHealth.detail || '').match(
    new RegExp(`\\b${name}:(OK|WARN|UNKNOWN|SUPPORTING)\\b`, 'i')
  );
  return match ? match[1].toUpperCase() : 'SUPPORTING';
}

function currentTurnHealthDetail() {
  return [
    `state:${currentTurnHealthOverlay.kind}`,
    `live:${currentTurnHealthOverlay.status}`,
    `stream:${safeDebugCockpitDetail(currentTurnHealthOverlay.streamStatus)}`,
    `context:${safeDebugCockpitDetail(currentTurnHealthOverlay.streamContext)}`,
    `proof:${healthEvidenceState('proof')}`,
    `external:${healthEvidenceState('external')}`
  ].join(' ');
}

function applyCurrentTurnHealthOverlay() {
  if (currentTurnHealthOverlay.kind === 'none') return false;
  setStatusRailHealth(currentTurnHealthOverlay.status, currentTurnHealthDetail());
  return true;
}

function setServerStatusRailHealth(status, detail) {
  lastServerHealth = {
    status: status === 'OK' ? 'OK' : 'WARN',
    detail: safeDebugCockpitDetail(detail)
  };
  if (!applyCurrentTurnHealthOverlay()) {
    setStatusRailHealth(lastServerHealth.status, lastServerHealth.detail);
  }
}

function resetCurrentTurnHealthOverlay() {
  currentTurnHealthOverlay.kind = 'none';
  currentTurnHealthOverlay.status = 'OK';
  currentTurnHealthOverlay.streamStatus = 'unknown';
  currentTurnHealthOverlay.streamContext = 'none';
  setStatusRailHealth(lastServerHealth.status, lastServerHealth.detail);
}

function syncCurrentTurnHealthOverlay(streamStatus, streamContext, flags = {}) {
  const statusText = String(streamStatus || 'unknown');
  const contextText = String(streamContext || 'none');
  const combined = `${statusText} ${contextText}`;
  let kind;
  let status;

  if (flags.streamStopped) {
    kind = 'stopped';
    status = 'OK';
  } else if (/timeout|deadline|error|message_failed|empty_stream_eof/i.test(combined)) {
    kind = 'attention';
    status = 'WARN';
  } else if (/model_wait/i.test(statusText) || /client-wait:\d+ms\s+next:stop_or_wait/i.test(contextText)) {
    kind = 'pending';
    status = 'WARN';
  } else if (flags.streamDone || flags.streamAnswerComplete) {
    resetCurrentTurnHealthOverlay();
    return;
  } else if (/connecting|attaching|streaming|thought|understanding|status|transformer|evidence|retrying/i.test(statusText)) {
    kind = 'responding';
    status = 'OK';
  } else {
    applyCurrentTurnHealthOverlay();
    return;
  }

  currentTurnHealthOverlay.kind = kind;
  currentTurnHealthOverlay.status = status;
  currentTurnHealthOverlay.streamStatus = statusText;
  currentTurnHealthOverlay.streamContext = contextText;
  applyCurrentTurnHealthOverlay();
}

function debugHeartbeatSummaryTitle(status, detail) {
  const text = String(detail || "");
  if (/stream:(cancelled|stopped)|context:(server-cancel|local-stop|local-detach|abort-error|server-cancel-unavailable)/i.test(text)) {
    return "Response stopped";
  }
  if (status === "OK") {
    return text.includes("wait:none") ? "Core live" : "Core active";
  }
  if (text.includes("external:WARN") || text.includes("External") || text.includes("supabase:WARN")) {
    return "External proof waiting";
  }
  if (/wait:(?!none)/i.test(text)) {
    return "Model wait";
  }
  return "Attention needed";
}

function setDebugHeartbeatSummary(status, detail) {
  const card = dom.debugHeartbeatBar?.querySelector("[data-debug-heartbeat-summary]");
  if (!card) return;
  card.dataset.status = status === "OK" ? "OK" : "WARN";
  const strong = card.querySelector("strong");
  const small = card.querySelector("small");
  const title = debugHeartbeatSummaryTitle(status, detail);
  const summary = `${title}: ${card.dataset.status} - ${safeDebugCockpitDetail(detail)}`;
  const primaryCode = String(dom.diagnosticsSummary?.dataset?.diagnosticCode || "");
  if (
    dom.diagnosticsSummary &&
    currentTurnHealthOverlay.kind === "none" &&
    (!primaryCode || primaryCode === "not-observed")
  ) {
    const diagnosticsTitle = card.dataset.status === "OK" ? "Core signals live" : title;
    setDiagnosticsSummaryPresentation(card.dataset.status, diagnosticsTitle);
  }
  card.setAttribute("aria-label", summary);
  card.title = summary;
  if (strong) strong.textContent = title;
  if (small) {
    small.textContent = safeDebugCockpitDetail(detail);
    small.title = safeDebugCockpitDetail(detail);
  }
  ensureDebugCellTextSeparators(card);
}

function setDebugMatrixCell(name, status, title, detail) {
  const cell = document.querySelector(`[data-debug-matrix-cell="${name}"]`);
  if (!cell) return;
  cell.dataset.status = status === "OK" ? "ok" : "warn";
  const strong = cell.querySelector("strong");
  const small = cell.querySelector("small");
  if (strong) strong.textContent = title;
  if (small) {
    small.textContent = safeDebugCockpitDetail(detail);
    small.textContent = compactVisibleDebugDetail(title, detail);
  }
  ensureDebugCellTextSeparators(cell);
  setDebugCellAccessible(cell, title, status, detail);
}

function setDebugCockpitCell(name, status, title, detail) {
  const cell = document.querySelector(`[data-debug-cockpit-cell="${name}"]`);
  if (!cell) return;
  cell.dataset.status = status === "OK" ? "ok" : "warn";
  const strong = cell.querySelector("strong");
  const small = cell.querySelector("small");
  if (strong) strong.textContent = title;
  if (small) {
    small.textContent = safeDebugCockpitDetail(detail);
    small.textContent = compactVisibleDebugDetail(title, detail);
  }
  ensureDebugCellTextSeparators(cell);
  setDebugCellAccessible(cell, title, status, detail);
}

function setDebugMissionAxis(name, status, title, value, detail) {
  const cell = document.querySelector(`[data-debug-mission-axis="${name}"]`);
  if (!cell) return;
  cell.dataset.status = status === "OK" ? "ok" : "warn";
  const strong = cell.querySelector("strong");
  const span = cell.querySelector("span");
  const small = cell.querySelector("small");
  if (strong) strong.textContent = title;
  if (span) span.textContent = safeDebugCockpitDetail(value);
  if (small) {
    small.textContent = safeDebugCockpitDetail(detail);
    small.textContent = compactVisibleDebugDetail(title, detail);
  }
  ensureDebugCellTextSeparators(cell);
  setDebugCellAccessible(cell, title, status, detail, value);
}

function firstWarnMissionAxis(...axes) {
  return axes.find((axis) => axis.status === "WARN") || axes[0] || {
    status: "OK",
    value: "ok",
    detail: "all_clear"
  };
}

function summarizeMissingLaneNames(rows) {
  const names = rows.map((item) => item?.name).filter(Boolean);
  if (names.length <= 2) return names.join(",");
  return `${names.slice(0, 2).join(",")},+${names.length - 2}`;
}

function setDebugFlowStep(name, status, title, value, detail) {
  const cell = document.querySelector(`[data-debug-flow-step="${name}"]`);
  if (!cell) return;
  cell.dataset.status = status === "OK" ? "ok" : "warn";
  const strong = cell.querySelector("strong");
  const span = cell.querySelector("span");
  const small = cell.querySelector("small");
  if (strong) strong.textContent = title;
  if (span) span.textContent = safeDebugCockpitDetail(value);
  if (small) {
    small.textContent = safeDebugCockpitDetail(detail);
    small.textContent = compactVisibleDebugDetail(title, detail);
  }
  ensureDebugCellTextSeparators(cell);
  setDebugCellAccessible(cell, title, status, detail, value);
}

function setDebugProofCell(name, status, title, value, detail) {
  const cell = document.querySelector(`[data-debug-proof-cell="${name}"]`);
  if (!cell) return;
  cell.dataset.status = status === "OK" ? "ok" : "warn";
  const strong = cell.querySelector("strong");
  const span = cell.querySelector("span");
  const small = cell.querySelector("small");
  if (strong) strong.textContent = title;
  if (span) span.textContent = safeDebugCockpitDetail(value);
  if (small) {
    small.textContent = safeDebugCockpitDetail(detail);
    small.textContent = compactVisibleDebugDetail(title, detail);
  }
  ensureDebugCellTextSeparators(cell);
  setDebugCellAccessible(cell, title, status, detail, value);
}

function refreshDebugHeartbeatSummary() {
  const detail = `live:${debugHeartbeatSummaryState.liveStatus} wait:${debugHeartbeatSummaryState.waitStatus} timeout:${debugHeartbeatSummaryState.timeoutStatus} cancel:${debugHeartbeatSummaryState.cancelStatus} next:${debugHeartbeatSummaryState.nextAction}`;
  setDebugHeartbeatSummary(debugHeartbeatSummaryState.liveStatus, detail);
}

let lastLocalLlmRecovery = null;

function localLlmRecoveryDetail(recovery) {
  if (!recovery || recovery.state === 'DISABLED' || recovery.state === 'STOPPED') return '';
  const port = Number.isInteger(recovery.port) ? recovery.port : 11435;
  if (recovery.state === 'READY' && recovery.modelReady === true) return `${port} Ollama와 대상 모델이 준비되었습니다.`;
  if (['PROBING', 'STARTING', 'SERVER_READY', 'MODEL_WARMING'].includes(recovery.state)) return `${port} Ollama 자동 복구를 시도하고 있습니다.`;
  if (recovery.fallbackUsed) return 'GPU 또는 모델 로딩 장애가 계속되어 로컬 경로를 중단하고 대체 경로로 전환했습니다.';
  if (recovery.reasonCode === 'GPU_UNAVAILABLE') return '로컬 GPU 경로를 복구하지 못했습니다. GPU·드라이버·PCIe 연결 상태를 확인해 주세요.';
  return `${port} Ollama 로컬 경로를 일시 중단했습니다. 자동 재검증을 기다리고 있습니다.`;
}

function renderRuntimeToolkit(snapshot) {
  const summary = document.getElementById('runtimeToolkitStatus');
  const services = document.getElementById('runtimeToolkitServices');
  if (!summary || !services) return;
  const data = snapshot || {};
  const full = data.FULL_LOAD_READY === true;
  summary.dataset.status = full ? 'OK' : 'WARN';
  summary.textContent = `${full ? 'FULL_LOAD_READY' : '검증 대기'} · 필수 ${Number(data.requiredReady) || 0}/${Number(data.requiredTotal) || 0} · 선택 ${Number(data.optionalReady) || 0}/${Number(data.optionalTotal) || 0}`;
  const rows = Array.isArray(data.services) ? data.services : [];
  services.textContent = rows.length ? rows.map(row => {
    const ownership = row.processOwned ? 'owned' : row.ownership || 'external / 미확인';
    return [row.serviceId, row.required ? '필수' : '선택', row.transport,
      `기본:${row.primaryPathStatus || '미검증'}`, `기능:${row.functionalStatus || '미검증'}`,
      row.status, row.reasonCode, `port:${row.configuredPort || '-'} / actual:${row.actualPort || '-'}`,
      `PID:${row.processPid || '-'} ${ownership}`, `restart:${row.restartCount || 0}`,
      `protocol:${row.protocolVersion || '-'}`, `tools:${row.toolCount || 0}`,
      `smoke:${row.smokeResult || '미검증'}`, `health:${row.lastHealthyAt || '미검증'}`,
      row.networkExposure].map(value => safeHeartbeatText(String(value || ''))).filter(Boolean).join(' · ');
  }).join('\n') : '현재 런타임 증거가 없습니다.';
  // This dedicated status never changes the primary diagnostic or requests recovery.
}

function renderDebugHeartbeat(data = {}) {
  lastLocalLlmRecovery = data.localLlmRecovery || null;
  renderRuntimeToolkit(data.runtimeToolkit);
  const recoveryDetail = localLlmRecoveryDetail(lastLocalLlmRecovery);
  const recoveryCard = dom.debugHeartbeatBar?.querySelector('[data-debug-heartbeat-field="model"]');
  if (recoveryCard) recoveryCard.style.display = recoveryDetail ? 'grid' : '';
  const externalRows = Array.isArray(data.externalEvidence) ? data.externalEvidence : [];
  const coreRow = findOverview(data, "coreRuntime");
  const uiRow = findOverview(data, "uiDebug");
  const externalRow = findOverview(data, "externalEvidence");
  const goalNextRow = externalRows.find((item) => item?.service === 'goal-next-auto') || {};
  const supabaseRow = externalRows.find((item) => item?.service === 'supabase') || {};
  const browserRow = externalRows.find((item) => item?.service === 'browser') || {};
  const computerRow = externalRows.find((item) => item?.service === 'computer-use') || {};
  const noetherRow = externalRows.find((item) => item?.service === 'noether') || {};
  const patchDropRow = externalRows.find((item) => item?.service === 'patchdrop') || {};
  const producerEvidenceRows = externalRows.filter((item) => item?.producerNode || item?.pendingProducerNode);
  const providerRows = Array.isArray(data.webProviders) ? data.webProviders : [];
  const providerStatusRows = Array.isArray(data.providerStatus) ? data.providerStatus : [];
  const providerStatusDetail = providerStatusSearchSummary(providerStatusRows);
  const providerRuntime = data.providerRuntime || {};
  const failSoftLadder = data.failSoftLadder || {};
  const traceSnapshotHealth = data.traceSnapshotHealth || {};
  const chatHarmony = data.chatHarmony || {};
  const traceMemory = data.traceMemory || {};
  const queryRewrite = data.queryRewrite || {};
  const debugAiMetrics = data.debugAiMetrics || {};
  const chatUsageDetail = chatUsageSummary(debugAiMetrics.chatUsage || debugAiMetrics.scorecard?.chatUsage || {});
  lastChatUsageDetail = chatUsageDetail;
  const memoryGate = data.memoryGate || {};
  const laneRows = Array.isArray(data.lanes) ? data.lanes : [];
  const strategyRows = Array.isArray(data.strategyPerformances) ? data.strategyPerformances : [];
  const hotspotRows = Array.isArray(data.hotspotDistribution) ? data.hotspotDistribution : [];
  const failureRows = Array.isArray(data.recentFailures) ? data.recentFailures : [];
  const answerOutput = data.answerOutput || {};
  const modelRuntime = data.modelRuntime || {};
  const heartbeatRow = findOverview(data, "heartbeat");
  const heartbeatFallbackActive = Boolean(data.reason || heartbeatRow.reason || heartbeatRow.detail);
  const heartbeatReason = safeHeartbeatText(data.reason || heartbeatRow.reason || heartbeatRow.detail || "");
  const heartbeatStatus = data.status ? statusOf(data) : statusOf(heartbeatRow);

  const localReady = goalNextRow.localReady === true || goalNextRow.sourceHealthExit === 0;
  const completionReady = goalNextRow.completionReady === true || goalNextRow.completionAuditExit === 0;
  const demandDrivenExternalOnly = isDemandDrivenExternalEvidenceOnly(
    goalNextRow, supabaseRow, browserRow, computerRow, patchDropRow, noetherRow, producerEvidenceRows
  );
  const producerAction = normalizedNextAction(goalNextRow.nextAction) || normalizedNextAction(goalNextRow.firstAction);
  const explicitProducerEvidenceRequired = isExplicitProducerEvidenceAction(producerAction);
  const blockingPatchDrop = isBlockingPatchDropEvidence(patchDropRow);
  const nextExternalAction = primaryExternalNextAction(goalNextRow, supabaseRow);
  const effectiveNextExternalAction = demandDrivenExternalOnly ? "" : nextExternalAction;
  const desktopOnlyReady = (isDesktopLocalReady(goalNextRow) || demandDrivenExternalOnly)
    && !effectiveNextExternalAction
    && !blockingPatchDrop;
  const coreStatus = heartbeatFallbackActive && !coreRow.status ? heartbeatStatus : statusOf(coreRow);
  const uiStatus = heartbeatFallbackActive && !uiRow.status ? heartbeatStatus : statusOf(uiRow);
  const externalStatus = heartbeatFallbackActive && !externalRow.status ? heartbeatStatus : statusOf(externalRow);
  const gateStatus = !desktopOnlyReady && (goalNextRow.decision === 'evidence_needed' || goalNextRow.externalInputGateStatus === 'external_input_needed' || goalNextRow.localPatchJustified === false) ? 'WARN' : 'OK';
  const actionStatus = !desktopOnlyReady && (goalNextRow.localPatchJustified === false || goalNextRow.decision === 'evidence_needed') ? 'WARN' : 'OK';
  const explicitLocalLlmOperatorAction = modelRuntime.localLlmOperatorAction || {};
  const explicitLocalLlmOperatorPresent = Boolean(explicitLocalLlmOperatorAction.failureClass || explicitLocalLlmOperatorAction.nextAction || explicitLocalLlmOperatorAction.triggerReason || explicitLocalLlmOperatorAction.upstreamFailureClass);
  if (explicitLocalLlmOperatorPresent) {
    lastAssistantModelFallback = null;
  }
  const assistantModelFallback = explicitLocalLlmOperatorPresent ? null : lastAssistantModelFallback;
  const modelStatus = recoveryDetail
    ? (lastLocalLlmRecovery.state === 'READY' && lastLocalLlmRecovery.modelReady === true
        && (!explicitLocalLlmOperatorPresent || isClearedLocalLlmOperatorAction(explicitLocalLlmOperatorAction)) ? 'OK' : 'WARN')
    : modelRuntimeStatus(modelRuntime, assistantModelFallback);
  const localLlmOperatorAction = explicitLocalLlmOperatorAction;
  const localLlmOperatorPresent = explicitLocalLlmOperatorPresent;
  const localLlmOperatorCleared = isClearedLocalLlmOperatorAction(localLlmOperatorAction);
  const localLlmUpstreamDetail = localLlmOperatorAction.upstreamFailureClass
    ? ` upstream:${localLlmOperatorAction.upstreamFailureClass} status:${localLlmOperatorAction.upstreamStatus ?? 'n/a'} upstreamNext:${localLlmOperatorAction.upstreamNextAction || 'none'}`
    : '';
  const localLlmOperatorDetail = localLlmOperatorPresent
    ? ` llm:${localLlmOperatorAction.failureClass || 'none'} next:${localLlmOperatorAction.nextAction || 'none'} score:${localLlmOperatorAction.actionScore ?? 0}${localLlmUpstreamDetail}`
    : '';
  const primaryModelNextAction = localLlmOperatorPresent && !localLlmOperatorCleared
    ? (normalizedNextAction(localLlmOperatorAction.nextAction) || normalizedNextAction(localLlmOperatorAction.upstreamNextAction) || 'inspect_model_route_or_start_local_llm')
    : null;
  const coreDetail = coreRow.detail || coreRow.reason || heartbeatReason || "core";
  const uiDetail = uiRow.detail || uiRow.reason || (heartbeatFallbackActive ? "heartbeat_json_available" : "ui");
  const externalDetail = externalRow.detail || externalRow.reason || (heartbeatFallbackActive ? "admin_pipeline_not_exposed" : "external");
  const gateDetail = `gate:${goalNextRow.externalInputGateStatus || 'unknown'} source:${goalNextRow.sourceHealthExit ?? 'n/a'} audit:${goalNextRow.completionAuditExit ?? 'n/a'} localPatch:${goalNextRow.localPatchJustified === false ? 'no' : 'yes'}`;
  const modelRuntimeDetail = `route:${modelRuntime.route || 'unknown'} delivery:${modelRuntime.deliveryState || 'unknown'} wait:${modelRuntime.defaultWaitCode || 'none'} hits:${modelRuntime.timeoutHits ?? 0} len:${modelRuntime.modelLength ?? 0} evidence:${modelRuntime.reason || 'unknown'} source:${modelRuntime.source || 'unknown'}`;
  const modelDetail = recoveryDetail
    ? `${recoveryDetail}${localLlmOperatorPresent && !localLlmOperatorCleared ? localLlmOperatorDetail : ''}`
    : assistantModelFallback?.modelDetail || `${modelRuntimeDetail}${localLlmOperatorDetail}`;
  if (!supabaseRow.projectScopeStatus && supabaseRow.evidenceScope) supabaseRow.projectScopeStatus = supabaseRow.evidenceScope;
  if (supabaseRow.evidenceNeededCount == null && supabaseRow.evidenceNeeded) supabaseRow.evidenceNeededCount = supabaseRow.evidenceNeeded;
  if (!supabaseRow.requiredEnvNames && (supabaseRow.projectRefEnvStatus || supabaseRow.authEnvStatus || supabaseRow.mcpConfigStatus || supabaseRow.probeFileStatus)) {
    supabaseRow.requiredEnvNames = `projectScope:${supabaseRow.projectRefEnvStatus || 'unknown'} auth:${supabaseRow.authEnvStatus || 'unknown'} mcp:${supabaseRow.mcpConfigStatus || 'unknown'} probe:${supabaseRow.probeFileStatus || 'unknown'}`;
  }
  if (!supabaseRow.requiredMcpTools && supabaseRow.nextAction) supabaseRow.requiredMcpTools = `next:${supabaseRow.nextAction}`;
  const supabaseRequiredEnv = supabaseRow.requiredEnvNames || goalNextRow.supabaseRequiredEnvNames || 'none';
  const supabaseRequiredMcp = supabaseRow.requiredMcpTools || goalNextRow.supabaseRequiredMcpTools || 'none';
  const supabaseStatus = statusOf(supabaseRow);
  const supabaseDetail = `scope:${supabaseRow.projectScopeStatus || 'unknown'} needed:${supabaseRow.evidenceNeededCount ?? goalNextRow.supabaseEvidenceNeededCount ?? 0} env:${supabaseRequiredEnv} mcp:${supabaseRequiredMcp} next:${supabaseRow.nextAction || 'none'}`;
  const browserRawStatus = statusOf(browserRow);
  const browserLocalProofDetail = localBrowserProof(browserRow);
  const browserStatus = browserLocalProofDetail ? 'OK' : browserRawStatus;
  const browserProofStatus = browserStatus;
  const browserDetail = browserLocalProofDetail || externalEvidenceDetail(browserRow, `surface:${browserRow.browserSurface || 'unknown'} evidence:${browserRow.evidenceNeeded || 'none'} next:${browserRow.nextAction || 'none'}`);
  const computerStatus = computerRow.status || (computerRow.reachable && !computerRow.stale ? 'OK' : 'WARN');
  const computerDetail = `apps:${computerRow.appCount ?? 0} windows:${computerRow.targetableWindowCount ?? computerRow.windowCount ?? 0} stale:${computerRow.stale ? 'yes' : 'no'}`;
  const computerEvidenceDetail = `${externalEvidenceDetail(computerRow, computerDetail)} ${computerDetail}`;
  const browserStaleOnlyWarn = String(browserRow.evidenceNeeded || '') === 'browser_ui_smoke_stale'
    && browserRow.stale === true
    && (browserRow.secretHits ?? 0) === 0
    && browserRow.reachable === true
    && browserRow.targetAccepted === true
    && browserRow.targetContentVisible === true;
  const browserSupportingProof = isDesktopLocalReady(goalNextRow) && desktopOnlyReady && browserStatus === 'WARN' && isOptionalBrowserProofRow(browserRow) && isStaleExternalEvidenceRow(browserRow) && browserStaleOnlyWarn;
  const browserUiStatus = browserSupportingProof ? 'SUPPORTING' : browserStatus;
  const browserUiDetail = browserSupportingProof ? `supporting ${browserDetail}` : browserDetail;
  const computerStaleOnlyWarn = String(computerRow.evidenceNeeded || '') === 'computer_use_smoke_stale'
    || (computerRow.status == null && computerRow.stale === true && computerRow.reachable === true);
  const computerSupportingProof = isDesktopLocalReady(goalNextRow) && desktopOnlyReady && computerStatus === 'WARN' && isOptionalComputerProofRow(computerRow) && isStaleExternalEvidenceRow(computerRow) && computerStaleOnlyWarn;
  const computerUiStatus = computerSupportingProof ? 'SUPPORTING' : computerStatus;
  const computerUiDetail = computerSupportingProof ? `supporting ${computerEvidenceDetail}` : computerEvidenceDetail;
  const noetherStatus = noetherRow.status || (noetherRow.responded ? 'OK' : 'WARN');
  const noetherDetail = `wait:${noetherRow.waiting ? 'yes' : 'no'} reply:${noetherRow.responded ? 'yes' : 'no'} kind:${noetherRow.lastMessageKind || 'unknown'}`;
  const staleEvidenceRows = externalRows.filter((item) => item?.stale === true);
  const evidenceAgeMinutes = externalRows.map((item) => Number(item?.ageMinutes)).filter(Number.isFinite);
  const maxEvidenceAgeMinutes = evidenceAgeMinutes.length ? Math.max(...evidenceAgeMinutes) : 0;
  const freshnessStatus = externalRows.length === 0 || staleEvidenceRows.length > 0 ? 'WARN' : 'OK';
  const freshnessDetail = `stale:${staleEvidenceRows.length}/${externalRows.length} maxAge:${Math.round(maxEvidenceAgeMinutes)}m`;
  const supportingPatchDrop = isSupportingPatchDropEvidence(patchDropRow) && !explicitProducerEvidenceRequired;
  const patchDropStatus = supportingPatchDrop
    ? 'OK'
    : (patchDropRow.status || ((patchDropRow.activeTopLevelPatchCount ?? 0) > 0 || (patchDropRow.nestedProducerPatchCount ?? 0) > 0 || (patchDropRow.reportOnlyPendingCount ?? 0) > 0 ? 'WARN' : 'OK'));
  const patchDropDetail = `${supportingPatchDrop ? 'supporting ' : ''}top:${patchDropRow.activeTopLevelPatchCount ?? 0} nested:${patchDropRow.nestedProducerPatchCount ?? 0} report:${patchDropRow.reportOnlyPendingCount ?? 0}`;
  const actionSource = desktopOnlyReady ? 'desktop' : (goalNextRow.firstActionSource || goalNextRow.nextActionSource || 'unknown');
  const actionDecision = desktopOnlyReady ? 'desktop only ready' : (goalNextRow.decision || data.decision || 'unknown');
  const topActionQueue = topActionQueueText(goalNextRow);
  const actionDetail = `next:${effectiveNextExternalAction || 'none'}`;
  const actionRuntimeDetail = `${actionDetail} source:${compactActionSource(actionSource)} decision:${actionDecision} queue:${topActionQueue || 'none'}`;
  const answerMode = answerOutput.answerMode || data.answerMode || data.streamStatus;
  const visibleAnswerMode = dom.streamStatus?.textContent || dom.modelStatus?.textContent;
  const answerOutputStatus = answerOutput.status || (answerOutput.emptyAnswerGuardTriggered || answerOutput.blankBaseFallback ? 'WARN' : 'OK');
  const directLiteralAnswerComplete = !assistantModelFallback && answerOutputStatus === 'OK' && isDirectLiteralMode(answerMode, visibleAnswerMode);
  const supplementalSearchRow = providerRows.find((item) => item?.provider === 'supplemental-multi-search') || {};
  const supplementalSearchProviderCount = Number(supplementalSearchRow.providerCount ?? 0);
  const supplementalSearchState = supplementalSearchProviderCount > 0 ? 'on' : (supplementalSearchRow.optional ? 'disabled' : 'unknown');
  const searchEnabled = providerRows.filter((item) => item?.hasKey === true).length;
  const rawSearchStatus = providerRuntime.status === 'WARN' || failSoftLadder.status === 'WARN' || providerRows.some((item) => item?.status === 'WARN') ? 'WARN' : (providerRuntime.status || failSoftLadder.status || (searchEnabled > 0 ? 'OK' : 'WARN'));
  const rawSearchDetail = `providers:${providerStatusDetail} enabled:${searchEnabled}/${providerRows.length} supplemental:${supplementalSearchState} supplementalCount:${supplementalSearchProviderCount} timeouts:${providerRuntime.awaitTimeoutCount ?? 0} cancels:${providerRuntime.cancelSuppressedCount ?? 0} cache:${failSoftLadder.cacheOnlyMergedCount ?? 0} vector:${failSoftLadder.vectorFallbackUsed ? 'yes' : 'no'}`;
  const rawLadderStatus = failSoftLadder.status || (failSoftLadder.poolSafeEmpty ? 'WARN' : 'OK');
  const rawLadderDetail = `out:${failSoftLadder.outCount ?? 0} tracePool:${failSoftLadder.tracePoolSize ?? 0} rescue:${failSoftLadder.rescueMergeUsed ? 'yes' : 'no'} trigger:${failSoftLadder.starvationFallbackTrigger || 'none'}`;
  const idleRetrievalWarmup = !directLiteralAnswerComplete && isIdleRetrievalWarmupState(
    answerOutput, modelRuntime, providerRuntime, failSoftLadder, visibleAnswerMode, answerMode
  );
  const searchStatus = directLiteralAnswerComplete || idleRetrievalWarmup ? 'OK' : rawSearchStatus;
  const searchDetail = directLiteralAnswerComplete
    ? `${rawSearchDetail} bypassed:DIRECT_LITERAL`
    : idleRetrievalWarmup ? `${rawSearchDetail} idle:no_query` : rawSearchDetail;
  const ladderStatus = directLiteralAnswerComplete || idleRetrievalWarmup ? 'OK' : rawLadderStatus;
  const ladderDetail = directLiteralAnswerComplete
    ? `${rawLadderDetail} bypassed:DIRECT_LITERAL`
    : idleRetrievalWarmup ? `${rawLadderDetail} idle:no_query` : rawLadderDetail;
  const traceStatus = traceSnapshotHealth.status || (traceSnapshotHealth.available ? 'OK' : 'WARN');
  const traceDetail = `summaries:${traceSnapshotHealth.summaryCount ?? 0} entries:${traceSnapshotHealth.latestTraceEntryCount ?? 0} events:${traceSnapshotHealth.latestEventCount ?? 0} error:${traceSnapshotHealth.latestErrorPresent ? 'yes' : 'no'}`;
  const harmonyDegraded = chatHarmony.latestDegraded === true || chatHarmony.latestDegraded === 'true';
  const harmonyStatus = harmonyDegraded ? 'WARN' : (chatHarmony.status || (chatHarmony.latestAgentVisible ? 'OK' : 'WARN'));
  const harmonyDetail = `degraded:${harmonyDegraded ? 'true' : 'false'} action:${chatHarmony.latestDebugAction || 'wait_for_chat_harmony_trace'} reason:${chatHarmony.latestDebugReason || chatHarmony.latestReason || 'unknown'} route:${chatHarmony.latestTraceRoute || 'none'}`;
  const traceMemoryTriggered = traceMemory.latestTriggered === true || traceMemory.latestTriggered === 'true';
  const traceMemoryQuarantine = traceMemory.latestQuarantine === true || traceMemory.latestQuarantine === 'true';
  const traceMemoryRisk = traceMemory.latestRisk || 'unknown';
  const traceMemoryRecoveryRoute = traceMemory.latestRecoveryRoute || 'none';
  const traceMemoryRecoveryDecision = traceMemory.latestRecoveryRouteDecision || 'none';
  const traceMemorySuspectIsolated = traceMemory.suspectPayloadIsolated === true || traceMemory.suspectPayloadIsolated === 'true';
  const traceMemoryCfvmDetail = traceMemory.latestCfvmOffered === true || traceMemory.latestCfvmOffered === 'true'
    ? `offered:${traceMemory.latestCfvmPatternId ?? 0}`
    : 'not-offered';
  const traceMemoryStatus = traceMemoryTriggered || traceMemoryQuarantine || traceMemoryRisk === 'BREAK' || traceMemoryRisk === 'WARN'
    ? 'WARN'
    : (traceMemory.status || 'WARN');
  const traceMemoryChangedDetail = `changed:${traceMemory.latestFingerprintChanged === true || traceMemory.latestFingerprintChanged === 'true' ? 'true' : 'false'}`;
  const traceMemoryRecoveryDetail = `recovery:${traceMemory.latestRecoveryAction || 'inspect_trace_memory_trace'} route:${traceMemoryRecoveryRoute} decision:${traceMemoryRecoveryDecision} risk:${traceMemoryRisk} quarantine:${traceMemoryQuarantine ? 'true' : 'false'} isolated:${traceMemorySuspectIsolated ? 'true' : 'false'} cfvm:${traceMemoryCfvmDetail}`;
  const traceMemoryModeDetail = traceMemoryTriggered || traceMemoryQuarantine || traceMemoryRisk === 'BREAK' || traceMemoryRisk === 'WARN'
    ? traceMemoryRecoveryDetail
    : traceMemoryChangedDetail;
  const traceMemoryVirtualStage = traceMemory.latestVirtualCheckpointStage || traceMemory.latestStage || 'unknown';
  const traceMemoryVirtualPhase = traceMemory.latestVirtualCheckpointPhase || traceMemory.latestPhase || 'unknown';
  const traceMemoryVirtualKey = traceMemory.latestVirtualCheckpointKey || 'unknown';
  const traceMemoryDetail = `stage:${traceMemoryVirtualStage} phase:${traceMemoryVirtualPhase} vkey:${traceMemoryVirtualKey} checkpoints:${traceMemory.latestCheckpointHistorySize ?? 0} ${traceMemoryModeDetail} route:${traceMemory.latestTraceRoute || 'none'} json:${traceMemory.latestCheckpointJsonRoute || 'none'}`;
  const metricsStatus = debugAiMetrics.status || (Number(debugAiMetrics.errorEvents || 0) > 0 || Number(debugAiMetrics.warnEvents || 0) > 0 ? 'WARN' : 'OK');
  const virtualMatrixCount = debugAiMetrics.virtualMatrixCount ?? debugAiMetrics.scorecard?.virtualMatrixCount ?? 0;
  const virtualMatrixChunkCount = debugAiMetrics.virtualMatrixChunkCount ?? debugAiMetrics.scorecard?.virtualMatrixChunkCount ?? 0;
  const virtualMatrixScoreRole = debugAiMetrics.virtualMatrixScoreRole || debugAiMetrics.scorecard?.virtualMatrixScoreRole || 'evidence';
  const virtualMatrixScoreTrusted = debugAiMetrics.virtualMatrixScoreTrusted ?? debugAiMetrics.scorecard?.virtualMatrixScoreTrusted ?? false;
  const virtualMatrixDecision = debugAiMetrics.virtualMatrixDecision || debugAiMetrics.scorecard?.virtualMatrixDecision || 'observe';
  const virtualMatrixActionAllowed = debugAiMetrics.virtualMatrixActionAllowed ?? debugAiMetrics.scorecard?.virtualMatrixActionAllowed ?? false;
  const historyComparisonComparable = debugAiMetrics.historyComparisonComparable ?? debugAiMetrics.scorecard?.historyComparisonComparable ?? false;
  const historyComparisonReason = debugAiMetrics.historyComparisonReason || debugAiMetrics.scorecard?.historyComparisonReason || 'baseline_missing';
  const currentWindowMs = debugAiMetrics.currentWindowMs ?? debugAiMetrics.scorecard?.currentWindowMs ?? 0;
  const previousWindowMs = debugAiMetrics.previousWindowMs ?? debugAiMetrics.scorecard?.previousWindowMs ?? 0;
  const currentSampleLimit = debugAiMetrics.currentSampleLimit ?? debugAiMetrics.scorecard?.currentSampleLimit ?? 0;
  const previousSampleLimit = debugAiMetrics.previousSampleLimit ?? debugAiMetrics.scorecard?.previousSampleLimit ?? 0;
  const virtualMatrixHotChunkIndex = debugAiMetrics.hotChunkIndex ?? debugAiMetrics.scorecard?.virtualMatrixHotChunkIndex ?? -1;
  const virtualMatrixHotChunkRiskScore = debugAiMetrics.hotChunkRiskScore ?? debugAiMetrics.scorecard?.virtualMatrixHotChunkRiskScore ?? 0;
  const matrixHotChunkDetail = Number(virtualMatrixHotChunkIndex) >= 0 ? ` hot:${virtualMatrixHotChunkIndex} risk:${virtualMatrixHotChunkRiskScore}` : '';
  const metricsDetail = `matrix:${virtualMatrixCount}/${virtualMatrixChunkCount} decision:${virtualMatrixDecision} role:${virtualMatrixScoreRole} trusted:${virtualMatrixScoreTrusted} actionAllowed:${virtualMatrixActionAllowed} historyComparable:${historyComparisonComparable} compare:${historyComparisonReason} window:${currentWindowMs}/${previousWindowMs} limit:${currentSampleLimit}/${previousSampleLimit}${matrixHotChunkDetail}`;
  const memoryStatus = memoryGate.status || (Number(memoryGate.quarantined || 0) > 0 || Number(memoryGate.stale || 0) > 0 ? 'WARN' : 'OK');
  const memoryDetail = `active:${memoryGate.active ?? 0}/${memoryGate.total ?? 0} pending:${memoryGate.pending ?? 0} quarantine:${memoryGate.quarantined ?? 0} stale:${memoryGate.stale ?? 0}`;
  const disabledLaneRows = laneRows.filter((item) => item?.status === 'DISABLED' || item?.enabled === false);
  const laneStatus = disabledLaneRows.length > 0 ? 'WARN' : 'OK';
  const disabledLaneNames = disabledLaneRows.map((item) => item?.name).filter(Boolean);
  const disabledLaneMore = disabledLaneNames.length > 2 ? `+${disabledLaneNames.length - 2}` : 'none';
  const laneDetail = `enabled:${laneRows.length - disabledLaneRows.length}/${laneRows.length} disabled:${disabledLaneRows.length} reason:${disabledLaneRows[0]?.disabledReason || 'none'}`;
  const laneRuntimeDetail = `enabled:${laneRows.length - disabledLaneRows.length}/${laneRows.length} disabled:${disabledLaneRows.length} missing:${disabledLaneNames.slice(0, 2).join(',') || 'none'} ${disabledLaneMore} reason:${disabledLaneRows[0]?.disabledReason || 'none'}`;
  const dppLane = laneRows.find((item) => item?.name === 'dppDiversityReranker') || {};
  const dppStatus = dppLane.name ? statusOf(dppLane) : 'WARN';
  const dppDetail = dppLane.name
    ? `lane:${dppLane.enabled === false ? 'missing' : 'present'} reason:${dppLane.disabledReason || 'none'}`
    : 'lane:missing reason:dpp_diversity_reranker_missing';
  const cfvmLaneNames = ['cfvmFailureRecorder', 'cfvmRawMatrixBuffer'];
  const cfvmRows = cfvmLaneNames.map((name) => laneRows.find((item) => item?.name === name)).filter(Boolean);
  const cfvmMissingNames = cfvmLaneNames.filter((name) => !laneRows.some((item) => item?.name === name));
  const cfvmDisabledRows = cfvmRows.filter((item) => item?.status === 'DISABLED' || item?.enabled === false);
  const cfvmStatus = cfvmMissingNames.length > 0 || cfvmDisabledRows.length > 0 ? 'WARN' : 'OK';
  const cfvmDetail = `lanes:${cfvmRows.length}/${cfvmLaneNames.length} missing:${cfvmMissingNames.join(',') || 'none'} reason:${cfvmDisabledRows[0]?.disabledReason || 'none'}`;
  const strategyStatus = strategyRows.length > 0 || hotspotRows.length > 0 ? 'OK' : 'WARN';
  const strategyTop = strategyRows[0]?.strategyName || strategyRows[0]?.strategy || hotspotRows[0]?.hotspot || hotspotRows[0]?.label || hotspotRows[0]?.name || 'none';
  const strategyDetail = `strategies:${strategyRows.length} hotspots:${hotspotRows.length} top:${strategyTop}`;
  const failureStatus = failureRows.length > 0 ? 'WARN' : 'OK';
  const failureDetail = `recent:${failureRows.length} class:${failureRows[0]?.failureClass || failureRows[0]?.classification || failureRows[0]?.reason || 'none'}`;
  const qtxLane = laneRows.find((item) => item?.name === 'queryTransformer') || {};
  const qtxHintDetail = queryRewriteTemperatureHintTokens(queryRewrite);
  const qtxTraceEntryCount = Number(queryRewrite.latestTraceEntryCount ?? 0);
  const qtxRewriteObserved = queryRewrite.enabled === true
    || queryRewrite.enabled === 'true'
    || Number(queryRewrite.branchCount ?? 0) > 0
    || Number(queryRewrite.verificationLaneCount ?? 0) > 0
    || Number(queryRewrite.explorationLaneCount ?? 0) > 0
    || qtxHintDetail.length > 0
    || qtxTraceEntryCount > 0;
  if (qtxRewriteObserved) latestStreamQueryRewriteHeartbeat = null;
  const qtxStatus = qtxLane.status
    || (qtxLane.enabled === false ? 'WARN' : (qtxRewriteObserved && queryRewrite.status ? queryRewrite.status : (latestStreamQueryRewriteHeartbeat?.status || 'OK')));
  const qtxCoverage = queryRewrite.coverageComplete === true || queryRewrite.coverageComplete === 'true';
  const qtxProfileDetail = queryRewrite.temperatureProfile ? ` profile:${queryRewrite.temperatureProfile}` : '';
  const qtxTraceDetail = qtxTraceEntryCount > 0 ? ` trace:${qtxTraceEntryCount}` : '';
  const qtxHintSummary = qtxHintDetail ? ` hints:${qtxHintDetail}` : '';
  const qtxRewriteDetail = qtxRewriteObserved
    ? `${qtxProfileDetail}${qtxTraceDetail}${qtxHintSummary} branches:${queryRewrite.branchCount ?? 0} axes:${queryRewrite.axisCount ?? 0} coverage:${qtxCoverage ? 'yes' : 'no'} lanes:${queryRewrite.verificationLaneCount ?? 0}/${queryRewrite.explorationLaneCount ?? 0} temp:${queryRewrite.validationTemperature ?? 0}/${queryRewrite.explorationTemperature ?? 0}`
    : '';
  const qtxDetail = qtxRewriteObserved || !latestStreamQueryRewriteHeartbeat
    ? `enabled:${qtxLane.enabled === false ? 'no' : 'yes'} reason:${qtxLane.disabledReason || 'none'}${qtxRewriteDetail}`
    : latestStreamQueryRewriteHeartbeat.detail;
  const causalLane = laneRows.find((item) => item?.name === 'causalProbe') || {};
  const causalStatus = causalLane.status || (causalLane.enabled === false ? 'WARN' : 'OK');
  const causalDetail = `phase:${causalLane.phase || 'not_observed'} hypothesis:${causalLane.dominantFailure || 'none'} evidence:${causalLane.counterEvidenceCount ?? 0} confidence:${causalLane.confidence ?? 0}->${causalLane.confidenceRange || 'pending'} retrieval:${causalLane.retrievalConfidence ?? 0} coherence:${causalLane.coherenceStatus || 'pending'} release:${causalLane.releaseStatus || 'hold'} gate:${causalLane.verificationGatePassed === true ? 'pass' : 'hold'}`;
  const retrieverLaneNames = ['webSearch', 'vectorSearch', 'kgSearch'];
  const retrieverRows = laneRows.filter((item) => retrieverLaneNames.includes(item?.name));
  const disabledRetrieverRows = retrieverRows.filter((item) => item?.status === 'DISABLED' || item?.enabled === false);
  const retrieverStatus = disabledRetrieverRows.length > 0 ? 'WARN' : 'OK';
  const retrieverDetail = `enabled:${retrieverRows.length - disabledRetrieverRows.length}/${retrieverRows.length} first:${disabledRetrieverRows[0]?.name || 'none'} reason:${disabledRetrieverRows[0]?.disabledReason || 'none'}`;
  const breakerLane = laneRows.find((item) => item?.name === 'circuitBreaker') || {};
  const breakerStatus = breakerLane.status || (breakerLane.enabled === false ? 'WARN' : 'OK');
  const breakerDetail = `state:${breakerLane.disabledReason || 'closed'} enabled:${breakerLane.enabled === false ? 'no' : 'yes'}`;
  const answerStatus = assistantModelFallback ? 'WARN' : answerOutputStatus;
  const answerRuntimeDetail = assistantModelFallback?.answerDetail || `mode:${answerOutput.answerMode || 'none'} guard:${answerOutput.emptyAnswerGuardTriggered ? 'yes' : 'no'} fallback:${answerOutput.emptyAnswerFallback || 'none'} docs:${answerOutput.evidenceDocs ?? 0}`;
  const answerDetail = `${chatUsageDetail} ${answerRuntimeDetail}`;
  const coreUiStatus = [coreStatus, uiStatus].includes('WARN') ? 'WARN' : 'OK';
  const coreUiDetail = `core:${coreStatus} ui:${uiStatus}`;
  const modelAnswerStatus = modelStatus !== 'OK' || answerStatus === 'WARN' ? 'WARN' : 'OK';
  const modelAnswerDetail = `model:${modelStatus} answer:${answerStatus}`;
  const searchTraceStatus = [searchStatus, ladderStatus, traceStatus].includes('WARN') ? 'WARN' : 'OK';
  const searchTraceDetail = `search:${searchStatus} trace:${traceStatus} providers:${providerStatusDetail}`;
  const liveStatus = [coreStatus, uiStatus].includes('WARN') || modelStatus !== 'OK' ? 'WARN' : 'OK';
  const externalProofStatus = [externalStatus, supabaseStatus, browserProofStatus, computerStatus, freshnessStatus, patchDropStatus].includes('WARN') ? 'WARN' : 'OK';
  const externalProofDetail = `supabase:${supabaseStatus} browser:${browserSupportingProof ? 'SUPPORTING' : browserProofStatus} computer:${computerSupportingProof ? 'SUPPORTING' : computerStatus}`;
  const externalOverviewBenign = String(externalRow.status || '').toUpperCase() === 'DISABLED'
    || isStaleExternalEvidenceRow(externalRow);
  const externalProofSeriousWarn = (externalStatus === 'WARN' && !externalOverviewBenign)
    || (supabaseStatus === 'WARN' && !(isOptionalSupabaseProofRow(supabaseRow) && isStaleExternalEvidenceRow(supabaseRow)))
    || (browserProofStatus === 'WARN' && !browserSupportingProof)
    || (computerStatus === 'WARN' && !computerSupportingProof)
    || (patchDropStatus === 'WARN' && !(isStaleExternalEvidenceRow(patchDropRow) || isSupportingPatchDropEvidence(patchDropRow)));
  const healthRailProofStatus = desktopOnlyReady && externalProofStatus === 'WARN' && !externalProofSeriousWarn ? 'SUPPORTING' : externalProofStatus;
  const healthRailExternalStatus = desktopOnlyReady && externalStatus === 'WARN' && externalOverviewBenign ? 'SUPPORTING' : externalStatus;
  const healthRailStatus = healthRailProofStatus === 'WARN' ? 'WARN' : liveStatus;
  const healthRailDetail = `live:${liveStatus} core:${coreStatus} ui:${uiStatus} model:${modelStatus} answer:${answerStatus} proof:${healthRailProofStatus} external:${healthRailExternalStatus}`;
  const externalProofUiStatus = healthRailProofStatus === 'SUPPORTING' ? 'OK' : externalProofStatus;
  const externalProofUiDetail = healthRailProofStatus === 'SUPPORTING' ? `supporting ${externalProofDetail}` : externalProofDetail;
  const externalSummaryStatus = healthRailExternalStatus === 'SUPPORTING' ? 'OK' : externalStatus;
  const externalHeartbeatStatus = healthRailExternalStatus === 'SUPPORTING' ? 'OK' : externalStatus;
  const externalHeartbeatDetail = healthRailExternalStatus === 'SUPPORTING' ? `supporting ${externalDetail}` : externalDetail;
  const localProofStatus = goalNextRow.sourceHealthExit === 0 && goalNextRow.completionAuditExit === 0 ? 'OK' : 'WARN';
  const localEvidenceStatus = localReady ? 'OK' : localProofStatus;
  const localGateDetail = `${gateDetail} localReady:${localReady ? 'yes' : 'no'} completionReady:${completionReady ? 'yes' : 'no'}`;
  const producerRequired = explicitProducerEvidenceRequired || blockingPatchDrop;
  const producerProofStatus = producerRequired
    ? (patchDropStatus === 'OK' && (producerEvidenceRows.length > 0 || noetherRow.responded === true) ? 'OK' : 'WARN')
    : 'OK';
  const producerProofDetail = producerRequired
    ? `rows:${producerEvidenceRows.length}`
    : `optional rows:${producerEvidenceRows.length} patchdrop:${patchDropStatus}`;
  const summaryStatus = [coreStatus, uiStatus, externalSummaryStatus, gateStatus, actionStatus].includes('WARN') || modelStatus !== 'OK' ? 'WARN' : 'OK';
  const waitStatus = modelRuntime.defaultWaitCode || modelRuntime.deliveryState || "none";
  const timeoutStatus = providerRuntime.awaitTimeoutCount ? "WARN" : "OK";
  const cancelStatus = providerRuntime.cancelSuppressedCount ? "WARN" : "OK";
  const summaryNextAction = primaryModelNextAction
    || effectiveNextExternalAction
    || (demandDrivenExternalOnly ? 'none' : (goalNextRow.nextAction || goalNextRow.firstAction))
    || 'none';
  const summaryDetail = `core:${coreStatus} ui:${uiStatus} model:${modelStatus} external:${healthRailExternalStatus} next:${summaryNextAction}`;
  const modelAnswerLiveStatus = liveStatus === 'OK' && modelAnswerStatus === 'OK' ? 'OK' : 'WARN';
  const heartbeatLiveDetail = `live:${liveStatus} core:${coreStatus} ui:${uiStatus}`;
  const heartbeatWaitStatus = waitStatus === "none" || /^(OK|READY|DELIVERED|COMPLETE)$/i.test(waitStatus) ? "OK" : "WARN";
  const heartbeatWaitDetail = `wait:${waitStatus} delivery:${modelRuntime.deliveryState || 'none'}`;
  const heartbeatTimeoutDetail = `awaitTimeoutCount:${providerRuntime.awaitTimeoutCount ?? 0}`;
  const heartbeatCancelDetail = `cancelSuppressedCount:${providerRuntime.cancelSuppressedCount ?? 0}`;
  const modelAnswerMatrixDetail = `${modelAnswerDetail} live:${liveStatus}${localLlmOperatorDetail}`;

  debugHeartbeatSummaryState.liveStatus = liveStatus;
  debugHeartbeatSummaryState.waitStatus = waitStatus;
  debugHeartbeatSummaryState.timeoutStatus = timeoutStatus;
  debugHeartbeatSummaryState.cancelStatus = cancelStatus;
  debugHeartbeatSummaryState.nextAction = summaryNextAction;

  setDebugHeartbeatField('core', coreStatus, coreDetail);
  setDebugHeartbeatField('ui', uiStatus, uiDetail);
  setDebugHeartbeatField('external', externalHeartbeatStatus, externalHeartbeatDetail);
  setDebugHeartbeatField('supabase', supabaseStatus, supabaseDetail);
  setDebugHeartbeatField('browser', browserUiStatus, browserUiDetail);
  setDebugHeartbeatField('action', actionStatus, actionDetail);
  setDebugHeartbeatField('action', actionStatus, actionRuntimeDetail);
  setDebugHeartbeatField('gate', gateStatus, gateDetail);
  setDebugHeartbeatField('model', modelStatus, modelDetail);
  setDebugHeartbeatField('search', searchStatus, searchDetail);
  setDebugHeartbeatField('ladder', ladderStatus, ladderDetail);
  setDebugHeartbeatField('trace', traceStatus, traceDetail);
  setDebugHeartbeatField('harmony', harmonyStatus, harmonyDetail);
  setDebugHeartbeatField('traceMemory', traceMemoryStatus, traceMemoryDetail);
  setDebugHeartbeatField('metrics', metricsStatus, metricsDetail);
  setDebugHeartbeatField('memory', memoryStatus, memoryDetail);
  setDebugHeartbeatField('lanes', laneStatus, laneDetail);
  setDebugHeartbeatField('lanes', laneStatus, laneRuntimeDetail);
  setDebugHeartbeatField('strategy', strategyStatus, strategyDetail);
  setDebugHeartbeatField('failures', failureStatus, failureDetail);
  setDebugHeartbeatField('qtx', qtxStatus, qtxDetail);
  setDebugHeartbeatField('causal', causalStatus, causalDetail);
  setDebugHeartbeatField('retrievers', retrieverStatus, retrieverDetail);
  setDebugHeartbeatField('breaker', breakerStatus, breakerDetail);
  setDebugHeartbeatField('answer', answerStatus, answerDetail);
  setDebugHeartbeatField('computer', computerUiStatus, computerDetail);
  setDebugHeartbeatField('computer', computerUiStatus, computerUiDetail);
  setDebugHeartbeatField('noether', noetherStatus, noetherDetail);
  setDebugHeartbeatField('freshness', freshnessStatus, freshnessDetail);
  setDebugHeartbeatField('patchdrop', patchDropStatus, patchDropDetail);
  setDebugHeartbeatField('liveStream', liveStatus, heartbeatLiveDetail);
  setDebugHeartbeatField('modelWait', heartbeatWaitStatus, heartbeatWaitDetail);
  setDebugHeartbeatField('timeout', timeoutStatus, heartbeatTimeoutDetail);
  setDebugHeartbeatField('cancel', cancelStatus, heartbeatCancelDetail);
  setOrchSignalBadge('model', modelStatus, modelDetail);
  setOrchSignalBadge('dpp', dppStatus, dppDetail);
  setOrchSignalBadge('cfvm', cfvmStatus, cfvmDetail);
  setOrchSignalBadge('supabase', supabaseStatus, supabaseDetail);
  setServerStatusRailHealth(healthRailStatus, healthRailDetail);
  setDebugHeartbeatSummary(summaryStatus, summaryDetail);
  setDebugMatrixCell('core-ui', coreUiStatus, 'Core/UI', coreUiDetail);
  setDebugMatrixCell('model-answer', modelAnswerLiveStatus, 'Model/Answer', modelAnswerMatrixDetail);
  setDebugMatrixCell('search-trace', searchTraceStatus, 'Search/Trace', searchTraceDetail);
  setDebugMatrixCell('external-proof', externalProofUiStatus, 'External Proof', externalProofUiDetail);
  applyAssistantModelFallbackDebug();

  function updateDebugCockpitFromState() {
    setDebugCockpitCell('stream', debugHeartbeatSummaryState.liveStatus, 'Stream', debugHeartbeatSummaryState.waitStatus);
    setDebugCockpitCell('model', modelStatus, 'Model', modelDetail);
    setDebugCockpitCell('search', searchTraceStatus, 'Search', searchTraceDetail);
    setDebugCockpitCell('external', externalProofUiStatus, 'External', externalProofUiDetail);
    setDebugCockpitCell('next', actionStatus, 'Next', actionDetail);
    setDebugCockpitCell('next', actionStatus, 'Next', actionRuntimeDetail);
  }

  const missionFocus = firstWarnMissionAxis(
    { status: coreUiStatus, value: coreUiStatus, detail: coreUiDetail },
    { status: uiStatus, value: uiStatus, detail: uiDetail },
    { status: externalProofUiStatus, value: externalProofUiStatus, detail: externalProofUiDetail },
    { status: actionStatus, value: actionStatus, detail: actionRuntimeDetail }
  );
  setDebugMissionAxis('core', coreUiStatus, 'Core', coreUiStatus, coreUiDetail);
  setDebugMissionAxis('ui', uiStatus, 'UI', uiStatus, uiDetail);
  setDebugMissionAxis('external', externalProofUiStatus, 'External', externalProofUiStatus, externalProofUiDetail);
  setDebugMissionAxis('next', actionStatus, 'Next', actionStatus, actionRuntimeDetail);
  setDebugMissionAxis('focus', missionFocus.status, 'Focus', missionFocus.value, missionFocus.detail);
  setDebugFlowStep('input', debugHeartbeatSummaryState.liveStatus, 'Input', debugHeartbeatSummaryState.waitStatus, summaryDetail);
  setDebugFlowStep('model', modelAnswerStatus, 'Model', modelStatus, modelAnswerDetail);
  setDebugFlowStep('search', searchTraceStatus, 'Search', searchStatus, searchTraceDetail);
  setDebugFlowStep('trace', traceStatus, 'Trace', traceStatus, traceDetail);
  setDebugFlowStep('external', externalProofUiStatus, 'External', externalProofUiStatus, externalProofUiDetail);
  setDebugFlowStep('action', actionStatus, 'Action', actionStatus, actionRuntimeDetail);
  setDebugProofCell('local', localProofStatus, 'Local', localProofStatus, gateDetail);
  setDebugProofCell('local', localEvidenceStatus, 'Local', localEvidenceStatus, localGateDetail);
  setDebugProofCell('browser', browserUiStatus, 'Browser', browserUiStatus, browserUiDetail);
  setDebugProofCell('computer', computerUiStatus, 'Computer', computerUiStatus, computerUiDetail);
  setDebugProofCell('supabase', supabaseStatus, 'Supabase', supabaseStatus, supabaseDetail);
  setDebugProofCell('producer', producerProofStatus, 'Producer', producerProofStatus, producerProofDetail);
  setDebugProofCell('action', actionStatus, 'Action', actionStatus, actionRuntimeDetail);
  updateDebugCockpitFromState();
  if (!directLiteralAnswerComplete && !assistantModelFallback) applyVisibleTurnEvidenceDebug(data);
  if (observedRecoveryCode(currentTurnRecoveryState)) {
    clearAssistantModelFallbackDebugOnSuccessfulAnswer({
      answerMode: "chat",
      model: dom.modelSelect?.value || "current",
      recoveryState: currentTurnRecoveryState
    }, liveStatus);
  }
  refreshDebugHeartbeatSummary();
}

function renderLiveDebugHeartbeat(partial = {}) {
  const streamStatus = String(partial.streamStatus || 'unknown');
  const streamContext = String(partial.streamContext || 'none');
  const streamAnswerComplete = successfulAnswerMode(streamStatus) || successfulAnswerMode(partial.answerMode);
  const streamDone = /^(final|done|complete)$/i.test(streamStatus);
  const streamStopped = /^(cancelled|stopped)$/i.test(streamStatus)
    || /^(server-cancel|local-stop|local-detach|abort-error|server-cancel-unavailable)$/i.test(streamContext);
  const liveStatus = streamDone || streamStopped || streamAnswerComplete ? 'OK' : 'WARN';
  const liveDetail = `stream:${streamStatus} context:${streamContext}${streamAnswerComplete ? ' answer:complete' : ''}`;
  const waitSource = [partial.streamStatus, partial.streamContext, partial.answerMode, partial.model, partial.modelBadge]
    .filter(Boolean)
    .join(' ');
  const waitReason = /waiting[_-]for[_-]default[_-]model/i.test(waitSource) ? 'waiting_for_default_model' : 'none';
  const waitStatus = waitReason === 'none' && (streamDone || streamStopped || streamAnswerComplete) ? 'OK' : 'WARN';
  const waitDetail = `default:${waitReason} stream:${streamStatus}`;
  const timeoutReason = /timeout/i.test(waitSource) ? 'timeout' : /fallback/i.test(waitSource) ? 'fallback' : /error/i.test(waitSource) ? 'error' : 'none';
  const timeoutStatus = timeoutReason === 'none' ? 'OK' : 'WARN';
  const timeoutDetail = `reason:${timeoutReason} stream:${streamStatus}`;
  const cancelReason = /cancel/i.test(waitSource) ? 'cancelled' : 'none';
  const cancelStatus = cancelReason === 'none' || streamStopped ? 'OK' : 'WARN';
  const cancelDetail = `reason:${cancelReason} stream:${streamStatus}`;
  debugHeartbeatSummaryState.liveStatus = liveStatus;
  debugHeartbeatSummaryState.waitStatus = waitReason;
  debugHeartbeatSummaryState.timeoutStatus = timeoutStatus;
  debugHeartbeatSummaryState.cancelStatus = cancelStatus;
  debugHeartbeatSummaryState.nextAction = streamContext || 'none';
  setDebugHeartbeatSummary(liveStatus, liveDetail);
  setDebugHeartbeatField('liveStream', liveStatus, liveDetail);
  setDebugHeartbeatField('modelWait', waitStatus, waitDetail);
  setDebugHeartbeatField('timeout', timeoutStatus, timeoutDetail);
  setDebugHeartbeatField('cancel', cancelStatus, cancelDetail);
  syncCurrentTurnHealthOverlay(streamStatus, streamContext, {
    streamDone,
    streamStopped,
    streamAnswerComplete
  });
  renderPrimaryDiagnostic(selectPrimaryDiagnostic({
    healthOverlay: currentTurnHealthOverlay,
    streamStatus: streamStatus || "",
    isStopAvailable: Boolean(
      dom.stopBtn &&
      dom.stopBtn.hidden === false &&
      dom.stopBtn.style.display !== "none" &&
      dom.stopBtn.disabled === false
    )
  }));
  clearAssistantModelFallbackDebugOnSuccessfulAnswer(partial, liveStatus);
  clearRetrievalWarmupDebugOnSuccessfulAnswer(partial);
  applyDirectLiteralRetrievalBypassDebug(partial);
}

async function refreshDebugHeartbeat() {
  if (!dom.debugHeartbeatBar) return;
  try {
    let data;
    try {
      data = await readHeartbeatJson(CHAT_UI_HEARTBEAT_API, "chat_ui_heartbeat_unavailable");
    } catch {
      data = await readPipelineHealthJson();
    }
    renderDebugHeartbeat(data);
  } catch (error) {
    const heartbeatReason = safeDebugCockpitDetail(error?.message || "pipeline_health_unavailable");
    if (heartbeatReason === "pipeline_health_unavailable") setDebugHeartbeatSummary('WARN', 'pipeline_health_unavailable');
    else setDebugHeartbeatSummary('WARN', heartbeatReason);
    setDebugHeartbeatField('external', 'WARN', heartbeatReason);
    setDebugHeartbeatField('supabase', 'WARN', heartbeatReason);
    setDebugHeartbeatField('browser', 'WARN', heartbeatReason);
    setDebugHeartbeatField('action', 'WARN', 'next_proof_unknown');
    setDebugHeartbeatField('gate', 'WARN', 'goal_next_gate_unknown');
    setDebugHeartbeatField('model', 'WARN', 'model_runtime_unknown');
    setDebugHeartbeatField('search', 'WARN', 'search_runtime_unknown');
    setDebugHeartbeatField('ladder', 'WARN', 'failsoft_ladder_unknown');
    setDebugHeartbeatField('trace', 'WARN', 'trace_snapshot_unknown');
    setDebugHeartbeatField('harmony', 'WARN', 'chat_harmony_unknown');
    setDebugHeartbeatField('traceMemory', 'WARN', 'trace_memory_unknown');
    setDebugHeartbeatField('metrics', 'WARN', 'debug_ai_metrics_unknown');
    setDebugHeartbeatField('memory', 'WARN', 'memory_gate_unknown');
    setDebugHeartbeatField('lanes', 'WARN', 'runtime_lanes_unknown');
    setDebugHeartbeatField('strategy', 'WARN', 'strategy_runtime_unknown');
    setDebugHeartbeatField('failures', 'WARN', 'recent_failures_unknown');
    setDebugHeartbeatField('qtx', 'WARN', 'query_transformer_unknown');
    setDebugHeartbeatField('causal', 'WARN', 'probe_round_unknown');
    setDebugHeartbeatField('retrievers', 'WARN', 'retriever_lanes_unknown');
    setDebugHeartbeatField('breaker', 'WARN', 'circuit_breaker_unknown');
    setDebugHeartbeatField('liveStream', 'WARN', 'live_stream_unknown');
    setDebugHeartbeatField('modelWait', 'WARN', 'model_wait_unknown');
    setDebugHeartbeatField('timeout', 'WARN', 'timeout_unknown');
    setDebugHeartbeatField('cancel', 'WARN', 'cancel_state_unknown');
    setDebugHeartbeatField('answer', 'WARN', 'answer_output_unknown');
    setDebugHeartbeatField('computer', 'WARN', 'computer_use_unknown');
    setDebugHeartbeatField('noether', 'WARN', 'noether_status_unknown');
    setDebugHeartbeatField('freshness', 'WARN', 'freshness_unknown');
    setDebugHeartbeatField('patchdrop', 'WARN', 'patchdrop_queue_unknown');
    setOrchSignalBadge('model', 'WARN', 'model_runtime_unknown');
    setOrchSignalBadge('dpp', 'WARN', 'dpp_lane_unknown');
    setOrchSignalBadge('cfvm', 'WARN', 'cfvm_lanes_unknown');
    setOrchSignalBadge('supabase', 'WARN', 'supabase_evidence_unknown');
    setServerStatusRailHealth('WARN', 'proof:unknown');
    setDebugMissionAxis('focus', 'WARN', 'Focus', 'pipeline', 'pipeline_health_unavailable');
    setDebugFlowStep('action', 'WARN', 'Action', 'WAIT', 'next_proof_unknown');
    const recoveryDetail = localLlmRecoveryDetail(lastLocalLlmRecovery);
    if (recoveryDetail) setDebugHeartbeatField('model', 'WARN', `${recoveryDetail} (최근 확인 상태)`);
  }
}

async function readHeartbeatJson(path, nonJsonReason) {
  const response = await apiCall(path, { method: "GET" });
  const contentType = response.headers.get("content-type") || "";
  if (!response.ok || !contentType.toLowerCase().includes("json")) throw new Error(nonJsonReason);
  return response.json();
}

async function readPipelineHealthJson() {
  const response = await apiCall(PIPELINE_HEALTH_API, {
    method: "GET",
    headers: withChatCorrelationHeaders({}, { sessionId: state.currentSessionId })
  });
  const contentType = response.headers.get("content-type") || "";
  if (!response.ok || !contentType.toLowerCase().includes("json")) throw new Error("pipeline_health_login_required");
  return response.json();
}

function latestPlanModeDiagnostic(target, route) {
  const children = Array.from(target?.children || []);
  const anchor = latestMessageDiagnosticAnchor(target);
  const anchorIndex = anchor?.parentElement === target ? children.indexOf(anchor) : -1;
  const startIndex = anchorIndex >= 0 ? anchorIndex + 1 : 0;
  for (let i = children.length - 1; i >= startIndex; i -= 1) {
    const node = children[i];
    if (node?.matches?.('.message-debug-fx.plan-mode-diagnostic[data-role="plan-mode"]')
      && node.dataset.planRoute === route) {
      return node;
    }
  }
  return null;
}

function renderPlanModeCard(meta = {}, target = dom.chatMessages) {
  if (shouldSuppressMessageDiagnostics(target)) return null;
  const route = safeDebugCockpitDetail(meta.route || meta.planId || "plan");
  const existing = latestPlanModeDiagnostic(target, route);
  if (existing) {
    existing.textContent = `Plan route: ${route}`;
    existing.setAttribute("aria-label", `Plan route: ${route}`);
    return existing;
  }
  const card = document.createElement("div");
  card.className = "message-debug-fx plan-mode-diagnostic";
  card.dataset.role = "plan-mode";
  card.dataset.planRoute = route;
  card.textContent = `Plan route: ${route}`;
  card.setAttribute("aria-label", `Plan route: ${route}`);
  markChatDiagnosticNode(card);
  target?.appendChild(card);
  return card;
}

function isMessageDiagnosticAnchor(target) {
  return Boolean(target?.classList?.contains("message") || target?.dataset?.speaker);
}

function insertAfterMessageAnchor(anchor, node) {
  const parent = anchor?.parentElement;
  if (!parent || !node) return false;
  if (typeof anchor.after === "function") {
    anchor.after(node);
    return true;
  }
  if (Array.isArray(parent.children)) {
    const existingIndex = parent.children.indexOf(node);
    if (existingIndex >= 0) parent.children.splice(existingIndex, 1);
    const anchorIndex = parent.children.indexOf(anchor);
    if (anchorIndex >= 0) {
      node.parentElement = parent;
      parent.children.splice(anchorIndex + 1, 0, node);
      parent.children.forEach((child, index) => {
        child.nextElementSibling = parent.children[index + 1] || null;
      });
      return true;
    }
  }
  parent.appendChild(node);
  return true;
}

function renderTransformerCoreRail(target, blocks = [], meta = {}) {
  if (!target) return null;
  if (shouldSuppressMessageDiagnostics(target)) return null;
  const anchor = isMessageDiagnosticAnchor(target) ? target : null;
  const container = anchor?.parentElement || target;
  let holder = anchor ? anchor.__transformerCoreRail : target.querySelector?.('[data-role="transformer-core-rail"]');
  if (holder && anchor && holder.parentElement !== container) holder = null;
  if (!holder) {
    holder = document.createElement("aside");
    holder.dataset.role = "transformer-core-rail";
    holder.dataset.ttsIgnore = "1";
    holder.className = "transformer-core-rail";
    markChatDiagnosticNode(holder);
    if (anchor) {
      insertAfterMessageAnchor(anchor, holder);
      anchor.__transformerCoreRail = holder;
    } else {
      container.appendChild(holder);
    }
  }
  const safeBlocks = (Array.isArray(blocks) && blocks.length ? blocks : [{ label: "Transformer", status: meta.status || "running" }]).slice(0, 12);
  holder.replaceChildren();
  safeBlocks.forEach((block, index) => {
    const row = document.createElement("span");
    const label = document.createElement("strong");
    const value = document.createElement("small");
    const labelText = String(block?.label || block?.name || block?.id || "block");
    const valueText = String(block?.status || block?.reason || "-");
    row.dataset.blockId = String(block?.id || "");
    row.dataset.status = valueText;
    label.textContent = labelText;
    value.textContent = valueText;
    row.appendChild(label);
    row.appendChild(document.createTextNode(" "));
    row.appendChild(value);
    if (index > 0) {
      holder.appendChild(document.createTextNode(" "));
    }
    holder.appendChild(row);
  });
  applyChatHarmonyRailStatus(holder, anchor?.__chatHarmonyFinalStatus);
  return holder;
}

function markActiveStreamStoppedRail(reason = "stopped", target = activeStreamAssistant || dom.chatMessages) {
  const detail = safeDebugCockpitDetail(reason || "stopped");
  return renderTransformerCoreRail(target || dom.chatMessages, [
    { label: "Intake", status: "stopped" },
    { label: "Stream", status: "cancelled" },
    { label: "Model", status: detail || "stopped" },
    { label: "Next", status: "none" }
  ], { status: "stopped" });
}

function failedSendReason(error, fallback) {
  const meta = error?.chatFailure;
  if (!meta) return safeDebugCockpitDetail(fallback || "stream_failed");
  return safeDebugCockpitDetail(
    [meta.failureKind, meta.serverCode, meta.nextAction].filter(Boolean).join(" ")
  );
}

function renderMessageFailedDiagnostic(target, streamError = null, syncError = null) {
  return renderTransformerCoreRail(target || dom.chatMessages, [
    { label: "Message", status: "failed" },
    { label: "Stream", status: failedSendReason(streamError, "stream_failed") || "failed" },
    { label: "Sync", status: arguments.length >= 3 ? failedSendReason(syncError, "sync_failed") : "not_attempted" },
    { label: "Next", status: syncError?.chatFailure?.nextAction || streamError?.chatFailure?.nextAction || "check_run" }
  ], { status: "message_failed" });
}

async function apiCall(url, options = {}) {
  const headers = withCsrfHeaders(options.headers || {});
  if (typeof window.fetch !== "function") {
    if (typeof XMLHttpRequest === "function") {
      return xhrApiCall(url, { ...options, headers });
    }
    throw new Error("browser_fetch_unavailable");
  }
  const response = await window.fetch(url, { ...options, headers });
  if (!response.ok) {
    const error = new Error(`HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return response;
}

function xhrApiCall(url, options = {}) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(options.method || "GET", url, true);
    Object.entries(options.headers || {}).forEach(([key, value]) => {
      xhr.setRequestHeader(key, value);
    });
    xhr.onload = () => {
      const response = {
        ok: xhr.status >= 200 && xhr.status < 300,
        status: xhr.status,
        headers: { get: (name) => xhr.getResponseHeader(name) },
        json: async () => JSON.parse(xhr.responseText || "{}"),
        text: async () => xhr.responseText || ""
      };
      if (response.ok) resolve(response);
      else {
        const error = new Error(`HTTP ${xhr.status}`);
        error.status = xhr.status;
        reject(error);
      }
    };
    xhr.onerror = () => reject(new Error("xhr_network_error"));
    xhr.send(options.body || null);
  });
}

async function saveSettings() {
  try {
    await apiCall("/api/settings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({})
    });
  } catch (e) {
    if (isHttp403(e)) {
      setStatusRailValue(dom.traceStatus, "Settings save requires admin");
      return;
    }
    throw e;
  }
}

function isHttp403(error) {
  return error?.status === 403 || String(error?.message || "").includes("403");
}

// Gemini output belongs to this final response, outside the main answer and W/V evidence.
function renderGoogleSearchRescue(receipt, bubble) {
  if (!receipt || !bubble || receipt.reasonCode !== 'READY_FOR_DISPLAY' || !receipt.attributionValid) return;
  const answer = receipt.answer, html = answer?.metadata?.searchEntryPoint?.renderedContent;
  if (!answer || typeof answer.originalText !== 'string' || !answer.originalText.trim() || typeof html !== 'string' || !html.trim()) return;
  const template = document.createElement('template'); template.innerHTML = html;
  const allowed = new Set(['DIV','SPAN','STYLE','A','SVG','PATH','P','BR','G','CIRCLE']);
  const unsafeStyle = value => /\\|@import|url\s*\(|expression\s*\(|behavior\s*:|position\s*:\s*fixed/i.test(value || '');
  for (const el of template.content.querySelectorAll('*')) {
    if (!allowed.has(el.tagName.toUpperCase())) return;
    for (const attr of el.attributes) {
      const name = attr.name.toLowerCase();
      if (name.startsWith('on') || ['src','srcdoc','action','formaction','xlink:href'].includes(name)) return;
      if (name === 'href') { try { const url = new URL(attr.value); if (el.tagName.toUpperCase() !== 'A' || url.protocol !== 'https:' || url.username || url.password) return; } catch { return; } }
      if (name === 'style' && unsafeStyle(attr.value)) return;
    }
    if (el.tagName.toUpperCase() === 'STYLE' && unsafeStyle(el.textContent)) return;
  }
  const panel = document.createElement('section'); panel.dataset.googleSearchRescue = 'true';
  const label = document.createElement('strong'); label.textContent = '검색 보강';
  const text = document.createElement('div'); text.textContent = answer.originalText; text.style.whiteSpace = 'pre-wrap';
  const sources = document.createElement('div'), seen = new Set();
  for (const support of answer.metadata.groundingSupports || []) for (const index of support.groundingChunkIndices || []) {
    if (!Number.isInteger(index) || index < 0) return;
    if (seen.has(index)) continue; seen.add(index);
    const source = answer.metadata.groundingChunks?.[index]?.web;
    let url; try { url = new URL(source?.uri); } catch { return; }
    if (url.protocol !== 'https:' || url.username || url.password) return;
    const link = document.createElement('a'); link.href = source.uri; link.textContent = 'G' + (index + 1) + ': ' + (source.title || source.uri);
    link.target = '_blank'; link.rel = 'noopener noreferrer'; sources.append(link, document.createElement('br'));
  }
  if (!seen.size) return;
  const suggestions = document.createElement('div'); const shadow = suggestions.attachShadow?.({mode:'closed'});
  if (!shadow) return; shadow.innerHTML = html; suggestions.style.contain = 'content'; suggestions.style.position = 'relative';
  panel.append(label, text, sources, suggestions); bubble.appendChild(panel);
}

function renderChatEvent(payload, assistant, fallbackType = "message", exactFinalRun = null) {
  const rawType = payload?.type || fallbackType || "message";
  const type = rawType === "stream_failed" ? "error" : rawType;
  if (isAssistantStreamStopped(assistant)) {
    recordChatTransitionDebug({
      kind: "late-event",
      to: "blocked",
      reasonCode: "terminal-latch",
      terminalLatch: true,
      lateEventBlocked: true
    });
    return;
  }
  if (type === "token" || type === "message") {
    const bubble = assistant;
    const visibleText = filterAssistantReasoningChunk(payload.data || "", bubble);
    if (visibleText.trim()) clearAssistantPendingPlaceholder(bubble);
    if (visibleText && (visibleText.trim() || bubble?.dataset?.state === "ready")) {
      appendTextWithBreaks(bubble, visibleText);
    }
    refreshEvidenceRailFromAnswerText(bubble?.parentElement || dom.chatMessages,
      bubble?.dataset?.ariaText || bubble?.textContent || "", {
        answerMode: "streamed",
        model: state.responseModelUsed || dom.modelSelect?.value || "-"
      });
  } else if (type === "session") {
    const sid = sessionIdFromPayload(payload);
    const runToken = runTokenFromPayload(payload);
    const dockSignal = payload?.traceSignal;
    if (dockSignal && typeof CustomEvent === "function" && typeof document.dispatchEvent === "function") {
      document.dispatchEvent(new CustomEvent("awx:trace-turn", {
        detail: { requestIdHash: dockSignal.requestIdHash, traceIdHash: dockSignal.traceIdHash }
      }));
    }
    if (sid) {
      if (!rememberActiveRunIdentity(sid, runToken)) {
        activeSessionId = sid;
        activeRunToken = null;
      }
      rememberCurrentSessionId(sid);
      void refreshSessionList("session");
      const pendingStop = pendingStopBeforeToken;
      if (runToken && pendingStop?.assistant === assistant) {
        // Stop was expressed before the capability arrived. Cancelling the still
        // unacknowledged run must win before any ACK can release generation.
        void completePendingStopBeforeToken(sid, runToken, pendingStop);
      } else if (runToken) {
        void acknowledgeExactRun(sid, runToken);
      }
    }
    updateOrchestrationSignalBar({
        traceTurn: sessionTraceLabel(sid)
      });
    dispatchBrainStateSignal('session', { sessionId: sid });
  } else if (type === "selection_entropy") {
    // 일반 대화에서는 Selection replay 진단 표를 숨기고, ?debug= 로 켰을 때만 렌더한다.
    if (isChatTransitionDebugEnabled()) {
      renderSelectionEntropyTrace(payload, assistant);
    } else {
      clearSelectionEntropyTrace(assistant);
    }
  } else if (type === "final") {
    clearAssistantPendingPlaceholder(assistant);
    if (assistant?.dataset) {
      assistant.dataset.waitStopped = "true";
      if (assistant.dataset.state === "pending") assistant.dataset.state = "ready";
    }
    const legacyReportedModel = payload.modelUsed || state.responseModelUsed;
    const legacyModel = legacyReportedModel || dom.modelSelect?.value || "-";
    const hasObservation = ["observedModel", "observed_model", "observedProvider",
      "observed_provider", "observedReason", "observed_reason"]
      .some(key => Object.prototype.hasOwnProperty.call(payload, key));
    const observedModel = [payload.observedModel, payload.observed_model]
      .find(value => typeof value === "string" && value.trim())?.trim() || null;
    const observedProvider = [payload.observedProvider, payload.observed_provider]
      .find(value => typeof value === "string" && value.trim())?.trim() || null;
    const reportedModel = hasObservation ? observedModel : legacyReportedModel;
    window.AwxChatTraceUi?.upsertSummary(assistant, { observedModel, observedProvider });
    const model = hasObservation ? observedModel || "UNKNOWN" : legacyModel;
    const finalSessionId = sessionIdFromPayload(payload);
    const sid = finalSessionId || state.currentSessionId;
    if (finalSessionId && state.currentSessionId !== finalSessionId) {
      rememberCurrentSessionId(finalSessionId);
      dispatchBrainStateSignal('session', { sessionId: finalSessionId });
    }
    const pipeline = payload.pipelineSnapshot || payload.pipeline_snapshot || {};
    const inferredMode = finalAnswerMode(payload, legacyModel);
    const finalMode = normalizedAnswerMode(pipeline.answerMode || pipeline.answer_mode || pipeline.mode) || inferredMode;
    const traceTurnId = payload.traceTurnId || payload.trace_turn_id || pipeline.traceTurnId || pipeline.trace_turn_id || state.responseTraceId || "ready";
    const bubble = assistant;
    assistantReasoningStates.delete(bubble);
    const completeAnswer = stripAssistantReasoningBlocks(payload.data || "");
    if (completeAnswer && bubble) {
      const selectionEntropyCard = bubble.querySelector?.("[data-selection-entropy-card]") || null;
      clearAssistantPendingPlaceholder(bubble);
      if (typeof bubble.replaceChildren === "function") bubble.replaceChildren();
      else bubble.textContent = "";
      if (bubble.dataset) bubble.dataset.ariaText = "";
      appendTextWithBreaks(bubble, completeAnswer);
      if (selectionEntropyCard) bubble.appendChild(selectionEntropyCard);
    }
    try {
      window.ChatEvidenceGraph?.finalize(bubble, {
        identity: exactFinalRun,
        isCurrent: sameActiveRunIdentity,
        sessionId: finalSessionId,
        traceTurnId: payload.traceTurnId ?? payload.trace_turn_id
          ?? pipeline.traceTurnId ?? pipeline.trace_turn_id ?? null,
        evidence: payload.evidence,
        answerText: completeAnswer,
        projectionVersion: 1
      });
    } catch { /* An optional provenance view must not interrupt final delivery or ACK. */ }
    if (String(finalMode || "").toUpperCase().startsWith("FALLBACK")) {
      reflectAssistantModelFallback(bubble?.dataset?.ariaText || bubble?.textContent || "");
    }
    setStatusRailValue(dom.modelStatus, `${reportedModel ? "적용" : "요청"} ${modelStatusRailValue(model, finalMode)}`);
    renderExecutionModeReceipt(pipeline, bubble);
    const directLiteralDiagnosticsSuppressed = suppressDirectLiteralDiagnostics(bubble, finalMode, model);
    const compactExternalProofDiagnosticsSuppressed = suppressCompactExternalProofDiagnostics(bubble, finalMode, model);
    const localUiModeDiagnosticsSuppressed = suppressLocalUiModeDiagnostics(bubble, finalMode, model);
    const messageDiagnosticsSuppressed = directLiteralDiagnosticsSuppressed
      || compactExternalProofDiagnosticsSuppressed
      || localUiModeDiagnosticsSuppressed
      || shouldSuppressMessageDiagnostics(bubble?.parentElement || bubble);
    updateOrchestrationSignalBar({
      streamStatus: finalMode || "final",
      model,
      traceTurnId: traceTurnId || pipeline.traceTurnId || sessionTraceLabel(sid),
      answerMode: finalMode,
      pipelineSnapshot: {
        ...pipeline,
        answerMode: finalMode
      }
    });
    if (hasObservation) setStatusRailValue(dom.modelStatus, `응답 ${model}`);
    resetCurrentTurnHealthOverlay();
    renderPrimaryDiagnostic(selectPrimaryDiagnostic({
      healthOverlay: currentTurnHealthOverlay,
      answerMode: finalMode,
      isStopAvailable: false
    }));
    if (!messageDiagnosticsSuppressed && bubble?.parentElement) upsertAnswerModeBadge(bubble.parentElement, finalMode);
    persistAnswerModeBadge(sid || state.currentSessionId, finalMode);
    upsertSessionModeBadgeInList(sid || state.currentSessionId, finalMode, traceTurnId);
    if (!messageDiagnosticsSuppressed) {
      renderEvidenceRail(payload.evidence, assistant.parentElement || dom.chatMessages, {
        answerMode: finalMode,
        model,
        answerText: bubble?.dataset?.ariaText || bubble?.textContent || "",
        pipeline
      });
      renderFallbackEvidenceDiagnostic(bubble?.parentElement || dom.chatMessages, finalMode, pipeline, payload.evidence, model);
    }
    renderGoogleSearchRescue(payload.googleSearchRescue, bubble);
    dispatchBrainStateSignal('answer', {
      sessionId: sid,
      answerMode: finalMode,
      evidenceCount: Array.isArray(payload.evidence) ? payload.evidence.length : 0,
      learningContext: payload.learningContext || payload.learning_context || {}
    });
    setCoreStatus("done", finalMode || "done");
    if (strictBackendSessionId(sid) !== null) void refreshSessionList("final");
  } else if (type === "thought") {
    thoughtEventCount += 1;
    updateOrchestrationSignalBar({
      streamStatus: "thought",
      streamContext: `thoughts:${thoughtEventCount}`
    });
    setCoreStatus("streaming", `thoughts:${thoughtEventCount}`);
  } else if (type === "understanding") {
    understandingEventCount += 1;
    updateOrchestrationSignalBar({
      streamStatus: "understanding",
      streamContext: `understanding:${understandingEventCount}`
    });
    setCoreStatus("streaming", `understanding:${understandingEventCount}`);
  } else if (type === "status") {
    const signal = payload.signal || payload.statusSignal || payload.status_signal || payload;
    if (signal.cancelled === true || String(signal.code || "").toLowerCase() === "cancelled") {
      clearActiveRunIdentity();
      markAssistantStreamStopped(assistant);
      markActiveStreamStoppedRail("server-cancel", assistant || dom.chatMessages);
      setCoreStatus("stopped", "server cancel");
      syncCurrentTurnHealthOverlay("cancelled", "server-cancel", { streamStopped: true });
      renderDecisionRibbon(presentObservedDecision({
        streamStatus: "cancelled",
        healthOverlay: currentTurnHealthOverlay
      }));
      renderPrimaryDiagnostic(selectPrimaryDiagnostic({
        healthOverlay: currentTurnHealthOverlay,
        streamStatus: "cancelled",
        isStopAvailable: false
      }));
      return;
    }
    updateAssistantWaitProgress(assistant, signal);
    const waitReason = defaultModelWaitReason(signal.code || signal.message || payload.data);
    updateOrchestrationSignalBar({
      streamStatus: type,
      streamContext: waitReason || statusContext(signal),
      answerMode: waitReason ? "MODEL_WAIT" : undefined,
      modelActive: waitReason ? true : undefined,
      modelBadge: waitReason ? "Model: running" : undefined,
      resilienceActive: Boolean(waitReason) || signal.cancelled === true
    });
    if (waitReason) {
      renderPlanModeCard({
        route: signal.phase || signal.code || "status",
        answerMode: "MODEL_WAIT",
        pipelineSnapshot: {}
      });
    }
    setCoreStatus("streaming", waitReason || statusContext(signal));
  } else if (type === "evidence") {
    const evidenceCount = Array.isArray(payload.evidence) ? payload.evidence.length : 0;
    renderEvidenceRail(payload.evidence, assistant?.parentElement || dom.chatMessages, {
      answerMode: "streamed",
      model: state.responseModelUsed || dom.modelSelect?.value || "-",
      answerText: assistant?.dataset?.ariaText || assistant?.textContent || ""
    });
    updateOrchestrationSignalBar({ streamStatus: "evidence", streamContext: `evidence:${evidenceCount}` });
  } else if (type === "scoreDelta") {
    const signal = payload?.scoreDelta || {};
    const context = scoreDeltaContext(signal);
    renderScoreDeltaDetail(signal, assistant);
    updateOrchestrationSignalBar({
      streamStatus: "scoreDelta",
      streamContext: context,
      scoreDelta: signal.rawScoreDelta,
      scoreDeltaContext: context
    });
    setStatusRailValue(dom.traceStatus, "scoreDelta");
  } else if (type === "trace" || type === "transformer") {
    const modelReason = payload?.modelReason || payload?.reason || payload?.data || "";
    const modelWaitReason = defaultModelWaitReason(modelReason);
    const signal = payload.traceSignal || payload.signal || payload;
    const pipeline = payload.pipelineSnapshot || payload.pipeline_snapshot || {};
    const meta = payload.meta || payload;
    const blocks = Array.isArray(payload.blocks) ? payload.blocks : (Array.isArray(payload.transformerBlocks) ? payload.transformerBlocks : []);
    const hasBlocks = blocks.length > 0;
    const hasLearningContext = payload.learningContext && Object.keys(payload.learningContext).length > 0;
    const transformerBadges = hasBlocks ? deriveTransformerBadges(blocks) : {};
    if (hasBlocks) promoteQueryRewriteTransformerToHeartbeat(blocks);
    const liveHeartbeatModelReason = transformerBadges.model || transformerBadges.modelBadge || pipeline.failureClass || pipeline.disabledReason || pipeline.answerMode;
    const bubble = assistant;
    renderExecutionModeReceipt(pipeline, bubble);
    if (type === "transformer") {
      if (!hasBlocks && meta?.status === "final") return;
      renderTransformerCoreRail(bubble || dom.chatMessages, blocks, meta);
    }
    updateOrchestrationSignalBar({
      streamStatus: meta.status || transformerBadges.streamStatus || "transformer",
      streamContext: transformerBadges.streamContext || liveHeartbeatModelReason || transformerBadges.answerMode || pipeline.answerMode,
      model: transformerBadges.model,
      modelBadge: transformerBadges.modelBadge,
      recoveryState: transformerBadges.recoveryState,
      answerMode: transformerBadges.answerMode || pipeline.answerMode,
      scoreDelta: signal.rawScoreDelta,
      scoreDeltaContext: scoreDeltaContext(signal),
      pipelineSnapshot: pipeline,
      ...deriveTransformerModelDiagnostic(transformerBadges, transformerBadges.model, transformerBadges.answerMode || pipeline.answerMode, meta)
    });
    updateOrchestrationSignalBar({
      streamStatus: modelWaitReason ? "waiting_for_default_model" : undefined,
      streamContext: modelWaitReason,
      answerMode: modelWaitReason ? "MODEL_WAIT" : undefined
    });
    if (hasBlocks && (transformerBadges.answerMode || Object.keys(pipeline).length > 0 || hasLearningContext)) {
      renderPlanModeCard({
        answerMode: transformerBadges.answerMode || pipeline.answerMode,
        route: pipeline.route || meta.status || "transformer",
        pipelineSnapshot: pipeline,
        learningContext: payload.learningContext || {}
      }, bubble?.parentElement || dom.chatMessages);
    }
    if (type === "trace") {
      if (typeof CustomEvent === "function" && typeof document.dispatchEvent === "function") {
        document.dispatchEvent(new CustomEvent("awx:trace-turn", {
          detail: { requestIdHash: signal.requestIdHash, traceIdHash: signal.traceIdHash }
        }));
      }
      renderTraceSignalDetail(signal, pipeline, bubble);
      renderScoreDeltaDetail(signal, bubble);
      if (payload.html) renderTraceHtml(payload, bubble);
    }
    setStatusRailValue(dom.traceStatus, type);
  } else if (type === "debug_fx") {
    setStatusRailValue(dom.traceStatus, type);
    handleDebugFxSignal(payload);
    reconcileChatHarmonyRailFromDebugFx(payload, assistant);
    renderDebugFxTrace(payload, assistant);
  } else if (type === "trace_html") {
    renderTraceHtml(payload, assistant);
  } else if (type === "error") {
    clearActiveRunIdentity();
    clearSelectionEntropyTrace(assistant);
    setMessageContent(assistant, "assistant", "message_failed", "error");
    renderMessageFailedDiagnostic(assistant, new Error("stream_event_error"));
    updateOrchestrationSignalBar({ streamStatus: "error", streamContext: "message_failed" });
    setCoreStatus("error", "message_failed");
  } else {
    const target = assistant?.parentElement || dom.chatMessages;
    const diagnostic = document.createElement("div");
    diagnostic.className = "message-debug-fx";
    diagnostic.dataset.role = "stream-diagnostic";
    const eventName = safeUnknownEventName(type);
    diagnostic.textContent = `Stream event ${eventName}`;
    diagnostic.setAttribute("aria-label", diagnostic.textContent);
    markChatDiagnosticNode(diagnostic);
    target?.appendChild(diagnostic);
    setStatusRailValue(dom.traceStatus, eventName);
    scrollChatToLatestAssistant();
  }
}

async function requestServerCancel(sessionId, runToken) {
  const token = normalizeRunToken(runToken);
  if (!sessionId || !token) return { outcome: "invalid", reason: "run_identity_required" };
  const response = await apiCall("/api/chat/cancel", {
    method: "POST",
    headers: withChatCorrelationHeaders(
      { "Content-Type": "application/json" },
      { sessionId, runToken: token }),
    body: JSON.stringify({ sessionId, runToken: token })
  });
  try {
    const result = await response.json();
    return result?.cancelled === true
      ? { outcome: "cancelled", reason: result?.reason || "cancelled" }
      : { outcome: "rejected", reason: result?.reason || "not_cancellable" };
  } catch {
    return { outcome: "invalid_response", reason: "cancel_response_invalid" };
  }
}

async function acknowledgeExactRun(sessionId, runToken, phase = "ready") {
  const token = normalizeRunToken(runToken);
  if (!sessionId || !token) return false;
  let timeoutId = null;
  const acknowledgementAbort = new AbortController();
  const timeoutMs = phase === "ready" ? READY_ACK_TIMEOUT_MS : FINAL_ACK_TIMEOUT_MS;
  const acknowledgementTask = (async () => {
    try {
      const response = await apiCall("/api/chat/ack", {
        method: "POST",
        headers: withChatCorrelationHeaders(
          { "Content-Type": "application/json" },
          { sessionId, runToken: token }),
        body: JSON.stringify({ sessionId, runToken: token, phase }),
        signal: acknowledgementAbort.signal
      });
      const result = await response.json();
      return result?.acknowledged === true;
    } catch {
      return false;
    }
  })();
  try {
    return await Promise.race([
      acknowledgementTask,
      new Promise((resolve) => {
        timeoutId = window.setTimeout(() => {
          acknowledgementAbort.abort();
          resolve(false);
        }, timeoutMs);
      })
    ]);
  } finally {
    if (timeoutId != null) window.clearTimeout(timeoutId);
  }
}

async function requestServerCancelWithTimeout(sessionId, runToken, timeoutMs = SERVER_CANCEL_TIMEOUT_MS) {
  if (!sessionId || !normalizeRunToken(runToken)) {
    return { outcome: "invalid", reason: "run_identity_required" };
  }
  let timeoutId = null;
  try {
    return await Promise.race([
      requestServerCancel(sessionId, runToken),
      new Promise((resolve) => {
        timeoutId = window.setTimeout(
          () => resolve({ outcome: "timeout", reason: "cancel_outcome_unknown" }),
          timeoutMs);
      })
    ]);
  } finally {
    if (timeoutId != null) window.clearTimeout(timeoutId);
  }
}

async function exactRunStateForCancel(expectedRun, timeoutMs = SERVER_CANCEL_TIMEOUT_MS) {
  if (!sameActiveRunIdentity(expectedRun)) return null;
  let timeoutId = null;
  const stateTask = (async () => {
    try {
      const response = await apiCall(chatTraceRequestUrl(`/api/chat/state?sessionId=${expectedRun.sessionId}`), {
        headers: withChatCorrelationHeaders({}, expectedRun)
      });
      if (!sameActiveRunIdentity(expectedRun)) return null;
      const runState = await response.json();
      return sameActiveRunIdentity(expectedRun) ? runState : null;
    } catch {
      return null;
    }
  })();
  try {
    return await Promise.race([
      stateTask,
      new Promise((resolve) => {
        timeoutId = window.setTimeout(() => resolve(null), timeoutMs);
      })
    ]);
  } finally {
    if (timeoutId != null) window.clearTimeout(timeoutId);
  }
}

function showRetryableCancelOutcome(reason = "cancel outcome unknown") {
  updateOrchestrationSignalBar({
    streamStatus: "streaming",
    streamContext: reason
  });
  setCoreStatus("streaming", reason);
  if (dom.stopBtn) dom.stopBtn.disabled = false;
}

function applySuccessfulStreamCancel(expectedRun, options = {}) {
  if (expectedRun && !sameActiveRunIdentity(expectedRun)) return false;
  const assistant = options.assistant || activeStreamAssistant;
  const controller = options.controller || streamController;
  streamCancelRequested = true;
  streamRenderSuppressed = true;
  clearActiveStreamHeartbeat();
  markAssistantStreamStopped(assistant);
  markActiveStreamStoppedRail(options.railReason || "server-cancel", assistant || dom.chatMessages);
  updateOrchestrationSignalBar({
    streamStatus: "cancelled",
    streamContext: options.streamContext || "server-cancel",
    traceTurn: "cancelled"
  });
  setCoreStatus("stopped", options.coreReason || "server cancel");
  pendingStopBeforeToken = null;
  controller?.abort();
  syncSendButtonState();
  if (expectedRun) clearActiveRunIdentityIfMatch(expectedRun);
  if (dom.stopBtn) dom.stopBtn.disabled = true;
  return true;
}

async function completePendingStopBeforeToken(sessionId, runToken, pending) {
  if (!pending || pendingStopBeforeToken !== pending) return false;
  const expectedRun = activeRunIdentitySnapshot();
  if (!expectedRun || expectedRun.sessionId !== normalizeSessionIdValue(sessionId) ||
      expectedRun.runToken !== normalizeRunToken(runToken)) return false;
  const cancelResult = await requestServerCancelWithTimeout(expectedRun.sessionId, expectedRun.runToken);
  if (pendingStopBeforeToken !== pending || !sameActiveRunIdentity(expectedRun)) return false;
  if (pending.requireTerminal) {
    const runState = await exactRunStateForCancel(expectedRun);
    if (pendingStopBeforeToken !== pending || !sameActiveRunIdentity(expectedRun)) return false;
    if (runState?.terminal === true && runState?.runStatus === "cancelled") {
      return applySuccessfulStreamCancel(expectedRun, {
        ...pending.options, assistant: pending.assistant, controller: pending.controller
      });
    }
    if (runState?.terminal !== true || runState?.runStatus !== "cancelled") {
      pending.unresolved = true;
      showRetryableCancelOutcome("cancel terminal not observed");
      return false;
    }
  }
  if (cancelResult.outcome === "cancelled") return applySuccessfulStreamCancel(expectedRun, {
    ...pending.options,
    assistant: pending.assistant,
    controller: pending.controller
  });
  if (pending.requireTerminal) pending.unresolved = true;
  else pendingStopBeforeToken = null;
  showRetryableCancelOutcome(cancelResult.outcome === "timeout"
    ? "cancel outcome unknown"
    : "cancel not acknowledged");
  return false;
}

async function reconcilePendingStopRequest(pending) {
  const receipt = pending?.receipt;
  if (!receipt?.sessionId || !receipt?.key || pendingStopBeforeToken !== pending) return false;
  let timeoutId;
  try {
    const stateTask = (async () => {
      const response = await apiCall(chatTraceRequestUrl(`/api/chat/state?sessionId=${receipt.sessionId}`), {
        headers: withChatCorrelationHeaders({ "Idempotency-Key": receipt.key }, { sessionId: receipt.sessionId })
      });
      return response.json();
    })();
    const runState = await Promise.race([stateTask, new Promise(resolve => {
      timeoutId = window.setTimeout(() => resolve(null), SERVER_CANCEL_TIMEOUT_MS);
    })]);
    if (pendingStopBeforeToken !== pending || normalizeSessionIdValue(state.currentSessionId) !== receipt.sessionId) return false;
    const token = normalizeRunToken(runState?.runToken);
    if (!token) return false; // Lookup miss is unknown; the original request may register later.
    const active = activeRunIdentitySnapshot();
    if (active && (active.sessionId !== receipt.sessionId || active.runToken !== token)) return false;
    if (!active) rememberActiveRunIdentity(receipt.sessionId, token);
    pending.requireTerminal = true;
    return completePendingStopBeforeToken(receipt.sessionId, token, pending);
  } catch {
    return false;
  } finally {
    if (timeoutId != null) window.clearTimeout(timeoutId);
  }
}

async function cancelActiveStream(options = {}) {
  if (streamCancelInFlight) return streamCancelInFlight;
  const cancelTask = (async () => {
    const sessionId = activeSessionId;
    const runToken = activeRunToken;
    const hasExactRun = Boolean(sessionId && runToken);
    if (!hasExactRun) {
      const pending = pendingStopBeforeToken?.unresolved ? pendingStopBeforeToken : {
        assistant: activeStreamAssistant,
        controller: streamController,
        receipt: streamController?.requestReceipt,
        options
      };
      pendingStopBeforeToken = pending;
      updateOrchestrationSignalBar({
        streamStatus: "stopping",
        streamContext: "waiting-for-run-identity"
      });
      setCoreStatus("streaming", "stop pending run identity");
      if (dom.stopBtn) dom.stopBtn.disabled = true;
      return reconcilePendingStopRequest(pending);
    }
    if (pendingStopBeforeToken?.unresolved) {
      return reconcilePendingStopRequest(pendingStopBeforeToken);
    }
    const expectedRun = activeRunIdentitySnapshot();
    const exactCancelOptions = {
      ...options,
      assistant: activeStreamAssistant,
      controller: streamController
    };
    updateOrchestrationSignalBar({
      streamStatus: "stopping",
      streamContext: options.streamContext || "server-cancel"
    });
    setCoreStatus("streaming", "requesting exact cancel");
    try {
      const cancelResult = await requestServerCancelWithTimeout(sessionId, runToken);
      if (!sameActiveRunIdentity(expectedRun)) return false;
      if (cancelResult.outcome === "cancelled") {
        return applySuccessfulStreamCancel(expectedRun, exactCancelOptions);
      }
      if (["timeout", "rejected", "invalid_response"].includes(cancelResult.outcome)) {
        const runState = await exactRunStateForCancel(expectedRun);
        if (!sameActiveRunIdentity(expectedRun)) return false;
        const status = String(runState?.runStatus || "").toLowerCase();
        if (status === "cancelled") {
          return applySuccessfulStreamCancel(expectedRun, exactCancelOptions);
        }
        if (status === "committing" || status === "done") {
          updateOrchestrationSignalBar({
            streamStatus: "streaming",
            streamContext: "cancel-rejected-commit-won"
          });
          setCoreStatus("streaming", "cancel rejected after commit boundary");
          if (dom.stopBtn) dom.stopBtn.disabled = true;
          return false;
        }
        showRetryableCancelOutcome(cancelResult.outcome === "timeout"
          ? "cancel outcome unknown"
          : "cancel not acknowledged");
        return false;
      }
      showRetryableCancelOutcome("cancel not acknowledged");
      return false;
    } catch {
      updateOrchestrationSignalBar({
        streamStatus: "streaming",
        streamContext: options.streamContext || "server-cancel-unavailable",
      });
      setCoreStatus("streaming", "server cancel unavailable");
      return false;
    }
  })();
  streamCancelInFlight = cancelTask;
  try {
    return await cancelTask;
  } finally {
    if (streamCancelInFlight === cancelTask) streamCancelInFlight = null;
  }
}

async function waitForPendingStreamCancel() {
  if (!streamCancelInFlight) return;
  if (dom.messageInput) dom.messageInput.disabled = true;
  if (dom.sendBtn) dom.sendBtn.disabled = true;
  setCoreStatus("stopped", "finishing server cancel");
  try {
    await streamCancelInFlight;
  } catch {
    setCoreStatus("stopped", "server cancel unavailable");
  } finally {
    if (dom.messageInput) dom.messageInput.disabled = false;
    syncSendButtonState();
  }
}

function shouldSubmitComposer(event) {
  if (!event || event.defaultPrevented || event.isComposing || event.keyCode === 229) return false;
  return event.key === "Enter" && !event.shiftKey && !event.ctrlKey && !event.metaKey && !event.altKey;
}

function handleComposerKeydown(event) {
  if (!shouldSubmitComposer(event)) return undefined;
  event.preventDefault();
  if (dom.messageInput?.disabled) return undefined;
  if (!dom.messageInput?.value?.trim()) {
    clearBlankComposerDraft();
    return undefined;
  }
  if (dom.sendBtn?.disabled) return undefined;
  if (typeof dom.form?.requestSubmit === "function") {
    dom.form.requestSubmit();
    return undefined;
  }
  return sendMessage();
}

function clearBlankComposerDraft() {
  if (dom.messageInput && !dom.messageInput.value.trim()) {
    dom.messageInput.value = "";
  }
  syncSendButtonState();
}

function handleQuickPromptClick(event) {
  const prompt = String(event?.currentTarget?.dataset?.q || "").trim();
  if (!prompt || !dom.messageInput || dom.messageInput.disabled) return;
  dom.messageInput.value = prompt;
  syncComposerDraftState();
  dom.messageInput.focus();
}

const IMAGE_SLASH_COMMAND_PATTERN = /^\/(?:imagine|image|img)\b/i;
const KOREAN_IMAGE_REQUEST_PATTERN = /(?:\uC774\uBBF8\uC9C0|\uADF8\uB9BC|\uC0AC\uC9C4|\uC77C\uB7EC\uC2A4\uD2B8)(?:\uB97C|\uC744)?(?:\s*(?:\uD558\uB098|\uD55C\s*\uC7A5|1\uAC1C))?\s*(?:\uB9CC\uB4E4\uC5B4|\uC0DD\uC131\uD574|\uADF8\uB824|\uC81C\uC791\uD574)\s*(?:\uC918|\uC8FC\uC138\uC694|\uC8FC\uB77C|\uBD10)?/u;
const KOREAN_IMAGE_REQUEST_PREFIX_PATTERN = /^(?:\uC774\uBBF8\uC9C0|\uADF8\uB9BC|\uC0AC\uC9C4|\uC77C\uB7EC\uC2A4\uD2B8)(?:\uB97C|\uC744)?(?:\s*(?:\uD558\uB098|\uD55C\s*\uC7A5|1\uAC1C))?\s*(?:\uB9CC\uB4E4\uC5B4|\uC0DD\uC131\uD574|\uADF8\uB824|\uC81C\uC791\uD574)\s*(?:\uC918|\uC8FC\uC138\uC694|\uC8FC\uB77C|\uBD10)?\s*[:,.-]?\s*/u;
const KOREAN_IMAGE_REQUEST_SUFFIX_PATTERN = /\s*(?:\uC774\uBBF8\uC9C0|\uADF8\uB9BC|\uC0AC\uC9C4|\uC77C\uB7EC\uC2A4\uD2B8)(?:\uB97C|\uC744)?(?:\s*(?:\uD558\uB098|\uD55C\s*\uC7A5|1\uAC1C))?\s*(?:\uB9CC\uB4E4\uC5B4|\uC0DD\uC131\uD574|\uADF8\uB824|\uC81C\uC791\uD574)\s*(?:\uC918|\uC8FC\uC138\uC694|\uC8FC\uB77C|\uBD10)?[.!?]*$/u;

function isImagineCommand(text) {
  const value = String(text || "").trim();
  return IMAGE_SLASH_COMMAND_PATTERN.test(value) || KOREAN_IMAGE_REQUEST_PATTERN.test(value);
}

function imagePromptFromCommand(text) {
  let value = String(text || "").trim();
  value = value.replace(IMAGE_SLASH_COMMAND_PATTERN, "").trim();
  value = value.replace(KOREAN_IMAGE_REQUEST_PREFIX_PATTERN, "").trim();
  value = value.replace(KOREAN_IMAGE_REQUEST_SUFFIX_PATTERN, "").trim();
  return value;
}

function imageJobFallbackUiModule(reason = "image_ui_unavailable") {
  const fallbackReason = safeDebugCockpitDetail(reason || "image_ui_unavailable");
  const updateImageJobCard = (card, job = {}) => {
    if (!card) return;
    const status = safeDebugCockpitDetail(job.status || "pending");
    const id = safeDebugCockpitDetail(job.id || job.jobId || job.job_id || "-");
    const jobReason = safeDebugCockpitDetail(job.reason || job.error || job.errorCode || job.error_code || fallbackReason);
    const content = safeDebugCockpitDetail(job.content || "[image job unavailable]");
    card.dataset.status = status;
    card.setAttribute("aria-label", `Image request unavailable; status ${status}; reason ${jobReason}`);
    card.title = card.getAttribute("aria-label") || "";
    card.textContent = `Image job Image request unavailable status: ${status} id: ${id} reason: ${jobReason} content: ${content}`;
  };
  return {
    renderImageJobCard(target, job = {}) {
      const card = document.createElement("section");
      card.dataset.imageJobDebug = "true";
      card.className = "image-job-card image-job-card-fallback";
      card.setAttribute("role", "status");
      card.setAttribute("aria-live", "polite");
      updateImageJobCard(card, job);
      target?.appendChild(card);
      return card;
    },
    updateImageJobCard,
    async attachImageJobDebug() {
      return null;
    },
    async attachImageJobConfigDebug(card) {
      const config = {
        "openai.image.enabled": false,
        imageServiceAvailable: false,
        disabledReason: fallbackReason,
        nextAction: fallbackReason
      };
      updateImageJobCard(card, { status: "failed", reason: fallbackReason, content: "[image job unavailable]" });
      return config;
    }
  };
}

async function loadImageJobUi() {
  if (!imageJobUiModule) {
    try {
      imageJobUiModule = await import("/js/image-jobs-ui.js");
    } catch {
      imageJobUiModule = imageJobFallbackUiModule("image_ui_unavailable");
    }
  }
  return imageJobUiModule;
}

async function imageJobFailureReason(response) {
  const fallback = `image_job_${response?.status || "failed"}`;
  try {
    const contentType = response?.headers?.get?.("content-type") || "";
    if (contentType.includes("application/json")) {
      const payload = await response.json();
      return safeDebugCockpitDetail(payload?.reason || payload?.error || payload?.message || fallback);
    }
    return safeDebugCockpitDetail((await response.text()) || fallback);
  } catch {
    return safeDebugCockpitDetail(fallback);
  }
}

function imageJobHandledError(reason) {
  const error = new Error(`image_job_${reason || "failed"}`);
  error.imageJobHandled = true;
  error.reason = safeDebugCockpitDetail(reason || "image_job_failed");
  return error;
}

function imageJobHandledStatus(reason) {
  const value = String(reason || "").toLowerCase();
  return (value.includes("disabled") || value.includes("unavailable") || value.includes("required"))
    ? "image_job_disabled"
    : "image_job_failed";
}

function imageJobConfigDisabledReason(config = {}) {
  if (!config || typeof config !== "object") return "";
  if (config["openai.image.enabled"] === false || config.imageServiceAvailable === false) {
    return safeDebugCockpitDetail(config.disabledReason || config.nextAction || "image plugin disabled");
  }
  return "";
}

function rejectImageJobLocally(card, updateImageJobCard, assistant, reason) {
  const safeReason = safeDebugCockpitDetail(reason || "image_job_unavailable");
  updateImageJobCard(card, { status: "failed", reason: safeReason, content: "[image job unavailable]" });
  setMessageContent(assistant, "assistant", `Image job unavailable: ${safeReason}`, "error");
  updateOrchestrationSignalBar({ streamStatus: "image_job_disabled", streamContext: safeReason });
  setCoreStatus("error", `image ${safeReason}`);
  throw imageJobHandledError(safeReason);
}

async function monitorImageJob(initialJob, card, assistant, headers, updateImageJobCard, maxClientWaitMs) {
  const id = String(initialJob.id || initialJob.jobId || initialJob.job_id);
  const deadline = Date.now() + maxClientWaitMs;
  const pollIntervalMs = Math.max(2000, Math.ceil(maxClientWaitMs / 90));
  let job = initialJob;
  for (let attempt = 0; attempt <= 90; attempt += 1) {
    if (card?.isConnected === false || assistant?.isConnected === false || isAssistantStreamStopped(assistant)) return;
    const status = String(job.status || "PENDING").trim().toUpperCase();
    if (status === "SUCCEEDED") {
      updateImageJobCard(card, { ...job, id, status, content: "[image generated]" });
      setMessageContent(assistant, "assistant", "[image generated]");
      return;
    }
    if (status === "FAILED") {
      const reason = /^[A-Z0-9_]{1,80}$/i.test(String(job.reason || "")) ? String(job.reason) : "IMAGE_JOB_FAILED";
      updateImageJobCard(card, { ...job, id, status, reason, content: "[image job failed]" });
      setMessageContent(assistant, "assistant", `Image job failed: ${reason}`, "error");
      return;
    }
    const remainingMs = deadline - Date.now();
    if (remainingMs <= 0 || attempt === 90) {
      updateImageJobCard(card, { status: IMAGE_JOB_STILL_RUNNING_CHECK_STATUS_OR_MANIFEST, id, maxClientWaitMs });
      setMessageContent(assistant, "assistant", "Image job still running. Check job status or manifest.");
      return;
    }
    updateImageJobCard(card, { ...job, id, status, content: "[image request submitted]" });
    if (attempt === 0) setMessageContent(assistant, "assistant", "Image job submitted; waiting for completion.");
    await new Promise(resolve => window.setTimeout(resolve, Math.min(pollIntervalMs, remainingMs)));
    if (card?.isConnected === false || assistant?.isConnected === false || isAssistantStreamStopped(assistant)) return;
    if (Date.now() >= deadline) continue;
    const controller = new AbortController();
    const requestTimeoutId = window.setTimeout(() => controller.abort(), Math.min(10000, deadline - Date.now()));
    let unavailableReason = "image_job_status_unavailable";
    try {
      const response = await fetch(`/api/image-plugin/jobs/${encodeURIComponent(id)}`, {
        method: "GET", headers, signal: controller.signal
      });
      if (!response.ok) {
        unavailableReason = `image_job_status_http_${Number(response.status) || 0}`;
        throw new Error("image_job_status_unavailable");
      }
      const nextJob = await response.json();
      if (!nextJob || String(nextJob.id || "") !== id) throw new Error("image_job_status_identity_mismatch");
      job = nextJob;
    } catch (_) {
      if (card?.isConnected !== false && assistant?.isConnected !== false && !isAssistantStreamStopped(assistant)) {
        updateImageJobCard(card, { status: "STATUS_UNAVAILABLE", id, reason: unavailableReason });
        setMessageContent(assistant, "assistant", "Image job status unavailable. Check job status or manifest.");
      }
      return;
    } finally {
      window.clearTimeout(requestTimeoutId);
    }
  }
}

async function submitImageJob(text, assistant) {
  const prompt = imagePromptFromCommand(text);
  const { renderImageJobCard, updateImageJobCard, attachImageJobDebug, attachImageJobConfigDebug } = await loadImageJobUi();
  const card = renderImageJobCard(assistant?.parentElement || dom.chatMessages, { status: "submitted" });
  const headers = withCsrfHeaders({ "Content-Type": "application/json" });
  if (!prompt) {
    rejectImageJobLocally(card, updateImageJobCard, assistant, "image_prompt_required");
  }
  const config = await attachImageJobConfigDebug(card, headers);
  const disabledReason = imageJobConfigDisabledReason(config);
  if (disabledReason) {
    rejectImageJobLocally(card, updateImageJobCard, assistant, disabledReason);
  }
  const response = await fetch("/api/image-plugin/jobs", {
    method: "POST",
    headers,
    body: JSON.stringify({ prompt })
  });
  if (!response.ok) {
    const reason = await imageJobFailureReason(response);
    updateImageJobCard(card, { status: "failed", reason, content: "[image job failed]" });
    await attachImageJobConfigDebug(card, headers);
    setMessageContent(assistant, "assistant", `Image job failed: ${reason}`, "error");
    updateOrchestrationSignalBar({ streamStatus: "image_job_failed", streamContext: reason });
    setCoreStatus("error", `image ${reason}`);
    throw imageJobHandledError(reason);
  }
  const job = await response.json();
  const id = job?.id || job?.jobId || job?.job_id;
  if (!id) rejectImageJobLocally(card, updateImageJobCard, assistant, "image_job_id_missing");
  const reportedEtaMs = job.etaSeconds != null ? Number(job.etaSeconds) * 1000
    : Number(job.etaMs || job.eta_ms || job.estimatedMs || 0);
  const etaMs = Number.isFinite(reportedEtaMs) && reportedEtaMs > 0 ? reportedEtaMs : 0;
  const maxClientWaitMs = Math.min(30 * 60 * 1000, Math.max(120000, etaMs + 45000));
  const initialStatus = String(job.status || "PENDING").trim().toUpperCase();
  updateOrchestrationSignalBar({ streamStatus: `image_job_${initialStatus.toLowerCase()}`, streamContext: "image job accepted" });
  setCoreStatus(initialStatus === "SUCCEEDED" ? "done" : initialStatus === "FAILED" ? "error" : "idle", `image job ${initialStatus.toLowerCase()}`);
  void monitorImageJob(job, card, assistant, headers, updateImageJobCard, maxClientWaitMs);
  void attachImageJobDebug(card, id, headers);
}

async function sendMessage() {
  if (!chatModelCatalogReady) return;
  if (chatAccessState === "login_required" || chatAccessState === "forbidden") return;
  const text = dom.messageInput.value.trim();
  if (!text) {
    clearBlankComposerDraft();
    return;
  }
  if (pendingAttachments.some(item => item.uploading)) {
    setStatusRailValue(dom.traceStatus, "첨부 업로드 완료 후 전송");
    return;
  }
  if (sendMessageInFlight || restoredRunResumeInFlight || pendingStopBeforeToken?.unresolved) return;
  sendMessageInFlight = true;
  try {
    return await sendMessageUnlocked(text);
  } finally {
    sendMessageInFlight = false;
  }
}

function modelSelectionPayload() {
  const mode = dom.modelSelectionMode?.value || "strict";
  return {
    model: mode === "auto" ? "llmrouter.auto" : dom.modelSelect?.value || undefined,
    strictModelSelection: mode !== "preferred" && mode !== "auto"
  };
}

async function sendMessageUnlocked(text) {
  // Capture this submission before cancellation yields to mutable controls.
  const payload = {
    message: text,
    question: text,
    ...modelSelectionPayload(),
    useRag: dom.useRag?.checked ?? true,
    useWebSearch: dom.searchModeSelect?.value !== "OFF",
    googleSearchRescueEnabled: currentControlSettings().googleSearchRescueEnabled,
    searchMode: dom.searchModeSelect?.value || "AUTO",
    executionMode: dom.executionMode?.value || "AUTO"
  };
  const currentSessionId = sessionIdFromPayload({ sessionId: state.currentSessionId });
  if (currentSessionId) payload.sessionId = currentSessionId;
  const attachmentIds = pendingAttachments.map(item => item.id).filter(Boolean).slice(0, MAX_PENDING_ATTACHMENTS);
  if (attachmentIds.length) payload.attachmentIds = attachmentIds;
  payload.attachmentGraphConsent = attachmentIds.length > 0 && $("attachmentGraphConsent")?.checked === true;
  payload.contextPreparationRequested = attachmentIds.length > 0 && $("contextPreparationRequested")?.checked === true;
  await waitForPendingStreamCancel();
  if (pendingStopBeforeToken?.unresolved) return;
  clearSelectionEntropyTrace(dom.chatMessages);
  clearDirectLiteralDiagnosticsSuppression();
  state.latestVisibleTurnEvidence = null;
  const draftText = text;
  let clearDraft = false;
  clearActiveRunIdentity();
  streamCancelRequested = false;
  streamRenderSuppressed = false;
  appendMessage("user", text);
  const loaderId = `assistant-${Date.now()}-${++assistantMessageSequence}`;
  beginChatTransitionDebugTurn(`turn:${assistantMessageSequence}`, "new-turn");
  const assistant = appendMessage("assistant", "");
  assistant.id = loaderId;
  assistant.dataset.executionModeRequested = payload.executionMode;
  suppressAnswerOnlyDiagnostics(assistant, draftText);
  activeStreamAssistant = assistant;
  invalidatePendingSessionSelectionForTranscriptOwnership();
  syncSessionSelectionCapability();
  latestStreamQueryRewriteHeartbeat = null;
  dom.messageInput.value = "";
  updateOrchestrationSignalBar({
    streamStatus: "connecting",
    streamContext: payload.searchMode,
    model: payload.model,
    pipelineSnapshot: {}
  });
  setStatusRailValue(dom.searchStatus, `요청 ${searchModeRailValue(payload.searchMode)}`);
  setStatusRailValue(dom.ragStatus, `요청 ${payload.useRag ? "ON" : "OFF"}`);
  dom.messageInput.disabled = true;
  dom.sendBtn.disabled = true;
  if (dom.stopBtn) dom.stopBtn.disabled = false;
  setComposerBusy(true);
  renderPrimaryDiagnostic(selectPrimaryDiagnostic({
    healthOverlay: currentTurnHealthOverlay,
    streamStatus: "connecting",
    isStopAvailable: Boolean(
      dom.stopBtn &&
      dom.stopBtn.hidden === false &&
      dom.stopBtn.style.display !== "none" &&
      dom.stopBtn.disabled === false
    )
  }));
  setCoreStatus("streaming", "connecting");
  try {
    const imageCommand = isImagineCommand(text);
    if (imageCommand) {
      await submitImageJob(text, assistant);
    } else {
      await streamChat(payload, loaderId);
    }
    clearDraft = true;
  } catch (error) {
    const pendingStop = pendingStopBeforeToken;
    if (pendingStop?.assistant === assistant && !streamCancelRequested) {
      pendingStop.unresolved = true;
      await cancelActiveStream();
    }
    if (error?.name === "AbortError" || streamCancelRequested) {
      clearSelectionEntropyTrace(assistant);
      markAssistantStreamStopped(assistant);
      markActiveStreamStoppedRail("abort-error", assistant);
      clearDraft = true;
    } else if (error?.imageJobHandled === true) {
      clearActiveRunIdentity();
      clearDraft = true;
      const imageHandledReason = safeDebugCockpitDetail(error.reason || "image job failed");
      const imageHandledStatus = imageJobHandledStatus(imageHandledReason);
      updateOrchestrationSignalBar({
        streamStatus: imageHandledStatus,
        streamContext: imageHandledReason
      });
      setCoreStatus(imageHandledStatus === "image_job_disabled" ? "fallback" : "error", imageHandledReason);
    } else if (isAssistantStreamStopped(assistant)) {
      clearDraft = true;
    } else {
      const typedTerminalFailure = Boolean(error?.chatFailure || error?.streamFailureCode);
      let expectedRun = null;
      if (!typedTerminalFailure) {
        expectedRun = activeRunIdentitySnapshot();
        if (expectedRun) {
          try {
            const recovered = await recoverExactRunAfterTransportLoss(expectedRun, loaderId);
            if (recovered) {
              clearDraft = true;
              return;
            }
          } catch (recoveryError) {
            if (error?.name !== "TypeError") {
              renderMessageFailedDiagnostic(assistant, error, recoveryError);
            }
          }
        }
      }
      if (typedTerminalFailure || error?.name === "TypeError") {
        const failureMeta = error?.chatFailure || classifyChatFailure({
          error,
          protocolCode: error?.streamFailureCode
        });
        const typedError = error?.chatFailure ? error : chatFailureError(failureMeta);
        clearActiveRunIdentity();
        if (failureMeta.serverCode === "session_forbidden") {
          forgetCurrentSessionId(currentSessionId);
        }
        clearSelectionEntropyTrace(assistant);
        renderChatFailureNotice(assistant, failureMeta, { draftText, sessionId: currentSessionId });
        applyChatFailureState(assistant, failureMeta);
        renderMessageFailedDiagnostic(assistant, typedError);
        updateOrchestrationSignalBar({
          streamStatus: "error",
          streamContext: failureMeta.failureKind
        });
        setCoreStatus("error", "message_failed");
        return;
      }
      clearSelectionEntropyTrace(assistant);
      renderChatFailureNotice(assistant, classifyChatFailure({ error }));
      renderMessageFailedDiagnostic(assistant, error);
      updateOrchestrationSignalBar({
        streamStatus: "error",
        streamContext: expectedRun ? "exact-run-recovery-unavailable" : "stream-identity-unavailable"
      });
      setCoreStatus("error", expectedRun ? "exact run recovery unavailable" : "message_failed");
    }
  } finally {
    dom.messageInput.disabled = false;
    dom.sendBtn.disabled = false;
    dom.messageInput.value = clearDraft ? "" : draftText;
    if (clearDraft && pendingAttachments.length) {
      pendingAttachments.length = 0;
      renderAttachChips();
    }
    dom.messageInput.focus();
    if (dom.stopBtn) dom.stopBtn.disabled = true;
    setComposerBusy(false);
    streamCancelRequested = false;
    streamRenderSuppressed = false;
    if (activeStreamAssistant === assistant) activeStreamAssistant = null;
    syncSessionSelectionCapability();
    syncSendButtonState();
    if (pendingStopBeforeToken?.unresolved) showRetryableCancelOutcome("request outcome unknown; retry Stop");
  }
}

function isActiveStreamRenderTarget(assistant, controller) {
  if (!assistant || !controller) return false;
  if (isAssistantStreamStopped(assistant)) return false;
  if (streamRenderSuppressed || streamCancelRequested || controller.signal?.aborted) return false;
  return assistant === activeStreamAssistant && controller === streamController;
}

function streamClientDeadlineMs(payload = {}) {
  return streamServerBudgetMs(payload);
}

function streamServerBudgetMs(payload = {}) {
  // Rendered from the same server property used by admission. Keep legacy
  // bounded defaults only when an older page has no admitted policy.
  const configured = Number(document.querySelector('meta[name="chat-request-budget-ms"]')?.getAttribute("content"));
  if (Number.isInteger(configured) && configured > 0) return configured;
  return 600000;
}

function streamServerBudgetHeaders(payload = {}) {
  const budgetMs = streamServerBudgetMs(payload);
  return budgetMs ? { "X-Budget-Ms": String(budgetMs) } : {};
}

const chatAdmissionKeys = new WeakMap();
function generationIdempotencyHeaders(payload) {
  if (!payload || payload.attach === true) return {};
  let key = chatAdmissionKeys.get(payload);
  if (!key) {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    key = Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
    chatAdmissionKeys.set(payload, key);
  }
  return { "Idempotency-Key": key };
}

async function streamChat(payload, loaderId, options = {}) {
  const assistant = document.getElementById(loaderId);
  if (payload?.attach !== true && typeof CustomEvent === "function" && typeof document.dispatchEvent === "function") {
    document.dispatchEvent(new CustomEvent("awx:trace-turn", { detail: {} }));
  }
  const streamOwnedActiveAssistant = assistant && !activeStreamAssistant;
  if (streamOwnedActiveAssistant) {
    activeStreamAssistant = assistant;
    invalidatePendingSessionSelectionForTranscriptOwnership();
  }
  syncSessionSelectionCapability();
  streamController = new AbortController();
  const currentStreamController = streamController;
  const admissionHeaders = generationIdempotencyHeaders(payload);
  currentStreamController.requestReceipt = payload?.attach === true ? null : {
    sessionId: normalizeSessionIdValue(payload.sessionId), key: admissionHeaders["Idempotency-Key"]
  };
  const streamAbortRequested = () => currentStreamController?.signal?.aborted;
  const previousStartedAt = Number(assistant?.dataset?.streamStartedAt);
  const streamStartedAt = payload?.attach === true && Number.isFinite(previousStartedAt)
    ? previousStartedAt : nowMs();
  let lastTransportProgressAt = nowMs();
  let transportHeadersSeen = false;
  const transportIdleMs = () => Math.max(0, Math.round(nowMs() - lastTransportProgressAt));
  let firstAnswerSeen = payload?.attach === true && assistant?.dataset?.streamAnswerSeen === "true";
  let streamSseStarted = payload?.attach === true && assistant?.dataset?.streamSseStartMs != null;
  if (assistant?.dataset) {
    assistant.dataset.streamStartedAt = String(streamStartedAt);
    assistant.dataset.streamAnswerSeen = String(firstAnswerSeen);
    if (payload?.attach !== true) {
      assistant.dataset.streamStartedEpochMs = String(Date.now());
      delete assistant.dataset.streamSseStartMs;
      delete assistant.dataset.streamFirstAnswerMs;
    }
  }
  const streamWaitMs = () => {
    const elapsedMs = Math.max(0, Math.round(nowMs() - streamStartedAt));
    return elapsedMs;
  };
  if (payload?.attach === true && assistant?.dataset?.state === "pending") assistant.dataset.waitPhase = "attaching";
  markAssistantClientWait(assistant, streamWaitMs());
  const streamHeartbeatContext = () => {
    return `client-wait:${streamWaitMs()}ms`;
  };
  let clientDeadlineTriggered = false;
  let clientDeadlineReject = null;
  const clientDeadlineMs = streamClientDeadlineMs(payload);
  const clientDeadlinePromise = clientDeadlineMs == null ? null : new Promise((_, reject) => {
    clientDeadlineReject = reject;
  });
  function triggerStreamClientDeadline(elapsedMs) {
    if (clientDeadlineTriggered) return;
    clientDeadlineTriggered = true;
    const safeElapsedMs = Math.max(0, Math.round(Number(elapsedMs) || 0));
    updateOrchestrationSignalBar({
      streamStatus: "attaching",
      streamContext: `transport_detach client-wait:${safeElapsedMs}ms`
    });
    setCoreStatus("streaming", "reconnecting exact run");
    clientDeadlineReject?.(streamClientDeadlineError(safeElapsedMs));
    // Preserve the run identity for the existing transport-loss recovery handler.
    currentStreamController.abort();
  }
  let streamHeartbeatTimer = null;
  const stopStreamHeartbeat = () => {
    if (streamHeartbeatTimer != null) {
      clearActiveStreamHeartbeat(streamHeartbeatTimer);
      streamHeartbeatTimer = null;
    }
  };
  streamHeartbeatTimer = window.setInterval(() => {
    if (activeStreamHeartbeatTimer !== streamHeartbeatTimer || streamController?.signal?.aborted) return;
    if (streamCancelRequested || streamRenderSuppressed || streamAbortRequested()) return;
    const elapsedMs = streamWaitMs();
    const streamHeartbeatStale = elapsedMs >= STREAM_STALE_WAIT_MS;
    const streamHeartbeatStatus = streamHeartbeatStale ? "model_wait" : payload?.attach ? "attaching" : "streaming";
    const streamHeartbeatDetail = `client-wait:${elapsedMs}ms${streamHeartbeatStale ? " next:stop_or_wait" : ""}`;
    updateOrchestrationSignalBar({
      streamStatus: streamHeartbeatStatus,
      streamContext: streamHeartbeatDetail
    });
    markAssistantClientWait(assistant, elapsedMs);
    if (streamHeartbeatStale) {
      setCoreStatus("streaming", streamHeartbeatDetail);
    }
    // Each transport keeps a positive header wait and a byte-idle limit.
    const effectiveDeadlineMs = transportHeadersSeen
      ? clientDeadlineMs : Math.min(5000, clientDeadlineMs);
    if (clientDeadlineMs != null && (elapsedMs >= clientDeadlineMs
      || transportIdleMs() >= effectiveDeadlineMs)) {
      triggerStreamClientDeadline(elapsedMs);
    }
  }, 250);
  activeStreamHeartbeatTimer = streamHeartbeatTimer;
  try {
    const streamUrl = chatTraceRequestUrl(payload?.attach === true
      ? "/api/chat/stream?attach=true" : "/api/chat/stream");
    const responsePromise = fetch(streamUrl, {
      method: "POST",
      headers: withChatCorrelationHeaders({
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "X-Chat-Run-Ack-Required": "1",
        ...admissionHeaders,
        ...streamServerBudgetHeaders(payload)
      }, { sessionId: payload.sessionId, runToken: payload.runToken }),
      body: JSON.stringify(payload),
      signal: currentStreamController.signal
    });
    const response = clientDeadlinePromise == null ? await responsePromise
      : await Promise.race([responsePromise, clientDeadlinePromise]);
    if (streamAbortRequested()) throw streamAbortError();
    if (!response.ok) {
      const bodyPromise = readBoundedFailureBody(response, 1200);
      const bounded = clientDeadlinePromise == null ? await bodyPromise
        : await Promise.race([bodyPromise, clientDeadlinePromise]);
      const meta = classifyChatFailure({
        status: response.status,
        boundedText: bounded.text,
        contentType: bounded.contentType,
        truncated: bounded.truncated,
        retryAfter: responseHeader(response, "Retry-After")
      });
      throw chatFailureError(meta);
    }
    if (!response.body) {
      throw chatFailureError(classifyChatFailure({ streamCode: "stream_failed" }));
    }
    if (response.redirected || !/^text\/event-stream(?:\s*;|$)/i.test(responseHeader(response, "Content-Type") || "")) {
      try { await response.body.cancel(); } catch { /* no parsing of HTML/JSON as SSE */ }
      throw chatFailureError(classifyChatFailure({ protocolCode: "unexpected_content_type" }));
    }
    applyChatResponseHeaders(response, { allowSessionIdentity: false });
    if (streamAbortRequested()) throw streamAbortError();
    if (!transportHeadersSeen && clientDeadlineMs != null
      && transportIdleMs() >= Math.min(5000, clientDeadlineMs)) {
      triggerStreamClientDeadline(streamWaitMs());
      throw streamClientDeadlineError(streamWaitMs());
    }
    transportHeadersSeen = true;
    lastTransportProgressAt = nowMs();
    streamSseStarted = true;
    if (assistant?.dataset) {
      if (assistant.dataset.streamSseStartMs == null) assistant.dataset.streamSseStartMs = String(streamWaitMs());
      assistant.dataset.streamHttpStatus = String(response.status);
      assistant.dataset.streamRequestId = String(responseHeader(response, "x-request-id") || "").slice(0, 128);
    }
    const reader = response.body.getReader();
    let terminalEventSeen = false;
    let terminalFailureMeta = null;
    let finalAckPromise = null;
    const parser = createSseEventParser({
      onEvent(event) {
        const decoded = decodeSseEvent(event);
        const effectiveType = decoded.effectiveType || "message";
        const eventPayload = decoded.payload;
        if (!isActiveStreamRenderTarget(assistant, currentStreamController)) {
          recordChatTransitionDebug({ kind: "late-event", to: "blocked",
            reasonCode: "inactive-stream-target", lateEventBlocked: true });
          return true;
        }
        const statusSignal = eventPayload?.statusSignal || eventPayload?.status_signal || eventPayload?.signal || eventPayload;
        const cancelledStatus = effectiveType === "status" &&
          (statusSignal?.cancelled === true || String(statusSignal?.code || "").toLowerCase() === "cancelled");
        const failureEvent = effectiveType === "error" || effectiveType === "stream_failed";
        if (failureEvent) {
          terminalEventSeen = true;
          clearActiveRunIdentity();
          terminalFailureMeta = classifyChatFailure(decoded.malformedTerminal
            ? { protocolCode: "malformed_terminal_event" }
            : { streamCode: sseFailureCode(eventPayload) });
          return false;
        }
        const exactFinalRun = effectiveType === "final" ? activeRunIdentitySnapshot() : null;
        terminalEventSeen = terminalEventSeen || effectiveType === "final" || cancelledStatus;
        if (streamAbortRequested()) throw streamAbortError();
        if (isActiveStreamRenderTarget(assistant, currentStreamController)) {
          renderChatEvent(eventPayload, assistant, effectiveType, exactFinalRun);
          if (effectiveType === "session" && assistant?.dataset) {
            const requestHash = eventPayload?.traceSignal?.requestIdHash;
            if (/^hash:[a-f0-9]{12,64}$/i.test(requestHash || "")) assistant.dataset.streamRequestHash = requestHash;
          }
          // The renderer removes reasoning tags before appending accessible text.
          // SSE liveness and first visible answer remain separate observations.
          const visibleAnswer = (effectiveType === "token" || effectiveType === "message" || effectiveType === "final")
            && String(assistant?.dataset?.ariaText || "").trim().length > 0;
          if (visibleAnswer && assistant?.dataset && assistant.dataset.streamFirstAnswerMs == null) {
            assistant.dataset.streamFirstAnswerMs = String(streamWaitMs());
          }
          if (terminalEventSeen || visibleAnswer) {
            firstAnswerSeen = true;
            if (assistant?.dataset) assistant.dataset.streamAnswerSeen = "true";
          }
        } else {
          const terminalLatch = isAssistantStreamStopped(assistant)
            || streamRenderSuppressed
            || streamCancelRequested
            || Boolean(currentStreamController.signal?.aborted);
          recordChatTransitionDebug({
            kind: "late-event",
            to: "blocked",
            reasonCode: terminalLatch ? "terminal-latch" : "inactive-stream-target",
            terminalLatch,
            lateEventBlocked: true
          });
        }
        if (effectiveType === "final") {
          if (exactFinalRun && responseHeader(response, "X-Idempotent-Replay") !== "true") {
            finalAckPromise = acknowledgeExactRun(
              exactFinalRun.sessionId, exactFinalRun.runToken, "final")
              .then((acknowledged) => {
                if (acknowledged) clearActiveRunIdentityIfMatch(exactFinalRun);
                return acknowledged;
              });
          } else {
            clearActiveRunIdentity();
          }
        }
        return !terminalEventSeen;
      }
    });
    while (true) {
      const next = clientDeadlinePromise == null
        ? await reader.read()
        : await Promise.race([reader.read(), clientDeadlinePromise]);
      if (streamAbortRequested()) throw streamAbortError();
      if (next.done) break;
      if (next.value?.byteLength > 0) lastTransportProgressAt = nowMs();
      parser.push(next.value);
      if (terminalFailureMeta) throw chatFailureError(terminalFailureMeta);
      if (terminalEventSeen) break;
    }
    parser.finish();
    if (terminalFailureMeta) throw chatFailureError(terminalFailureMeta);
    if (finalAckPromise) {
      if (clientDeadlinePromise == null) await finalAckPromise;
      else await Promise.race([finalAckPromise, clientDeadlinePromise]);
    }
    if (!terminalEventSeen) {
      const exactRun = activeRunIdentitySnapshot();
      if (exactRun) {
        const transportError = new Error("exact_run_transport_eof");
        transportError.exactRun = exactRun;
        throw transportError;
      }
      throw chatFailureError(classifyChatFailure({ protocolCode: "stream_incomplete" }));
    }
  } finally {
    if (typeof CustomEvent === "function" && typeof document.dispatchEvent === "function") {
      document.dispatchEvent(new CustomEvent("awx:trace-turn", { detail: { complete: true } }));
    }
    stopStreamHeartbeat();
    if (streamOwnedActiveAssistant && activeStreamAssistant === assistant) activeStreamAssistant = null;
    syncSessionSelectionCapability();
  }
}

function dispatchBrainStateSignal(name, detail = {}) {
  document.dispatchEvent(new CustomEvent(`brain-state:${name}`, { detail }));
}

const OPS_WINDOWS = {
  brainState: '/admin/brain-state',
  modelSettings: '/model-settings',
  pipelineStatus: '/admin/pipeline-status',
  ragOps: '/admin/rag-ops-cockpit',
  vectorDiagnostics: '/admin/vector-diagnostics'
};

const OPS_WINDOW_ACTIONS = {
  'open-brain-state': 'brainState',
  'open-model-settings': 'modelSettings',
  'open-pipeline-status': 'pipelineStatus',
  'open-rag-ops': 'ragOps',
  'open-vector-diagnostics': 'vectorDiagnostics'
};

function openOpsWindow(name, options = {}) {
  const target = OPS_WINDOWS[name];
  if (!target) return false;
  const protectedSurface = options.protectedSurface === true || target.startsWith("/admin/");
  const protectedDetail = `admin_sign_in_required target:${target}`;
  setStatusRailValue(dom.traceStatus, protectedSurface ? protectedDetail : "opening ops surface");
  if (dom.traceStatus) {
    dom.traceStatus.title = protectedSurface ? `Auth disabled for test mode: ${target}` : `Opening ${target}`;
  }
  if (protectedSurface) {
    return false;
  }
  window.location.assign(target);
  return true;
}

(document.querySelectorAll?.("[data-menu-action]") || []).forEach((link) => {
  link.addEventListener("click", (event) => {
    const action = link.getAttribute("data-menu-action");
    const targetName = OPS_WINDOW_ACTIONS[action];
    if (!targetName) return;
    event.preventDefault();
    openOpsWindow(targetName, {
      protectedSurface: link.getAttribute("data-admin-surface") === "protected"
    });
  });
});

function syncChatAccessNotice() {
  const notice = document.getElementById("chatAccessNotice");
  if (!notice) return;
  const login = chatAccessState === "login_required";
  notice.hidden = !login && chatAccessState !== "forbidden" && chatModelCatalogReady;
  if (notice.hidden) return;
  notice.replaceChildren();
  const label = document.createElement("span");
  label.textContent = login ? "로그인이 필요합니다. 작성한 질문은 로그인 후 복원됩니다."
    : chatAccessState === "forbidden" ? "현재 대화에 접근할 수 없습니다. 대화 기록에서 다른 대화를 선택해 주세요."
    : "모델 상태 확인이 필요합니다. 모델 탐색에서 선택 가능한 모델을 확인해 주세요.";
  notice.appendChild(label);
  if (login) {
    const link = document.createElement("a");
    link.href = "/login";
    link.textContent = "로그인";
    link.addEventListener("click", preserveComposerDraft);
    notice.appendChild(link);
  }
}
const historyPanel = document.getElementById("historyPanel");
document.addEventListener("chat:model-catalog", (event) => {
  chatModelCatalogReady = event.detail?.ready === true;
  syncSendButtonState();
});
const narrowChat = window.matchMedia?.("(max-width: 760px)");
if (historyPanel && narrowChat?.matches) historyPanel.open = false;
narrowChat?.addEventListener?.("change", (event) => {
  if (historyPanel) historyPanel.open = !event.matches;
});
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  document.querySelectorAll(".answer-tools[open], .admin-tools[open]").forEach(panel => { panel.open = false; });
  if (historyPanel && narrowChat?.matches) historyPanel.open = false;
});
// ===== 첨부 (기존 /api/attachments 업로드 계약을 /api/chat/attachments 별칭으로 위임) =====
function renderAttachChips() {
  const list = dom.attachList;
  if (!list) return;
  list.replaceChildren();
  list.hidden = pendingAttachments.length === 0;
  const consentLabel = $("attachmentGraphConsentLabel");
  if (consentLabel) consentLabel.hidden = pendingAttachments.length === 0;
  const consent = $("attachmentGraphConsent");
  if (consent) consent.checked = false;
  const preparationLabel = $("contextPreparationLabel");
  if (preparationLabel) preparationLabel.hidden = pendingAttachments.length === 0;
  const preparation = $("contextPreparationRequested");
  if (preparation) preparation.checked = false;
  pendingAttachments.forEach((item, index) => {
    const chip = document.createElement("li");
    chip.className = "attach-chip" + (item.error ? " error" : "");
    const label = document.createElement("span");
    label.textContent = item.uploading ? `${item.name} · 업로드 중…` : item.name;
    chip.appendChild(label);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "attach-remove";
    remove.setAttribute("aria-label", `${item.name} 제거`);
    remove.textContent = "×";
    remove.disabled = item.uploading === true;
    remove.addEventListener("click", () => {
      pendingAttachments.splice(index, 1);
      renderAttachChips();
    });
    chip.appendChild(remove);
    list.appendChild(chip);
  });
}

async function uploadSelectedAttachments(fileList) {
  const files = Array.from(fileList || []).filter(file => file && file.size >= 0);
  if (!files.length) return;
  const room = Math.max(0, MAX_PENDING_ATTACHMENTS - pendingAttachments.length);
  const picked = files.slice(0, room);
  if (picked.length < files.length) {
    setStatusRailValue(dom.traceStatus, `첨부는 최대 ${MAX_PENDING_ATTACHMENTS}개까지`);
  }
  if (!picked.length) return;
  const items = picked.map(file => ({ id: null, name: file.name, size: file.size, uploading: true, error: false }));
  pendingAttachments.push(...items);
  renderAttachChips();
  try {
    const sessionId = sessionIdFromPayload({ sessionId: state.currentSessionId });
    const body = new FormData();
    picked.forEach(file => body.append("files", file, file.name));
    if (sessionId) body.append("sessionId", String(sessionId));
    const response = await fetch("/api/chat/attachments", {
      method: "POST",
      headers: withChatCorrelationHeaders({}, { sessionId }),
      body
    });
    if (!response.ok) throw new Error(`attachment_upload_http_${response.status}`);
    const saved = await response.json();
    const rows = Array.isArray(saved) ? saved : [];
    items.forEach((item, index) => {
      item.uploading = false;
      const dto = rows[index];
      if (dto && dto.id) {
        item.id = String(dto.id);
        if (dto.name) item.name = String(dto.name);
      } else {
        item.error = true;
      }
    });
  } catch {
    items.forEach(item => { item.uploading = false; item.error = true; });
    setStatusRailValue(dom.traceStatus, "첨부 업로드 실패");
  }
  renderAttachChips();
  syncSendButtonState();
}

// ===== 음성 입력: conversate pcm-worklet(16kHz Int16 mono) → /api/chat/transcribe =====
const micSupported = () => Boolean(
  window.isSecureContext && navigator.mediaDevices?.getUserMedia && window.AudioContext && window.AudioWorkletNode
);

function setMicRecordingUi(active) {
  if (!dom.micBtn) return;
  dom.micBtn.setAttribute("aria-pressed", active ? "true" : "false");
  dom.micBtn.classList.toggle("recording", active);
}

async function micRecordingStart() {
  if (micRecording || micUnavailable || !micSupported() || dom.messageInput?.disabled) return;
  let media;
  try {
    media = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }
    });
  } catch {
    setStatusRailValue(dom.traceStatus, "마이크 권한이 없습니다");
    return;
  }
  try {
    const context = new AudioContext();
    await context.resume();
    await context.audioWorklet.addModule("/api/chat/pcm-worklet.js");
    const node = new AudioWorkletNode(context, "conversate-pcm");
    const session = { media, context, node, source: null, chunks: [], bytes: 0, flushed: null };
    node.port.onmessage = (event) => {
      if (event.data?.stopped) {
        session.flushed?.();
        return;
      }
      const pcm = event.data?.pcm;
      if (!(pcm instanceof ArrayBuffer) || !pcm.byteLength) return;
      session.chunks.push(pcm);
      session.bytes += pcm.byteLength;
      if (session.bytes >= MIC_PCM_MAX_BYTES) void micRecordingFinish();
    };
    node.onprocessorerror = () => void micRecordingCancel();
    const source = context.createMediaStreamSource(media);
    const mute = context.createGain();
    mute.gain.value = 0;
    source.connect(node).connect(mute).connect(context.destination);
    session.source = source;
    micRecording = session;
    setMicRecordingUi(true);
    setStatusRailValue(dom.traceStatus, "녹음 중… 다시 누르면 전사");
  } catch {
    media.getTracks().forEach(track => track.stop());
    micUnavailable = true;
    if (dom.micBtn) dom.micBtn.hidden = true;
  }
}

async function micRecordingTeardown(session, finish) {
  try {
    if (finish && session.node) {
      const flushed = new Promise(resolve => { session.flushed = resolve; });
      session.node.port.postMessage("finish");
      await Promise.race([flushed, new Promise(resolve => setTimeout(resolve, 1500))]);
    } else if (session.node) {
      session.node.port.postMessage("stop");
    }
  } catch { /* 워크렛이 이미 종료됨 */ }
  try { session.source?.disconnect(); } catch { /* 연결 해제 실패는 무시 */ }
  try { session.node?.disconnect(); } catch { /* 연결 해제 실패는 무시 */ }
  session.media?.getTracks?.().forEach(track => track.stop());
  if (session.context) await session.context.close().catch(() => {});
}

function micCollectedPcm(session) {
  if (!session || session.bytes < 640) return null;
  const out = new Uint8Array(session.bytes);
  let offset = 0;
  for (const chunk of session.chunks) {
    out.set(new Uint8Array(chunk), offset);
    offset += chunk.byteLength;
  }
  return out;
}

async function micRecordingFinish() {
  const session = micRecording;
  if (!session) return;
  micRecording = null;
  setMicRecordingUi(false);
  setStatusRailValue(dom.traceStatus, "전사 중…");
  await micRecordingTeardown(session, true);
  const pcm = micCollectedPcm(session);
  if (!pcm) {
    setStatusRailValue(dom.traceStatus, "");
    return;
  }
  try {
    const response = await fetch("/api/chat/transcribe", {
      method: "POST",
      headers: withChatCorrelationHeaders({ "Content-Type": "application/octet-stream" }, { sessionId: sessionIdFromPayload({ sessionId: state.currentSessionId }) }),
      body: pcm
    });
    if (!response.ok) {
      const reason = (await response.json().catch(() => null))?.reason || `http_${response.status}`;
      setStatusRailValue(dom.traceStatus, "음성 전사 불가");
      if (reason === "asr_disabled" || response.status === 404) {
        micUnavailable = true;
        if (dom.micBtn) dom.micBtn.hidden = true;
      }
      return;
    }
    const data = await response.json();
    const transcript = String(data?.transcript || "").trim();
    if (transcript && dom.messageInput) {
      const base = dom.messageInput.value.trimEnd();
      dom.messageInput.value = base ? `${base} ${transcript}` : transcript;
      syncComposerDraftState();
      dom.messageInput.focus();
    }
    setStatusRailValue(dom.traceStatus, transcript ? "" : "인식된 음성이 없습니다");
  } catch {
    setStatusRailValue(dom.traceStatus, "음성 전사 실패");
  }
}

async function micRecordingCancel() {
  const session = micRecording;
  if (!session) return;
  micRecording = null;
  setMicRecordingUi(false);
  await micRecordingTeardown(session, false);
  setStatusRailValue(dom.traceStatus, "");
}

dom.attachBtn?.addEventListener("click", () => dom.attachInput?.click());
dom.attachInput?.addEventListener("change", () => {
  void uploadSelectedAttachments(dom.attachInput.files);
  dom.attachInput.value = "";
});
dom.micBtn?.addEventListener("click", () => {
  if (dom.messageInput?.disabled) return;
  if (micRecording) void micRecordingFinish(); else void micRecordingStart();
});
if (dom.micBtn && !micSupported()) dom.micBtn.hidden = true;

dom.stopBtn?.addEventListener("click", () => {
  void cancelActiveStream();
});

dom.sendBtn?.addEventListener("click", () => {
  if (dom.messageInput?.disabled || dom.sendBtn?.disabled) return;
  void sendMessage();
});

dom.modelSelect?.addEventListener("change", handleControlChange);
dom.modelSelectionMode?.addEventListener("change", handleControlChange);
dom.executionMode?.addEventListener("change", handleControlChange);
dom.searchModeSelect?.addEventListener("change", handleControlChange);
dom.useRag?.addEventListener("change", handleControlChange);
dom.googleSearchRescue?.addEventListener("change", () => {
  if (dom.googleSearchRescue.dataset.awxSettingsApplying === "true") return;
  dom.googleSearchRescue.dataset.rescueExplicit = "true";
  handleControlChange();
});
document.addEventListener("awx:rescue-owner-verified", () => persistControlSettings());
dom.newChatBtn?.addEventListener("click", startNewChatSession);
document.querySelectorAll(".qa[data-q]").forEach((button) => {
  button.addEventListener("click", handleQuickPromptClick);
});
dom.messageInput?.addEventListener("keydown", handleComposerKeydown);
dom.messageInput?.addEventListener("input", syncComposerDraftState);
dom.form?.addEventListener("submit", (event) => {
  event.preventDefault();
  sendMessage();
});
initializeContextSummary();
if (!restoreStoredControlSettings()) {
  resetControlSettingsToDefaults();
  const smokeProofDefaultsApplied = applySmokeProofControlDefaults();
  syncControlStatus({ source: "factory", persist: !smokeProofDefaultsApplied });
}
setComposerBusy(false);
try {
  const recoveredDraft = sessionStorage.getItem("chat.recoveryDraft");
  if (recoveredDraft && dom.messageInput && !dom.messageInput.value) dom.messageInput.value = recoveredDraft;
  sessionStorage.removeItem("chat.recoveryDraft");
  syncSendButtonState();
} catch { /* optional per-tab draft recovery */ }
restoreCurrentSessionId();
window.ChatConversationExport?.bind({ getCurrentSessionId: () => state.currentSessionId });
void refreshSessionList("init");
restoreActiveRunIdentity();
reconcileRestoredSessionTranscript();
scheduleRestoredSessionTranscriptReconcile();
window.addEventListener("pageshow", scheduleRestoredSessionTranscriptReconcile);
refreshDebugHeartbeat();
window.setInterval(refreshDebugHeartbeat, 30000);
dispatchBrainStateSignal('session', state.currentSessionId ? { sessionId: state.currentSessionId } : {});
dispatchBrainStateSignal('answer', {});
