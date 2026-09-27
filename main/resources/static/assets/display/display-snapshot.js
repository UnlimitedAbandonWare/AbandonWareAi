(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory();else root.DisplaySnapshot=factory();})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  // 확정된 질문 한 건당 호출되는 단발 촬영기. 대기 중 카메라를 열지 않고,
  // 유효 프레임 한 장을 확보하면 즉시 비디오 트랙을 해제한 뒤에야 호출자가 업로드한다.
  // 각 촬영은 독립 job 상태를 가지며 stop()은 현재 job의 소유 트랙과 대기를 즉시 종료한다.
  // 마이크/PCM 등 외부 스트림은 절대 건드리지 않는다.
  function createSnapshotter({navigator:nav,document:doc,setTimer:envTimer,clearTimer:envClear}={}){
    const envNav=nav||(typeof navigator!=='undefined'?navigator:null);
    const envDoc=doc||(typeof document!=='undefined'?document:null);
    const setTimer=envTimer||((fn,ms)=>setTimeout(fn,ms));
    const clearTimer=envClear||((id)=>clearTimeout(id));
    let current=null;
    function stopTracks(stream){try{stream?.getTracks?.().forEach(t=>{try{t.stop();}catch{}});}catch{}}
    function newJob(){
      const job={cancelled:false,done:false,stream:null,timers:new Set()};
      // 모든 대기 지점이 공유하는 취소 promise. abort() 한 번으로 어떤 await이든 즉시 끝난다.
      job.aborted=new Promise((_,reject)=>{job.abort=reject;});
      job.aborted.catch(()=>{});
      return job;
    }
    function stop(){
      const job=current;
      if(!job||job.done)return;
      job.cancelled=true;
      stopTracks(job.stream); // 이미 열린 스트림은 다음 await를 기다리지 않고 즉시 해제한다.
      for(const id of job.timers)clearTimer(id);
      job.timers.clear();
      job.abort(new Error('cancelled'));
    }
    async function captureOnce(options={}){
      if(current&&!current.done)return {ok:false,error:'capture_busy'};
      if(!envNav?.mediaDevices?.getUserMedia||!envDoc)return {ok:false,error:'getusermedia_not_supported'};
      // 남은 서버 마감을 넘겨 재부여하지 않는다. 예산이 없으면 카메라를 열지 않는다.
      const timeoutMs=Math.max(0,Math.min(options.timeoutMs??12000,30000));
      if(timeoutMs<=0)return {ok:false,error:'camera_timeout'};
      const facingMode=options.facingMode||'environment';
      const mimeType=options.mimeType||'image/jpeg';
      const maxLongEdge=Math.max(256,Math.min(options.maxLongEdge||1280,4096));
      const deadline=Date.now()+timeoutMs;
      const job=newJob();current=job;
      const guard=work=>Promise.race([work,job.aborted]);
      const timer=(ms,code)=>{const id=setTimer(()=>job.abort(new Error(code)),ms);job.timers.add(id);return id;};
      let stream=null,video=null;
      try{
        // 후면 요청은 exact로 시작하고, 장치가 exact를 거부하면 ideal로 낮춘 뒤 실제 트랙을 검증한다.
        const exact=facingMode==='environment';
        let request=envNav.mediaDevices.getUserMedia({video:{facingMode:exact?{exact:'environment'}:{ideal:facingMode}},audio:false});
        // 취소/타임아웃 뒤 늦게 승인되는 권한은 이 job 소유가 아니며 트랙만 즉시 정리한다.
        request.then(late=>{if(job.cancelled||job.done||current!==job)stopTracks(late);else job.stream=late;}).catch(()=>{});
        const grantId=timer(timeoutMs,'camera_timeout');
        try{stream=await guard(request);}catch(first){
          if(exact&&!job.cancelled&&!job.done&&/overconstrained|notfound|notreadable/i.test(String(first&&first.name||'')+String(first&&first.message||''))){
            request=envNav.mediaDevices.getUserMedia({video:{facingMode:{ideal:facingMode}},audio:false});
            request.then(late=>{if(job.cancelled||job.done||current!==job)stopTracks(late);else job.stream=late;}).catch(()=>{});
            stream=await guard(request);
          }else throw first;
        }finally{clearTimer(grantId);job.timers.delete(grantId);}
        job.stream=stream;
        if(job.cancelled)return {ok:false,error:'cancelled'};
        const track=stream.getVideoTracks?.()[0];
        if(!track)return {ok:false,error:'camera_no_video_track'};
        const reported=track.getSettings?.().facingMode;
        if(facingMode==='environment'&&reported&&reported!=='environment')return {ok:false,error:'camera_rear_unverified'};
        video=envDoc.createElement('video');
        video.muted=true;video.playsInline=true;video.srcObject=stream;
        const frameId=timer(Math.min(5000,Math.max(0,deadline-Date.now())),'camera_frame_timeout');
        try{
          await guard((async()=>{
            await video.play();
            if(video.readyState>=2&&video.videoWidth>0)return;
            if(typeof video.requestVideoFrameCallback==='function')await new Promise(resolve=>video.requestVideoFrameCallback(()=>resolve()));
            else await new Promise((resolve,reject)=>{video.onloadeddata=()=>resolve();video.onerror=()=>reject(new Error('video_load_failed'));});
          })());
        }finally{clearTimer(frameId);job.timers.delete(frameId);}
        if(job.cancelled)return {ok:false,error:'cancelled'};
        const w=video.videoWidth,h=video.videoHeight;
        if(!(w>0&&h>0))return {ok:false,error:'camera_frame_invalid'};
        const scale=Math.min(1,maxLongEdge/Math.max(w,h));
        const cw=Math.max(1,Math.round(w*scale)),ch=Math.max(1,Math.round(h*scale));
        const canvas=envDoc.createElement('canvas');canvas.width=cw;canvas.height=ch;
        const ctx=canvas.getContext('2d');ctx.drawImage(video,0,0,cw,ch);
        // 첫 프레임은 여러 영역을 표본 조사한다: 좌상단만 보면 암전 시작을 놓친다.
        let lit=false;
        for(const fy of[0.12,0.5,0.88])for(const fx of[0.12,0.5,0.88]){
          const sx=Math.min(cw-1,Math.max(0,Math.round(cw*fx)-2)),sy=Math.min(ch-1,Math.max(0,Math.round(ch*fy)-2));
          const sw=Math.min(4,Math.max(1,cw-sx)),sh=Math.min(4,Math.max(1,ch-sy));
          const data=ctx.getImageData(sx,sy,sw,sh).data;
          for(let i=0;i+2<data.length;i+=4){if(data[i]>8||data[i+1]>8||data[i+2]>8){lit=true;break;}}
          if(lit)break;
        }
        if(!lit)return {ok:false,error:'camera_black_frame'};
        const dataUrl=canvas.toDataURL(mimeType,options.quality??0.8);
        const base64=dataUrl.substring(dataUrl.indexOf(',')+1);
        if(!base64)return {ok:false,error:'capture_encode_failed'};
        const result={ok:true,base64,mimeType,width:cw,height:ch,bytes:Math.round(base64.length*3/4)};
        stopTracks(stream);stream=null;job.stream=null; // 사진 확보 직후 카메라 즉시 해제, 이후 업로드/응답 대기
        return result;
      }catch(error){
        return {ok:false,error:error?.message||'capture_failed'};
      }finally{
        if(stream)stopTracks(stream);
        if(job.stream)stopTracks(job.stream);
        if(video)video.srcObject=null;
        for(const id of job.timers)clearTimer(id);
        job.timers.clear();job.done=true;
        if(current===job)current=null;
      }
    }
    return {captureOnce,stop,get busy(){return current!=null&&!current.done;}};
  }
  return {createSnapshotter};
});
