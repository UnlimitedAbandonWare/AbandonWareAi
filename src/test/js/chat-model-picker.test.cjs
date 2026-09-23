const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync("main/resources/static/js/chat-model-picker.js", "utf8");

// A small DOM fixture: focus detaches exactly when a focused descendant is removed.
// Native dialog keyboard containment is verified separately in the real browser.
function fixture() {
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
  filter.value = "available"; select.value = "local:0";
  const calls = [], ready = [], storage = new Map();
  document.addEventListener("chat:model-catalog", event => ready.push(event.detail.ready));
  class Event { constructor(type, opts = {}) { this.type = type; Object.assign(this,opts); } preventDefault() { this.defaultPrevented = true; } }
  vm.runInNewContext(source, {document, Event, CustomEvent:Event, Map, console,
    localStorage:{getItem:key=>storage.get(key),setItem:(key,value)=>storage.set(key,value)},
    fetch:(url)=>new Promise((resolve,reject)=>calls.push({url,resolve,reject}))});
  return {document,panel,select,trigger,search,filter,list,status,count,more,close,refresh,calls,ready,
    async respond(index, rows) { calls[index].resolve({ok:true,redirected:false,headers:{get:()=>"application/json"},json:async()=>rows}); await settle(); },
    change(mode) { filter.value=mode; filter.dispatchEvent(new Event("change")); },
    findFavorite(id) { return list.querySelectorAll("button").find(item=>item.getAttribute("aria-label")===id+" 즐겨찾기"); }
  };
}
const settle = () => new Promise(resolve=>setImmediate(resolve));
const models = count => Array.from({length:count},(_,i)=>({id:"local:"+i,modelId:"local:"+i,provider:"Ollama",selectable:true,status:"installed",release:"stable"}));

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
