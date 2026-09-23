const test = require("node:test");
const assert = require("node:assert/strict");
const { createPicker } = require("../main/resources/static/js/chat-model-picker.js");

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
