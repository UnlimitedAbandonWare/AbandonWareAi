/* PCM stays in memory; 240 ms chunks, mono 16 kHz. No playback, recording or storage. */
class ConversatePcm extends AudioWorkletProcessor {
  constructor(){super();this.running=true;this.buffer=new ArrayBuffer(7680);this.view=new DataView(this.buffer);this.index=0;this.sum=0;this.weight=0;this.ratio=sampleRate/16000;this.port.onmessage=e=>{if(e.data==='stop'||e.data==='finish'){if(e.data==='finish'&&this.running){if(this.index){const tail=new ArrayBuffer(Math.ceil(this.index*2/640)*640);new Uint8Array(tail).set(new Uint8Array(this.buffer,0,this.index*2));this.port.postMessage({pcm:tail},[tail]);}this.port.postMessage({stopped:true});}this.running=false;this.view=new DataView(new ArrayBuffer(0));this.buffer=null;this.sum=this.weight=this.index=0;}};}
  process(inputs){if(!this.running)return false;const channel=inputs[0]?.[0];if(!channel)return true;
    for(const value of channel){let remaining=1;while(remaining>1e-9){const take=Math.min(remaining,this.ratio-this.weight);this.sum+=value*take;this.weight+=take;remaining-=take;if(this.weight>=this.ratio-1e-9){const pcm=Math.round(Math.max(-1,Math.min(1,this.sum/this.ratio))*32767);this.view.setInt16(this.index*2,pcm,true);this.index++;this.sum=this.weight=0;if(this.index===3840){this.port.postMessage({pcm:this.buffer},[this.buffer]);this.buffer=new ArrayBuffer(7680);this.view=new DataView(this.buffer);this.index=0;}}}}
    return true;
  }
}
registerProcessor('conversate-pcm',ConversatePcm);
