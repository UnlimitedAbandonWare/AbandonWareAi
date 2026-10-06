// Tri-system frontend isolation guard (Invariant 4, Surface).
// Static contract: the three surfaces must not reach into each other's
// DOM/state identifiers. 0-match assertions are regression locks — run with
// `node --test scripts/test_tri_system_frontend_isolation.cjs` (exit 0 = clean).
// chat-display-bridge.js is a DECLARED opt-in bridge (chat.displayBridge.enabled,
// default off); the test locks its opt-in gate, not its existence.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const ROOT=path.join(__dirname,'..');
const read=p=>fs.readFileSync(path.join(ROOT,p),'utf8');

// Main /chat state identifiers that belong only to js/chat*.js (verified
// 2026-10-05: sessionListRefresh*/chatAccessState/strictBackendSessionId are
// defined in chat.js).
const CHAT_STATE=[
  /sessionListRefresh/,/chatAccessState/,/strictBackendSessionId/,
  /sessionListContainer/,/sessionListRowMetadata/,/data-session-list/,
  /data-session-selection-state/,/session-mode-list/,/newChatBtn/,
  /chat-request-budget-ms/,/data-chat-surface/,
];
// Display/lens surface identifiers that belong only to assets/display/*.
const DISPLAY_MARKERS=[
  /display-focus/,/focus-controls/,/receiver\.js/,/lens\.js/,/lens\.html/,
  /DisplayConversate/,/assets\/display\//,/caption-text/,/hint-text/,
  /evidence-text/,/nf-answer/,/nf-embed/,/nf-memory/,/display-focus-controls/,
];

const CHAT_JS=['main/resources/static/js/chat.js'];
const DISPLAY_JS=[
  'main/resources/static/assets/display/display-focus-controls.js',
  'main/resources/static/assets/display/display-focus-flow.js',
  'main/resources/static/assets/display/display-focus.js',
  'main/resources/static/assets/display/display-conversate.js',
  'main/resources/static/assets/display/display-core.js',
  'main/resources/static/assets/display/lens.js',
  'main/resources/static/assets/display/receiver.js',
  'main/resources/static/assets/display/app.js',
];
const INTERVIEW_DIR='main/resources/static/assets/interview';
const interviewJs=fs.readdirSync(path.join(ROOT,INTERVIEW_DIR))
  .filter(f=>f.endsWith('.js')).map(f=>INTERVIEW_DIR+'/'+f);

function absent(text,patterns,file){
  for(const p of patterns){
    const m=text.match(p);
    assert.equal(m,null,`${file}: forbidden cross-surface marker ${p} at index ${m&&m.index}`);
  }
}

test('main /chat controller carries zero Display/lens/interview markers',()=>{
  for(const f of CHAT_JS) absent(read(f),DISPLAY_MARKERS,f);
});

test('display assets never touch main chat session state or DOM ids',()=>{
  for(const f of DISPLAY_JS) absent(read(f),CHAT_STATE,f);
});

test('local interview debug screen never manipulates main /chat state',()=>{
  assert.ok(interviewJs.length>0,'interview assets expected');
  for(const f of interviewJs){
    const t=read(f);
    absent(t,CHAT_STATE,f);
    absent(t,[/localStorage\.setItem\(['"]chat/,/localStorage\[['"]chat/],f);
  }
});

test('chat-ui.html loads /js/chat.js but never display-focus/receiver/lens assets',()=>{
  const t=read('main/resources/templates/chat-ui.html');
  assert.match(t,/src="\/js\/chat\.js/,'chat-ui.html must load /js/chat.js');
  absent(t,[/display-focus-controls/,/receiver\.js/,/lens\.js/,/display-conversate\.js/,/lens\.html/],'chat-ui.html');
});

test('display html shells never load /js/chat.js',()=>{
  for(const f of ['main/resources/static/assets/display/index.html',
                  'main/resources/static/assets/display/lens.html',
                  'main/resources/static/assets/display/receiver.html',
                  'main/resources/static/assets/display/meta/index.html',
                  'main/resources/static/assets/interview/index.html',
                  'main/resources/static/assets/interview/studio.html']){
    absent(read(f),[/\/js\/chat\.js/],f);
  }
});

test('declared opt-in bridge keeps its enable gate (bridge is allowed, ungated bleed is not)',()=>{
  const t=read('main/resources/static/js/chat-display-bridge.js');
  assert.match(t,/resolveEnabled/,'chat-display-bridge.js must keep the opt-in resolver');
  assert.match(t,/displayBridge/,'opt-in flag name must remain displayBridge');
});
