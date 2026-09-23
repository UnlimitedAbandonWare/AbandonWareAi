const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
test('phone worklet resamples to bounded 16k PCM frames and discards capture on stop',()=>{
  for(const rate of [16000,44100,48000]){
    const messages=[];let Worklet;
    class Base {constructor(){this.port={postMessage:(m)=>messages.push(m),onmessage:null};}}
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../../main/resources/static/conversate/pcm-worklet.js'),'utf8'),{sampleRate:rate,AudioWorkletProcessor:Base,registerProcessor:(_,C)=>Worklet=C});
    const w=new Worklet();for(let n=0;n<rate;n+=128)w.process([[new Float32Array(Math.min(128,rate-n)).fill(.25)]]);
    assert.equal(messages.length,4);for(const m of messages){assert.equal(m.pcm.byteLength,7680);assert.equal(new DataView(m.pcm).getInt16(0,true),8192);}
    w.port.onmessage({data:'stop'});assert.equal(w.process([[new Float32Array(128).fill(.5)]]),false);assert.equal(messages.length,4);
  }
});
