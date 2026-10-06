// HTML attribute data-first-session-model is read as dataset.firstSessionModel.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { createPicker } = require("../main/resources/static/js/chat-model-picker.js");

const OAUTH = "Codex OAuth";
const API = "\uC678\uBD80 API";
const OTHER = "\uAE30\uD0C0 \uB4F1\uB85D \uBAA8\uB378";
const LOCAL = "\uB85C\uCEEC \u00B7 Ollama";

let doc;

function element(tag) {
  const node = {
    tagName: String(tag || "div").toUpperCase(),
    children: [],
    dataset: {},
    attrs: {},
    listeners: {},
    className: "",
    hidden: false,
    disabled: false,
    open: false,
    scrollTop: 0,
    selected: false,
    label: "",
    parentNode: null,
    _value: "",
    _text: ""
  };
  node.append = function () {
    for (let i = 0; i < arguments.length; i++) {
      arguments[i].parentNode = node;
      node.children.push(arguments[i]);
    }
  };
  node.appendChild = function (item) { node.append(item); return item; };
  node.prepend = function (item) {
    item.parentNode = node;
    node.children.unshift(item);
    if (item.selected) node._value = item._value;
  };
  node.replaceChildren = function () {
    for (const child of node.children) child.parentNode = null;
    node.children = [];
    node._text = "";
    for (let i = 0; i < arguments.length; i++) node.append(arguments[i]);
  };
  node.setAttribute = function (name, value) { node.attrs[name] = String(value); };
  node.getAttribute = function (name) { return Object.prototype.hasOwnProperty.call(node.attrs, name) ? node.attrs[name] : null; };
  node.addEventListener = function (name, fn) {
    if (!node.listeners[name]) node.listeners[name] = [];
    node.listeners[name].push(fn);
  };
  node.dispatchEvent = function (event) {
    for (const fn of node.listeners[event.type] || []) fn(event);
    return true;
  };
  node.focus = function () { doc.activeElement = node; };
  function matches(current, selector) {
    return selector.split(",").some(function (part) {
      const item = part.trim();
      if (item.startsWith(".")) return String(current.className || "").split(" ").includes(item.slice(1));
      if (item.startsWith("[")) return current.attrs[item.slice(1, -1)] !== undefined;
      return current.tagName.toLowerCase() === item;
    });
  }
  node.querySelectorAll = function (selector) {
    const found = [];
    for (const child of node.children) {
      if (matches(child, selector)) found.push(child);
      for (const nested of child.querySelectorAll(selector)) found.push(nested);
    }
    return found;
  };
  node.querySelector = function (selector) { return node.querySelectorAll(selector)[0] || null; };
  Object.defineProperty(node, "value", {
    get: function () { return node._value; },
    set: function (value) { node._value = String(value); }
  });
  Object.defineProperty(node, "textContent", {
    get: function () { return node._text + node.children.map(function (child) { return child.textContent; }).join(""); },
    set: function (value) { node.replaceChildren(); node._text = String(value); }
  });
  Object.defineProperty(node, "options", {
    get: function () { return node.querySelectorAll("option"); }
  });
  return node;
}

function harness() {
  const panel = element("dialog");
  const select = element("select");
  const trigger = element("button");
  const search = element("input");
  const filter = element("select");
  const list = element("div");
  const status = element("p");
  const count = element("p");
  const more = element("button");
  const close = element("button");
  const refresh = element("button");
  const refs = {
    "data-model-search": search,
    "data-model-filter": filter,
    "data-model-results": list,
    "data-model-catalog-status": status,
    "data-model-count": count,
    "data-model-more": more,
    "data-model-close": close,
    "data-model-refresh": refresh
  };
  for (const key of Object.keys(refs)) {
    refs[key].setAttribute(key, "");
    panel.append(refs[key]);
  }
  const ids = { modelBrowser: panel, modelSelect: select, modelBrowserTrigger: trigger };
  doc = {
    activeElement: null,
    getElementById: function (id) { return ids[id] || null; },
    createElement: function (tag) { return element(tag); },
    addEventListener: function () {},
    dispatchEvent: function () { return true; }
  };
  filter.value = "available";
  const writes = [];
  const store = new Map();
  const pending = [];
  const picker = createPicker(doc, {
    fetch: function () { return new Promise(function (resolve) { pending.push(resolve); }); },
    storage: {
      getItem: function (key) { return store.has(key) ? store.get(key) : null; },
      setItem: function (key, value) { writes.push(key); store.set(key, value); }
    },
    sessionStorage: {
      getItem: function () { return null; },
      setItem: function () { writes.push("session"); }
    },
    autostart: false
  });
  return { picker: picker, select: select, writes: writes, pending: pending };
}

function pack(rows) {
  return {
    ok: true,
    redirected: false,
    headers: { get: function () { return "application/json"; } },
    json: async function () { return rows; }
  };
}

const local = { id: "local:0", modelId: "local:0", provider: "Ollama", endpointId: "local-default", selectable: true, status: "installed", defaultChoice: true };
const api = { id: "llmrouter.fixture-api", modelId: "gpt-5.6-luna", provider: "OpenAI", endpointId: "fixture-api", evidence: "server_catalog", selectable: true, status: "configured" };
const sol = { id: "chatgpt-oauth:gpt-5.6-sol", modelId: "gpt-5.6-sol", provider: "chatgpt_oauth", endpointId: "chatgpt-oauth", selectable: true, status: "configured" };
const luna = { id: "chatgpt-oauth:gpt-5.6-luna", modelId: "gpt-5.6-luna", provider: "chatgpt_oauth", endpointId: "chatgpt-oauth", selectable: true, status: "configured" };
const unknown = { id: "fixture:unknown", modelId: "Luna5.6", provider: "Unknown", selectable: true, status: "configured" };
const bareRoute = { id: "llmrouter.bare", modelId: "gpt-5.6-luna", provider: "OpenAI", endpointId: "bare", selectable: true, status: "configured" };

function labels(select) {
  return select.children.filter(function (child) { return child.tagName === "OPTGROUP"; }).map(function (group) { return group.label; });
}
function ids(select) {
  return select.options.filter(function (option) { return !option.disabled; }).map(function (option) { return option.value; });
}

test("fresh first-session default stays sol while Luna is only displayed first", async function () {
  const h = harness();
  h.select.dataset.firstSessionModel = "chatgpt-oauth:gpt-5.6-sol,chatgpt-oauth:gpt-5.5";
  const rows = [local, api, unknown, sol, luna];
  const before = JSON.stringify(rows);
  const started = h.picker.refresh(false);
  h.pending[0](pack(rows));
  await started;
  assert.deepEqual(labels(h.select), [OAUTH, API, OTHER, LOCAL]);
  assert.equal(ids(h.select)[0], luna.id);
  assert.equal(h.select.value, sol.id);
  assert.equal(JSON.stringify(rows), before);
  assert.deepEqual(h.writes.filter(function (key) { return key !== "chat.recentModels"; }), []);
});

test("server default stays local when no HTML first-session id matches", async function () {
  const h = harness();
  const started = h.picker.refresh(false);
  h.pending[0](pack([local, api, luna]));
  await started;
  assert.equal(ids(h.select)[0], luna.id);
  assert.equal(h.select.value, local.id);
});

test("a touched API choice stays selected across the OAuth display order", async function () {
  const h = harness();
  h.select.dataset.firstSessionModel = "chatgpt-oauth:gpt-5.6-sol,chatgpt-oauth:gpt-5.5";
  h.select.value = api.id;
  h.select.dispatchEvent({ type: "change" });
  const started = h.picker.refresh(false);
  h.pending[0](pack([local, api, sol, luna]));
  await started;
  assert.equal(h.select.value, api.id);
  assert.equal(ids(h.select)[0], luna.id);
  assert.equal(h.writes.includes("chat.controlSettings"), false);
});

test("a luna-like name or llmrouter prefix alone stays unclassified", async function () {
  const h = harness();
  h.select.value = sol.id;
  h.select.dispatchEvent({ type: "change" });
  const started = h.picker.refresh(false);
  h.pending[0](pack([bareRoute, unknown, sol]));
  await started;
  assert.deepEqual(labels(h.select), [OAUTH, OTHER]);
  assert.deepEqual(ids(h.select), [sol.id, bareRoute.id, unknown.id]);
  assert.equal(h.select.value, sol.id);
});

test("a late older catalog cannot replace the newest list or the exact choice", async function () {
  const h = harness();
  h.select.value = sol.id;
  h.select.dispatchEvent({ type: "change" });
  const first = h.picker.refresh(false);
  const second = h.picker.refresh(false);
  h.pending[1](pack([local, sol, luna]));
  await second;
  h.pending[0](pack([Object.assign({}, local, { id: "local:old", modelId: "old-marker" })]));
  await first;
  assert.equal(h.select.value, sol.id);
  assert.equal(ids(h.select)[0], luna.id);
  assert.equal(ids(h.select).includes("local:old"), false);
});
