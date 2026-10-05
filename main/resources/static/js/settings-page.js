(function () {
  'use strict';
  const core = window.AwxSettingsCore;
  const fields = [...document.querySelectorAll('[data-preference]')];
  const localStatus = document.getElementById('local-status');
  const catalogStatus = document.getElementById('model-catalog-status');
  const drafts=new Map();
  const retry=document.getElementById('local-retry'), revert=document.getElementById('local-revert'), undoButton=document.getElementById('local-undo');
  let saved={},undo=null,baseline=null,migration=false,saving=false;
  const saveButton=document.getElementById('local-save');
  const names={model:'답변 AI',modelSelectionMode:'AI 사용 방식',executionMode:'실행 전략',searchMode:'웹 검색',useRag:'참고자료',temperature:'Temperature',topP:'Top P',frequencyPenalty:'Frequency penalty',presencePenalty:'Presence penalty',maxTokens:'Max tokens',ragAnswerPolicy:'근거 부족 시 답변',customInstructions:'추가 지침',responseTone:'말투',responseLength:'답변 길이',responseLanguage:'답변 언어',memoryMode:'대화 기억'};
  const describe=v=>v===undefined||v===''?'기본 설정 따르기':String(v);
  let catalog = [];
  function temperatureSupport() {
    if(['model','modelSelectionMode'].some(key=>drafts.has(key)&&drafts.get(key)===''))return 'UNKNOWN';
    const model=drafts.get('model') || baseline?.overrides.model || baseline?.effective.model;
    const selection=drafts.get('modelSelectionMode') || baseline?.overrides.modelSelectionMode || baseline?.effective.modelSelectionMode;
    const capability=baseline?.sampling?.temperature;
    if(!capability)return 'UNKNOWN';
    return selection!=='auto' && capability?.model===model && capability?.modelSelectionMode===selection
      ? capability.support : 'UNKNOWN';
  }
  function renderModels(field, saved) {
    if(document.getElementById('modelBrowser')){
      if(![...field.options].some(o=>o.value==='')){const inherit=document.createElement('option');inherit.value='';inherit.textContent='기본 설정 따르기 · 채팅에서 선택';field.prepend(inherit);}
      if(saved&&![...field.options].some(o=>o.value===saved)){const unknown=document.createElement('option');unknown.value=saved;unknown.textContent=saved+' · 가용성 미확인';field.append(unknown);}
      field.value=saved;return;
    }
    const inherit = document.createElement('option');
    inherit.value = ''; inherit.textContent = '상속 · 채팅에서 선택';
    field.replaceChildren(inherit);
    for (const model of catalog) {
      const option = document.createElement('option');
      option.value = model.id;
      const local = model.provider.toLowerCase() === 'ollama';
      const available = model.selectable !== false && ['installed','available'].includes(model.status);
      option.textContent = model.id + ' · ' + model.provider + ' · ' + (local ? '로컬' : 'API')
        + ' · ' + (available ? '사용 가능(카탈로그)' : '가용성 미확인');
      option.disabled = model.selectable === false;
      field.append(option);
    }
    if (saved && !catalog.some(model => model.id === saved)) {
      const option = document.createElement('option');
      option.value = saved; option.textContent = saved + ' · 가용성 미확인';
      field.append(option);
    }
    field.value = saved;
  }
  function render() {
    try {
      const values = baseline ? baseline.overrides : {};
      saved=values;
      for (const field of fields) {
        const key = field.dataset.preference;
        const value = drafts.has(key)?drafts.get(key):Object.hasOwn(values,key) ? String(values[key]) : '';
        if (key === 'model') renderModels(field, value);
        else field.value=value;
        field.setAttribute('aria-invalid',String(drafts.has(key)));
      }
      const temperature=fields.find(f=>f.dataset.preference==='temperature');
      const support=temperatureSupport();
      if(temperature)temperature.disabled=support!=='YES';
      const temperatureStatus=document.getElementById('temperature-status');
      if(temperatureStatus)temperatureStatus.textContent=support==='YES'
        ?'선택 경로는 Temperature를 지원합니다. 실제 적용값은 모델·역할 제한과 최종 실행에 따라 달라집니다.'
        :support==='NO'?'선택 경로에서 Temperature 미전송 · 저장값은 보존합니다.'
        :'Temperature 적용 미확인 · 모델 설정을 저장하고 다시 확인하세요. 저장값은 보존합니다.';
      localStatus.textContent=drafts.size
        ?'아직 저장되지 않았습니다. '+[...drafts].map(([key,value])=>names[key]+' · 선택값: '+describe(value)+' / 마지막 저장값: '+describe(saved[key])).join(' · ')
        :baseline?'서버 개인 설정 · revision '+baseline.revision+' · 다음 새 대화부터 적용됩니다.':'서버 개인 설정 확인 중';
      if(baseline){
        const list=document.getElementById('personal-values');list.replaceChildren();
        for(const key of core.PREFERENCE_KEYS){
          const name=document.createElement('dt'),value=document.createElement('dd');
          name.textContent=key;
          value.textContent=String(baseline.effective[key])+' · '+baseline.sources[key]+' · 공장값 '+String(baseline.factoryDefaults[key])
            +(key==='temperature'?' · 상속 계산값 · '+(support==='NO'?'선택 경로 미전송':support==='YES'?'실제 전송값 미관측':'적용 미확인'):'');
          list.append(name,value);
        }
      }
      retry.hidden=revert.hidden=!drafts.size;undoButton.hidden=!undo;
      saveButton.disabled=saving || !baseline || !drafts.size;
      const summary=document.getElementById('local-advanced-summary');
      if(summary){
        const mode=fields.find(f=>f.dataset.preference==='modelSelectionMode').value;
        summary.textContent='AI 사용 방식 · '+({'':'기본 설정 따르기',preferred:'선택한 AI를 우선 사용',strict:'이 AI만 사용',auto:'사용 가능한 AI에서 자동 선택'}[mode]||'확인 필요');
      }
    } catch { localStatus.textContent='브라우저 저장소를 읽을 수 없습니다. 기존 값은 보존합니다.'; }
  }

  function fieldValue(key,value) {
    if (['useRag','useWebSearch'].includes(key)) return value === 'true';
    if (['temperature','topP','frequencyPenalty','presencePenalty','maxTokens'].includes(key)) return Number(value);
    return value;
  }
  async function reload(preserveDrafts=true) {
    try {
      baseline=await core.readPreferences(window);
      if(!preserveDrafts)drafts.clear();
      render();

    }catch{localStatus.textContent='개인 설정 서버 읽기 실패 · 현재 선택과 초안은 유지합니다.';}
  }
  async function persist(){
    if(!baseline||saving)return;
    const submitted=new Map(drafts);
    if(temperatureSupport()!=='YES' && submitted.get('temperature')!=='')submitted.delete('temperature');
    if(!submitted.size){render();return;}
    const set={},unset=[];
    for(const[key,value]of submitted){if(value==='')unset.push(key);else set[key]=fieldValue(key,value);}
    saving=true;saveButton.disabled=retry.disabled=true;
    try{
      baseline=await core.savePreferences(window,baseline,set,unset,migration);
      for(const[key,value]of submitted){if(drafts.get(key)===value)drafts.delete(key);}
      undo=null;migration=false;render();
      localStatus.textContent='서버 저장과 재조회 확인됨 · revision '+baseline.revision+
        (drafts.size?' · '+localStatus.textContent:' · 다음 새 대화부터 적용됩니다.');
    }catch(error){
      if(error.status===409)await reload(true);
      render();retry.hidden=revert.hidden=false;
      localStatus.textContent='저장 실패'+(error.status===409?' · 다른 저장과 충돌하여 서버 값을 다시 읽었습니다.':'')+' · 초안은 유지합니다. 확인 후 다시 저장하세요.';
    }finally{saving=false;saveButton.disabled=!baseline||!drafts.size;retry.disabled=false;}
  }
  for(const field of fields)field.addEventListener('change',()=>{
    drafts.set(field.dataset.preference,field.value);render();
  });
  saveButton.addEventListener('click',persist);
  retry.addEventListener('click',persist);
  revert.addEventListener('click',()=>{drafts.clear();migration=false;render();});
  document.getElementById('local-reload').addEventListener('click',()=>void reload(true));
  document.getElementById('local-reset').addEventListener('click',async()=>{
    if(!baseline)return;
    if(!window.confirm('개인 설정과 저장되지 않은 초안을 해제하고 서버 공통값을 상속할까요? 진행 중인 대화는 유지합니다.'))return;
    const before=baseline.overrides;
    try{
      baseline=await core.savePreferences(window,baseline,{},core.PREFERENCE_KEYS);
      drafts.clear();undo={values:before,revision:baseline.revision,hash:baseline.hash};render();
      localStatus.textContent='개인 설정 해제됨 · 서버 공통값과 공장 기본값을 상속합니다.';
    }catch{localStatus.textContent='초기화 실패 · 기존 값과 초안은 보존합니다.';}
  });
  undoButton.addEventListener('click',async()=>{
    if(!undo || !baseline)return;
    try{
      if(undo.revision!==baseline.revision || undo.hash!==baseline.hash)throw new Error('conflict');
      const values={...undo.values};
      const restoreTemperature=Object.hasOwn(values,'temperature') && (temperatureSupport()!=='YES'
        || (Object.hasOwn(values,'model')&&values.model!==baseline.sampling?.temperature?.model)
        || (Object.hasOwn(values,'modelSelectionMode')&&values.modelSelectionMode!==baseline.sampling?.temperature?.modelSelectionMode));
      const deferredTemperature=values.temperature;
      if(restoreTemperature)delete values.temperature;
      if(Object.keys(values).length)baseline=await core.savePreferences(window,baseline,values);
      if(restoreTemperature)drafts.set('temperature',String(deferredTemperature));
      undo=null;render();
    }catch{localStatus.textContent='실행취소 실패 · 서버 값 확인 후 다시 시도하세요.';}
  });
  document.getElementById('local-migrate').addEventListener('click',()=>{
    try{
      const values=core.readSettings(window.localStorage);
      for(const[key,value]of Object.entries(values))drafts.set(key,String(value));
      migration=true;render();localStatus.textContent='이전 브라우저 값 가져오기 미리보기 · 개인 기본값 저장을 눌러 적용하세요.';
    }catch{localStatus.textContent='이전 브라우저 설정 형식 오류 · 원본은 유지합니다.';}
  });
  document.getElementById('local-import').addEventListener('change',async event=>{
    try{
      const file=event.target.files?.[0];if(!file)return;
      if(file.size>core.MAX_BYTES)throw new Error('too_large');
      const values=core.importPreferences(await file.text());
      for(const[key,value]of Object.entries(values))drafts.set(key,String(value));
      render();localStatus.textContent='가져오기 미리보기 · 저장 버튼을 눌러 서버에 적용하세요.';
    }catch{localStatus.textContent='가져오기 실패 · 기존 값과 초안은 유지합니다.';}
    finally{event.target.value='';}
  });
  document.getElementById('local-export').addEventListener('click',()=>{
    if(!baseline)return;
    const url=URL.createObjectURL(new Blob([JSON.stringify({version:2,values:baseline.overrides},null,2)],{type:'application/json'}));
    const a=document.createElement('a');a.href=url;a.download='chat-preferences.json';a.click();URL.revokeObjectURL(url);
  });
  window.addEventListener('storage',event=>{if(event.key===core.CACHE_KEY || event.key===null)void reload(true);});
  async function readModels() {
    if(document.getElementById('modelBrowser')){catalogStatus.textContent='모델 탐색에서 검색·즐겨찾기·최근 선택과 카탈로그 상태를 확인하세요. 목록 확인은 실제 생성 성공을 뜻하지 않습니다.';return;}
    try {
      const response = await fetch('/api/chat/models', {
        credentials:'same-origin', cache:'no-store', headers:{Accept:'application/json'}
      });
      if (response.status === 401 || response.status === 403 || response.redirected) {
        catalogStatus.textContent = '카탈로그 읽기 권한 없음 · 저장된 모델을 유지합니다.'; return;
      }
      if (!response.ok) {
        catalogStatus.textContent = '모델 목록 읽기 실패 · 저장된 모델을 유지합니다.'; return;
      }
      const rows = await response.json();
      if (!Array.isArray(rows) || rows.some(row => !row || typeof row.id !== 'string'
          || !row.id.trim() || typeof row.provider !== 'string')) {
        catalogStatus.textContent = '모델 목록 응답 형식 오류 · 저장된 모델을 유지합니다.'; return;
      }
      catalog = [...new Map(rows.map(row => [row.id, row])).values()];
      render();
      catalogStatus.textContent = catalog.length
        ? '카탈로그 모델 ' + catalog.length + '개 · 실제 사용 모델은 답변 trace에서 확인합니다.'
        : '모델 카탈로그가 비어 있습니다 · 저장된 모델을 유지합니다.';
    } catch {
      catalogStatus.textContent = '모델 목록 읽기 실패 · 저장된 모델을 유지합니다.';
    }
  }
  const publicKeys=['TEMPERATURE','TOP_P','FREQUENCY_PENALTY','PRESENCE_PENALTY','OPENAI_MODEL','FINE_TUNED_MODEL','chat.defaults.useWebSearch','chat.ragAnswerPolicy'];
  async function readServer() {
    const status=document.getElementById('server-status');
    try {
      const response=await fetch('/api/settings',{credentials:'same-origin',headers:{Accept:'application/json'}});
      if (response.status===401 || response.status===403 || response.redirected) {status.textContent='잠김 · 현재 권한에서는 서버 설정을 읽을 수 없습니다.';return;}
      if(!response.ok)throw new Error('read_failed');
      const values=await response.json(), list=document.getElementById('server-values');
      list.replaceChildren();
      for(const key of publicKeys) {
        if(typeof values[key]!=='string' && typeof values[key]!=='number' && typeof values[key]!=='boolean')continue;
        const name=document.createElement('dt'), value=document.createElement('dd');
        name.textContent=key;value.textContent=String(values[key]);list.append(name,value);
      }
      status.textContent='설정값 · 요청·모델에 따른 실제 적용값과 실행 관측값은 별도입니다.';
    } catch { status.textContent='서버 설정 미관측 · 다시 페이지를 열어 확인하세요.'; }
  }
  render(); void reload(); void readServer(); void readModels();
})();
