"""One synthetic Focus request on an isolated test channel, with server defaults."""
import datetime, hashlib, http.cookiejar, json, pathlib, sys, time, urllib.request, urllib.error, uuid
base='http://127.0.0.1:18180'
client=uuid.uuid4().hex
channel='test-'+uuid.uuid4().hex
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
headers={'Content-Type':'application/json','Origin':base,'X-Display-Client':'1','X-Display-Test-Channel':channel}
out={'surface':'owned-synthetic-Focus-AUTO','syntheticInput':True,'requests':[],'hardwareRenderedObserved':False}
identity=None
def post(path, body):
    req=urllib.request.Request(base+path,data=json.dumps(body).encode(),headers=headers,method='POST')
    try:
        with opener.open(req,timeout=15) as r: status=r.status; response=json.load(r)
    except urllib.error.HTTPError as e: status=e.code; response=json.loads(e.read())
    out['requests'].append({'endpoint':path,'status':status})
    if status>=400: raise RuntimeError('http_'+str(status))
    return response
try:
    view=post('/api/assist/display/phone-test',{'assistId':None,'epoch':0,'clientId':client,'activate':True})
    identity={'assistId':view['assistId'],'epoch':view['epoch'],'clientId':client}
    stored=post('/api/assist/display/focus/settings/read',identity)
    settings=stored['settings']
    out['serverDefaultSelection']=settings.get('answerSelection')
    settings.update({'enabled':True,'answerLengthChars':80})
    if '--preserve-search' not in sys.argv: settings['webSearchEnabled']=False
    if '--replay-settings' in sys.argv:
        replay=json.loads(pathlib.Path(sys.argv[sys.argv.index('--replay-settings')+1]).read_text(encoding='utf-8'))
        assert replay.get('ok') and replay.get('rowCount')==1
        settings.update(replay['settings'])
        out['replayedSettingsVersion']=replay['settingsVersion']
        out['surface']='owned-synthetic-Focus-stored-selection-replay'
    out['effectiveSelection']=settings.get('answerSelection')
    out['answerLengthChars']=settings.get('answerLengthChars')
    out['reasoningPreset']=settings.get('reasoningPreset')
    out['searchConfigured']=settings.get('webSearchEnabled')
    out['searchAllowed']=settings.get('webSearchEnabled') is not False
    post('/api/assist/display/focus/settings',{**identity,'settingsVersion':stored['settingsVersion'],'settings':settings})
    post('/api/assist/display/focus/open',{**identity,'renderTarget':'fold'})
    request_id=str(uuid.uuid4()); started=time.monotonic()
    view=post('/api/assist/display/focus/input',{**identity,'requestId':request_id,'text':'Say hello in one short sentence.'})
    for index in range(30):
        if view.get('phase') in ['ANSWER_READY','PRESENTING'] or view.get('active') is False: break
        time.sleep(1)
        view=post('/api/assist/display/poll',identity).get('focus') or {}
    answer=view.get('answerText') or ''
    out.update({'elapsedMs':int((time.monotonic()-started)*1000),'phase':view.get('phase'),'active':view.get('active'),'reason':view.get('reason'),'answerChars':len(answer),'answerHash':hashlib.sha256(answer.encode()).hexdigest()[:12] if answer else None,'epoch':identity['epoch']})
    out['sessionHash']='hash:'+hashlib.sha256(identity['assistId'].encode()).hexdigest()[:12]
    with urllib.request.urlopen(base+'/api/diagnostics/debug/events?limit=80',timeout=8) as r: events=json.load(r)
    out['diagnostics']=[{k:d[k] for k in ['stage','requestHash','sessionHash','serverInstanceHash','observedAtMs','epoch','reasonCode','latencyMs','outcome','answerModel','exceptionClass','exceptionRootClass'] if k in d}
        for e in events if (d:=e.get('data') or {}).get('sessionHash')==out['sessionHash']]
except Exception as e: out.update({'verdict':'NOT_PROVEN','errorClass':type(e).__name__})
finally:
    if identity:
        for path, body in [('/api/assist/display/focus/close',identity),('/api/assist/display/relay/settings',{**identity,'enabled':False,'segmentSeconds':0})]:
            try:post(path,body)
            except Exception:pass
    out['checkedAtUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    target=pathlib.Path(__file__).parent/(sys.argv[1] if len(sys.argv)>1 else 'focus-auto-before.json')
    target.write_text(json.dumps(out,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in out.items() if k not in ['requests','diagnostics']},ensure_ascii=True))
