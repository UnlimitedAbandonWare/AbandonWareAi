(() => {
  "use strict";
  const exactHash = /^hash:[0-9a-f]{12}$/;
  const pagePath = "/api/diagnostics/debug/events/page";
  let nodes = null, timer = null, inFlight = null, sequence = 0;
  let hash = null, hashField = null, cursor = null, completedAt = null;
  let headId = null, cycleHeadId = null, cycleEvents = [];
  let failures = 0, stopReason = null, paused = false;
  const seen = new Set();

  function status(message) { if (nodes) nodes.status.textContent = message; }
  function clearTimer() { if (timer !== null) clearTimeout(timer); timer = null; }
  function retire() {
    sequence++;
    clearTimer();
    if (inFlight) {
      clearTimeout(inFlight.watchdog);
      inFlight.controller.abort();
      inFlight = null;
    }
  }
  function canRead() {
    return nodes && nodes.readAllowed && hash && !stopReason && !paused && !document.hidden;
  }
  function schedule(delay) {
    clearTimer();
    if (!canRead()) return;
    if (completedAt !== null) {
      const remaining = 60000 - (Date.now() - completedAt);
      if (remaining <= 0) { status("진단 관찰 종료"); return; }
      delay = Math.min(delay, remaining);
    }
    timer = setTimeout(() => { timer = null; void poll(); }, delay);
  }
  function safeText(value) {
    if (typeof value === "string") return value.slice(0, 90);
    if (typeof value === "number" && Number.isFinite(value)) return String(value);
    return "";
  }
  function eventSummary(event) {
    const data = event && typeof event.data === "object" && !Array.isArray(event.data) ? event.data : {};
    return [event?.ts, data.stage ?? data.type ?? event?.type, data.status ?? event?.status,
      data.provider, data.model, data.why_code ?? data.whyCode, data.durationMs, data.fallbackTo]
      .map(safeText).filter(Boolean).join(" · ") || "진단 이벤트";
  }
  function appendRow(message, id) {
    const row = document.createElement("div");
    row.className = "trace-dock-event";
    row.textContent = message;
    if (id) row.dataset.eventId = id;
    nodes.history.prepend(row);
    while (nodes.history.children.length > 100) {
      nodes.history.children[nodes.history.children.length - 1].remove();
    }
  }
  function clearHistory() {
    while (nodes.history.children.length) nodes.history.children[0].remove();
    seen.clear();
  }
  function commitCycle() {
    let added = 0;
    // The server pages newest first. Prepend oldest first so the latest 100
    // stay visible, while the older-page cursor is used only within a cycle.
    for (const event of cycleEvents.slice().reverse()) {
      if (seen.has(event.id)) continue;
      seen.add(event.id);
      const summary = eventSummary(event);
      appendRow(summary, event.id);
      nodes.current.textContent = "요청 " + hash + " · " + summary;
      added++;
    }
    if (cycleHeadId) headId = cycleHeadId;
    cursor = null;
    cycleHeadId = null;
    cycleEvents = [];
    return added;
  }
  async function poll() {
    if (!canRead() || inFlight) return;
    if (!cursor) { cycleHeadId = null; cycleEvents = []; }
    const token = ++sequence;
    const controller = new AbortController();
    const params = new URLSearchParams({ limit: 50, [hashField]: hash });
    if (cursor) params.set("cursor", cursor);
    const watchdog = setTimeout(() => controller.abort(), 3000);
    inFlight = { token, controller, watchdog };
    let nextDelay = null;
    try {
      const response = await fetch(pagePath + "?" + params.toString(), {
        credentials: "same-origin", cache: "no-store", signal: controller.signal
      });
      if (token !== sequence) return;
      if (response.status === 410) {
        cursor = null;
        headId = null;
        cycleHeadId = null;
        cycleEvents = [];
        appendRow("일부 기록 유실(링버퍼 밀림)");
        status("일부 기록 유실(링버퍼 밀림)");
        nextDelay = 0;
      } else if (response.status === 400) {
        stopReason = "invalid_event_page";
        status("요청 식별자 오류");
      } else if (response.status === 401 || response.status === 403) {
        stopReason = "forbidden";
        status("진단 읽기 권한 없음");
      } else if (response.status !== 200) {
        throw new Error("diagnostics_unavailable");
      } else {
        const page = await response.json();
        if (token !== sequence) return;
        if (!page || !Array.isArray(page.items) || page.items.length > 50) throw new Error("invalid_event_page");
        const items = page.items.filter(event => event && typeof event.id === "string" && event.id);
        if (!cursor && items.length) cycleHeadId = items[0].id;
        let caughtUp = false;
        for (const event of items) {
          if (headId && event.id === headId) { caughtUp = true; break; }
          if (!seen.has(event.id) && !cycleEvents.some(queued => queued.id === event.id)) cycleEvents.push(event);
        }
        failures = 0;
        if (caughtUp || page.hasMore !== true) {
          const gap = Boolean(headId && !caughtUp && items.length);
          if (gap) appendRow("일부 기록 유실(링버퍼 밀림)");
          const added = commitCycle();
          status(gap ? "일부 기록 유실(링버퍼 밀림)"
            : added ? "진단 " + added + "건"
              : nodes.history.children.length ? "새 기록 없음" : "아직 기록 없음");
          nextDelay = completedAt === null ? 1500 : 10000;
        } else {
          if (typeof page.nextCursor !== "string" || !page.nextCursor) throw new Error("invalid_event_page");
          cursor = page.nextCursor;
          status("진단 확인 중");
          nextDelay = 0;
        }
      }
    } catch (_) {
      if (token !== sequence) return;
      failures++;
      const base = Math.min(30000, 2000 * (2 ** (failures - 1)));
      nextDelay = Math.round(base * (0.8 + Math.random() * 0.4));
      status("진단 읽기 지연");
    } finally {
      clearTimeout(watchdog);
      if (inFlight?.token === token) {
        inFlight = null;
        if (nextDelay !== null) schedule(nextDelay);
      }
    }
  }
  function setRequest(detail = {}) {
    if (!nodes) return;
    if (detail.complete === true && detail.requestIdHash == null && detail.traceIdHash == null) {
      completedAt = Date.now();
      if (hash && !inFlight) schedule(0);
      return;
    }
    const field = detail.requestIdHash != null ? "requestIdHash" : "traceIdHash";
    const candidate = detail[field];
    if (candidate === hash && field === hashField) {
      if (detail.complete === true) {
        completedAt = Date.now();
        if (!inFlight) schedule(0);
      }
      return;
    }
    retire();
    hash = null;
    hashField = null;
    cursor = null;
    headId = null;
    cycleHeadId = null;
    cycleEvents = [];
    clearHistory();
    completedAt = detail.complete === true ? Date.now() : null;
    failures = 0;
    if (stopReason !== "forbidden") stopReason = null;
    if (!exactHash.test(candidate || "")) {
      nodes.current.textContent = "";
      status(candidate ? "관찰 불가(요청 hash 형식 불일치)" : "요청 식별자 미수신");
      return;
    }
    hash = candidate;
    hashField = field;
    nodes.current.textContent = "요청 " + hash;
    if (!nodes.readAllowed || stopReason === "forbidden") {
      status("진단 읽기 권한 없음(관리자 로그인 시 표시)");
      return;
    }
    status("아직 기록 없음");
    schedule(0);
  }
  function pause() {
    paused = true;
    retire();
    cursor = null;
    cycleHeadId = null;
    cycleEvents = [];
    if (nodes?.readAllowed) status("진단 일시정지");
  }
  function resume() {
    paused = false;
    if (!nodes) return;
    if (!nodes.readAllowed || stopReason === "forbidden") status("진단 읽기 권한 없음(관리자 로그인 시 표시)");
    else if (!hash) status("요청 식별자 미수신");
    else schedule(0);
  }
  function onVisibility() { if (document.hidden) pause(); else resume(); }
  function onTurn(event) { setRequest(event.detail || {}); }
  function onToggle() {
    nodes.body.hidden = !nodes.body.hidden;
    nodes.toggle.setAttribute("aria-expanded", String(!nodes.body.hidden));
  }
  function dispose() {
    retire();
    if (!nodes) return;
    nodes.toggle.removeEventListener("click", onToggle);
    document.removeEventListener("visibilitychange", onVisibility);
    document.removeEventListener("awx:trace-turn", onTurn);
    nodes = null;
  }
  function attach(root = document.querySelector("[data-trace-dock]")) {
    if (!root) return false;
    if (nodes) dispose();
    const toggle = root.querySelector("[data-testid=trace-dock-toggle]");
    const body = root.querySelector("#traceDockBody");
    const current = root.querySelector("[data-testid=trace-dock-current]");
    const history = root.querySelector("[data-testid=trace-dock-history]");
    const statusNode = root.querySelector("[data-testid=trace-dock-status]");
    if (!toggle || !body || !current || !history || !statusNode) return false;
    nodes = { root, toggle, body, current, history, status: statusNode,
      readAllowed: root.getAttribute("data-diagnostics-read") === "true" };
    toggle.addEventListener("click", onToggle);
    document.addEventListener("visibilitychange", onVisibility);
    document.addEventListener("awx:trace-turn", onTurn);
    status(nodes.readAllowed ? "요청 식별자 미수신" : "진단 읽기 권한 없음(관리자 로그인 시 표시)");
    return true;
  }
  window.AwxTraceDock = { attach, setRequest, pause, resume, dispose };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", () => attach(), { once: true });
  else attach();
})();
