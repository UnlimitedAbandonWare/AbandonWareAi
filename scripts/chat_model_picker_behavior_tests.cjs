const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const { createPicker } = require("../main/resources/static/js/chat-model-picker.js");
const { installBridge } = require("../main/resources/static/js/chat-settings-bridge.js");

function element(tag) {
  const node = {
    tag,
    children: [],
    className: "",
    textContent: "",
    type: "",
    value: "",
    label: "",
    disabled: false,
    selected: false,
    open: false,
    parent: null,
    attrs: {},
    listeners: {},
    dataset: {}
  };
  node.classList = {
    contains(name) {
      return String(node.className || "").split(/\s+/).includes(name);
    }
  };
  node.setAttribute = (key, value) => {
    node.attrs[key] = String(value);
  };
  node.getAttribute = (key) => (Object.prototype.hasOwnProperty.call(node.attrs, key) ? node.attrs[key] : null);
  node.append = (...kids) => {
    for (const kid of kids) {
      kid.parent = node;
      node.children.push(kid);
    }
  };
  node.appendChild = (kid) => {
    node.append(kid);
    return kid;
  };
  node.prepend = (kid) => {
    kid.parent = node;
    node.children.unshift(kid);
  };
  node.replaceChildren = (...kids) => {
    node.children = [];
    if (kids.length) node.append(...kids);
  };
  node.addEventListener = (type, fn) => {
    (node.listeners[type] ||= []).push(fn);
  };
  node.dispatchEvent = (event) => {
    const ev = event || {};
    if (!ev.target) ev.target = node;
    for (const fn of node.listeners[ev.type] || []) fn(ev);
    return true;
  };
  node.focus = () => {
    node.owner.activeElement = node;
  };
  node.contains = (other) => {
    let current = other;
    while (current) {
      if (current === node) return true;
      current = current.parent;
    }
    return false;
  };
  node.querySelectorAll = (sel) => queryAll(node, sel);
  node.querySelector = (sel) => queryAll(node, sel)[0] || null;
  return node;
}

function matches(node, sel) {
  if (sel.startsWith("[") && sel.endsWith("]")) return Object.prototype.hasOwnProperty.call(node.attrs, sel.slice(1, -1));
  const dot = sel.indexOf(".");
  if (dot >= 0) {
    const tag = sel.slice(0, dot);
    const cls = sel.slice(dot + 1);
    return node.classList.contains(cls) && (!tag || node.tag === tag);
  }
  return node.tag === sel;
}

function queryAll(node, sel) {
  const out = [];
  for (const child of node.children || []) {
    if (matches(child, sel)) out.push(child);
    out.push(...queryAll(child, sel));
  }
  return out;
}

function walk(node, acc = []) {
  acc.push(node);
  for (const child of node.children || []) walk(child, acc);
  return acc;
}

function harness() {
  const doc = element("document");
  doc.owner = doc;
  doc.activeElement = null;
  doc.getElementById = (id) => walk(doc).find(node => node.attrs.id === id) || null;
  doc.createElement = (tag) => {
    const node = element(tag);
    node.owner = doc;
    return node;
  };
  const select = element("select");
  select.owner = doc;
  select.setAttribute("id", "modelSelect");
  Object.defineProperty(select, "value", {
    get() {
      const options = walk(select).filter(node => node.tag === "option");
      const selected = options.find(node => node.selected) || options.find(node => !node.disabled);
      return selected ? selected.value : "";
    },
    set(value) {
      for (const option of walk(select).filter(node => node.tag === "option")) {
        option.selected = option.value === value;
      }
    }
  });
  const trigger = element("button");
  trigger.owner = doc;
  trigger.setAttribute("id", "modelBrowserTrigger");
  const panel = element("dialog");
  panel.owner = doc;
  panel.tagName = "DIALOG";
  panel.setAttribute("id", "modelBrowser");
  panel.showModal = () => { panel.open = true; };
  panel.close = () => { panel.open = false; };
  const closer = element("button");
  closer.owner = doc;
  closer.setAttribute("data-model-close", "");
  const search = element("input");
  search.owner = doc;
  search.setAttribute("data-model-search", "");
  const filter = element("select");
  filter.owner = doc;
  filter.setAttribute("data-model-filter", "");
  filter.value = "available";
  const status = element("p");
  status.owner = doc;
  status.setAttribute("data-model-catalog-status", "");
  const count = element("p");
  count.owner = doc;
  count.setAttribute("data-model-count", "");
  const list = element("div");
  list.owner = doc;
  list.setAttribute("data-model-results", "");
  const more = element("button");
  more.owner = doc;
  more.hidden = true;
  more.setAttribute("data-model-more", "");
  const refresh = element("button");
  refresh.owner = doc;
  refresh.setAttribute("data-model-refresh", "");
  panel.append(closer, search, filter, status, count, list, more, refresh);
  doc.append(select, trigger, panel);
  const store = new Map();
  const storage = {
    getItem: (key) => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => store.set(key, String(value))
  };
  return { doc, select, panel, trigger, closer, search, filter, status, count, list, more, storage };
}

function row(index, extra = {}) {
  return {
    id: "id-" + index,
    modelId: "model-" + index,
    provider: index % 2 ? "ollama" : "public",
    selectable: true,
    status: "installed",
    release: "stable",
    ...extra
  };
}

function jsonResponse(rows) {
  return {
    ok: true,
    redirected: false,
    headers: { get: (name) => String(name).toLowerCase() === "content-type" ? "application/json" : null },
    json: async () => rows
  };
}

function choiceButtons(list) {
  return list.querySelectorAll("button.model-choice-select").filter(button => button.getAttribute("data-model-more") !== "1");
}

const firstSessionCandidates = fs.readFileSync(require.resolve("../main/resources/templates/chat-ui.html"), "utf8")
  .match(/data-first-session-model="([^"]+)"/)?.[1] || "first-session-candidate";
const firstSessionModel = firstSessionCandidates.split(",")[0].trim();
const chatSource = fs.readFileSync(require.resolve("../main/resources/static/js/chat.js"), "utf8");

function firstSessionHarness(settings = { source: "factory" }) {
  const ui = harness();
  ui.select.dataset.firstSessionModel = firstSessionModel;
  const option = ui.doc.createElement("option"); option.value = "id-2"; option.selected = true;
  ui.select.appendChild(option);
  const mode = ui.doc.createElement("select"); mode.setAttribute("id", "modelSelectionMode"); mode.value = "strict";
  ui.doc.append(mode);
  const values = new Map(settings ? [["chat.controlSettings", JSON.stringify(settings)]] : []);
  const session = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
  const context = vm.createContext({
    dom: { modelSelect: ui.select, modelSelectionMode: mode }, window: { sessionStorage: session },
    CONTROL_SETTINGS_STORAGE_KEY: "chat.controlSettings", controlHydrationPhase: "READY",
    ControlHydrationPhase: { READY: "READY" }, localControlOverrideActive: false,
    setStatusRailValue() {}, setCurrentModelBadge() {}, searchModeRailValue: value => value
  });
  vm.runInContext(chatSource.slice(chatSource.indexOf("function currentControlSettings("), chatSource.indexOf("function storedControlSettings("))
    + chatSource.slice(chatSource.indexOf("function syncControlStatus("), chatSource.indexOf("function syncSendButtonState(")), context);
  ui.select.addEventListener("change", context.handleControlChange);
  mode.addEventListener("change", context.handleControlChange);
  return { ...ui, mode, session, context };
}

test("first-session model applies once when only factory init settings exist", async () => {
  const ui = firstSessionHarness();
  const picker = createPicker(ui.doc, { fetch: async () => jsonResponse([
    row(1, { id: firstSessionModel }), row(2), row(3, { defaultChoice: true })
  ]), storage: ui.storage, sessionStorage: ui.session, autostart: false });
  await picker.refresh(false);
  assert.equal(ui.select.value, firstSessionModel);
  assert.equal(ui.mode.value, "preferred");
  const saved = JSON.parse(ui.session.getItem("chat.controlSettings"));
  assert.equal(saved.model, firstSessionModel);
  assert.equal(saved.modelSelectionMode, "preferred");
  assert.notEqual(saved.source, "user");
  assert.notEqual(saved.source, "factory");
  assert.equal(ui.context.localControlOverrideActive, false);
  await picker.refresh(false);
  assert.equal(ui.select.value, firstSessionModel);
});

test("first-session defaults to exact registered Luna and preserves fallback when unavailable", async () => {
  for (const lunaState of ["selectable", "disabled", "absent"]) {
    const ui = firstSessionHarness();
    ui.select.dataset.firstSessionModel = firstSessionCandidates;
    const rows = [row(1, { id: "chatgpt-oauth:gpt-5.6-sol" }), row(2),
      row(3, { defaultChoice: true }), row(4, { id: "gpt-5.6-luna" })];
    if (lunaState !== "absent") rows.push(row(5, {
      id: "chatgpt-oauth:gpt-5.6-luna", selectable: lunaState === "selectable"
    }));
    const picker = createPicker(ui.doc, { fetch: async () => jsonResponse(rows),
      storage: ui.storage, sessionStorage: ui.session, autostart: false });
    await picker.refresh(false);
    assert.equal(ui.select.value, lunaState === "selectable"
      ? "chatgpt-oauth:gpt-5.6-luna" : "chatgpt-oauth:gpt-5.6-sol", lunaState);
    assert.equal(ui.mode.value, "preferred");
    assert.equal(JSON.parse(ui.session.getItem("chat.controlSettings")).source, "catalog-default");
  }
});

test("first-session list picks the first selectable candidate", async () => {
  const ui = firstSessionHarness();
  ui.select.dataset.firstSessionModel = " chatgpt-oauth:gpt-5.6-sol , chatgpt-oauth:gpt-5.5 ";
  const picker = createPicker(ui.doc, { fetch: async () => jsonResponse([
    row(1, { id: "chatgpt-oauth:gpt-5.5" }), row(2), row(3, { defaultChoice: true }),
    row(4, { id: "chatgpt-oauth:gpt-5.6-sol" })
  ]), storage: ui.storage, sessionStorage: ui.session, autostart: false });
  await picker.refresh(false);
  assert.equal(ui.select.value, "chatgpt-oauth:gpt-5.6-sol");
  assert.equal(ui.mode.value, "preferred");
  const saved = JSON.parse(ui.session.getItem("chat.controlSettings"));
  assert.equal(saved.model, "chatgpt-oauth:gpt-5.6-sol");
  assert.equal(saved.modelSelectionMode, "preferred");
  assert.equal(saved.source, "catalog-default");
});

test("first-session list falls through to 5.5", async () => {
  for (const solPresent of [false, true]) {
    const ui = firstSessionHarness();
    ui.select.dataset.firstSessionModel = "chatgpt-oauth:gpt-5.6-sol,chatgpt-oauth:gpt-5.5";
    const rows = [row(1, { id: "chatgpt-oauth:gpt-5.5" }), row(2), row(3, { defaultChoice: true })];
    if (solPresent) rows.push(row(4, { id: "chatgpt-oauth:gpt-5.6-sol", selectable: false }));
    const picker = createPicker(ui.doc, { fetch: async () => jsonResponse(rows),
      storage: ui.storage, sessionStorage: ui.session, autostart: false });
    await picker.refresh(false);
    assert.equal(ui.select.value, "chatgpt-oauth:gpt-5.5", String(solPresent));
    assert.equal(ui.mode.value, "preferred");
    assert.equal(JSON.parse(ui.session.getItem("chat.controlSettings")).source, "catalog-default");
  }
});

test("first-session list with no candidate keeps server default", async () => {
  for (const serverDefault of [false, true]) {
    const ui = firstSessionHarness();
    ui.select.dataset.firstSessionModel = "chatgpt-oauth:gpt-5.6-sol,chatgpt-oauth:gpt-5.5";
    const picker = createPicker(ui.doc, { fetch: async () => jsonResponse([
      row(2), row(3, { defaultChoice: serverDefault }), row(4, { id: "gpt-5.6-sol" }),
      row(5, { id: "chatgpt-oauth:gpt-5.5-extra" })
    ]), storage: ui.storage, sessionStorage: ui.session, autostart: false });
    await picker.refresh(false);
    assert.equal(ui.select.value, serverDefault ? "id-3" : "id-2");
    assert.equal(ui.mode.value, serverDefault ? "auto" : "strict");
  }
});

test("first-session list survives factory preferences arriving before catalog", async () => {
  const ui = firstSessionHarness();
  ui.select.dataset.firstSessionModel = "chatgpt-oauth:gpt-5.6-sol,chatgpt-oauth:gpt-5.5";
  Object.defineProperty(ui.select, "options", { get: () => walk(ui.select).filter(node => node.tag === "option") });
  ui.mode.options = ["auto", "strict", "preferred"].map(value => ({ value }));
  const search = ui.doc.createElement("select"); search.setAttribute("id", "searchModeSelect"); search.options = [{ value: "OFF" }];
  const execution = ui.doc.createElement("select"); execution.setAttribute("id", "executionModeSelect");
  execution.options = [{ value: "AUTO" }];
  const rag = ui.doc.createElement("input"); rag.setAttribute("id", "useRagToggle");
  rag.type = "checkbox"; rag.checked = false;
  rag.addEventListener("change", ui.context.handleControlChange);
  ui.doc.append(search, execution, rag);
  const picker = createPicker(ui.doc, { fetch: async () => jsonResponse([
    row(1, { id: "chatgpt-oauth:gpt-5.5" }), row(2), row(3, { id: "chatgpt-oauth:gpt-5.6-sol" })
  ]), storage: ui.storage, sessionStorage: ui.session, autostart: false });
  installBridge({ document: ui.doc, sessionStorage: ui.session, localStorage: ui.storage,
    Event, CustomEvent, addEventListener() {}, fetch: async () => ({ ok: true, redirected: false,
      json: async () => ({ revision: 0, hash: null, defaultsVersion: "test", overrides: {},
        effective: { model: "chatgpt-oauth:gpt-5.5", modelSelectionMode: "preferred", useRag: true },
        factoryDefaults: {}, sources: { model: "FACTORY", modelSelectionMode: "FACTORY", useRag: "FACTORY" } }) }) });
  await new Promise(resolve => setImmediate(resolve));
  await picker.refresh(false);
  assert.equal(ui.select.value, "chatgpt-oauth:gpt-5.6-sol");
  assert.equal(ui.mode.value, "preferred");
  assert.equal(JSON.parse(ui.session.getItem("chat.controlSettings")).model, "chatgpt-oauth:gpt-5.6-sol");
  assert.equal(JSON.parse(ui.session.getItem("chat.controlSettings")).source, "catalog-default");
  assert.equal(rag.checked, true);
  assert.equal(ui.select.dataset.awxSettingsReady, "ready");
});

test("chat factory initialization marks only newly created settings", () => {
  const init = chatSource.slice(chatSource.indexOf("if (!restoreStoredControlSettings()) {"), chatSource.indexOf("setComposerBusy(false);", chatSource.indexOf("if (!restoreStoredControlSettings()) {")));
  for (const restored of [false, true]) {
    const calls = [];
    vm.runInNewContext(init, { restoreStoredControlSettings: () => restored,
      resetControlSettingsToDefaults() {}, applySmokeProofControlDefaults: () => false,
      syncControlStatus: options => calls.push(options) });
    assert.equal(calls.length, restored ? 0 : 1);
    if (!restored) assert.equal(calls[0].source, "factory");
  }
});

test("first-session model never overrides saved selections or existing sessions", async () => {
  for (const scenario of ["user", "", "missing-source", "malformed", "chat.currentSessionId", "chat.activeRun"]) {
    const ui = firstSessionHarness(["user", ""].includes(scenario) ? { source: scenario } : scenario === "missing-source" ? {} : { source: "factory" });
    if (scenario.startsWith("chat.")) ui.session.setItem(scenario, "present");
    if (scenario === "malformed") ui.session.setItem("chat.controlSettings", "present");
    const picker = createPicker(ui.doc, { fetch: async () => jsonResponse([row(1, { id: firstSessionModel, defaultChoice: true }), row(2)]),
      storage: ui.storage, sessionStorage: ui.session, autostart: false });
    await picker.refresh(false);
    assert.equal(ui.select.value, "id-2", scenario);
    assert.equal(ui.mode.value, "strict", scenario);
  }
});

test("first-session model skipped when missing or unselectable with existing default fallback", async () => {
  for (const present of [false, true]) for (const serverDefault of [false, true]) {
    const ui = firstSessionHarness();
    const rows = [row(2), row(3, { defaultChoice: serverDefault })];
    if (present) rows.push(row(1, { id: firstSessionModel, selectable: false }));
    const picker = createPicker(ui.doc, { fetch: async () => jsonResponse(rows), storage: ui.storage, sessionStorage: ui.session, autostart: false });
    await picker.refresh(false);
    assert.equal(ui.select.value, serverDefault ? "id-3" : "id-2");
    assert.equal(ui.mode.value, serverDefault ? "auto" : "strict");
  }
});

test("user change during catalog fetch wins", async () => {
  const ui = firstSessionHarness();
  const option = ui.doc.createElement("option"); option.value = "id-4"; ui.select.appendChild(option);
  let resolve;
  const picker = createPicker(ui.doc, { fetch: () => new Promise(done => { resolve = done; }), storage: ui.storage, sessionStorage: ui.session, autostart: false });
  const pending = picker.refresh(false);
  ui.select.value = "id-4"; ui.select.dispatchEvent({ type: "change" });
  resolve(jsonResponse([row(1, { id: firstSessionModel }), row(2), row(4)]));
  await pending;
  assert.equal(ui.select.value, "id-4");
  assert.equal(JSON.parse(ui.session.getItem("chat.controlSettings")).source, "user");
});

test("stored personal default blocks first-session model", async () => {
  const ui = firstSessionHarness();
  const oldCore = globalThis.AwxSettingsCore;
  globalThis.AwxSettingsCore = { readSettings: () => ({ model: "id-2" }) };
  try {
    const picker = createPicker(ui.doc, { fetch: async () => jsonResponse([row(1, { id: firstSessionModel }), row(2)]), storage: ui.storage, sessionStorage: ui.session, autostart: false });
    await picker.refresh(false);
    assert.equal(ui.select.value, "id-2");
  } finally { globalThis.AwxSettingsCore = oldCore; }
});

test("new chat keeps explicit selection through server preference reapplication", async () => {
  const ui = firstSessionHarness();
  const option = ui.doc.createElement("option"); option.value = "id-1"; ui.select.appendChild(option);
  Object.defineProperty(ui.select, "options", { get: () => walk(ui.select).filter(node => node.tag === "option") });
  ui.mode.options = ["auto", "strict", "preferred"].map(value => ({ value }));
  const search = ui.doc.createElement("select"); search.setAttribute("id", "searchModeSelect"); search.options = [{ value: "OFF" }];
  const rag = ui.doc.createElement("input"); rag.setAttribute("id", "useRagToggle");
  ui.doc.append(search, rag);
  installBridge({ document: ui.doc, sessionStorage: ui.session, localStorage: ui.storage,
    Event, CustomEvent, addEventListener() {}, fetch: async () => ({ ok: true, redirected: false,
      json: async () => ({ revision: 0, hash: null, defaultsVersion: "test", overrides: {},
        effective: { model: "id-1", modelSelectionMode: "auto" }, factoryDefaults: {}, sources: {} }) }) });
  ui.doc.dispatchEvent({ type: "chat:model-catalog", detail: { ready: true } });
  await new Promise(resolve => setImmediate(resolve));
  ui.select.value = "id-2"; ui.select.dispatchEvent({ type: "change" });
  ui.mode.value = "strict"; ui.mode.dispatchEvent({ type: "change" });
  ui.doc.dispatchEvent({ type: "brain-state:session", detail: {} });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ui.select.value, "id-2");
  assert.equal(ui.mode.value, "strict");
});

test("stored user search OFF and RAG false survive late server defaults", async () => {
  const stored = { model: "id-2", modelSelectionMode: "strict", executionMode: "AUTO",
    searchMode: "OFF", useRag: false, source: "user" };
  const ui = firstSessionHarness(stored);
  Object.defineProperty(ui.select, "options", { get: () => walk(ui.select).filter(node => node.tag === "option") });
  ui.mode.options = ["auto", "strict", "preferred"].map(value => ({ value }));
  ui.select.value = stored.model;
  const search = ui.doc.createElement("select"); search.setAttribute("id", "searchModeSelect");
  search.options = ["OFF", "AUTO"].map(value => ({ value })); search.value = "OFF";
  const execution = ui.doc.createElement("select"); execution.setAttribute("id", "executionModeSelect");
  execution.options = [{ value: "AUTO" }]; execution.value = "AUTO";
  const rag = ui.doc.createElement("input"); rag.setAttribute("id", "useRagToggle"); rag.type = "checkbox"; rag.checked = false;
  ui.doc.append(search, execution, rag);
  const picker = createPicker(ui.doc, { fetch: async () => jsonResponse([row(1), row(2)]),
    storage: ui.storage, sessionStorage: ui.session, autostart: false });
  await picker.refresh(false);
  installBridge({ document: ui.doc, sessionStorage: ui.session, localStorage: ui.storage,
    Event, CustomEvent, addEventListener() {}, fetch: async () => ({ ok: true, redirected: false,
      json: async () => ({ revision: 0, hash: null, defaultsVersion: "test", overrides: {},
        effective: { model: "id-1", modelSelectionMode: "preferred", executionMode: "AUTO", searchMode: "AUTO", useRag: true },
        factoryDefaults: {}, sources: {} }) }) });
  ui.doc.dispatchEvent({ type: "chat:model-catalog", detail: { ready: true } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ui.select.value, "id-2"); assert.equal(ui.mode.value, "strict");
  assert.equal(search.value, "OFF"); assert.equal(rag.checked, false);
  assert.equal(ui.select.dataset.awxSettingsReady, "ready");
});

test("server cloud default applies once to an untouched fresh chat", async () => {
  const ui=harness();
  const mode=ui.doc.createElement("select"); mode.setAttribute("id","modelSelectionMode"); mode.value="strict";
  ui.doc.append(mode);
  const picker=createPicker(ui.doc,{fetch:async()=>jsonResponse([row(1,{defaultChoice:true,provider:"gemini"}),row(2)]),storage:ui.storage,autostart:false});
  await picker.refresh(false);
  assert.equal(ui.select.value,"id-1");
  assert.equal(mode.value,"auto");
  ui.select.value="id-2"; ui.select.dispatchEvent({type:"change"});
  assert.equal(mode.value,"strict");
  await picker.refresh(false);
  assert.equal(ui.select.value,"id-2");
});

test("server default preserves restored controls and explicit mode", async () => {
  const ui=harness();
  const opt=ui.doc.createElement("option"); opt.value="id-2"; opt.selected=true; ui.select.appendChild(opt);
  const session={getItem:key=>key === "chat.controlSettings" ? "present" : null};
  const picker=createPicker(ui.doc,{fetch:async()=>jsonResponse([row(1,{defaultChoice:true,provider:"gemini"}),row(2)]),
    storage:ui.storage,sessionStorage:session,autostart:false});
  await picker.refresh(false); assert.equal(ui.select.value,"id-2");
});

test("shows 80 rows and expands the rest without a listbox role", async () => {
  const ui = harness();
  const rows = Array.from({ length: 100 }, (_, index) => row(index));
  const picker = createPicker(ui.doc, {
    fetch: async () => jsonResponse(rows),
    storage: ui.storage,
    autostart: false
  });
  await picker.refresh(false);
  assert.equal(choiceButtons(ui.list).length, 80);
  assert.equal(ui.more.hidden, false);
  assert.equal(ui.more.textContent, "모델 더 보기 · 검색 결과 100개 중 80개 표시");
  assert.equal(ui.count.textContent, "검색 결과 100개 중 80개 표시");
  assert.equal(ui.list.querySelector("[role]"), null);
  assert.match(ui.status.textContent, /100개 선택 가능/);
  assert.match(choiceButtons(ui.list)[0].querySelector("small").textContent, /설치됨 · 생성 미검증/);
  ui.doc.activeElement = ui.more;
  ui.more.dispatchEvent({ type: "click", target: ui.more });
  assert.equal(choiceButtons(ui.list).length, 100);
  assert.equal(ui.more.hidden, true);
  assert.equal(ui.doc.activeElement, choiceButtons(ui.list)[99]);
});

test("search still reaches a model past the first page", async () => {
  const ui = harness();
  const picker = createPicker(ui.doc, {
    fetch: async () => jsonResponse(Array.from({ length: 100 }, (_, index) => row(index))),
    storage: ui.storage,
    autostart: false
  });
  await picker.refresh(false);
  ui.search.value = "model-90";
  ui.search.dispatchEvent({ type: "input", target: ui.search });
  const shown = choiceButtons(ui.list);
  assert.equal(shown.length, 1);
  assert.equal(shown[0].getAttribute("data-model-id"), "id-90");
  assert.equal(ui.more.hidden, true);
});

test("a late older catalog response does not replace the newer list", async () => {
  const ui = harness();
  const pending = [];
  const picker = createPicker(ui.doc, {
    fetch: () => new Promise((resolve, reject) => pending.push({ resolve, reject })),
    storage: ui.storage,
    autostart: false
  });
  const older = picker.refresh(true);
  const newer = picker.refresh(false);
  pending[1].resolve(jsonResponse([row(1)]));
  await newer;
  pending[0].resolve(jsonResponse([row(2), row(3)]));
  await older;
  assert.deepEqual(choiceButtons(ui.list).map(button => button.getAttribute("data-model-id")), ["id-1"]);
  assert.match(ui.status.textContent, /1개 선택 가능/);
  const failed = picker.refresh(false);
  const current = picker.refresh(false);
  pending[3].resolve(jsonResponse([row(4)]));
  await current;
  pending[2].reject(new Error("late-catalog"));
  await failed;
  assert.deepEqual(choiceButtons(ui.list).map(button => button.getAttribute("data-model-id")), ["id-4"]);
  assert.match(ui.status.textContent, /1개 선택 가능/);
});

test("favorite rebuild keeps focus and Escape returns to the opener", async () => {
  const ui = harness();
  const picker = createPicker(ui.doc, {
    fetch: async () => jsonResponse([row(1), row(2)]),
    storage: ui.storage,
    autostart: false
  });
  await picker.refresh(false);
  const favorite = ui.list.querySelector("button.model-favorite");
  ui.doc.activeElement = favorite;
  favorite.dispatchEvent({ type: "click", target: favorite });
  assert.equal(ui.doc.activeElement.className, "model-favorite");
  assert.equal(ui.doc.activeElement.getAttribute("data-model-id"), "id-1");
  assert.equal(ui.doc.activeElement.getAttribute("aria-pressed"), "true");
  assert.equal(ui.doc.activeElement.textContent, "★");
  ui.trigger.dispatchEvent({ type: "click", target: ui.trigger });
  assert.equal(ui.panel.open, true);
  assert.equal(ui.doc.activeElement, ui.search);
  ui.doc.dispatchEvent({ type: "keydown", key: "Escape", target: ui.search });
  assert.equal(ui.panel.open, false);
  assert.equal(ui.doc.activeElement, ui.trigger);
  ui.trigger.dispatchEvent({ type: "click", target: ui.trigger });
  ui.closer.dispatchEvent({ type: "click", target: ui.closer });
  assert.equal(ui.panel.open, false);
  assert.equal(ui.doc.activeElement, ui.trigger);
});
