/* Per-answer operator trace. The server remains the authority for full HTML. */
(function () {
  "use strict";

  const MAX_HTML = 60000;
  const MAX_SNAPSHOT_FETCHES = 2;
  const MAX_SCORE_EVENTS = 32;
  const SNAPSHOT_FETCH_TIMEOUT_MS = 12000;
  const SNAPSHOT_ID = /^[A-Za-z0-9_.:-]{1,160}$/;
  const FIELD_KEY = /^[A-Za-z0-9_.:-]{1,80}$/;
  const FIELD_VALUE = /^[\x20-\x7E]{0,160}$/;
  const TAGS = ["details", "summary", "div", "span", "p", "strong", "em", "small", "code",
    "pre", "table", "thead", "tbody", "tr", "th", "td", "ul", "ol", "li", "section",
    "h3", "h4", "dl", "dt", "dd", "br", "b"];
  const byAssistant = new WeakMap();
  const byPanel = new WeakMap();
  let activeSnapshotFetches = 0;

  function enabled() {
    return Boolean(document.querySelector("[data-admin-diagnostics]") &&
      document.querySelector("[data-chat-trace-toggle]")?.checked);
  }

  function withDebugQuery(path) {
    const url = String(path || "");
    if (!enabled() || /(?:[?&])debug=/.test(url)) return url;
    return url + (url.includes("?") ? "&" : "?") + "debug=true";
  }

  function status(state, message) {
    const note = document.createElement("p");
    note.className = "awx-trace-status";
    note.textContent = message;
    state.body.replaceChildren(note);
  }

  function abortSnapshot(state) {
    state.version += 1;
    if (state.controller) state.controller.abort();
    state.controller = null;
    state.loading = false;
  }

  function ensurePanel(assistant) {
    if (!assistant || !assistant.parentElement) return null;
    const existing = byAssistant.get(assistant);
    if (existing && existing.panel.isConnected) return existing;
    const panel = document.createElement("details");
    panel.className = "message-trace awx-trace-panel";
    panel.dataset.role = "trace";
    panel.dataset.liveRegion = "excluded";
    panel.setAttribute("aria-live", "off");
    const summary = document.createElement("summary");
    summary.textContent = "이 답변 추적";
    const metadata = document.createElement("div");
    metadata.className = "awx-trace-meta";
    const signals = document.createElement("div");
    signals.className = "awx-trace-signals";
    metadata.appendChild(signals);
    const body = document.createElement("div");
    body.className = "awx-trace-body";
    panel.append(summary, metadata, body);
    // A details element directly in the transcript grid can grow beyond its
    // allocated row after opening. The block slot gives that row its real height.
    const slot = document.createElement("div");
    slot.className = "awx-trace-slot";
    const overview = document.createElement("p");
    overview.className = "awx-trace-overview";
    overview.setAttribute("aria-live", "off");
    slot.append(panel, overview);
    if (typeof assistant.after === "function") assistant.after(slot);
    else assistant.parentElement.insertBefore(slot, assistant.nextSibling);
    const state = { assistant, slot, panel, overview, metadata, signals, body, snapshotId: null,
      diagnosticNodes: new Map(), scoreKeys: [], summaryFields: {},
      controller: null, loading: false, loaded: false, version: 0, live: false };
    byAssistant.set(assistant, state);
    byPanel.set(panel, state);
    panel.addEventListener("toggle", () => {
      if (panel.open && state.snapshotId) loadSnapshot(state);
    });
    showOverview(state, {});
    return state;
  }

  function showOverview(state, fields) {
    const summary = state.summaryFields;
    if (typeof fields?.observedModel === "string" &&
      /^[A-Za-z0-9][A-Za-z0-9._/:+\-]{0,199}$/.test(fields.observedModel)
      && !/^(sk-|AIza|eyJ)/.test(fields.observedModel)) summary.observedModel = fields.observedModel;
    const count = String(fields?.['prompt.citableEvidenceCount'] ?? "");
    if (["string", "number"].includes(typeof fields?.['prompt.citableEvidenceCount']) &&
        /^(0|[1-9][0-9]{0,6})$/.test(count) && Number(count) <= 1000000)
      summary['prompt.citableEvidenceCount'] = count;
    if (["NORMAL", "STRIKE", "BYPASS", "COMPRESSION"].includes(fields?.['orch.mode']))
      summary['orch.mode'] = fields['orch.mode'];
    if (typeof fields?.observedProvider === "string" &&
        /^[A-Za-z0-9_.:-]{1,80}$/.test(fields.observedProvider) &&
        !/^(sk-|AIza|eyJ)/.test(fields.observedProvider)) summary.observedProvider = fields.observedProvider;
    const model = summary.observedModel ?? "NOT_OBSERVED";
    const provider = summary.observedProvider ?? "NOT_OBSERVED";
    const evidence = summary['prompt.citableEvidenceCount'] ?? "NOT_OBSERVED";
    const mode = summary['orch.mode'] ?? "NOT_OBSERVED";
    // The aggregate search signal cannot identify either provider's outcome.
    state.overview.textContent = "이 답변 추적 · 모델: " + model +
      " · 응답 제공자: " + provider + " · Brave: NOT_OBSERVED · Naver: NOT_OBSERVED" +
      " · 인용 가능한 근거: " + evidence + " · 모드: " + mode;
    state.slot.hidden = !enabled();
    state.summaryAvailable = true;
  }

  function upsertSummary(assistant, fields) {
    if (!enabled()) return null;
    const state = ensurePanel(assistant);
    if (!state) return null;
    state.live = true;
    state.panel.hidden = false;
    showOverview(state, fields || {});
    return state.panel;
  }

  function sanitizedTrace(html, snapshot) {
    if (!window.DOMPurify || typeof window.DOMPurify.sanitize !== "function") return null;
    const source = String(html || "");
    if (!source || source.length > MAX_HTML) return null;
    const fragment = window.DOMPurify.sanitize(source, {
      RETURN_DOM_FRAGMENT: true,
      ALLOWED_TAGS: TAGS,
      ALLOWED_ATTR: ["class", "colspan", "rowspan"],
      FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "form", "input",
        "img", "svg", "math", "link", "meta", "audio", "video", "source"],
      FORBID_ATTR: ["id", "name", "style", "href", "src", "srcset"],
      ALLOW_DATA_ATTR: false,
      ALLOW_ARIA_ATTR: false
    });
    if (!fragment || fragment.nodeType !== 11) return null;
    for (const node of fragment.querySelectorAll("[class]")) {
      const classes = Array.from(node.classList).filter(value =>
        /^(?:search-trace|trace-[a-z0-9-]+|small|text-muted)$/.test(value));
      node.className = classes.join(" ");
    }
    boundTraceMarkup(fragment);
    if (!snapshot) return fragment;
    // A stored snapshot can be a whole HTML document. Only its sanitized trace
    // panel crosses into the live page; head/style/script never does.
    const details = fragment.querySelector("details.search-trace");
    if (details) return details;
    // Older metadata-only snapshots have a section root. Accept only the two
    // producer shapes, then wrap the sanitized section as a summary, not search detail.
    const markers = Array.from(source.matchAll(/<section data-trace="(trace-memory|chat-harmony)" data-kind="metadata-only">/g));
    if (markers.length !== 1) return null;
    const metadata = markers[0];
    const bare = source.trim().startsWith(metadata[0]) && source.trim().endsWith("</section>");
    const wrapped = source.startsWith('<!doctype html><html><head><meta charset="utf-8"/>') &&
      source.includes("<title>Trace Snapshot ") &&
      source.includes("</head><body><h2>Trace Snapshot</h2><table>") &&
      source.endsWith("</body></html>");
    if (!bare && !wrapped) return null;
    const sections = fragment.querySelectorAll("section");
    if (sections.length !== 1) return null;
    const section = sections[0];
    const heading = section.querySelectorAll("h3");
    const lists = section.querySelectorAll("dl");
    const expectedHeading = metadata[1] === "trace-memory"
      ? "Trace Memory Checkpoint" : "Chat Harmony Trace";
    const maxLists = metadata[1] === "trace-memory" ? 1 : 3;
    if (heading.length !== 1 || heading[0].textContent.trim() !== expectedHeading ||
        lists.length < 1 || lists.length > maxLists ||
        lists[0].querySelectorAll("dt").length === 0) return null;
    const summaryOnly = document.createElement("details");
    summaryOnly.className = "search-trace trace-metadata-only";
    const summary = document.createElement("summary");
    summary.textContent = "진단 요약만 저장됨";
    summaryOnly.append(summary, section);
    return summaryOnly;
  }

  function expectedSnapshotResponse(response, path) {
    if (response.redirected || !response.url) return false;
    const contentType = response.headers?.get?.("content-type") || "";
    if (!/^text\/html(?:\s*;|$)/i.test(contentType)) return false;
    try {
      const expected = new URL(path, window.location.href);
      const actual = new URL(response.url, window.location.href);
      return actual.origin === expected.origin && actual.pathname === expected.pathname &&
        actual.search === expected.search && !actual.hash;
    } catch (_) {
      return false;
    }
  }

  function expectedSnapshotMarkup(html) {
    const source = String(html || "").trim();
    if (source.startsWith('<!doctype html><html data-trace-redacted="1">')) return true;
    if (/^<details data-trace-redacted="1" class="search-trace(?:\s|\")/.test(source)) return true;
    const metadata = /<section data-trace="(?:trace-memory|chat-harmony)" data-kind="metadata-only">/.test(source);
    return metadata && (source.startsWith('<section data-trace="') ||
      source.startsWith('<!doctype html><html><head><meta charset="utf-8"/>'));
  }

  function boundTraceMarkup(fragment) {
    const holder = document.createElement("div");
    holder.appendChild(fragment);
    const tables = [];
    for (const table of holder.querySelectorAll("table")) {
      // Layout rows hold later diagnostic groups; nested data rows never spend
      // their parent's budget. Header rows are not data.
      if (table.classList.contains("trace-kv") || table.querySelector("table")) continue;
      const rows = Array.from(table.querySelectorAll("tr")).filter(row =>
        row.closest("table") === table && row.parentElement.tagName !== "THEAD" &&
        Array.from(row.cells).some(cell => cell.tagName === "TD"));
      const total = rows.length;
      for (const row of rows.splice(100)) row.remove();
      const note = document.createElement("p");
      note.className = "trace-row-limit small text-muted";
      table.after(note);
      const update = () => {
        note.textContent = "total=" + total + " displayed=" + rows.length +
          " omitted=" + (total - rows.length);
      };
      update();
      tables.push({ rows, update });
    }
    // Budget sanitized, complete DOM nodes rather than cutting source markup
    // inside a tag, character, or later terminal/failure group.
    const budget = MAX_HTML - 256;
    let size = holder.innerHTML.length;
    const omitted = size > budget;
    if (omitted) {
      for (const entry of tables) {
        while (size > budget && entry.rows.length) {
          const row = entry.rows.pop();
          size -= row.outerHTML.length;
          row.remove();
        }
        entry.update();
      }
      size = holder.innerHTML.length;
      const walker = document.createTreeWalker(holder, NodeFilter.SHOW_TEXT);
      const texts = [];
      while (walker.nextNode()) {
        if (walker.currentNode.length > 256) texts.push(walker.currentNode);
      }
      texts.sort((a, b) => b.length - a.length);
      for (const node of texts) {
        if (size <= budget) break;
        const keep = Math.max(128, node.length - (size - budget) - 128);
        // Never leave a dangling UTF-16 high surrogate at the text boundary.
        node.data = node.data.slice(0, keep).replace(/[\uD800-\uDBFF]$/, "") + "…";
        size = holder.innerHTML.length;
      }
      if (size > budget) {
        // Pathological markup alone can exhaust the budget. Fail explicitly,
        // never present an arbitrary partial document as a complete trace.
        holder.replaceChildren();
      }
      const note = document.createElement("p");
      note.className = "trace-size-limit small text-muted";
      note.textContent = "표시 크기 제한으로 세부 항목 일부 생략됨";
      const root = holder.querySelector("details.search-trace, section");
      (root || holder).appendChild(note);
    }
    fragment.append(...holder.childNodes);
  }

  function wireTraceSteps(safe) {
    for (const panel of safe.querySelectorAll("details.trace-steps-panel")) {
      const table = panel.querySelector("table.trace-steps-table");
      const tbody = table?.querySelector("tbody");
      const controls = panel.querySelector(".trace-steps-controls");
      if (!tbody || !controls) continue;
      const rows = Array.from(tbody.querySelectorAll("tr"));
      const entries = rows.map((row, order) => {
        const cells = Array.from(row.querySelectorAll("td"));
        if (cells.length !== 5 && cells.length !== 6) return null;
        const values = cells.map(cell => cell.textContent.trim());
        const numeric = value => /^\d+(?:\.\d+)?$/.test(value) ? Number(value) : null;
        const duration = /^(\d+(?:[.,]\d+)?)(ms|s)$/.exec(values.at(-1));
        return { row, order, query: values[1].toLowerCase(),
          idx: numeric(values[0]), qlen: cells.length === 6 ? numeric(values[2]) : null,
          returned: numeric(values.at(-3)), kept: numeric(values.at(-2)),
          took: duration ? Number(duration[1].replace(",", ".")) * (duration[2] === "s" ? 1000 : 1) : null };
      }).filter(Boolean);
      if (!entries.length) continue;
      controls.replaceChildren();
      const checkbox = label => {
        const wrapper = document.createElement("label");
        const input = document.createElement("input");
        input.type = "checkbox";
        const text = document.createElement("span");
        text.textContent = label;
        wrapper.append(input, text);
        controls.appendChild(wrapper);
        return input;
      };
      const nonOk = checkbox("non-ok");
      const slow = checkbox("slow (1000ms 이상)");
      const query = document.createElement("input");
      query.type = "search";
      query.setAttribute("aria-label", "표시된 검색어 필터");
      controls.appendChild(query);
      const sort = document.createElement("select");
      sort.setAttribute("aria-label", "검색 단계 정렬 항목");
      for (const key of ["idx", "query", ...(entries.some(entry => entry.qlen != null) ? ["qlen"] : []),
        "returned", "kept", "took"]) {
        const option = document.createElement("option");
        option.value = key;
        option.textContent = key === "idx" ? "#" : key;
        sort.appendChild(option);
      }
      sort.value = "idx";
      controls.appendChild(sort);
      const direction = document.createElement("select");
      direction.setAttribute("aria-label", "검색 단계 정렬 방향");
      for (const [value, label] of [["asc", "오름차순"], ["desc", "내림차순"]]) {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = label;
        direction.appendChild(option);
      }
      direction.value = "asc";
      controls.appendChild(direction);
      const apply = () => {
        const term = String(query.value || "").trim().toLowerCase();
        for (const entry of entries) {
          entry.row.hidden = Boolean((term && !entry.query.includes(term)) ||
            (nonOk.checked && !(entry.returned === 0 || entry.kept === 0)) ||
            (slow.checked && !(entry.took != null && entry.took >= 1000)));
        }
        for (const row of rows) if (!entries.some(entry => entry.row === row)) {
          row.hidden = Boolean(term || nonOk.checked || slow.checked);
        }
      };
      const reorder = () => {
        const key = sort.value;
        const descending = direction.value === "desc";
        entries.sort((a, b) => {
          const left = a[key], right = b[key];
          if (left == null) return right == null ? a.order - b.order : 1;
          if (right == null) return -1;
          const order = key === "query" ? left.localeCompare(right) : left - right;
          return (descending ? -order : order) || a.order - b.order;
        });
        for (const entry of entries) tbody.appendChild(entry.row);
        for (const row of rows) if (!entries.some(entry => entry.row === row)) tbody.appendChild(row);
      };
      query.addEventListener("input", apply);
      nonOk.addEventListener("change", apply);
      slow.addEventListener("change", apply);
      sort.addEventListener("change", reorder);
      direction.addEventListener("change", reorder);
      apply();
    }
  }

  function wirePipelineEvents(safe) {
    for (const table of safe.querySelectorAll("table.trace-pipeline-events-table")) {
      const tbody = table.querySelector("tbody");
      if (!tbody || !table.parentElement) continue;
      const rows = Array.from(tbody.querySelectorAll("tr"));
      if (!rows.length || rows.length > 200) continue;
      const entries = rows.map((row, order) => {
        const cells = Array.from(row.querySelectorAll("td"));
        if (cells.length !== 8) return null;
        const values = cells.map(cell => cell.textContent.trim());
        const count = key => {
          const match = new RegExp("(?:^|\\s)" + key + "=(\\d+)(?:\\s|$)").exec(values[5]);
          return match ? Number(match[1]) : null;
        };
        return { row, order, idx: /^\d+$/.test(values[0]) ? Number(values[0]) : null,
          stage: values[2], status: values[4].toLowerCase(),
          returned: count("returned"), kept: count("afterFilter"), took: count("ms") };
      }).filter(Boolean);
      if (!entries.length) continue;
      const controls = document.createElement("div");
      controls.className = "trace-pipeline-controls";
      const checkbox = label => {
        const wrapper = document.createElement("label");
        const input = document.createElement("input");
        input.type = "checkbox";
        const caption = document.createElement("span");
        caption.textContent = label;
        wrapper.append(input, caption);
        controls.appendChild(wrapper);
        return input;
      };
      const nonOk = checkbox("non-ok");
      const slow = checkbox("slow (1000ms 이상)");
      const stage = document.createElement("select");
      stage.setAttribute("aria-label", "RAG 단계 필터");
      const stageValues = ["", ...new Set(entries.map(entry => entry.stage)
        .filter(value => /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(value)))].slice(0, 33);
      for (const value of stageValues) {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = value || "모든 단계";
        stage.appendChild(option);
      }
      stage.value = "";
      controls.appendChild(stage);
      const sort = document.createElement("select");
      sort.setAttribute("aria-label", "RAG 이벤트 정렬 항목");
      for (const [key, label] of [["idx", "#"], ["returned", "returned"],
        ["kept", "afterFilter"], ["took", "ms"]]) {
        const option = document.createElement("option");
        option.value = key;
        option.textContent = label;
        sort.appendChild(option);
      }
      sort.value = "idx";
      controls.appendChild(sort);
      const direction = document.createElement("select");
      direction.setAttribute("aria-label", "RAG 이벤트 정렬 방향");
      for (const [value, label] of [["asc", "오름차순"], ["desc", "내림차순"]]) {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = label;
        direction.appendChild(option);
      }
      direction.value = "asc";
      controls.appendChild(direction);
      table.parentElement.insertBefore(controls, table);
      const apply = () => {
        for (const entry of entries) {
          entry.row.hidden = Boolean((stage.value && entry.stage !== stage.value) ||
            (nonOk.checked && ["ok", "success", "done", "completed", "executed"].includes(entry.status)) ||
            (slow.checked && !(entry.took != null && entry.took >= 1000)));
        }
        for (const row of rows) if (!entries.some(entry => entry.row === row)) {
          row.hidden = Boolean(stage.value || nonOk.checked || slow.checked);
        }
      };
      const reorder = () => {
        const key = sort.value;
        const descending = direction.value === "desc";
        entries.sort((a, b) => {
          const left = a[key], right = b[key];
          if (left == null) return right == null ? a.order - b.order : 1;
          if (right == null) return -1;
          const order = left - right;
          return (descending ? -order : order) || a.order - b.order;
        });
        for (const entry of entries) tbody.appendChild(entry.row);
        for (const row of rows) if (!entries.some(entry => entry.row === row)) tbody.appendChild(row);
      };
      nonOk.addEventListener("change", apply);
      slow.addEventListener("change", apply);
      stage.addEventListener("change", apply);
      sort.addEventListener("change", reorder);
      direction.addEventListener("change", reorder);
      apply();
    }
  }

  function showHtml(state, html, snapshot) {
    try {
      const safe = sanitizedTrace(html, snapshot);
      if (!safe) {
        status(state, String(html || "").length > MAX_HTML
          ? "트레이스 크기 제한: 60,000자 초과. 잘린 상세 대신 저장된 요약을 확인하세요."
          : "트레이스 표시 불가");
        return false;
      }
      wireTraceSteps(safe);
      wirePipelineEvents(safe);
      state.body.replaceChildren(safe);
      return true;
    } catch (_) {
      status(state, "트레이스 표시 불가");
      return false;
    }
  }

  function upsert(assistant, view) {
    if (!enabled()) return null;
    const state = ensurePanel(assistant);
    if (!state) return null;
    state.live = true;
    state.panel.hidden = false;
    state.slot.hidden = false;
    showHtml(state, view?.html, false);
    return state.panel;
  }

  function upsertDiagnostic(assistant, kind, detail, eventKey) {
    if (!enabled() || !detail || (kind !== "trace" && kind !== "score")) return null;
    const state = ensurePanel(assistant);
    if (!state) return null;
    const key = kind === "trace" ? "trace" : "score:" + String(eventKey || "").slice(0, 512);
    const previous = state.diagnosticNodes.get(key);
    if (previous) previous.remove();
    if (kind === "score" && !previous) {
      state.scoreKeys.push(key);
      if (state.scoreKeys.length > MAX_SCORE_EVENTS) {
        const oldest = state.scoreKeys.shift();
        state.diagnosticNodes.get(oldest)?.remove();
        state.diagnosticNodes.delete(oldest);
      }
    }
    state.signals.appendChild(detail);
    state.diagnosticNodes.set(key, detail);
    state.live = true;
    state.panel.hidden = false;
    state.slot.hidden = false;
    return detail;
  }

  function restore(assistant, turnTrace) {
    const snapshotId = typeof turnTrace?.snapshotId === "string" ? turnTrace.snapshotId : "";
    if (!SNAPSHOT_ID.test(snapshotId)) return null;
    const fields = turnTrace.fields && typeof turnTrace.fields === "object" ? turnTrace.fields : {};
    const sessionId = fields.sessionId;
    if (sessionId !== undefined && (typeof sessionId !== "string" || !/^[1-9][0-9]{0,15}$/.test(sessionId)
        || !Number.isSafeInteger(Number(sessionId)))) return null;
    const state = ensurePanel(assistant);
    if (!state) return null;
    state.panel.hidden = !enabled();
    if (state.snapshotId !== snapshotId || state.sessionId !== sessionId) {
      // First binding belongs to this assistant; a later identity change must
      // never carry observations from a previous answer or session.
      if (state.snapshotId !== null) state.summaryFields = {};
      abortSnapshot(state);
      state.snapshotId = snapshotId;
      state.sessionId = sessionId;
      state.loaded = false;
      delete state.panel.dataset.traceStorage;
      delete state.panel.dataset.traceError;
    }
    state.panel.dataset.traceSnapshotId = snapshotId;
    state.metadata.replaceChildren();
    const list = document.createElement("dl");
    for (const key of Object.keys(fields).slice(0, 16)) {
      const value = fields[key];
      if (!FIELD_KEY.test(key) || typeof value !== "string" || !FIELD_VALUE.test(value)) continue;
      const term = document.createElement("dt");
      term.textContent = key;
      const description = document.createElement("dd");
      description.textContent = value;
      list.append(term, description);
    }
    state.metadata.replaceChildren(list, state.signals);
    showOverview(state, fields);
    if (sessionId) {
      const download = document.createElement("a");
      download.className = "awx-trace-download";
      download.textContent = "이 답변 진단 내려받기";
      download.href = "/api/chat/sessions/" + sessionId + "/traces/" +
        encodeURIComponent(snapshotId) + "/html?format=bundle";
      download.download = "answer-trace-bundle.zip";
      state.metadata.appendChild(download);
    }
    if (!state.live && !state.loaded) status(state, "저장된 요약입니다. 상세는 펼칠 때 조회합니다.");
    if (state.panel.open) loadSnapshot(state);
    return state.panel;
  }

  async function loadSnapshot(state) {
    if (!enabled() || !state.snapshotId || state.loaded || state.loading || !state.panel.isConnected) return;
    if (activeSnapshotFetches >= MAX_SNAPSHOT_FETCHES) {
      status(state, "상세 조회가 진행 중입니다. 잠시 후 다시 펼쳐 주세요.");
      return;
    }
    const version = ++state.version;
    const snapshotId = state.snapshotId;
    const controller = new AbortController();
    state.controller = controller;
    state.loading = true;
    activeSnapshotFetches += 1;
    status(state, "상세 트레이스를 조회하는 중입니다.");
    let timeoutId;
    let timedOut = false;
    const timeout = new Promise((_, reject) => {
      timeoutId = setTimeout(() => {
        timedOut = true;
        controller.abort();
        reject(new Error("snapshot-fetch-timeout"));
      }, SNAPSHOT_FETCH_TIMEOUT_MS);
    });
    try {
      const detailPath = state.sessionId
        ? "/api/chat/sessions/" + state.sessionId + "/traces/"
        : "/api/diagnostics/trace/snapshots/";
      const requestPath = detailPath + encodeURIComponent(snapshotId) + "/html";
      const response = await Promise.race([fetch(requestPath, {
        method: "GET", cache: "no-store", credentials: "same-origin", signal: controller.signal
      }), timeout]);
      if (version !== state.version || !state.panel.isConnected || !enabled()) return;
      if (!response.ok) {
        state.panel.dataset.traceError = response.status === 404 ? "missing_or_evicted"
          : response.status === 401 || response.status === 403 ? "unauthorized"
            : response.status >= 500 ? "server_error" : "unexpected_response";
        status(state, response.status === 404
          ? "상세 스냅샷을 현재 저장소에서 찾을 수 없음"
          : response.status === 401 || response.status === 403
            ? "상세 조회 권한 없음" : response.status >= 500
              ? "서버 오류로 상세 조회 불가" : "상세 조회 불가");
        return;
      }
      if (!expectedSnapshotResponse(response, requestPath)) {
        state.panel.dataset.traceError = "unexpected_response";
        status(state, "예상하지 못한 진단 응답으로 표시 불가");
        return;
      }
      const html = await Promise.race([response.text(), timeout]);
      if (version !== state.version || state.snapshotId !== snapshotId ||
          controller.signal.aborted || !state.panel.isConnected || !enabled()) return;
      if (!expectedSnapshotMarkup(html)) {
        state.panel.dataset.traceError = "unexpected_response";
        status(state, "예상하지 못한 진단 응답으로 표시 불가");
        return;
      }
      state.loaded = showHtml(state, html, true);
      if (state.loaded) {
        const header = response.headers?.get?.("x-trace-storage");
        const storage = header === "ring" || header === "durable_projection" ? header : "unknown";
        state.panel.dataset.traceStorage = storage;
        delete state.panel.dataset.traceError;
        const provenance = document.createElement("p");
        provenance.className = "awx-trace-status";
        provenance.textContent = storage === "ring" ? "현재 프로세스 메모리에 보존된 상세입니다."
          : storage === "durable_projection" ? "저장된 진단 요약입니다. 전체 로그는 보존되지 않았습니다."
            : "진단 출처는 확인되지 않았습니다.";
        state.body.appendChild(provenance);
      } else {
        state.panel.dataset.traceError = "unexpected_response";
      }
    } catch (_) {
      if (version === state.version && timedOut && enabled()) {
        state.panel.dataset.traceError = "timeout";
        status(state, "상세 조회 시간 초과. 다시 펼쳐 주세요.");
      } else if (version === state.version && !controller.signal.aborted) {
        state.panel.dataset.traceError = "network_error";
        status(state, "네트워크 오류로 상세 조회 불가");
      }
    } finally {
      clearTimeout(timeoutId);
      activeSnapshotFetches -= 1;
      if (state.controller === controller) state.controller = null;
      if (version === state.version) state.loading = false;
    }
  }

  function dispose(root) {
    if (!root || typeof root.querySelectorAll !== "function") return;
    for (const panel of root.querySelectorAll('[data-role="trace"]')) {
      const state = byPanel.get(panel);
      if (!state) continue;
      abortSnapshot(state);
      byAssistant.delete(state.assistant);
      byPanel.delete(panel);
    }
  }

  document.addEventListener("change", event => {
    if (!event.target?.matches?.("[data-chat-trace-toggle]")) return;
    for (const panel of document.querySelectorAll('[data-role="trace"]')) {
      const state = byPanel.get(panel);
      if (!state) continue;
      if (!enabled()) {
        abortSnapshot(state);
        state.loaded = false;
        state.live = false;
        state.signals.replaceChildren();
        state.diagnosticNodes.clear();
        state.scoreKeys.length = 0;
        state.body.replaceChildren();
        if (state.snapshotId) status(state, "저장된 요약입니다. 상세는 펼칠 때 조회합니다.");
        panel.hidden = true;
        panel.open = false;
        state.slot.hidden = true;
      } else {
        panel.hidden = !state.snapshotId && !state.live && !state.summaryAvailable;
        state.slot.hidden = panel.hidden;
        if (panel.open && state.snapshotId) loadSnapshot(state);
      }
    }
  });

  window.addEventListener?.("pagehide", () => dispose(document));
  window.AwxChatTraceUi = { enabled, withDebugQuery, upsert, upsertSummary, upsertDiagnostic, restore, dispose };
})();
