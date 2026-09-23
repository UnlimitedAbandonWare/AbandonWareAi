const {test}=require('node:test'),assert=require('node:assert/strict');
const {createCache}=require('../../../main/resources/static/assets/display/display-focus-controls.js');
test('default Focus cache never opens durable browser storage',async()=>{
 let opens=0;
 const cache=createCache({indexedDB:{open(){opens++;throw Error('durable storage must remain unused');}}});
 const scope='a'.repeat(64);
 await cache.change(scope,row=>row.outbox.push({requestId:'fixture',text:'synthetic private question'}));
 assert.equal((await cache.read(scope)).outbox.length,1);
 assert.equal(opens,0);
 const reloaded=createCache({});
 assert.equal((await reloaded.read(scope)).outbox.length,0);
 cache.clear();
 assert.equal((await cache.read(scope)).outbox.length,0);
});
test('changing owner scope discards the previous in-memory body',async()=>{
 const cache=createCache({}),a='a'.repeat(64),b='b'.repeat(64);
 await cache.change(a,row=>row.outbox.push({requestId:'fixture',text:'synthetic question'}));
 assert.equal((await cache.read(b)).outbox.length,0);
 assert.equal((await cache.read(a)).outbox.length,0);
});

