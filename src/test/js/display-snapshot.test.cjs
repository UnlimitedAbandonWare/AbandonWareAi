const {test}=require('node:test'),assert=require('node:assert/strict');
const {createSnapshotter}=require('../../../main/resources/static/assets/display/display-snapshot.js');

function fakeStream(){
  const tracks=[{stop(){this.stopped=true},stopped:false},{stop(){this.stopped=true},stopped:false}];
  return {tracks,getTracks:()=>tracks,getVideoTracks:()=>tracks};
}
function fakeVideo(){return {muted:false,playsInline:false,srcObject:null,readyState:2,videoWidth:640,videoHeight:480,play:async()=>{},onloadeddata:null,onerror:null};}
function fakeCanvas(lit=true){
  return {width:0,height:0,
    getContext:()=>({drawImage(){},getImageData:()=>({data:new Uint8ClampedArray(16*16*4).fill(lit?64:0)})}),
    toDataURL:()=>'data:image/jpeg;base64,QUJD'};
}
function fakeDoc(video,canvas){return {createElement:tag=>tag==='video'?video:canvas};}

test('captureOnce opens a video-only stream, returns one image, and stops tracks immediately',async()=>{
  const stream=fakeStream(),video=fakeVideo(),calls=[];
  const nav={mediaDevices:{getUserMedia:async c=>{calls.push(c);return stream;}}};
  const s=createSnapshotter({navigator:nav,document:fakeDoc(video,fakeCanvas())});
  const r=await s.captureOnce({facingMode:'environment'});
  assert.equal(r.ok,true);assert.equal(r.base64,'QUJD');assert.equal(r.mimeType,'image/jpeg');
  assert.equal(calls.length,1);assert.equal(calls[0].audio,false);assert.ok(calls[0].video);
  assert.ok(stream.tracks.every(t=>t.stopped),'tracks released before the result resolves');
  assert.equal(video.srcObject,null);
});

test('a second capture while busy is rejected',async()=>{
  const stream=fakeStream();
  const nav={mediaDevices:{getUserMedia:async()=>stream}};
  const s=createSnapshotter({navigator:nav,document:fakeDoc(fakeVideo(),fakeCanvas())});
  const first=s.captureOnce();
  const second=await s.captureOnce();
  assert.equal(second.ok,false);assert.equal(second.error,'capture_busy');
  assert.equal((await first).ok,true);
});

test('unsupported environment reports failure without touching camera',async()=>{
  const s=createSnapshotter({navigator:{mediaDevices:{}},document:{createElement(){throw Error('unused');}}});
  const r=await s.captureOnce();
  assert.equal(r.ok,false);assert.equal(r.error,'getusermedia_not_supported');
});

test('late permission grant after stop is released',async()=>{
  const stream=fakeStream();let resolve;
  const nav={mediaDevices:{getUserMedia:()=>new Promise(r=>resolve=r)}};
  const s=createSnapshotter({navigator:nav,document:fakeDoc(fakeVideo(),fakeCanvas())});
  const job=s.captureOnce({timeoutMs:500});
  s.stop();
  resolve(stream);
  const r=await job;
  assert.equal(r.ok,false);
  assert.ok(stream.tracks.every(t=>t.stopped),'late-granted tracks are released');
});

test('black first frame is rejected and camera released',async()=>{
  const stream=fakeStream();
  const nav={mediaDevices:{getUserMedia:async()=>stream}};
  const s=createSnapshotter({navigator:nav,document:fakeDoc(fakeVideo(),fakeCanvas(false))});
  const r=await s.captureOnce();
  assert.equal(r.ok,false);assert.equal(r.error,'camera_black_frame');
  assert.ok(stream.tracks.every(t=>t.stopped));
});

test('stop during frame wait releases owned tracks immediately',async()=>{
  const stream=fakeStream();
  const video={muted:false,playsInline:false,srcObject:null,readyState:0,videoWidth:0,videoHeight:0,play:async()=>{},onloadeddata:null,onerror:null};
  const nav={mediaDevices:{getUserMedia:async()=>stream}};
  const s=createSnapshotter({navigator:nav,document:fakeDoc(video,fakeCanvas())});
  const job=s.captureOnce({timeoutMs:30000});
  await new Promise(r=>setTimeout(r,20)); // 권한 승인 후 프레임 대기에 진입
  s.stop();
  assert.ok(stream.tracks.every(t=>t.stopped),'tracks released synchronously on stop');
  const r=await job;
  assert.equal(r.ok,false);assert.equal(r.error,'cancelled');
});

test('late grant resolving after a newer job started is released, not captured',async()=>{
  let resolveFirst,calls=0;
  const firstStream=fakeStream(),secondStream=fakeStream();
  const nav={mediaDevices:{getUserMedia:()=>{calls++;return calls===1?new Promise(r=>resolveFirst=r):Promise.resolve(secondStream);}}};
  const s=createSnapshotter({navigator:nav,document:fakeDoc(fakeVideo(),fakeCanvas())});
  const first=s.captureOnce({timeoutMs:500});
  s.stop();
  assert.equal((await first).ok,false,'cancelled job settles');
  const second=s.captureOnce(); // 새 작업이 진행 중
  resolveFirst(firstStream); // 이전 작업의 권한이 뒤늦게 승인됨
  const r2=await second;
  assert.equal(r2.ok,true);
  assert.ok(firstStream.tracks.every(t=>t.stopped),'stale grant tracks released');
  assert.ok(secondStream.tracks.every(t=>t.stopped));
});

test('rear capture requests exact environment facingMode',async()=>{
  const calls=[];const nav={mediaDevices:{getUserMedia:async c=>{calls.push(c);return fakeStream();}}};
  const s=createSnapshotter({navigator:nav,document:fakeDoc(fakeVideo(),fakeCanvas())});
  const r=await s.captureOnce({facingMode:'environment'});
  assert.equal(r.ok,true);
  assert.equal(calls[0].video.facingMode.exact,'environment');assert.equal(calls[0].audio,false);
});

test('a reported front-facing track is rejected and released',async()=>{
  const front={stop(){this.stopped=true},stopped:false,getSettings:()=>({facingMode:'user'})};
  const stream={getTracks:()=>[front],getVideoTracks:()=>[front]};
  const nav={mediaDevices:{getUserMedia:async()=>stream}};
  const s=createSnapshotter({navigator:nav,document:fakeDoc(fakeVideo(),fakeCanvas())});
  const r=await s.captureOnce({facingMode:'environment'});
  assert.equal(r.ok,false);assert.equal(r.error,'camera_rear_unverified');assert.ok(front.stopped);
});

test('an exhausted deadline never opens the camera',async()=>{
  let calls=0;const nav={mediaDevices:{getUserMedia:async()=>{calls++;return fakeStream();}}};
  const s=createSnapshotter({navigator:nav,document:fakeDoc(fakeVideo(),fakeCanvas())});
  const r=await s.captureOnce({timeoutMs:0});
  assert.equal(r.ok,false);assert.equal(r.error,'camera_timeout');assert.equal(calls,0);
});
