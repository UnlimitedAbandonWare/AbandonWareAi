const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync("main/resources/static/js/chat-model-picker.js", "utf8");

// A small DOM fixture: focus detaches exactly when a focused descendant is removed.
// Native dialog keyboard containment is verified separately in the real browser.
function fixture(storedSettings = null, preferences = {}) {
  let document;
  class Element {
    constructor(tag = "div") {
      this.tagName = tag.toUpperCase(); this.children = []; this.dataset = {};
      this.attrs = {}; this.listeners = {}; this._value = ""; this._text = "";
      this.hidden = false; this.disabled = false; this.open = false; this.scrollTop = 0;
    }
    get tabIndex() { return this.attrs.tabindex === undefined ? 0 : Number(this.attrs.tabindex); }
    getClientRects() { return this.hidden ? [] : [{}]; }
    get value() { return this._value; }
    get options() { return this.querySelectorAll("option"); }
    set value(value) { this._value = String(value); }
    get textContent() { return this._text + this.children.map(child => child.textContent).join(""); }
    set textContent(value) { this.replaceChildren(); this._text = String(value); }
    append(...items) { for (const item of items) { item.parentNode = this; this.children.push(item); } }
    appendChild(item) { this.append(item); return item; }
    prepend(item) { item.parentNode = this; this.children.unshift(item); if (item.selected) this.value = item.value; }
    replaceChildren(...items) {
      if (document && this.contains(document.activeElement) && document.activeElement !== this) document.activeElement = document.body;
      for (const child of this.children) child.parentNode = null;
      this.children = []; this._text = ""; this.append(...items);
    }
    contains(item) { return item === this || this.children.some(child => child.contains(item)); }
    setAttribute(name, value) { this.attrs[name] = String(value); }
    getAttribute(name) { return this.attrs[name] ?? null; }
    addEventListener(name, fn) { (this.listeners[name] ||= []).push(fn); }
    dispatchEvent(event) { for (const fn of this.listeners[event.type] || []) fn(event); return true; }
    focus() { document.activeElement = this; }
    click() { if (!this.disabled) { this.focus(); this.dispatchEvent({type:"click"}); } }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    querySelectorAll(selector) {
      const matches = element => selector.split(",").some(part => {
        const item=part.trim();
        return item.startsWith(".") ? element.className?.split(" ").includes(item.slice(1))
          : item.startsWith("[") ? element.attrs[item.slice(1,-1)] !== undefined
          : element.tagName.toLowerCase() === item;
      });
      return this.children.flatMap(child => [...(matches(child) ? [child] : []), ...child.querySelectorAll(selector)]);
    }
    showModal() { this.open = true; }
    close() { this.open = false; this.dispatchEvent({type:"close"}); }
  }
  document = new Element("document"); document.body = new Element("body"); document.activeElement = document.body;
  const panel = new Element("dialog"), select = new Element("select"), trigger = new Element("button");
  const search = new Element("input"), filter = new Element("select"), list = new Element();
  const status = new Element("p"), count = new Element("p"), more = new Element("button"), close = new Element("button"), refresh = new Element("button");
  const refs = {"data-model-search":search,"data-model-filter":filter,"data-model-results":list,
    "data-model-catalog-status":status,"data-model-count":count,"data-model-more":more,"data-model-close":close,"data-model-refresh":refresh};
  for (const [key,value] of Object.entries(refs)) { value.setAttribute(key,""); panel.append(value); }
  document.body.append(trigger,panel,select);
  document.createElement = tag => new Element(tag);
  document.getElementById = id => ({modelBrowser:panel,modelSelect:select,modelBrowserTrigger:trigger})[id] || null;
  filter.value = "available"; select.value = preferences.selected || "local:0";
  const calls = [], ready = [], storage = new Map(Object.entries(preferences.storage || {})), writes = [];
  document.addEventListener("chat:model-catalog", event => ready.push(event.detail.ready));
  class Event { constructor(type, opts = {}) { this.type = type; Object.assign(this,opts); } preventDefault() { this.defaultPrevented = true; } }
  const selectionMode = new Element("select");
  if (storedSettings) {
    const initial = new Element("option"); initial.value = "local:0"; select.append(initial);
    const chatSource = fs.readFileSync("main/resources/static/js/chat.js", "utf8");
    const actualFunction = name => {
      const start = chatSource.indexOf("function " + name + "(");
      assert.ok(start >= 0, "product function must exist: " + name);
      const end = chatSource.indexOf("\nfunction ", start + 1);
      return chatSource.slice(start, end < 0 ? undefined : end);
    };
    const restoreSource = ["restoredSessionSetting", "restoredSessionBoolean", "selectCanUseValue",
      "applyRestoredSessionSettings", "restoreStoredControlSettings"].map(actualFunction).join("\n");
    vm.runInNewContext(restoreSource + "\nrestoreStoredControlSettings();", {
      document, dom:{modelSelect:select,modelSelectionMode:selectionMode},
      storedControlSettings:()=>storedSettings, localControlOverrideActive:false,
      syncControlStatus(){}, markControlHydrationReady(){}, setStatusRailValue(){}, modelStatusRailValue:v=>v
    });
  }
  vm.runInNewContext(source, {document, Event, CustomEvent:Event, Map, console,
    localStorage:{getItem:key=>storage.get(key),setItem:(key,value)=>{ writes.push(key); storage.set(key,value); }},
    fetch:(url,opts)=>new Promise((resolve,reject)=>calls.push({url,opts,resolve,reject}))});
  return {document,panel,select,selectionMode,trigger,search,filter,list,status,count,more,close,refresh,calls,ready,storage,writes,
    async respond(index, rows) { calls[index].resolve({ok:true,redirected:false,headers:{get:()=>"application/json"},json:async()=>rows}); await settle(); },
    change(mode) { filter.value=mode; filter.dispatchEvent(new Event("change")); },
    findFavorite(id) { return list.querySelectorAll("button").find(item=>item.getAttribute("aria-label")===id+" 즐겨찾기"); }
  };
}
const settle = () => new Promise(resolve=>setImmediate(resolve));
const models = count => Array.from({length:count},(_,i)=>({id:"local:"+i,modelId:"local:"+i,provider:"Ollama",selectable:true,status:"installed",release:"stable"}));

const storedApi = {model:"llmrouter.api3",modelSelectionMode:"strict",source:"user"};
const apiModel = {id:"llmrouter.api3",modelId:"openai/gpt-oss-120b",provider:"Groq",selectable:true,status:"configured"};
for (const outcome of ["available","unavailable","offline"]) {
  test("stored strict API identity survives asynchronous catalog: " + outcome, async () => {
    const f=fixture(storedApi);
    assert.equal(f.select.value,storedApi.model);
    assert.equal(f.selectionMode.value,"strict");
    assert.equal(f.select.options.find(o=>o.value===storedApi.model).disabled,true);
    assert.equal(f.ready.at(-1),false);
    if (outcome==="offline") { f.calls[0].reject(new Error("offline")); await settle(); }
    else await f.respond(0,[...models(1),{...apiModel,selectable:outcome==="available"}]);
    assert.equal(f.select.value,storedApi.model,"catalog must never substitute a local model");
    assert.equal(f.ready.at(-1),outcome==="available");
    assert.equal(Boolean(f.select.options.find(o=>o.value===storedApi.model).disabled),outcome!=="available");
  });
}
test("a user model choice during catalog loading wins over restored API selection", async () => {
  const f=fixture(storedApi);
  f.select.value="local:0"; f.select.dispatchEvent({type:"change"});
  await f.respond(0,[...models(1),apiModel]);
  assert.equal(f.select.value,"local:0"); assert.equal(f.ready.at(-1),true);
});

test("all matches can be browsed in pages and search covers rows beyond the first page", async () => {
  const f=fixture(); await f.respond(0,models(100));
  assert.equal(f.list.querySelectorAll(".model-choice").length,80);
  assert.match(f.count.textContent,/100.*80/); assert.equal(f.more.hidden,false);
  f.more.click(); assert.equal(f.list.querySelectorAll(".model-choice").length,100);
  assert.equal(f.more.hidden,true);
  f.search.value="local:99"; f.search.dispatchEvent({type:"input"});
  assert.equal(f.list.querySelectorAll(".model-choice").length,1);
  assert.match(f.list.textContent,/local:99/);
});
test("late older success cannot overwrite the newest catalog, selection or readiness", async () => {
  const f=fixture(); f.change("all"); f.change("available"); f.refresh.click();
  await f.respond(2,models(2)); const status=f.status.textContent, count=f.ready.length;
  await f.respond(1,[{...models(1)[0],id:"stale",modelId:"stale",selectable:false}]);
  await f.respond(0,[]);
  assert.equal(f.select.value,"local:0"); assert.match(f.list.textContent,/local:1/);
  assert.doesNotMatch(f.list.textContent,/stale/); assert.equal(f.status.textContent,status);
  assert.equal(f.ready.length,count); assert.equal(f.ready.at(-1),true);
  f.change("all"); assert.equal(f.calls.length,4,"old public result must not mark the newest local result as fully discovered");
  await f.respond(3,models(3));
});
test("late old failure cannot undo a successful newer request", async () => {
  const f=fixture(); f.refresh.click(); await f.respond(1,models(1));
  const status=f.status.textContent, count=f.ready.length;
  f.calls[0].reject(new Error("old failure")); await settle();
  assert.equal(f.status.textContent,status); assert.equal(f.ready.length,count); assert.equal(f.ready.at(-1),true);
});
test("favorite rerender preserves the operated button and results scroll position", async () => {
  const f=fixture(); await f.respond(0,models(5)); f.list.scrollTop=90;
  f.findFavorite("local:3").click();
  assert.ok(f.document.activeElement===f.findFavorite("local:3"));
  assert.equal(f.document.activeElement.getAttribute("aria-pressed"),"true"); assert.equal(f.list.scrollTop,90);
  f.change("favorites"); f.findFavorite("local:3").click();
  assert.equal(f.document.activeElement,f.search,"removed favorite returns focus to search");
});
test("dialog opens at search and close or Escape restores its invoking button", async () => {
  const f=fixture(); await f.respond(0,models(1));
  f.trigger.click(); assert.equal(f.panel.open,true); assert.equal(f.document.activeElement,f.search);
  f.close.click(); assert.equal(f.panel.open,false); assert.equal(f.document.activeElement,f.trigger);
  f.trigger.click(); f.panel.dispatchEvent({type:"cancel",preventDefault(){}});
  assert.equal(f.panel.open,false); assert.equal(f.document.activeElement,f.trigger);
});
test("unavailable choices stay disabled, explain next steps, and never silently replace selection", async () => {
  const f=fixture(); await f.respond(0,[{...models(1)[0],selectable:false,reason:"route_not_configured"}]);
  assert.equal(f.select.value,"local:0"); assert.equal(f.ready.at(-1),false);
  f.change("unavailable"); await f.respond(1,[{...models(1)[0],selectable:false,reason:"route_not_configured"}]);
  assert.equal(f.list.querySelector(".model-choice-select").disabled,true);
  const help=f.list.querySelector(".model-help"); assert.ok(help); help.click();
  assert.equal(help.getAttribute("aria-expanded"),"true"); assert.match(f.list.textContent,/운영자/);
  assert.equal(f.list.querySelector(".model-recheck"),null,"policy-blocked rows offer no metadata recheck");
});
test("unobserved model offers one in-flight metadata recheck that preserves selection and draft state", async () => {
  const f=fixture(); await f.respond(0,[{...models(1)[0],selectable:false,reason:"capability_not_observed"}]);
  f.change("unavailable"); await f.respond(1,[{...models(1)[0],selectable:false,reason:"capability_not_observed"}]);
  const recheck=f.list.querySelector(".model-recheck"); assert.ok(recheck);
  recheck.click();
  assert.equal(recheck.disabled,true,"in-flight recheck collapses repeat clicks");
  recheck.click(); await settle();
  const posts=f.calls.filter(c=>c.url.includes("/api/chat/models/recheck"));
  assert.equal(posts.length,1,"repeat clicks merge into one metadata request");
  assert.match(posts[0].url,/\/api\/chat\/models\/recheck\?id=local%3A0/);
  assert.equal(posts[0].opts.method,"POST");
  posts[0].resolve({ok:true,redirected:false,headers:{get:()=>"application/json"},json:async()=>({})});
  await settle();
  await f.respond(f.calls.length-1,[{...models(1)[0],selectable:true,status:"installed"}]);
  assert.equal(f.select.value,"local:0","recheck restores the same selection instead of substituting another model");
  assert.equal(f.ready.at(-1),true,"recovery re-enables readiness only after an actual selectable row");
});
test("new failed refresh retains explicit stale list and disables readiness until a successful refresh", async () => {
  const f=fixture(); await f.respond(0,models(1)); f.refresh.click();
  assert.equal(f.ready.at(-1),false);
  f.calls[1].reject(new Error("offline")); await settle();
  assert.match(f.status.textContent,/이전 목록/); assert.match(f.list.textContent,/local:0/);
  f.findFavorite("local:0").click(); assert.equal(f.ready.at(-1),false);
});

test("stale catalog cannot re-enable sending through a different model selection", async () => {
  const f=fixture(); await f.respond(0,models(2)); f.refresh.click();
  f.select.value="local:1"; f.select.dispatchEvent({type:"change"});
  assert.equal(f.ready.at(-1),false,"pending refresh must keep sending disabled");
  f.calls[1].reject(new Error("offline")); await settle();
  f.select.value="local:0"; f.select.dispatchEvent({type:"change"});
  assert.equal(f.ready.at(-1),false,"failed refresh must keep sending disabled");
  f.refresh.click(); await f.respond(2,models(2));
  assert.equal(f.ready.at(-1),true);
});
test("Tab wraps inside the open dialog and Escape from search closes it", async () => {
  const f=fixture(); await f.respond(0,models(1)); f.trigger.click();
  const controls=f.panel.querySelectorAll("button, input, select, [tabindex]")
    .filter(el=>!el.hidden&&!el.disabled&&el.tabIndex!==-1);
  const first=controls[0],last=controls.at(-1);
  let prevented=false; last.focus();
  f.panel.dispatchEvent({type:"keydown",key:"Tab",preventDefault(){prevented=true;}});
  assert.equal(prevented,true); assert.equal(f.document.activeElement,first);
  prevented=false;
  f.panel.dispatchEvent({type:"keydown",key:"Tab",shiftKey:true,preventDefault(){prevented=true;}});
  assert.equal(prevented,true); assert.equal(f.document.activeElement,last);
  f.search.focus(); f.search.value="model";
  f.document.dispatchEvent({type:"keydown",key:"Escape",target:f.search,cancelable:true,preventDefault(){prevented=true;}});
  assert.equal(f.panel.open,false); assert.equal(f.document.activeElement,f.trigger);
});

// Synthetic producer identities only; no account catalog or history is copied here.
const localRow = {id:"local:0",modelId:"local:0",provider:"Ollama",endpointId:"local-default",selectable:true,status:"installed"};
const apiRow = {id:"llmrouter.fixture-api",modelId:"gpt-5.6-luna",provider:"OpenAI",endpointId:"fixture-api",evidence:"server_catalog",selectable:true,status:"configured"};
const oauthRow = modelId => ({id:"chatgpt-oauth:"+modelId,modelId,provider:"chatgpt_oauth",endpointId:"chatgpt-oauth",selectable:true,status:"configured"});
const oauthSol = oauthRow("gpt-5.6-sol"), oauthLuna = oauthRow("gpt-5.6-luna");
const unknownRow = {id:"fixture:unknown",modelId:"Codex OAuth Luna5.6",provider:"Unknown",selectable:true};
const renderedIds = f => f.list.querySelectorAll(".model-choice-select").map(button=>button.getAttribute("data-model-id"));
const optionIds = f => f.select.options.filter(option=>!option.disabled).map(option=>option.value);

test("native and browser lists order producer groups before favorites and keep input order within ties", async () => {
  const f=fixture(null,{storage:{"chat.modelFavorites":JSON.stringify([localRow.id,oauthSol.id])}});
  const rows=[localRow,apiRow,unknownRow,oauthSol,oauthLuna,oauthRow("fixture-second"),oauthRow("fixture-first")];
  const before=JSON.stringify(rows);
  await f.respond(0,rows);
  const expected=[oauthLuna.id,oauthSol.id,"chatgpt-oauth:fixture-second","chatgpt-oauth:fixture-first",apiRow.id,unknownRow.id,localRow.id];
  assert.deepEqual(renderedIds(f),expected);
  assert.deepEqual(optionIds(f),expected);
  assert.deepEqual(f.select.children.map(group=>group.label),["Codex OAuth","외부 API","기타 등록 모델","로컬 · Ollama"]);
  assert.equal(f.select.value,localRow.id);
  assert.equal(JSON.stringify(rows),before,"display ordering must not mutate the catalog");
  assert.deepEqual(f.writes,[],"refresh/reorder must not rewrite preferences");
});

test("duplicate names and contradictory provenance retain distinct exact IDs in unknown groups", async () => {
  const contradictions=[
    {...oauthRow("fixture-mismatch"),endpointId:"external"},
    {...oauthRow("fixture-slug"),id:"chatgpt-oauth:other-slug"},
    {...localRow,id:"fixture:local-name",endpointId:"external"},
    {...apiRow,id:"llmrouter.other-route"},
    {...apiRow,id:"fixture:api-name",evidence:"unverified"}
  ];
  const f=fixture(); await f.respond(0,[...contradictions,localRow,apiRow,oauthLuna,unknownRow]);
  assert.deepEqual(renderedIds(f),[oauthLuna.id,apiRow.id,...contradictions.map(row=>row.id),unknownRow.id,localRow.id]);
  assert.equal(f.select.options.length,9,"same modelId must not merge providers");
  assert.match(f.list.textContent,/Codex OAuth/);
  assert.match(f.list.textContent,/OpenAI · API/);
});

for(const state of ["absent","disabled"]) test("Luna recommendation never invents or enables an unavailable row: "+state,async()=>{
  const f=fixture(); const rows=[localRow,oauthSol];
  if(state==="disabled") rows.push({...oauthLuna,selectable:false,reason:"auth_missing"});
  await f.respond(0,rows); f.change("all"); await f.respond(1,rows);
  assert.deepEqual(optionIds(f),[oauthSol.id,localRow.id]);
  const luna=f.list.querySelectorAll(".model-choice-select").find(button=>button.getAttribute("data-model-id")===oauthLuna.id);
  assert.equal(Boolean(luna),state==="disabled");
  if(luna) assert.equal(luna.disabled,true);
  assert.equal(f.select.value,localRow.id);
});

for(const selected of [localRow.id,apiRow.id,oauthSol.id,"fixture:missing"]) test("reordering preserves exact stored choice and disabled placeholder: "+selected,async()=>{
  const f=fixture({model:selected,modelSelectionMode:"strict",source:"user"});
  await f.respond(0,[localRow,apiRow,oauthSol,oauthLuna]);
  assert.equal(f.select.value,selected); assert.equal(f.selectionMode.value,"strict");
  assert.equal(Boolean(f.select.options.find(option=>option.value===selected).disabled),selected==="fixture:missing");
  assert.deepEqual(f.writes,[]);
});

test("favorites keep their set, recent keeps usage order, filters/search and focus preserve exact identities", async()=>{
  const f=fixture(null,{storage:{"chat.modelFavorites":JSON.stringify([localRow.id,oauthSol.id]),"chat.recentModels":JSON.stringify([localRow.id,apiRow.id,oauthSol.id])}});
  await f.respond(0,[localRow,apiRow,unknownRow,oauthSol,oauthLuna]);
  f.change("favorites"); assert.deepEqual(renderedIds(f),[oauthSol.id,localRow.id]);
  f.change("recent"); assert.deepEqual(renderedIds(f),[localRow.id,apiRow.id,oauthSol.id]);
  f.change("available"); f.search.value="gpt-5.6-luna"; f.search.dispatchEvent({type:"input"});
  assert.deepEqual(renderedIds(f),[oauthLuna.id,apiRow.id]);
  const apiFavorite=f.list.querySelectorAll(".model-favorite").find(button=>button.getAttribute("data-model-id")===apiRow.id);
  apiFavorite.click();
  assert.equal(f.document.activeElement.getAttribute("data-model-id"),apiRow.id);
  assert.deepEqual(renderedIds(f),[oauthLuna.id,apiRow.id],"API favorite must stay below OAuth");
  assert.deepEqual(f.writes,["chat.modelFavorites"]);
  assert.equal(f.storage.get("chat.recentModels"),JSON.stringify([localRow.id,apiRow.id,oauthSol.id]));
});

test("interleaved provider rows retain the same native/browser order and safe provider labels",async()=>{
  const otherApi={...apiRow,id:"llmrouter.fixture-other",endpointId:"fixture-other",provider:"OtherAPI"};
  const tailApi={...apiRow,id:"llmrouter.fixture-tail",endpointId:"fixture-tail",modelId:"fixture-tail"};
  const f=fixture(null,{storage:{"chat.modelFavorites":JSON.stringify([apiRow.id,otherApi.id])}});
  await f.respond(0,[apiRow,otherApi,tailApi,localRow]);
  assert.deepEqual(optionIds(f),renderedIds(f));
  assert.match(f.select.options.find(option=>option.value===otherApi.id).textContent,/OtherAPI/);
});

test("toggling a favorite immediately synchronizes native order without changing selection or focus",async()=>{
  const f=fixture(); await f.respond(0,[localRow,oauthSol,oauthRow("fixture-other")]);
  const target="chatgpt-oauth:fixture-other";
  const clickFavorite=()=>f.list.querySelectorAll(".model-favorite").find(button=>button.getAttribute("data-model-id")===target).click();
  for(let i=0;i<2;i++){
    clickFavorite();
    assert.deepEqual(optionIds(f),renderedIds(f));
    assert.equal(f.select.value,localRow.id);
    assert.equal(f.document.activeElement.getAttribute("data-model-id"),target);
  }
});

test("local provenance with contradictory exact model identity remains unclassified",async()=>{
  const contradiction={...localRow,id:"llmrouter.fixture-external"};
  const f=fixture(); await f.respond(0,[localRow,contradiction,oauthSol]);
  assert.deepEqual(renderedIds(f),[oauthSol.id,contradiction.id,localRow.id]);
});
