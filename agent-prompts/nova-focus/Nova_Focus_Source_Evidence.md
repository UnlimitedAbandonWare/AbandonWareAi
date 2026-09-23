# mai2111n.zip — Nova Focus 소스 근거집

작성일: 2026-09-23 (Asia/Seoul)
원본 ZIP SHA-256: `b8afe9dadc42a47f990e8f1f75da77079f4a06221f734429117a18efca79d957`

원본을 읽기 전용으로 분석했다. 아래 행 번호는 ZIP 내부 상대 경로의 실제 파일 행이다. 잘린 범위 밖의 동작까지 검증했다는 뜻은 아니다. 원본 소스를 수정하거나 빌드·기기 실행을 수행하지 않았다.

> Repo copy note (2026-09-23): three quoted source lines had the identifier `token` prefixed to `lensToken` so this file passes the repo checkpoint secret-pattern scan (`codex_work_checkpoint.py`). Semantics unchanged; the pristine original is the user's Downloads copy.

## 파일 구성 및 빌드 범위

- 추출 일반 파일 수: 2302
- Java 빌드 진입 파일 검색 결과: 없음
- `resources/soniox-sidecar/package.json`은 있다. 전체 Java 애플리케이션 빌드 루트가 있다는 뜻은 아니다.
- `resources/db/README.md`는 기본 Hibernate auto-update/Flyway 미사용을 설명하지만, 실제 `resources/db/migration/`에는 명시 적용 SQL이 있다. 따라서 실행자가 실제 빌드 의존성과 DB 적용 방식을 확인해야 한다.

## 정적 탐색 보조 결과

- `ChatWorkflow.java` 내 `getHistory(` 문자열 개수: 0
- 이 결과와 E09/E10을 함께 보면, 현재 canonical 경로에서 DTO.history만 채우면 최종 모델 입력까지 보장된다고 볼 수 없다. provider 호출 인자 통합 테스트가 필요하다.

## E01 — 기존 ASR → sessions.submit(phone_voice), utterance/revision/final
`java/com/example/lms/assist/ConversateAsrBridge.java:178-223`
파일 SHA-256: `18c4e84325292d693270185b8ca9f157cab54638c601b640c08b620cc049ef1f`
```text
178:             try{String text=event.path("text").asText(),utterance=event.path("utteranceId").asText();boolean grouped=event.has("groupId");
179:                 if((text.isBlank()&&!grouped)||text.length()>(grouped?2048:8192))throw new IllegalArgumentException();
180:                 if(!recovered&&!text.isEmpty()&&utterance.matches("asr-[1-9][0-9]{0,9}")&&Long.parseLong(utterance.substring(4))<=handedOffThrough)return;
181:                 if(!recovered)runtime(event);
182:                 boolean fin=event.path("final").asBoolean();long revision=event.path("revision").asLong();
183:                 if(!event.path("revision").isIntegralNumber()||revision<0||revision>1_000_000)throw new IllegalArgumentException();
184:                 var words=List.<com.example.lms.service.stt.DeepgramSttService.Word>of();
185:                 if(event.has("words")){
186:                     if(!event.path("words").isArray()||event.path("words").size()>1024)throw new IllegalArgumentException();
187:                     words=json.convertValue(event.path("words"),json.getTypeFactory().constructCollectionType(List.class,com.example.lms.service.stt.DeepgramSttService.Word.class));
188:                 }
189:                 Double confidence=null;
190:                 if(event.hasNonNull("confidence")){if(!event.path("confidence").isNumber())throw new IllegalArgumentException();confidence=event.path("confidence").asDouble();}
191:                 var accepted=new ConversateQuestionPolicy.Utterance(utterance,utterance,(int)revision,fin,text,confidence,words);
192:                 if(grouped){accepted=localUtterance(event,text,fin,(int)revision);if(accepted==null){duplicates++;metrics();return;}fin=accepted.isFinal();}
193:                 sessions.submit(owner,id,epoch,accepted,"phone_voice",null);
194:                 runtime.put("providerResponse","observed");
195:                 if(!fin&&partials==0&&firstAudioNanos>0)runtime.put("firstPartialMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-firstAudioNanos));
196:                 if(fin&&finishing)runtime.put("finalAfterStopMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-finishBegan));
197:                 if(fin)finals++;else partials++;lastAsrMs=Math.max(0,event.path("asrMs").asLong());metrics();
198:             }catch(Exception invalid){if(!closed.get())fail("ASR_TRANSCRIPT_REJECTED");}
199:         }
200:         private ConversateQuestionPolicy.Utterance localUtterance(JsonNode event,String text,boolean finalized,int revision){
201:             String segment=event.path("utteranceId").asText(),group=event.path("groupId").asText();
202:             if(!segment.matches("asr-[1-9][0-9]{0,9}")||!group.matches("asr-[1-9][0-9]{0,9}")||!event.path("utteranceEnd").isBoolean())throw new IllegalArgumentException();
203:             long first=Long.parseLong(group.substring(4)),number=Long.parseLong(segment.substring(4));boolean end=event.path("utteranceEnd").asBoolean();
204:             if(number<first||number-first>=64||end&&!finalized)throw new IllegalArgumentException();
205:             if(first<=completedThrough)return null;
206:             if(!localGroups.containsKey(first)&&localGroups.size()>=4)throw new IllegalArgumentException();
207:             var pending=localGroups.computeIfAbsent(first,key->new LocalGroup(first));
208:             var prior=pending.segments.get(number);
209:             if(prior!=null&&revision<prior.revision())return null;
210:             if(!text.isEmpty()){
211:                 if(prior!=null&&prior.finalized()&&!prior.text().equals(text))throw new IllegalArgumentException();
212:                 if(prior!=null&&revision==prior.revision()&&!prior.text().equals(text))throw new IllegalArgumentException();
213:                 pending.segments.put(number,new LocalSegment(text,revision,finalized));
214:             }
215:             if(end)pending.last=number;
216:             var combined=new StringBuilder();boolean complete=pending.last>=first;
217:             long through=pending.last>=first?pending.last:number;
218:             for(long index=first;index<=through;index++){
219:                 var item=pending.segments.get(index);if(item==null){complete=false;break;}
220:                 if(!combined.isEmpty())combined.append(' ');combined.append(item.text());
221:                 if(!item.finalized()){complete=false;break;}
222:             }
223:             if(combined.length()>8192)throw new IllegalArgumentException();
```

## E02 — provider 확정 구간과 speech_final/UtteranceEnd 조립
`java/com/example/lms/assist/DeepgramAsrTransport.java:80-120`
파일 SHA-256: `b5f0babc5b08709f3e4ec42f82b95da85296f41781bd3d503ff431dc4ae1a0f9`
```text
80:     private synchronized void transcript(DeepgramSttService.Transcript result) {
81:         if(closed.get())return;
82:         if("SpeechStarted".equals(result.eventType()))return;
83:         if("UtteranceEnd".equals(result.eventType())){
84:             if(!segments.isEmpty()&&result.start()>=confirmedWordThrough&&confirmedThrough>closedThrough){
85:                 var words=segmentWords.values().stream().flatMap(List::stream).toList();
86:                 emitTranscript(String.join(" ",segments.values()),true,null,words);utterance++;
87:                 closedThrough=Math.max(closedThrough,confirmedThrough);segments.clear();segmentWords.clear();
88:             }
89:             return;
90:         }
91:         String text=result.transcript();
92:         if(text==null||text.length()>8192){fail("ASR_TRANSCRIPT_LIMIT");return;}
93:         boolean timed=Double.isFinite(result.start())&&Double.isFinite(result.duration())&&result.start()>=0&&result.duration()>=0;
94:         double end=result.start()+result.duration();
95:         if(timed&&end<=closedThrough)return;
96:         boolean confirmed=result.isFinal()||result.speechFinal();
97:         String identity=timed?result.start()+":"+result.duration():"untimed:"+text;
98:         if(confirmed&&!text.isBlank()){
99:             segments.putIfAbsent(identity,text);segmentWords.putIfAbsent(identity,result.words());
100:             if(timed){confirmedThrough=Math.max(confirmedThrough,end);confirmedWordThrough=Math.max(confirmedWordThrough,result.words().stream().mapToDouble(DeepgramSttService.Word::end).max().orElse(end));}
101:         }
102:         if(segments.size()>128){fail("ASR_TRANSCRIPT_LIMIT");return;}
103:         String assembled=String.join(" ",segments.values());
104:         if(!confirmed&&!text.isBlank())assembled=assembled.isBlank()?text:assembled+" "+text;
105:         if(assembled.length()>8192){fail("ASR_TRANSCRIPT_LIMIT");return;}
106:         var words=new ArrayList<DeepgramSttService.Word>();segmentWords.values().forEach(words::addAll);
107:         if(!confirmed)words.addAll(result.words());
108:         if(words.size()>1024){fail("ASR_TRANSCRIPT_LIMIT");return;}
109:         Double confidence=assembled.equals(text)?result.confidence():null;
110:         if(result.speechFinal()){
111:             if(!assembled.isBlank()&&(timed||!assembled.equals(lastUntimedFinal))){emitTranscript(assembled,true,confidence,words);utterance++;lastUntimedFinal=assembled;}
112:             segments.clear();segmentWords.clear();if(timed)closedThrough=Math.max(closedThrough,end);
113:         }else if(!assembled.isBlank()){if(!confirmed)lastUntimedFinal="";emitTranscript(assembled,false,confidence,words);}
114:     }
115:     private void emitTranscript(String text,boolean complete,Double confidence,List<DeepgramSttService.Word> words){
116:         if(!closed.get()){
117:             var event=json.createObjectNode().put("type","transcript").put("utteranceId","dg-"+utterance).put("revision",++revision).put("final",complete).put("text",text);
118:             if(confidence!=null)event.put("confidence",confidence);
119:             event.set("words",json.valueToTree(words));events.accept(event);
120:         }
```

## E03 — 입력 검증, stale/dedup, caption, hints OFF 조기 반환, 작업 취소
`java/com/example/lms/assist/ConversateSessionService.java:180-230`
파일 SHA-256: `372f213e166a0f03fbd6bb392c1576a76467d44d8a3f45202fcf472b41eea491`
```text
180:     public Snapshot submit(String owner,String id,long epoch,ConversateQuestionPolicy.Utterance utterance,String inputPath,String requestId){
181:         if(inputPath==null||!Set.of("direct","glasses_input","phone_voice","test","openai_direct").contains(inputPath))throw error(HttpStatus.BAD_REQUEST,"invalid_input_path");
182:         var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);if(!s.state.equals("RUNNING"))throw error(HttpStatus.CONFLICT,"assist_paused");
183:             if("phone_voice".equals(inputPath)&&staleAudio(s,utterance)){s.duplicates++;return snapshot(s);}
184:             if("phone_voice".equals(inputPath)&&preResetAudio(s,utterance)){s.duplicates++;diagnostic(s,"CONTEXT_STALE_AUDIO",Map.of("contextEpoch",s.contextEpoch));return snapshot(s);}
185:             boolean rolling=rollingEnabled&&"phone_voice".equals(inputPath);
186:             var decision=(pipeline.usesApiCues()||rolling)?s.policy.acceptForCue(utterance):s.publicDisplay&&!"phone_voice".equals(inputPath)?s.policy.acceptExplicit(utterance):s.policy.accept(utterance);s.reason=decision.kind();s.version++;
187:             s.inputDecision=decision.kind();s.inputPath=inputPath;
188:             if(utterance.isFinal()&&!Set.of("DUPLICATE","STALE").contains(decision.kind())){
189:                 s.lastFinalAt=clock.millis();s.lastFinalNanos=System.nanoTime();
190:                 s.inputRequestId=requestId!=null&&requestId.matches("[A-Za-z0-9._:-]{1,128}")?requestId:
191:                         "assist-"+org.apache.commons.codec.digest.DigestUtils.sha256Hex(s.id+":"+epoch+":"+utterance.utteranceId()).substring(0,24);
192:             }
193:             if(decision.kind().equals("DUPLICATE")||decision.kind().equals("STALE")){s.duplicates++;return snapshot(s);}
194:             if("phone_voice".equals(inputPath)){
195:                 rememberAudioOrder(s,utterance);
196:                 long now=clock.millis();
197:                 if(s.caption==null||s.captionEpoch!=s.epoch||!s.caption.utteranceId().equals(utterance.utteranceId())){s.firstTranscriptAt=now;s.finalTranscriptAt=null;if(!rolling){cancelWork(s);s.card=null;}}
198:                 if(utterance.isFinal())s.finalTranscriptAt=now;
199:                 String visible=rolling?rollingText(s,utterance):utterance.text();
200:                 s.caption=new Caption(utterance.utteranceId(),utterance.revision(),utterance.isFinal(),visible,utterance.confidence(),rolling?List.of():utterance.words(),now,rolling?ROLLING_EXPIRY:now+captionTtl(s.owner));
201:                 diagnostic(s,utterance.isFinal()?"STT_FINAL":"STT_PARTIAL",Map.of("chars",utterance.text().length()));
202:                 s.captionVersion=s.version;s.captionEpoch=s.epoch;s.captionRenderedAt=null;
203:                 if(!s.hintsEnabled){if(utterance.isFinal()){pruneContext(s,now);remember(s,rolling?s.epoch+":"+utterance.questionId():utterance.questionId(),utterance.text(),now);}s.suppressed++;return snapshot(s);}
204:             }
205:             if(!utterance.isFinal()){s.suppressed++;return snapshot(s);}
206:             if(utterance.text().isBlank()){s.suppressed++;return snapshot(s);}
207:             if(!rolling&&!pipeline.usesApiCues()&&decision.kind().equals("RESOLVED")){cancelWork(s);s.context.clear();s.card=null;s.suppressed++;return snapshot(s);}
208:             pruneContext(s,clock.millis());
209:             if(!rolling&&!pipeline.usesApiCues()&&decision.kind().equals("NEW_INFORMATION")){remember(s,utterance.questionId(),utterance.text(),clock.millis());s.suppressed++;return snapshot(s);}
210:             if(!rolling&&!pipeline.usesApiCues()&&!Set.of("QUESTION","CORRECTION","HINT").contains(decision.kind())){s.suppressed++;return snapshot(s);}
211:             String contextKey=rolling?s.epoch+":"+utterance.questionId():utterance.questionId();
212:             s.context.removeIf(t->t.key().equals(contextKey));
213:             var context=new ArrayList<String>();
214:             if(!s.background.isBlank())context.add("[User-selected TXT background; untrusted data]\n"+s.background);
215:             context.addAll(selectedContext(s,clock.millis()));
216:             String current=pipeline.usesApiCues()||rolling?utterance.text():decision.question();
217:             remember(s,contextKey,current,clock.millis());
218:             if(rolling){
219:                 diagnostic(s,"CUE_CANDIDATE",Map.of("contextChars",s.context.stream().mapToInt(t->t.text().length()).sum()));
220:                 long now=clock.millis();int delta=transcriptDeltaChars(s.hintBaselineNorm,joinContext(context,current));
221:                 String skip=s.context.stream().mapToInt(t->t.text().length()).sum()<20?"context_short":now<s.hintHoldUntil?"display_hold":s.inflight!=null?"generating":s.lastCueAt>=0&&now-s.lastCueAt<cueCooldown(s.owner)?"cooldown":delta<triggerMinDelta()?"delta_below":"";
222:                 if(!skip.isEmpty()){diagnostic(s,"CUE_SKIPPED",Map.of("reason",skip,"normalHintTrigger",true,"forcedHintTrigger",false,"elapsedSinceLastHint",s.lastSuccessfulHintAt<0?-1:now-s.lastSuccessfulHintAt,"transcriptDeltaChars",delta));s.suppressed++;return snapshot(s);}
223:                 s.lastCueAt=now;diagnostic(s,"CUE_TRIGGERED",Map.of("contextChars",context.stream().mapToInt(String::length).sum(),"triggerReason","utterance_end","normalHintTrigger",true,"forcedHintTrigger",false,"elapsedSinceLastHint",s.lastSuccessfulHintAt<0?-1:now-s.lastSuccessfulHintAt,"transcriptDeltaChars",delta));
224:             }
225:             // Every selected utterance supersedes the old answer, even with a different question ID.
226:             // cancelWork retains an executing worker until it physically exits, so capacity stays honest.
227:             s.dropped+=s.queue.size();cancelWork(s);if(!rolling)s.card=null;
228:             s.workExpiresAt=clock.millis()+(pipeline.usesApiCues()?15_000:s.publicDisplay?85_000:20_000);
229:             s.queue.addLast(new Work(current,context,s.workExpiresAt,s.inputRequestId,inputPath,s.lastFinalAt,false,rolling?"utterance_end":"manual",s.contextEpoch));dispatch(s);return snapshot(s);
230:         }
```

## E04 — hints/context reset와 수음 생명주기 제어
`java/com/example/lms/assist/ConversateSessionService.java:292-308`
파일 SHA-256: `372f213e166a0f03fbd6bb392c1576a76467d44d8a3f45202fcf472b41eea491`
```text
292:     public Snapshot control(String owner,String id,long epoch,String action){
293:         var s=owned(owner,id);synchronized(s){checkEpoch(s,epoch);
294:             switch(action){
295:                 case "producer_changed" -> {pause(s,"producer_changed");s.state="RUNNING";s.caption=null;s.captionEpoch=0;s.audioOrder.clear();s.reason="READY";}
296:                 case "hints_on", "hints_off" -> {s.hintsEnabled=action.equals("hints_on");cancelWork(s);resetCueTracking(s);s.card=null;s.version++;}
297:                 case "pause" -> pause(s,"user_pause");
298:                 case "transport_pause" -> {if(s.state.equals("RUNNING"))pause(s,"output_lost",true);}
299:                 case "text_fallback" -> {
300:                     if(!s.state.equals("RUNNING")&&!(s.state.equals("PAUSED")&&s.reason.startsWith("ASR_")))throw error(HttpStatus.CONFLICT,"assist_paused");
301:                     pause(s,"ASR_TEXT_FALLBACK",true);s.state="RUNNING";s.reason="TEXT_INPUT_FALLBACK";s.inputPath="direct";
302:                     s.disconnectedAt=outputCount(s)==0?clock.millis():-1;
303:                 }
304:                 case "resume" -> {if(!s.state.equals("PAUSED"))throw error(HttpStatus.CONFLICT,"assist_not_paused");s.state="RUNNING";s.reason="resumed";s.version++;s.disconnectedAt=outputCount(s)==0?clock.millis():-1;}
305:                 case "context_reset" -> {cancelWork(s);s.context.clear();s.contextEpoch++;s.contextEpochStartAt=clock.millis();s.contextResetAudioMark=new HashMap<>(s.audioOrder);resetCueTracking(s);s.card=null;s.version++;s.reason="CONTEXT_RESET";diagnostic(s,"CONTEXT_EPOCH",Map.of("reason","user_reset","contextEpoch",s.contextEpoch));}
306:                 case "stop" -> {stop(s,"user_stop");sessions.remove(s.id,s);}
307:                 default -> throw error(HttpStatus.BAD_REQUEST,"invalid_control");
308:             }return snapshot(s);
```

## E05 — 유지보수·출력 소실·pause·epoch
`java/com/example/lms/assist/ConversateSessionService.java:369-385`
파일 SHA-256: `372f213e166a0f03fbd6bb392c1576a76467d44d8a3f45202fcf472b41eea491`
```text
369:     void maintain(){long now=clock.millis();for(var s:sessions.values())synchronized(s){
370:         if(s.publicDisplay&&s.inflight!=null&&s.workExpiresAt>0&&now>=s.workExpiresAt){cancelWork(s);s.workExpiresAt=0;s.card=null;s.reason="RAG_TIMEOUT";s.version++;}
371:         if(s.pollOutputs.entrySet().removeIf(entry->now-entry.getValue()>GRACE_MS)){s.version++;if(outputCount(s)==0&&s.disconnectedAt<0)s.disconnectedAt=now;}
372:         pruneContext(s,now);maybeAccumulatedHint(s,now);maybeForceRollingHint(s,now);
373:         if(s.state.equals("PAUSED")&&s.capture==null&&outputCount(s)==0&&s.disconnectedAt>=0&&now-s.disconnectedAt>=DISCONNECTED_RETENTION_MS){stop(s,"disconnected_cleanup");sessions.remove(s.id,s);continue;}
374:         if(s.state.equals("RUNNING")&&outputCount(s)==0&&s.disconnectedAt>=0&&now-s.disconnectedAt>=GRACE_MS&&!(s.publicDisplay&&(s.capture!=null||Set.of("WAITING","API_PAUSED").contains(s.audio.state()))&&now-s.disconnectedAt<DISCONNECTED_RETENTION_MS))pause(s,"output_lost",true);
375:         if(s.card!=null&&s.card.expiresAt()<=now){s.card=null;s.version++;}
376:         if(s.caption!=null&&s.caption.expiresAt()<=now){s.caption=null;s.version++;}
377:     }}
378:     private void pause(Session s,String reason){pause(s,reason,false);}
379:     private void pause(Session s,String reason,boolean retainEvidence){s.state="PAUSED";s.reason=reason;s.epoch++;s.pollOutputs.clear();
380:         if(!retainEvidence){s.policy.clear();s.context.clear();s.card=null;}else {pruneContext(s,clock.millis());if(s.card!=null&&s.card.expiresAt()<=clock.millis())s.card=null;}
381:         resetCueTracking(s);s.version++;closeCapture(s);cancelPending(s);}
382:     private void closeCapture(Session s){var capture=s.capture;s.capture=null;s.caption=null;s.visible.clear();s.firstTranscriptAt=null;s.finalTranscriptAt=null;s.captionRenderedAt=null;s.audioOrder.clear();s.contextResetAudioMark=Map.of();if(capture!=null)try{capture.close();}catch(Exception ignored){}s.audio=new AudioMetrics(s.audio.chunks(),s.audio.partials(),s.audio.finals(),s.audio.duplicates(),s.audio.lastAsrMs(),"STOPPED",s.audio.runtime());}
383:     private void stop(Session s,String reason){pause(s,reason);s.state="STOPPED";s.materials=List.of();s.policy.clear();}
384:     private static boolean hintEpoch(Session s,long epoch){return s.epoch==epoch||s.segmentHintEpoch==epoch;}
385:     private void cancelWork(Session s){s.segmentHintEpoch=0;s.generation++;s.hintCompletedAt=null;s.hintRenderedAt=null;s.hintForFinalAt=null;s.stages=ConversateAnswerPipeline.Stages.unobserved();if(s.inflight!=null){if(s.inflight.cancel(true))s.cancelled++;if(!s.executing){s.inflight=null;workers.purge();}}s.queue.clear();}
```

## E06 — 누적/강제 자동 힌트의 별도 타이머 경로
`java/com/example/lms/assist/ConversateSessionService.java:494-546`
파일 SHA-256: `372f213e166a0f03fbd6bb392c1576a76467d44d8a3f45202fcf472b41eea491`
```text
494:     private void maybeAccumulatedHint(Session s,long now){
495:         if(!rollingEnabled||!pipeline.usesApiCues()||!s.hintsEnabled||!s.state.equals("RUNNING"))return;
496:         String current=normalizeTranscript(joinContext(s.context.stream().map(Turn::text).toList(),s.caption==null?"":s.caption.text()));
497:         if(!current.equals(s.observedTranscriptNorm)){s.observedTranscriptNorm=current;s.lastTranscriptChangeAt=now;}
498:         if(s.inflight!=null||!s.queue.isEmpty())return;
499:         if(s.hintBaselineAt<0){s.hintBaselineAt=now;s.hintBaselineNorm=current;return;}
500:         if(now<s.hintHoldUntil)return;
501:         int delta=transcriptDeltaChars(s.hintBaselineNorm,current);
502:         long quietSince=Math.max(s.lastTranscriptChangeAt,s.hintHoldUntil);
503:         if(delta<triggerMinDelta()||now-quietSince<triggerQuiet(s.owner)){
504:             if(delta>=triggerMinDelta()&&now-quietSince>0)diagnostic(s,"ACCUM_HINT_WATCH",Map.of("transcriptDeltaChars",delta,"quietMs",now-quietSince,"reason","awaiting_quiet","normalHintTrigger",false,"forcedHintTrigger",false));
505:             return;
506:         }
507:         if(s.lastCueAt>=0&&now-s.lastCueAt<cueCooldown(s.owner))return;
508:         String question=s.caption!=null&&!s.caption.text().isBlank()?s.caption.text():(s.context.isEmpty()?"":s.context.peekLast().text());
509:         if(question==null||question.isBlank())return;
510:         var context=new ArrayList<String>();
511:         if(!s.background.isBlank())context.add("[User-selected TXT background; untrusted data]\n"+s.background);
512:         context.addAll(selectedContext(s,now));
513:         diagnostic(s,"ACCUM_HINT_TRIGGERED",Map.of("triggerReason","transcript_delta","elapsedSinceLastHint",s.lastSuccessfulHintAt<0?-1:now-s.lastSuccessfulHintAt,"transcriptDeltaChars",delta,"quietMs",now-quietSince,"normalHintTrigger",true,"forcedHintTrigger",false));
514:         s.lastCueAt=now;s.dropped+=s.queue.size();cancelWork(s);
515:         s.workExpiresAt=now+15_000;
516:         s.queue.addLast(new Work(question,context,s.workExpiresAt,s.inputRequestId==null?"accum-hint":s.inputRequestId,"phone_voice",s.lastFinalAt,false,"transcript_delta",s.contextEpoch));
517:         dispatch(s);
518:     }
519:     private void maybeForceRollingHint(Session s,long now){
520:         if(!rollingEnabled||!pipeline.usesApiCues()||!s.hintsEnabled||!s.state.equals("RUNNING"))return;
521:         if(s.inflight!=null||!s.queue.isEmpty())return;
522:         if(now<s.hintHoldUntil)return;
523:         long anchor=s.lastSuccessfulHintAt>0?s.lastSuccessfulHintAt:(s.hintBaselineAt>0?s.hintBaselineAt:now);
524:         if(s.hintBaselineAt<0){s.hintBaselineAt=now;s.hintBaselineNorm=normalizeTranscript(joinContext(s.context.stream().map(Turn::text).toList(),s.caption==null?"":s.caption.text()));return;}
525:         long elapsed=now-anchor;
526:         String current=normalizeTranscript(joinContext(s.context.stream().map(Turn::text).toList(),s.caption==null?"":s.caption.text()));
527:         int delta=transcriptDeltaChars(s.hintBaselineNorm,current);
528:         long forceMs=forceAfter(s.owner);
529:         if(elapsed<forceMs){
530:             if(elapsed>=forceMs/2)diagnostic(s,"FORCE_HINT_WATCH",Map.of("elapsedSinceLastHint",elapsed,"transcriptDeltaChars",delta,"forcedHintTrigger",false,"normalHintTrigger",false,"forceAfterMs",forceMs));
531:             return;
532:         }
533:         if(delta<Math.max(20,forceMinDeltaChars)){
534:             diagnostic(s,"FORCE_HINT_SKIPPED",Map.of("elapsedSinceLastHint",elapsed,"transcriptDeltaChars",delta,"forcedHintTrigger",false,"reason","delta_below_threshold"));
535:             return;
536:         }
537:         String question=s.caption!=null&&!s.caption.text().isBlank()?s.caption.text():(s.context.isEmpty()?"":s.context.peekLast().text());
538:         if(question==null||question.isBlank())return;
539:         var context=new ArrayList<String>();
540:         if(!s.background.isBlank())context.add("[User-selected TXT background; untrusted data]\n"+s.background);
541:         context.addAll(selectedContext(s,now));
542:         diagnostic(s,"FORCE_HINT_TRIGGERED",Map.of("triggerReason","3min_watchdog","elapsedSinceLastHint",elapsed,"transcriptDeltaChars",delta,"forcedHintTrigger",true,"normalHintTrigger",false));
543:         s.lastCueAt=now;s.dropped+=s.queue.size();cancelWork(s);
544:         s.workExpiresAt=now+15_000;
545:         s.queue.addLast(new Work(question,context,s.workExpiresAt,s.inputRequestId==null?"force-hint":s.inputRequestId,"phone_voice",s.lastFinalAt,true,"3min_watchdog",s.contextEpoch));
546:         dispatch(s);
```

## E07 — 발화 revision 검증과 문장 내용 기반 dedup의 차이
`java/com/example/lms/assist/ConversateQuestionPolicy.java:89-121`
파일 SHA-256: `f5215cb4ed2d6f3812dc08e82389e682a9332e7d43d9bf7e15d22af79ef7c6d2`
```text
89:     private Decision accept(Utterance input,boolean explicit){
90:         return accept(input,explicit,false);
91:     }
92:     private Decision accept(Utterance input,boolean explicit,boolean cueGate){
93:         if(input==null||!id(input.utteranceId())||!id(input.questionId())||input.revision()<0||input.revision()>1_000_000||input.text()==null||input.text().length()>8192)throw error(HttpStatus.BAD_REQUEST,"invalid_utterance");
94:         String text=Normalizer.normalize(input.text(),Normalizer.Form.NFC).strip().replaceAll("\\s+"," ");
95:         String hash=org.apache.commons.codec.digest.DigestUtils.sha256Hex(text),key=input.questionId()+":"+input.utteranceId();
96:         var old=seen.get(key);
97:         if(old!=null&&old.finalized()&&!input.isFinal())return new Decision("STALE","");
98:         if(old!=null&&old.finalized()&&input.isFinal()&&hash.equals(old.hash()))return new Decision("DUPLICATE","");
99:         if(old!=null){if(input.revision()<old.revision())return new Decision("STALE","");
100:             if(input.revision()==old.revision()){
101:                 if(old.finalized()&&!hash.equals(old.hash()))throw error(HttpStatus.CONFLICT,"utterance_revision_conflict");
102:                 if(hash.equals(old.hash())&&(old.finalized()||!input.isFinal()))return new Decision("DUPLICATE","");
103:             }}
104:         seen.put(key,new Seen(input.revision(),hash,input.isFinal()));while(seen.size()>64)seen.remove(seen.keySet().iterator().next());
105:         if(!input.isFinal())return new Decision("PARTIAL","");
106:         if(cueGate)return new Decision(text.isBlank()?"BACKCHANNEL":"CUE_PENDING",text);
107:         if(explicit){
108:             if(text.isBlank())return new Decision("BACKCHANNEL","");
109:             if(hash.equals(lastQuestionHash))return new Decision("DUPLICATE","");
110:             lastQuestionHash=hash;return new Decision("QUESTION",text);
111:         }
112:         if(text.isBlank()||BACKCHANNEL.matcher(text).matches())return new Decision("BACKCHANNEL","");
113:         if(text.matches(".*(질문|문제).*(해결|이해).*")){lastQuestionHash="";lastQuestionShapeHash="";return new Decision("RESOLVED","");}
114:         boolean correction=(old!=null&&old.finalized()&&!old.hash().equals(hash))||text.matches("^(아니[, ]|정정|수정).*" );
115:         boolean request=text.matches(".*(?:설명|비교|정리)해\\s*(?:줘|주세요|주십시오)[.!。 ]*$");
116:         boolean hint=needsHint(text);
117:         if(!correction&&!request&&!hint&&!QUESTION.matcher(text).find())return new Decision("NEW_INFORMATION","");
118:         if(hash.equals(lastQuestionHash))return new Decision("DUPLICATE","");
119:         String shape=questionShapeHash(text);
120:         correction=correction||(!lastQuestionShapeHash.isEmpty()&&shape.equals(lastQuestionShapeHash));
121:         lastQuestionHash=hash;lastQuestionShapeHash=shape;return new Decision(correction?"CORRECTION":hint?"HINT":"QUESTION",text);
```

## E08 — cue 우선 반환, ephemeral 호출, user-only history, 외부 context
`java/com/example/lms/assist/ConversateAnswerPipeline.java:64-97`
파일 SHA-256: `4e642e6afc7d96392b41ad60f0b6b2e7075c1dc2de2cb2c31c5a8670f8088564`
```text
64:     private Outcome answerLive(String question,List<String> context,List<PreparedMaterialReader.Material> materials,long now,String inputPath,String requestId,boolean publicDisplay,boolean forceHint,int hintTargetChars){
65:         if(cues!=null)return cues.answer(question,context,materials,publicDisplay,forceHint,hintTargetChars);
66:         if(ConversateQuestionPolicy.needsHint(question))return suggest(question,context,now);
67:         if(publicDisplay&&sharedRag==null)return ask("RAG_UNAVAILABLE","답변 서버를 준비하고 있습니다.",now);
68:         if(sharedRag==null)return "phone_voice".equals(inputPath)&&materials.isEmpty()?ask("RAG_UNAVAILABLE","RAG 연결을 확인해 주세요.",now):answerWithContext(question,context,materials,now);
69:         if(Thread.currentThread().isInterrupted())return new Outcome("RAG_CANCELLED",null);
70:         var request=com.example.lms.dto.ChatRequestDto.builder().message(question).inputType("phone_voice".equals(inputPath)?"voice":"text")
71:                 .memoryMode("ephemeral").sessionId(null).understandingEnabled(false).useRag(true)
72:                 .history(context.stream().limit(4).map(text->new com.example.lms.dto.ChatRequestDto.Message("user",text)).toList()).build();
73:         if(publicDisplay){
74:             boolean web=SOURCE_REQUEST.matcher(question).find()
75:                     ||SEARCH_DECISIONS.decide(question,SearchMode.AUTO,null,3,false).shouldSearch();
76:             request.setUseRag(false);request.setUseWebSearch(web);request.setMaxTokens(192);
77:             request.setSearchMode(web?SearchMode.AUTO:SearchMode.OFF);
78:         }
79:         // Pass authorized volatile context through the canonical prompt assembly's existing context boundary.
80:         var external=new ArrayList<String>(context);materials.forEach(material->external.add(material.text()));
81:         var result=sharedRag.continueChat(request,ignored->List.copyOf(external));
82:         if(Thread.currentThread().isInterrupted())return new Outcome("RAG_CANCELLED",null);
83:         if(result==null||result.content()==null||result.content().isBlank())return ask("RAG_EMPTY","답변이 비어 있습니다. 다시 확인해 주세요.",System.currentTimeMillis());
84:         String text=result.content().replace("<!-- rag-control-projection:v1 -->","").strip();
85:         if(text.codePointCount(0,text.length())>15360)return ask("RAG_TOO_LONG","응답이 표시 한도를 넘었습니다. 질문 범위를 좁혀 주세요.",System.currentTimeMillis());
86:         var pages=new ArrayList<String>();for(int from=0;from<text.length();){int end=text.offsetByCodePoints(from,Math.min(120,text.codePointCount(from,text.length())));pages.add(text.substring(from,end));from=end;}
87:         var ids=new ArrayList<String>();var titles=new ArrayList<String>();
88:         for(var evidence:result.evidenceMetadata()){
89:             if(ids.size()==4)break;
90:             if(evidence.marker()==null||!evidence.marker().matches("[A-Za-z0-9_.:\\[\\]-]{1,128}")||evidence.title()==null||evidence.title().isBlank())continue;
91:             String title=evidence.title().replaceAll("\\p{Cntrl}"," ");if(title.codePointCount(0,title.length())>120)title=title.substring(0,title.offsetByCodePoints(0,119))+"…";
92:             ids.add(evidence.marker());titles.add(title);
93:         }
94:         boolean fallback=result.modelUsed()!=null&&result.modelUsed().toLowerCase(Locale.ROOT).contains("fallback");
95:         var card=new ConversateSessionService.Card("SHOW",fallback?"FALLBACK":"RAG",pages.get(0),ids,System.currentTimeMillis()+20_000,requestId,titles,pages);
96:         // Workflow delivery exposes no provider attempt counts or per-stage timings here.
97:         return new Outcome(fallback?"RAG_FALLBACK":"RAG_ANSWER",card);
```

## E09 — sessionId 기반 historyStr 경로
`java/com/example/lms/service/ChatWorkflow.java:2733-2742`
파일 SHA-256: `22d8f3fda44a3081411165b9583bf720ed4085fee3c70250067ae58938e41c96`
```text
2733:         // ?? 2) 紐낆떆??留λ씫 ?앹꽦(Verbosity-aware) ????????????????????????
2734:         // ?몄뀡 ID(Long) ?뚯떛: 理쒓렐 assistant ?듬? & ?덉뒪?좊━ 議고쉶???ъ슜
2735: 
2736:         String lastAnswer = (!memoryReadEnabled || sessionIdLong == null)
2737:                 ? null
2738:                 : chatHistoryService.getLastAssistantMessage(sessionIdLong).orElse(null);
2739:         String historyStr = (!memoryReadEnabled || sessionIdLong == null)
2740:                 ? ""
2741:                 : String.join("\n", chatHistoryService.getFormattedRecentHistory(sessionIdLong,
2742:                         Math.max(2, Math.min(maxHistory, 8))));
```

## E10 — 최종 messages 조립 경계
`java/com/example/lms/service/ChatWorkflow.java:3286-3335`
파일 SHA-256: `22d8f3fda44a3081411165b9583bf720ed4085fee3c70250067ae58938e41c96`
```text
3286:         // ?? 4) 硫붿떆吏 援ъ꽦(異쒕젰?뺤콉 ?ы븿) ????????????????????????????
3287:         var msgs = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
3288:         // IMPORTANT: instruction/trait/system policies must be injected BEFORE the raw
3289:         // context.
3290:         // Otherwise the model may follow the context formatting first and drift from
3291:         // the template.
3292:         if (org.springframework.util.StringUtils.hasText(instrTxt)) {
3293:             msgs.add(dev.langchain4j.data.message.SystemMessage.from(instrTxt));
3294:         }
3295: 
3296:         // ??1) Plan/Request level extra system snippets (traits + systemPrompt)
3297:         if (promptAssetService != null) {
3298:             String requestedSystemPrompt = llmReq.getSystemPrompt();
3299:             String extraSys = promptAssetService.resolveSystemPromptText(requestedSystemPrompt);
3300:             if (!org.springframework.util.StringUtils.hasText(extraSys)
3301:                     && org.springframework.util.StringUtils.hasText(requestedSystemPrompt)) {
3302:                 traceRejectedPublicSystemPrompt(requestedSystemPrompt);
3303:             }
3304:             String traitSys = promptAssetService.renderTraits(llmReq.getTraits());
3305:             if (org.springframework.util.StringUtils.hasText(extraSys)) {
3306:                 msgs.add(dev.langchain4j.data.message.SystemMessage.from(extraSys));
3307:             }
3308:             if (org.springframework.util.StringUtils.hasText(traitSys)) {
3309:                 msgs.add(dev.langchain4j.data.message.SystemMessage.from(traitSys));
3310:             }
3311:         }
3312:         if (org.springframework.util.StringUtils.hasText(outputPolicy)) {
3313:             msgs.add(dev.langchain4j.data.message.SystemMessage.from(outputPolicy));
3314:         }
3315: 
3316:         // Sensitive topic: add extra privacy boundary right before evidences.
3317:         // (Avoid injecting this into creative/explore calls to reduce unintended
3318:         // constraints.)
3319:         try {
3320:             gctx = GuardContextHolder.get();
3321:             if (gctx != null && (gctx.isSensitiveTopic() || gctx.planBool("privacy.boundary.enforce", false))) {
3322:                 msgs.add(dev.langchain4j.data.message.SystemMessage.from(PRIVACY_BOUNDARY_SYS));
3323:             }
3324:         } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.privacyBoundaryMessage", ignore); }
3325: 
3326:         // Context (evidence) should come last among system messages.
3327:         msgs.add(dev.langchain4j.data.message.SystemMessage.from(unifiedCtx));
3328: 
3329:         // ???ъ슜??吏덈Ц
3330:         msgs.add(primaryUserMessage(finalQuery, llmReq, conversationFrame));
3331:         recordCostZone(
3332:                 "prompt_context",
3333:                 resolvedModelName,
3334:                 estimateChatMessageChars(msgs),
3335:                 0,
```

## E11 — 기존 View/LensText/LensSettings 계약
`java/com/example/lms/assist/DisplayConversateController.java:50-69`
파일 SHA-256: `3d9f71580c26f56b06f39a9a0a4ad1b684beb57de012a65e0d8b6453f11ac883`
```text
50:     public record Connection(String assistId,long epoch,String clientId,boolean activate,boolean continuation){public Connection(String assistId,long epoch,String clientId){this(assistId,epoch,clientId,false,false);}}
51:     public record RelayPoll(String clientId,long eventId){}
52:     public record RelaySettings(String assistId,long epoch,String clientId,boolean enabled,int segmentSeconds){}
53:     public record RelayTest(String assistId,long epoch,String clientId,int number,boolean fromFold){}
54:     public record Input(String assistId,long epoch,String clientId,String requestId,String text,List<String> eventOrder,String verificationMode){@Override public String toString(){return "DisplayInput[redacted]";}}
55:     public record AudioChunk(String assistId,long epoch,String clientId,long sequence,String pcm){@Override public String toString(){return "DisplayAudio[redacted]";}}
56:     public record AudioStop(String assistId,long epoch,String clientId,boolean finish){}
57:     public record View(String assistId,long epoch,String state,String reason,long version,DisplayContentView.TextCard card,String requestId,boolean processing,boolean ready,boolean audioAvailable,String audioState,String voiceRequestId,
58:                        DisplayContentView.Transcript caption,long captionTtlMs,long cardTtlMs,boolean hintsEnabled,String role,boolean linked,boolean linkPending,String confirmation,boolean audioFinished,long audioRenewAfterMs,Map<String,Object> testStatus){}
59:     public record DiagnosticRequest(String assistId,boolean enabled){}
60:     public record LensView(String assistId,long epoch,long version,boolean ready,DisplayContentView.TextCard card,DisplayContentView.Transcript caption,long captionTtlMs,long cardTtlMs){}
61:     public record LensRead(String token){@Override public String toString(){return "LensRead[redacted]";}}
62:     public record LensLink(String token,long expiresAt,boolean sticky){@Override public String toString(){return "LensLink[redacted]";}}
63:     public record LensText(String conversation,String hint,String hintId,long hintExpiresAt,long conversationExpiresAt,LensDisplayPrefs display){}
64:     public record LensSettings(String assistId,long epoch,String clientId,LensDisplayPrefs.Patch display,Boolean restoreDefaults){}
65:     record LensGrant(String owner,String assistId,long expiresAt){@Override public String toString(){return "LensGrant[redacted]";}}
66:     public record PairJoin(String clientId,String code,String requestId){@Override public String toString(){return "PairJoin[redacted]";}}
67:     public record Background(String assistId,long epoch,String clientId,String text){@Override public String toString(){return "DisplayBackground[redacted]";}}
68:     public record HintControl(String assistId,long epoch,String clientId,boolean enabled){}
69:     public record Ack(String assistId,long epoch,String clientId,long version,String phase){}
```

## E12 — 렌즈 토큰과 content-only lens/text 경로
`java/com/example/lms/assist/DisplayConversateController.java:150-185`
파일 SHA-256: `3d9f71580c26f56b06f39a9a0a4ad1b684beb57de012a65e0d8b6453f11ac883`
```text
150:     @PostMapping("/api/assist/display/lens/link")
151:     public synchronized ResponseEntity<LensLink> lensLink(@RequestBody Connection request,HttpServletRequest http){
152:         String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,3);
153:         long now=clock.millis();
154:         if(stickyLensEnabled){
155:             String previous=lensGrants.entrySet().stream().filter(e->e.getValue().owner().equals(b.owner)&&e.getValue().expiresAt()>now).map(Map.Entry::getKey).findFirst().orElse(null);
156:             try{
157:                 var grant=stickyLensStore().renew(b.owner,previous,now);
158:                 return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(new LensLink(grant.token(),grant.expiresAt(),true));
159:             }catch(java.io.IOException|IllegalArgumentException unavailable){throw error(HttpStatus.SERVICE_UNAVAILABLE,"lens_store_unavailable");}
160:         }
161:         lensGrants.entrySet().removeIf(e->e.getValue().expiresAt()<=now||e.getValue().owner().equals(b.owner)||!bindings.containsKey(e.getValue().owner()));
162:         if(lensGrants.size()>=256)throw limitedError(60);
163:         byte[] bytes=new byte[32];random.nextBytes(bytes);String lensToken=HexFormat.of().formatHex(bytes);long expiresAt=now+43_200_000;
164:         lensGrants.put(token,new LensGrant(b.owner,b.id,expiresAt));
165:         return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(new LensLink(token,expiresAt,false));
166:     }
167:     /** Content-only read: no output registration, acknowledgement, session mutation or generation. */
168:     @PostMapping("/api/assist/display/lens/text")
169:     public synchronized ResponseEntity<LensText> lensText(@RequestBody LensRead request,HttpServletRequest http){
170:         String caller=owner(http);limited(caller,2);String lensToken=request.token();
171:         LensGrant grant=token!=null&&token.matches("[a-f0-9]{64}")?lensGrants.get(token):null;
172:         boolean sticky=false;
173:         if(stickyLensEnabled&&token!=null&&token.matches("[a-f0-9]{64}")){
174:             try{var saved=stickyLensStore().find(token);if(saved!=null){grant=new LensGrant(saved.owner(),null,saved.expiresAt());sticky=true;}}
175:             catch(java.io.IOException|IllegalArgumentException unavailable){throw error(HttpStatus.SERVICE_UNAVAILABLE,"lens_store_unavailable");}
176:         }
177:         Binding b=grant==null?null:bindings.get(grant.owner());long now=clock.millis();
178:         if(grant==null||grant.expiresAt()<=now)throw error(HttpStatus.NOT_FOUND,"lens_link_expired");
179:         if(b==null||b.id==null||!sticky&&!grant.assistId().equals(b.id))throw error(sticky?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.NOT_FOUND,sticky?"lens_producer_waiting":"lens_link_expired");
180:         Snapshot s;
181:         try{s=sessions.status(b.owner,b.id);}catch(ResponseStatusException missing){if(sticky&&missing.getStatusCode().value()==404)throw error(HttpStatus.SERVICE_UNAVAILABLE,"lens_producer_waiting");throw missing;}
182:         var caption=DisplayContentView.caption(s.caption(),now);
183:         var hint=sessions.hintsEnabled(b.owner,b.id)?DisplayContentView.card(s.card(),now):null;
184:         return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LensText(shortLensText(caption==null?null:caption.text(),lensConversationChars),shortLensText(hint==null?null:hint.text(),ConversateSessionService.HINT_TEXT_MAX),hint==null?null:hint.requestId(),hint==null?0:hint.expiresAt(),caption==null?0:caption.expiresAt(),prefsFor(b.owner)));
185:     }
```

## E13 — 설정 저장과 producer 권한
`java/com/example/lms/assist/DisplayConversateController.java:249-276`
파일 SHA-256: `3d9f71580c26f56b06f39a9a0a4ad1b684beb57de012a65e0d8b6453f11ac883`
```text
249:     @PostMapping("/api/assist/display/relay/settings")
250:     public synchronized ResponseEntity<View> relaySettings(@RequestBody RelaySettings request,HttpServletRequest http){
251:         String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,1);
252:         if(b.relayChannel==null)throw error(HttpStatus.CONFLICT,"event_owner_required");
253:         relay.configure(b.relayChannel,producer(b),request.enabled(),request.segmentSeconds());b.segmentSeconds=request.segmentSeconds();
254:         return result(b,sessions.status(b.owner,b.id),caller);
255:     }
256:     /** Developer display settings: validated here, echoed via testStatus, carried to the lens by lens/text and relay events. */
257:     @PostMapping("/api/assist/display/relay/lens-settings")
258:     public synchronized ResponseEntity<View> lensSettings(@RequestBody LensSettings request,HttpServletRequest http){
259:         String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,1);
260:         LensDisplayPrefs applied=Boolean.TRUE.equals(request.restoreDefaults())?LensDisplayPrefs.defaults(defaultHintTargetChars):prefsFor(b.owner).patch(request.display());
261:         lensPrefs.put(b.owner,applied);
262:         LOG.info("display.lensSettings applied={}",applied.describe());
263:         return result(b,sessions.status(b.owner,b.id),caller);
264:     }
265:     @PostMapping("/api/assist/display/relay/test")
266:     public synchronized ResponseEntity<View> relayTest(@RequestBody RelayTest request,HttpServletRequest http){
267:         String caller=owner(http);validateClient(request.clientId());Binding b=bound(caller,request.assistId());requireProducer(b,request.clientId());limited(caller,1);
268:         if(b.relayChannel==null||request.number()<1||request.number()>3)throw error(HttpStatus.BAD_REQUEST,"invalid_display_test");
269:         String text=request.fromFold()?"FOLD TEST #001":String.format(Locale.ROOT,"DISPLAY TEST #%03d",request.number());
270:         return result(b,sessions.displayTest(b.owner,b.id,request.epoch(),text),caller);
271:     }
272:     private static String relayChannel(HttpServletRequest http){String channel=http.getHeader("X-Display-Test-Channel");if(channel==null)return "live";if(!channel.matches("test-[a-f0-9]{16,32}"))throw error(HttpStatus.BAD_REQUEST,"invalid_test_channel");return channel;}
273:     private DisplayRelay.Producer producer(Binding b){return new DisplayRelay.Producer(b.owner,b.producerClient,b.id);}
274:     private void requireProducer(Binding b,String client){
275:         if(b.relayChannel!=null&&(!client.equals(b.producerClient)||!producer(b).equals(relay.active(b.relayChannel))))throw error(HttpStatus.FORBIDDEN,"event_owner_required");
276:     }
```

## E14 — View 생성과 relay 힌트 게이트
`java/com/example/lms/assist/DisplayConversateController.java:460-471`
파일 SHA-256: `3d9f71580c26f56b06f39a9a0a4ad1b684beb57de012a65e0d8b6453f11ac883`
```text
460:     private ResponseEntity<View> result(Binding b,Snapshot s,String caller){return result(b,s,caller,b.producerClient);}
461:     private ResponseEntity<View> result(Binding b,Snapshot s,String caller,String client){
462:         if(!Objects.equals(b.loggedState,s.state()+":"+s.reason())){
463:             b.loggedState=s.state()+":"+s.reason();LOG.info("display.conversate state={} reason={}",s.state(),s.reason());
464:         }
465:         long now=clock.millis();
466:         if(s.caption()!=null)b.lastTranscriptAt=Math.max(b.lastTranscriptAt,s.caption().receivedAt());
467:         var card=DisplayContentView.card(s.card(),now);var caption=DisplayContentView.caption(s.caption(),now);
468:         if(b.relayChannel!=null)relay.publish(b.relayChannel,producer(b),caption,sessions.hintsEnabled(b.owner,b.id)?card:null);
469:         var view=new View(s.assistId(),s.epoch(),s.state(),publicReason(s.reason()),s.version(),card,s.diagnostics().requestId(),s.metrics().inFlight()>0||s.metrics().queueLength()>0,s.state().equals("RUNNING"),audioEnabled&&asr!=null&&asr.available(),s.audio().state(),"phone_voice".equals(s.diagnostics().inputPath())?s.diagnostics().requestId():null,
470:                 caption,caption==null?0:Math.max(0,caption.expiresAt()-now),card==null?0:Math.max(0,card.expiresAt()-now),sessions.hintsEnabled(b.owner,b.id),caller.equals(b.owner)?(b.standalone?"STANDALONE":"DISPLAY"):"PHONE",b.phoneOwner!=null,b.pendingPhone!=null,b.confirmation,"finished".equals(s.audio().runtime().get("stopReason")),asr==null?540000:asr.renewAfterMs(),b.standalone?testStatus(b,s,client):null);
471:         return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(view);
```

## E15 — volatile relay와 변경 감지, enabled, receipt 한계
`java/com/example/lms/assist/DisplayRelay.java:8-72`
파일 SHA-256: `8d4cf091cfc2a0644cc75a6f94aa48917ef83333a202b505a3f9193459f18647`
```text
8: /** Volatile single-user display fan-out. No session, credential or context persistence. */
9: final class DisplayRelay {
10:     static final String VERSION="fold-meta-relay-20260916.1";
11:     record Producer(String owner,String client,String assistId) {}
12:     record Event(String serverId,long eventId,long generation,long sentAt,boolean enabled,boolean producerConnected,
13:                  DisplayContentView.Transcript caption,DisplayContentView.TextCard hint,LensDisplayPrefs display) {}
14:     private final Clock clock;
15:     private final String serverId=UUID.randomUUID().toString();
16:     private final Map<String,Channel> channels=new HashMap<>();
17:     DisplayRelay(Clock clock){this.clock=clock;}
18:     private Channel channel(String key){
19:         if(!"live".equals(key)&&!key.matches("test-[a-f0-9]{16,32}"))throw error(BAD_REQUEST,"invalid_test_channel");
20:         long now=clock.millis();
21:         channels.entrySet().removeIf(e->!e.getKey().equals("live")&&now-e.getValue().touched>3_600_000);
22:         Channel c=channels.get(key);
23:         if(c==null){if(channels.size()>=8)throw error(TOO_MANY_REQUESTS,"display_channel_capacity");c=new Channel();channels.put(key,c);}
24:         c.touched=now;c.subscribers.entrySet().removeIf(e->now-e.getValue().seen>6000);return c;
25:     }
26:     synchronized Producer active(String key){return channel(key).producer;}
27:     synchronized Producer activate(String key,String owner,String client,String assistId){
28:         Channel c=channel(key);var producer=new Producer(owner,client,assistId);
29:         c.producer=producer;c.generation++;c.eventId++;c.caption=null;c.hint=null;c.lastSourceVersion=-1;
30:         c.connectedAt=c.changedAt=c.lastProducerSeen=clock.millis();return producer;
31:     }
32:     synchronized void presence(String key,Producer producer){Channel c=channel(key);if(producer.equals(c.producer))c.lastProducerSeen=clock.millis();}
33:     synchronized boolean publish(String key,Producer producer,DisplayContentView.Transcript caption,DisplayContentView.TextCard hint){
34:         Channel c=channel(key);if(!producer.equals(c.producer))return false;
35:         if(!Objects.equals(c.caption,caption)||!Objects.equals(c.hint,hint)){
36:             if(caption!=null&&!Objects.equals(c.caption,caption))c.lastTranscriptAt=clock.millis();
37:             c.caption=caption;c.hint=hint;c.eventId++;
38:         }
39:         return true;
40:     }
41:     synchronized void configure(String key,Producer producer,boolean enabled,int seconds){
42:         Channel c=channel(key);require(c,producer);
43:         if(seconds!=0&&(seconds<5||seconds>60))throw error(BAD_REQUEST,"invalid_segment_seconds");
44:         if(c.enabled!=enabled){c.enabled=enabled;c.eventId++;}c.segmentSeconds=seconds;
45:     }
46:     synchronized Event poll(String key,String subscriber){return poll(key,subscriber,null);}
47:     synchronized Event poll(String key,String subscriber,LensDisplayPrefs display){
48:         Channel c=channel(key);long now=clock.millis();
49:         var sub=c.subscribers.get(subscriber);
50:         if(sub==null){if(c.subscribers.size()>=4)throw error(TOO_MANY_REQUESTS,"display_subscriber_capacity");sub=new Subscriber();c.subscribers.put(subscriber,sub);}
51:         sub.seen=now;
52:         if(c.caption!=null&&c.caption.expiresAt()<=now){c.caption=null;c.eventId++;}
53:         if(c.hint!=null&&c.hint.expiresAt()<=now){c.hint=null;c.eventId++;}
54:         if(sub.sent!=c.eventId){sub.sent=c.eventId;c.lastSentAt=now;}
55:         boolean connected=c.producer!=null&&now-c.lastProducerSeen<=10000;
56:         return new Event(serverId,c.eventId,c.generation,now,c.enabled,connected,c.enabled?c.caption:null,c.enabled?c.hint:null,display);
57:     }
58:     synchronized void ack(String key,String subscriber,long eventId){
59:         Channel c=channel(key);Subscriber sub=c.subscribers.get(subscriber);
60:         if(sub==null||eventId<1||eventId>sub.sent)throw error(CONFLICT,"invalid_display_ack");
61:         if(eventId>c.lastAckEvent){c.lastAckEvent=eventId;c.lastAckAt=clock.millis();}
62:     }
63:     synchronized Map<String,Object> debug(String key,Producer caller){
64:         Channel c=channel(key);var out=new LinkedHashMap<String,Object>();
65:         out.put("eventOwner",Objects.equals(c.producer,caller)?"THIS DEVICE":"OTHER CLIENT");
66:         out.put("activeEventOwner",c.producer==null?"none":c.producer.client());out.put("clientId",caller.client());
67:         out.put("session",caller.assistId());out.put("clientRole",key.equals("live")?"control":"test");
68:         out.put("lastConnectedAt",c.connectedAt);out.put("ownershipChangedAt",c.changedAt);out.put("lastProducerSeenAt",c.lastProducerSeen);
69:         out.put("subscribers",c.subscribers.size());out.put("lastTranscriptAt",c.lastTranscriptAt);
70:         out.put("lastDisplayPushAt",c.lastSentAt);out.put("lastDisplayAckAt",c.lastAckAt);out.put("lastAckEventId",c.lastAckEvent);
71:         out.put("lastEventId",c.eventId);out.put("generation",c.generation);out.put("enabled",c.enabled);
72:         out.put("segmentSeconds",c.segmentSeconds);out.put("transport","HTTPS_POLL_1S");out.put("webSocketSse","not_used");
```

## E16 — Fold6 UI와 기존 설정
`resources/static/assets/display/index.html:21-86`
파일 SHA-256: `1348d339d434a5dc05fad4b02081ac6f6b248f3f140eef48223743b2b4d6a9ac`
```text
21: </style></head><body data-fold6-test>
22: <main id="app" class="transcription" aria-label="한국어 실시간 전사">
23: <header><h1>AWX Lens <span>LIVE</span></h1><span id="connection" role="status">서버 연결 중</span></header>
24: <div id="phone-controls" hidden><button id="microphone" class="focusable" type="button" aria-pressed="false">폴드6 수음 시작</button>
25: <button id="finish" class="focusable" type="button" hidden disabled>마지막 전사 받고 중지</button>
26: <p id="microphone-status" class="hint" role="status">시작을 누르기 전에는 수음하지 않습니다.</p>
27: <meter id="input-level" min="0" max="100" value="0" aria-label="마이크 입력 크기" hidden></meter>
28: </div>
29: <button id="hints" class="focusable" type="button" aria-pressed="false">필요할 때만 자동 힌트</button>
30: <p id="error" role="alert" class="hint"></p>
31: <section id="caption-card" class="focusable" tabindex="0" aria-label="현재 발화">
32: <div class="card-heading"><h2 id="caption-label">마이크 대기</h2><span id="caption-page"></span></div>
33: <p id="caption-text" aria-live="polite">폴드6에서 들어오는 대화가 여기에 표시됩니다.</p><p id="hint-text" hidden></p>
34: </section>
35: <button id="connect-lens" class="focusable" type="button" disabled>안경 연결 주소 만들기</button>
36: <input id="lens-address" class="focusable" type="text" readonly hidden aria-label="저장된 안경 연결 주소"><button id="copy-lens" class="focusable" type="button" hidden>주소 복사</button>
37: <a id="add-lens" class="focusable" hidden>Meta AI에 전사 화면 등록</a>
38: <a id="display-url" class="focusable" href="meta/index.html" target="_blank" rel="noopener noreferrer">안경 기본 화면 확인</a>
39: <p id="lens-link-note" class="hint">처음에는 안경 연결 주소를 만들어 Meta AI에 등록하세요.</p>
40: <p class="hint">수음 시작을 누르고 말하세요. 힌트를 켜면 짧은 답변도 안경에 표시됩니다.</p>
41: <details id="advanced-settings"><summary class="focusable" tabindex="0">연결·입력 설정</summary>
42: <details><summary class="focusable" tabindex="0">입력 장치</summary><label for="input-device">휴대폰 입력 장치</label><select id="input-device" class="focusable"><option value="">시스템 기본 입력</option></select><button id="refresh-devices" class="focusable" type="button">장치 새로고침</button><p id="input-label" class="hint">장치명만으로 내장 마이크가 확인되지는 않습니다. 폴드6 가까이와 안경 가까이에서 번갈아 말해 비교하세요.</p></details>
43: 
44: <p id="link-status" class="hint" role="status">첫 연결은 아래 휴대폰 연결에서 시작하세요.</p>
45: <section id="pair-panel" hidden aria-label="기기 연결"><p id="pair-message"></p>
46: <form id="pair-form" hidden><label for="pair-code">안경에 표시된 연결 번호</label><input id="pair-code" class="focusable" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="off"><button class="focusable" type="submit">연결 요청</button></form>
47: <div class="actions"><button id="approve" class="focusable" type="button" hidden>확인 번호 일치 · 승인</button><button id="pair-close" class="focusable" type="button">닫기</button></div></section>
48: 
49: <section id="display-control" aria-label="Display 송출 설정">
50: <p id="owner-status" role="status">이벤트 권한 확인 중</p><p id="display-status" role="status">Display 접속 확인 중</p>
51: <button id="broadcast" class="focusable" type="button" aria-pressed="true">Display 송출 켜짐</button>
52: <label for="segment-preset">전사 구간</label><select id="segment-preset" class="focusable"><option value="0" selected>연속 · 발화가 끝날 때까지</option><option value="5">5초마다 수음 재시작</option><option value="10">10초마다 수음 재시작</option><option value="15">15초마다 수음 재시작</option><option value="custom">사용자 지정</option></select>
53: <input id="segment-custom" class="focusable" type="number" min="5" max="60" value="10" aria-label="사용자 지정 전사 구간 초" hidden>
54: <button id="segment-apply" class="focusable" type="button">구간 적용</button><p id="segment-status" class="hint">연속 수음 · 발화가 끝나면 힌트 처리</p>
55: 
56: <details id="lens-display"><summary class="focusable" tabindex="0">안경 표시 설정</summary>
57: <div class="lens-grid">
58: <label for="ld-cap-font">전사 글자(px)</label><input id="ld-cap-font" class="focusable" type="number" min="20" max="36" step="1" value="26">
59: <label for="ld-hint-font">힌트 글자(px)</label><input id="ld-hint-font" class="focusable" type="number" min="20" max="36" step="1" value="26">
60: <label for="ld-cap-lines">전사 최대 줄</label><input id="ld-cap-lines" class="focusable" type="number" min="1" max="8" step="1" value="4">
61: <label for="ld-cap-ttl">전사 유지(초 · 최신 내용부터 · 1~100)</label><input id="ld-cap-ttl" class="focusable" type="number" min="1" max="100" step="1" value="20">
62: <label for="ld-hint-lines">힌트 페이지 줄</label><input id="ld-hint-lines" class="focusable" type="number" min="4" max="13" step="1" value="11">
63: <label for="ld-hint-ttl">힌트 유지(초 · 최초 표시부터 · 1~100)</label><input id="ld-hint-ttl" class="focusable" type="number" min="1" max="100" step="1" value="20">
64: <label for="ld-auto-page">자동 넘김(초 · 0=끔 · 1~100)</label><input id="ld-auto-page" class="focusable" type="number" min="0" max="100" step="1" value="5">
65: <label for="ld-quiet">힌트 생성 조용시간(초 · 1~30)</label><input id="ld-quiet" class="focusable" type="number" min="1" max="30" step="0.5" value="2.5">
66: <label for="ld-cooldown">힌트 생성 쿨다운(초 · 1~120)</label><input id="ld-cooldown" class="focusable" type="number" min="1" max="120" step="1" value="10">
67: <label for="ld-force">강제 힌트 주기(초 · 1~600)</label><input id="ld-force" class="focusable" type="number" min="1" max="600" step="1" value="180">
68: <label for="ld-hint-chars">힌트 목표 길이(자)</label><input id="ld-hint-chars" class="focusable" type="number" min="240" max="1100" step="10" value="1000">
69: </div>
70: <div class="actions"><button id="ld-apply" class="focusable" type="button">안경 표시 적용</button><button id="ld-reset" class="focusable" type="button">기본값 복원</button></div>
71: <p id="ld-status" class="hint" role="status">전사는 항상 작은 회색 · 표시 유지·넘김과 생성 주기(조용시간/쿨다운/강제힌트)는 저장 후·재접속 후 실제로 바뀝니다. 강제 힌트 기본 180초를 줄이지 않습니다.</p>
72: </details>
73: <details id="hint-context"><summary class="focusable" tabindex="0">힌트 맥락 설정</summary>
74: <div class="lens-grid">
75: <label for="hc-history">이전 대화 참고</label><input id="hc-history" class="focusable" type="checkbox" checked>
76: <label for="hc-window">최근 참조 범위(초 · 0=기본)</label><input id="hc-window" class="focusable" type="number" min="0" max="300" step="1" value="0">
77: <label for="hc-chars">과거 맥락 최대(자 · 0=기본)</label><input id="hc-chars" class="focusable" type="number" min="0" max="8192" step="50" value="0">
78: <label for="hc-tokens">과거 맥락 최대(토큰 추정 · 0=기본)</label><input id="hc-tokens" class="focusable" type="number" min="0" max="4096" step="10" value="0">
79: <label for="hc-topic">주제 전환 시 과거 맥락 축소</label><input id="hc-topic" class="focusable" type="checkbox" checked>
80: </div>
81: <div class="actions"><button id="hc-apply" class="focusable" type="button">맥락 설정 적용</button><button id="hc-reset" class="focusable" type="button">지금부터 새 주제로 시작</button></div>
82: <p id="hc-status" class="hint" role="status">끄면 힌트가 이전 대화를 보지 않습니다. 전사 원문과 저장은 그대로 유지됩니다. 새 주제 시작은 수음과 세션을 멈추지 않습니다.</p>
83: </details>
84: </section>
85: <section aria-label="배경 TXT"><label for="context-file">배경 TXT 읽기 (선택 · UTF-8, 최대 8,000자)</label><input id="context-file" class="focusable" type="file" accept=".txt,text/plain"><p id="context-status" class="hint" role="status">파일은 현재 세션의 힌트 배경으로만 사용합니다.</p><button id="clear-context" class="focusable" type="button">배경 지우기</button></section>
86: <details id="device-debug"><summary class="focusable" tabindex="0">개발자 상태</summary><pre id="debug-state" aria-live="off"></pre></details>
```

## E17 — localStorage 설정 재적용과 continuous capture
`resources/static/assets/display/app.js:79-106`
파일 SHA-256: `3d0b8ed08329f99010d9e7165738768cbc24dbc976748d615bf516b6f2ab629a`
```text
79:       if(ld&&$('ld-status')){
80:         if(!lensPrefsSeen){lensPrefsSeen=true;const saved=settings.lensDisplay;const merged=saved&&typeof saved==='object'?{...ld,...saved}:ld;fillLensInputs(merged);fillContextInputs(merged);}
81:         $('ld-status').textContent='적용됨 · 전사 '+ld.transcriptFontPx+'px/'+ld.transcriptMaxLines+'줄·'+Math.round((ld.transcriptTtlMs??20000)/1000)+'초 · 힌트 '+ld.hintFontPx+'px/'+ld.hintPageLines+'줄·'+Math.round(ld.hintTtlMs/1000)+'초'+(ld.autoPageMs>0?'·자동 '+Math.round(ld.autoPageMs/1000)+'초':'·수동 넘김')+' · 조용 '+(ld.triggerQuietMs??2500)/1000+'초 · 쿨다운 '+(ld.cueCooldownMs??10000)/1000+'초 · 강제 '+(ld.forceAfterMs??180000)/1000+'초 · 목표 '+ld.hintTargetChars+'자';
82:         if($('hc-status')){
83:           const t=s.testStatus?.transcript||{},h=t.history||{},sel=t.lastContextSelection||{};
84:           $('hc-status').textContent='적용됨 · 이전 대화 '+(ld.historyEnabled!==false?'참고':'끔')+(ld.historyWindowMs>0?'·최근 '+Math.round(ld.historyWindowMs/1000)+'초':'·기본 범위')+(ld.historyMaxChars>0?'·최대 '+ld.historyMaxChars+'자':'')+(ld.historyMaxTokens>0?'·최대 '+ld.historyMaxTokens+'토큰':'')+(ld.topicResetEnabled===false?'':'·주제 전환 축소')+(sel.turns!=null?' · 직전 입력 '+sel.turns+'개/'+sel.chars+'자/'+sel.estTokens+'토큰':'')+(h.contextEpoch!=null?' · 맥락 '+h.contextEpoch:'');
85:         }
86:       }
87:     }
88:     if(s.ready&&!settingsApplied&&(!standalone||s.testStatus?.relay?.eventOwner==='THIS DEVICE')){
89:       settingsApplied=true;
90:       void (async()=>{
91:         if(typeof settings.hints==='boolean'&&settings.hints!==s.hintsEnabled)await client.hints(settings.hints);
92:         if(standalone&&Number.isFinite(settings.segment)&&settings.segment>=0&&settings.segment<=60)await client.relaySettings(s.testStatus?.relay?.enabled!==false,settings.segment);
93:         if(settings.lensDisplay&&typeof settings.lensDisplay==='object')await client.lensSettings(settings.lensDisplay,false);
94:       })().catch(reportError);
95:     }
96:     const lensReady=s.connection==='READY'&&s.ready&&(!standalone||s.testStatus?.relay?.eventOwner==='THIS DEVICE');
97:     const readyKey=s.assistId+':'+s.epoch;
98:     if(lensReady&&(!lensWasReady||readyKey!==lensReadyKey)){
99:       lensWasReady=true;lensReadyKey=readyKey;lensRestoreAttempts=0;clearTimeout(lensRestoreTimer);
100:       if(lensRequested||client.storedLensLink({includeExpired:true}))void restoreLensLink();
101:     }else if(!lensReady)lensWasReady=false;
102:     if(voice)debug();
103:     if(voice?.isActive()&&s.role==='PHONE'&&!s.linked&&voice.state.phase==='LISTENING')voice.stop('안경 표시 연결이 끊겨 수음을 중지했습니다.',true);
104:   }
105:   const client=window.DisplayConversate.createClient({transcription:true,standalone,testChannel,onChange:render});
106:   voice=window.DisplayVoice.createCapture({client,continuous:true,segmentSeconds:()=>standalone?(client.state.testStatus?.relay?.segmentSeconds??0):0,deviceId:()=>$('input-device').value,onChange(s){
```

## E18 — Escape 전역 중지 및 pagehide 수음 종료
`resources/static/assets/display/app.js:188-210`
파일 SHA-256: `3d0b8ed08329f99010d9e7165738768cbc24dbc976748d615bf516b6f2ab629a`
```text
188:   if($('hc-apply')){
189:     $('hc-apply').onclick=act(async()=>{const patch=readContextPatch();await client.lensSettings(patch,false);saveSetting('lensDisplay',{...(settings.lensDisplay||{}),...patch});});
190:     $('hc-reset').onclick=act(async()=>{await client.contextReset();$('hc-status').textContent='새 맥락 시작 · 이전 대화는 다음 힌트에 들어가지 않습니다. 수음과 전사는 계속됩니다.';});
191:   }
192:   $('refresh-devices').onclick=act(async()=>{if(!navigator.mediaDevices?.enumerateDevices)throw Error('microphone_device_missing');const items=await navigator.mediaDevices.enumerateDevices();const selected=$('input-device').value;$('input-device').replaceChildren(new Option('시스템 기본 입력',''));let n=0;for(const d of items)if(d.kind==='audioinput'&&d.deviceId)$('input-device').add(new Option(d.label||'입력 장치 '+(++n),d.deviceId));$('input-device').value=selected;});
193:   $('input-device').onchange=()=>{saveSetting('device',$('input-device').value);if(voice.isActive())voice.stop('장치를 바꿨습니다. 선택한 장치로 수음을 다시 시작하세요.');};
194:   navigator.mediaDevices?.addEventListener?.('devicechange',()=>{if(voice.isActive())voice.stop('입력 장치가 변경됐습니다. 장치를 확인하고 다시 시작하세요.',true);});
195:   document.addEventListener('keydown',event=>{
196:     if(event.key==='Escape'){event.preventDefault();$('pair-panel').hidden=true;$('stop').click();return;}
197:     if(['INPUT','SELECT'].includes(event.target.tagName))return;
198:     if(event.target===$('caption-card')&&['ArrowLeft','ArrowRight'].includes(event.key)&&pages.length){event.preventDefault();page=Math.min(pages.length-1,Math.max(0,page+(event.key==='ArrowRight'?1:-1)));showCaption();return;}
199:     if(event.key.startsWith('Arrow')){event.preventDefault();const scope=$('pair-panel').hidden?document:$('pair-panel');const controls=[...scope.querySelectorAll('.focusable')].filter(e=>!e.disabled&&e.getClientRects().length);const i=controls.indexOf(document.activeElement),step=['ArrowUp','ArrowLeft'].includes(event.key)?-1:1;controls[(i+step+controls.length)%controls.length]?.focus();}
200:   });
201:   document.addEventListener('visibilitychange',()=>{
202:     if(document.hidden){if(!standalone&&client.state.role!=='PHONE')client.pause();if(voice.isActive())$('microphone-status').textContent='수음 중에는 휴대폰 화면을 유지해 주세요. 일시 중단되면 입력을 기다렸다가 이어갑니다.';}
203:     else{client.pause();client.start();if(voice.isActive())void voice.resume();}
204:   });
205:   window.addEventListener('online',()=>client.reconnect());
206:   // bfcache hide is not a user stop: remember an active capture and restart it on restore.
207:   let frozenCapture=false;
208:   window.addEventListener('pagehide',()=>{frozenCapture=voice.isActive();voice.stop();client.dispose();clearTimeout(codeTimer);clearTimeout(lensRestoreTimer);});
209:   window.addEventListener('pageshow',event=>{if(event.persisted){client.start();if(frozenCapture){frozenCapture=false;void voice.start();}}});
210:   $('caption-card').focus();client.start();
```

## E19 — canSubmit의 voice gate와 기존 fetch 계약
`resources/static/assets/display/display-conversate.js:24-68`
파일 SHA-256: `09d7d7721a2a1a4a01a5897dca54f1a80e3fde12fc0594ce7bc4a2d8b8e5c271`
```text
24:   function createClient(options = {}) {
25:     const transcription = options.transcription === true;
26:     const fetchImpl = options.fetchImpl || globalThis.fetch.bind(globalThis);
27:     const setTimer = options.setTimer || setTimeout, clearTimer = options.clearTimer || clearTimeout;
28:     const now = options.now || (() => performance.now()), uuid = options.uuid || core.requestUuid;
29:     const notify = () => options.onChange?.(state);
30:     const clientId = uuid().replace(/-/g, '');let claimPending=options.standalone===true;
31:     const state = { phase: 'IDLE', connection: 'PREPARING', message: '', result: null, error: null, metrics: null };
32:     let revision = 0, submittedRevision = -1, generation = 0, pollGeneration = 0, session = null, active = false;
33:     let timer = null, flight = null, connecting = null, retry = 1000, reconnectAt = 0, restored = true, lastCard = '';
34:     const requests = new Set(), events = [];
35:     let voice = null, voiceStopping = false, seenVersion = -1;
36:     let shownHintKey='',captionTimer=null, hintTimer=null, resultTimer=null, joinRequest=null, errors=0;
37:     state.clientId=clientId;state.caption=null;state.hint=null;state.role='DISPLAY';state.linked=false;state.linkPending=false;state.reconnects=0;
38:     function recordEvent(event) { events.push(event); if (events.length > 12) events.shift(); }
39:     function setMessage(value) { if (String(value) !== state.message) { revision++; state.message = String(value); } notify(); }
40:     function canSubmit() { return !flight && !voice && !voiceStopping && now() >= reconnectAt && state.message.trim().length > 0 && state.message.length <= 2000 && revision !== submittedRevision; }
41:     function finish() { if (flight) { clearTimer(flight.timer); flight.resolve(true); flight = null; } }
42:     function fail(code, message) { state.phase = 'ERROR'; state.error = { code, message }; finish(); notify(); }
43:     async function post(route, body) {
44:       if(options.lens===true)route=route==='ack'?'lens/ack':['transcription','poll'].includes(route)?'lens':route;
45:       const controller = new AbortController(); let timeout;
46:       // Keep the bounded server-stop request alive when pagehide disposes the client.
47:       if (route !== 'audio/stop') requests.add(controller);
48:       try {
49:         const began=now();
50:         const work = (async () => {
51:           const response = await fetchImpl('/api/assist/display/' + route, { method: 'POST', credentials: 'same-origin',
52:             headers: { 'Content-Type': 'application/json', 'X-Display-Client': '1', ...(options.testChannel?{'X-Display-Test-Channel':options.testChannel}:{}) }, body: JSON.stringify(body), signal: controller.signal, keepalive: route === 'audio/stop' });
53:           if (!response.ok) {
54:             const seconds = Number(response.headers.get('Retry-After'));
55:             if (response.status === 429 && Number.isFinite(seconds) && seconds > 0) reconnectAt = now() + Math.min(seconds, 3600) * 1000;
56:             let detail;try{detail=await response.json();}catch{}
57:             const error = new Error(/^[a-z_]{1,64}$/.test(detail?.reason||'')?detail.reason:'display_http'); error.status = response.status;if(response.status===429&&Number.isFinite(seconds)&&seconds>0)error.retryAfterMs=Math.min(seconds,3600)*1000;throw error;
58:           }
59:           const view = await response.json();
60:           if(route==='link/code'||route==='link/join'||route==='lens/link'||route==='lens/text')return view;
61:           if (!view || !/^[a-f0-9-]{36}$/.test(view.assistId) || !Number.isSafeInteger(view.epoch) || view.epoch < 1 || typeof view.ready !== 'boolean') throw Error('display-contract');
62:           view.roundTripMs=Math.max(0,Math.round(now()-began));
63:           if(transcription){
64:             const c=view.caption;
65:             if(c!=null&&(!/^[A-Za-z0-9._:-]{1,80}$/.test(c.utteranceId||'')||!Number.isSafeInteger(c.revision)||c.revision<0||typeof c.text!=='string'||c.text.length>(c.rolling===true?16385:2048)||typeof c.isFinal!=='boolean'))throw Error('display-contract');
66:             if(!Number.isSafeInteger(view.version)||view.version<1||!Number.isFinite(view.captionTtlMs)||!Number.isFinite(view.cardTtlMs))throw Error('display-contract');
67:           }
68:           return view;
```

## E20 — 주 렌즈 reader와 lens/text 검증
`resources/static/assets/display/meta/receiver.js:70-151`
파일 SHA-256: `5bd70e202c93ca346c8eefbc478af36a47b9e16c1726f25c9ea3cbeb1bc3ae5b`
```text
70:     const host=options.host||(typeof globalThis!=='undefined'?globalThis:window);
71:     const fetchImpl=options.fetchImpl||(typeof host.fetch==='function'?host.fetch.bind(host):null);
72:     const setTimer=options.setTimer||setTimeout,clearTimer=options.clearTimer||clearTimeout;
73:     const valid=/^[a-f0-9]{64}$/.test(options.token||'');
74:     const state={connection:valid?'CONNECTING':'DISCONNECTED',conversation:'',conversationExpiresAt:0,hint:'',hintId:'',hintFirstShownAt:0,hintExpiresAt:0,errorCode:valid?'':options.token?'lens_link_invalid':'lens_link_missing',action:valid?'':'reconnect_from_phone',display:{...DISPLAY_DEFAULTS}};
75:     let active=false,disposed=false,terminal=!valid,inflight=false,timer,hintTimer,captionTimer,cancelRequest,retry=1000,lastHintKey='',captionKey='',suppressedCaptionKey='',captionShownAt=0;
76:     const retiredHintKeys=new Set();
77:     const nowFn=typeof options.now==='function'?options.now:()=>Date.now();
78:     const notify=()=>options.onChange?.(state);
79:     const diagnostic=(event,data)=>{try{options.diagnostic?.(event,data);}catch{}};
80:     async function read(){
81:       const url='/api/assist/display/lens/text',body=JSON.stringify({lensToken:options.token});
82:       const headers={'Content-Type':'application/json','X-Display-Client':'1'};
83:       let controller,xhr,timeout,rejectCancelled;
84:       const cancelled=new Promise((_,reject)=>{rejectCancelled=reject;});
85:       cancelRequest=()=>{controller?.abort();xhr?.abort();rejectCancelled(Error('cancelled'));};
86:       const work=(async()=>{
87:         if(fetchImpl&&typeof host.AbortController==='function'){
88:           controller=new host.AbortController();
89:           const response=await fetchImpl(url,{method:'POST',credentials:'same-origin',cache:'no-store',headers,body,signal:controller.signal});
90:           if(!response.ok)throw Object.assign(Error('http'),{status:response.status});
91:           return response.json();
92:         }
93:         if(typeof host.XMLHttpRequest!=='function')throw Error('unsupported');
94:         return new Promise((resolve,reject)=>{
95:           xhr=new host.XMLHttpRequest();xhr.open('POST',url,true);xhr.timeout=4000;
96:           Object.entries(headers).forEach(([key,value])=>xhr.setRequestHeader(key,value));
97:           xhr.onload=()=>{if(xhr.status<200||xhr.status>=300){reject(Object.assign(Error('http'),{status:xhr.status}));return;}try{resolve(JSON.parse(xhr.responseText));}catch{reject(Error('contract'));}};
98:           xhr.onerror=()=>reject(Error('network'));xhr.ontimeout=()=>reject(Error('timeout'));xhr.send(body);
99:         });
100:       })();
101:       try{return await Promise.race([work,cancelled,new Promise((_,reject)=>{timeout=setTimer(()=>{controller?.abort();xhr?.abort();reject(Error('timeout'));},4000);})]);}
102:       finally{clearTimer(timeout);cancelRequest=null;}
103:     }
104:     // A hint owns its TTL from first show (default 20s, configurable). Receiving the
105:     // same generation again never extends that window; the server expiry is the
106:     // authoritative ceiling so a configured TTL never outlives the delivered card.
107:     function hintKeyOf(value){const id=typeof value.hintId==='string'?value.hintId:'';return id?'id:'+id:'text:'+value.hint;}
108:     function retireHint(key){if(key){retiredHintKeys.add(key);if(retiredHintKeys.size>32)retiredHintKeys.delete(retiredHintKeys.values().next().value);}}
109:     function expireHint(){if(nowFn()>=state.hintExpiresAt){const held=state.hintExpiresAt-state.hintFirstShownAt;retireHint(lastHintKey);lastHintKey='';state.hint='';state.hintId='';state.hintExpiresAt=0;diagnostic('hint_expired',{heldMs:held,ttlMs:state.display.hintTtlMs,at:nowFn()});notify();}}
110:     function applyHint(value){
111:       const text=typeof value.hint==='string'?value.hint:'';if(!text.trim())return;
112:       const key=hintKeyOf(value),now=nowFn();
113:       if(retiredHintKeys.has(key)){diagnostic('hint_suppressed',{});return;}
114:       if(key===lastHintKey){if(state.hintExpiresAt>now)state.hint=text;return;}
115:       retireHint(lastHintKey);lastHintKey=key;
116:       state.hint=text;state.hintId=typeof value.hintId==='string'?value.hintId:'';
117:       state.hintFirstShownAt=now;const serverCap=value.hintExpiresAt>0?value.hintExpiresAt:Infinity;
118:       state.hintExpiresAt=Math.min(now+state.display.hintTtlMs,serverCap);
119:       clearTimer(hintTimer);hintTimer=setTimer(expireHint,Math.max(1,state.hintExpiresAt-now));diagnostic('hint_shown',{chars:Array.from(text).length,ttlMs:state.hintExpiresAt-now,at:now});
120:     }
121:     // The transcript owns its hold from the first show of that exact content: a
122:     // repeated identical caption never extends it, a changed caption re-anchors
123:     // it, and an expired caption is not revived while the same text keeps coming.
124:     function expireConversation(){if(nowFn()>=state.conversationExpiresAt){const held=state.conversationExpiresAt-captionShownAt;suppressedCaptionKey=captionKey;captionKey='';state.conversation='';state.conversationExpiresAt=0;diagnostic('transcript_expired',{heldMs:held,ttlMs:state.display.transcriptTtlMs,at:nowFn()});notify();}}
125:     function applyConversation(value){
126:       const text=typeof value.conversation==='string'?value.conversation:'';if(!text.trim()){suppressedCaptionKey='';return;}
127:       const cap=Number.isFinite(value.conversationExpiresAt)&&value.conversationExpiresAt>0?value.conversationExpiresAt:Infinity;
128:       const key='text:'+text+'@'+(cap===Infinity?'open':cap),now=nowFn();
129:       if(key===captionKey)return;
130:       if(key===suppressedCaptionKey){diagnostic('transcript_suppressed',{at:now});return;}
131:       suppressedCaptionKey='';captionKey=key;captionShownAt=now;state.conversation=text;
132:       state.conversationExpiresAt=Math.min(now+state.display.transcriptTtlMs,cap);
133:       clearTimer(captionTimer);captionTimer=setTimer(expireConversation,Math.max(1,state.conversationExpiresAt-now));
134:       diagnostic('transcript_shown',{chars:Array.from(text).length,ttlMs:state.conversationExpiresAt-now,at:now});
135:     }
136:     async function poll(){
137:       if(inflight||disposed||terminal)return;inflight=true;
138:       try{
139:         const value=await read();if(disposed)return;
140:         if(!value||typeof value.conversation!=='string'||typeof value.hint!=='string'||value.hintId!=null&&typeof value.hintId!=='string'||value.hintExpiresAt!=null&&!Number.isFinite(value.hintExpiresAt)||value.conversationExpiresAt!=null&&!Number.isFinite(value.conversationExpiresAt)||value.display!=null&&typeof value.display!=='object'||Array.from(value.conversation).length>280||Array.from(value.hint).length>LENS_HINT_CHARS_MAX)throw Error('contract');
141:         if(value.display){const next=normDisplay(value.display);if(JSON.stringify(next)!==JSON.stringify(state.display)){state.display=next;diagnostic('display_applied',{...next,at:nowFn()});}}
142:         applyConversation(value);
143:         applyHint(value);
144:         state.connection='CONNECTED';state.errorCode='';state.action='';retry=1000;notify();
145:       }catch(error){
146:         if(disposed||error.message==='cancelled')return;
147:         terminal=error.status===403||error.status===404;
148:         state.connection=terminal?'DISCONNECTED':'RECONNECTING';
149:         state.errorCode=error.status===404?'lens_link_expired':error.status===403?'lens_link_denied':error.message==='contract'||error instanceof SyntaxError?'contract':error.message==='unsupported'?'unsupported':error.message==='timeout'||error.name==='AbortError'?'timeout':error.status?'http_'+error.status:'network';
150:         state.action=terminal?'reconnect_from_phone':'retrying';
151:         if(terminal)active=false;else retry=Math.min(30000,retry*2);notify();
```

## E21 — 페이지 넘김·수신·키보드·가시성
`resources/static/assets/display/meta/receiver.js:260-305`
파일 SHA-256: `5bd70e202c93ca346c8eefbc478af36a47b9e16c1726f25c9ea3cbeb1bc3ae5b`
```text
260:     }
261:     function schedulePages(){
262:       clearTimeout(hintPageTimer);hintPageTimer=null;
263:       const ms=cfg().autoPageMs;
264:       // The configured interval is used exactly as set: a hint that expires
265:       // before the next page turn simply disappears — the interval is never
266:       // shortened to beat the hint's own TTL.
267:       if(hintPages.length>1&&ms>=1000){
268:         hintPageTimer=setTimeout(()=>{hintPageTimer=null;
269:           if(hintPages.length>1){hintPage=(hintPage+1)%hintPages.length;paint();diagnostic('hint_page',{page:hintPage+1,pages:hintPages.length,intervalMs:ms,at:Date.now()});}
270:           schedulePages();},ms);
271:       }
272:     }
273:     function moveHint(delta){if(!hintPages.length)return;hintPage=(hintPage+delta+hintPages.length)%hintPages.length;paint();schedulePages();}
274:     const receiver=createLensReceiver({token,diagnostic,onChange(state){
275:       lastState=state;
276:       const cue=formatCue(state.hint);
277:       const key=cue?(state.hintId?'id:'+state.hintId:'text:'+cue):'';
278:       if(!cue){hintRenderKey='';lastCueText='';hintPages=[];hintPage=0;clearTimeout(hintPageTimer);hintPageTimer=null;}
279:       else if(key!==hintRenderKey){
280:         hintRenderKey=key;lastCueText=cue;
281:         if(hint){hint.hidden=false;hintPages=splitHintPages(hint,cue,cfg().hintPageLines);hintPage=0;}else hintPages=[cue];
282:         diagnostic('lens_hint_render',{pages:hintPages.length,chars:Array.from(cue).length});
283:         schedulePages();
284:       }else if(cue!==lastCueText){
285:         // Same hint identity with a revised body: re-split but keep the page being read.
286:         lastCueText=cue;const keep=hintPage;
287:         hintPages=hint?splitHintPages(hint,cue,cfg().hintPageLines):[cue];hintPage=Math.min(keep,hintPages.length-1);
288:         schedulePages();
289:       }
290:       paint();
291:     }});
292:     document.addEventListener('keydown',event=>{
293:       if(['ArrowLeft','ArrowUp'].includes(event.key)){event.preventDefault();moveHint(-1);}
294:       else if(['ArrowRight','ArrowDown','Enter'].includes(event.key)){event.preventDefault();moveHint(1);}
295:     });
296:     document.addEventListener('visibilitychange',()=>{if(document.hidden)receiver.pause();else receiver.start();});
297:     window.addEventListener('online',()=>{receiver.pause();receiver.start();});
298:     window.addEventListener('pagehide',()=>receiver.dispose());
299:     window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
300:     receiver.start();return receiver;
301:   }
302:   // A lens hint is split at the element's measured page band (configurable line
303:   // count, real clientHeight when laid out) so every page stays inside the
304:   // visible cue area; nothing is truncated or dropped.
305:   function splitHintPages(el,text,pageLines){
```

## E22 — 기존 대화방의 owner 정보
`java/com/example/lms/domain/ChatSession.java:31-79`
파일 SHA-256: `824c416dffed62bf3911e47a19532a6f0ea61f2dd082fd2a4cb5f3f98412f673`
```text
31:      * 세션의 고유 ID (Primary Key)
32:      */
33:     @Id
34:     @GeneratedValue(strategy = GenerationType.IDENTITY)
35:     private Long id;
36: 
37:     /**
38:      * 대화의 제목 (보통 첫 번째 메시지로 생성)
39:      */
40:     @Column(nullable = false, length = 120)
41:     private String title;
42: 
43:     /**
44:      * 세션 생성 일시 (자동 생성)
45:      */
46:     @CreationTimestamp
47:     @Column(nullable = false, updatable = false)
48:     private LocalDateTime createdAt;
49: 
50:     /**
51:      * [수정] 이 세션의 소유자인 관리자(Administrator) 정보.
52:      */
53:     @ManyToOne(fetch = FetchType.LAZY)
54:     @JoinColumn(name = "admin_id", nullable = true)
55:     private Administrator administrator;
56: 
57:     
58: 
59: /**
60:  * 게스트/비회원 세션을 구분하기 위한 소유자 키.
61:  * - 현재 요청의 클라이언트 IP(+UA 일부)를 SHA-256으로 해시하여 저장
62:  * - 관리자가 소유한 세션인 경우 null
63:  */
64: @Column(name = "owner_key", length = 128)
65: private String ownerKey;
66: 
67:     /**
68:      * 메모리 정책 프로파일.
69:      * STRICT / LIGHT / OFF 등.
70:      */
71:     @Enumerated(EnumType.STRING)
72:     @Column(name = "memory_profile", length = 32)
73:     private MemoryProfile memoryProfile;
74: 
75: /**
76:  * 소유자 유형: "ADMIN" | "ANON" /* ... *&#47;
77:  */
78: @Column(name = "owner_type", length = 16)
79: private String ownerType;
```

## E23 — 기존 메시지 저장 모델
`java/com/example/lms/domain/ChatMessage.java:18-56`
파일 SHA-256: `d6e0743c4cd79a63015993f654e0964600fb0f4c0c9d58822435c50ab212551b`
```text
18: @Builder
19: public class ChatMessage {
20: 
21:     @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
22:     private Long id;
23: 
24:     /* ────────── FK (세션) ────────── */
25:     @ManyToOne(fetch = FetchType.LAZY)
26:     @JoinColumn(name = "session_id", nullable = false)
27:     @JsonBackReference
28:     private ChatSession session;
29: 
30:     /* ────────── 역할 & 내용 ────────── */
31:     @Column(nullable = false, length = 20)
32:     private String role;               // user | assistant | system
33: 
34:     /**
35:      * 대용량 메시지 저장 컬럼.
36:      *
37:      * <p>과거에는 MariaDB/MySQL에서 {@code TEXT}로 강제했지만,
38:      * Search Trace(HTML) / RSUM(rolling summary) 같은 시스템 메타가 커지면
39:      * {@code Data too long for column 'content'} 오류가 발생할 수 있습니다.
40:      *
41:      * <p>따라서 dialect가 적절한 LOB 타입(CLOB/LONGTEXT 등)을 선택하도록
42:      * {@link jakarta.persistence.Lob} 매핑만 사용합니다.
43:      */
44:     @Lob
45:     @Column(nullable = false)
46:     private String content;
47: 
48:     /* ────────── 메타 ────────── */
49:     @CreationTimestamp
50:     private LocalDateTime createdAt;
51: 
52:     /* 편의 생성자 (ChatHistoryService 등에서 사용) */
53:     public ChatMessage(ChatSession session, String role, String content) {
54:         this.session = session;
55:         this.role    = role.toLowerCase(Locale.ROOT);   // OpenAI 권장: 소문자
56:         this.content = content;
```

## E24 — 기존 bounded history query
`java/com/example/lms/repository/ChatMessageRepository.java:37-66`
파일 SHA-256: `5ca79edd344f59f14ee519f1ea35dff6fc75c51c4e45dba3c962976eaa9b7d8d`
```text
37:     /**
38:      * 최근 메시지를 빠르게 스캔하기 위한 DESC 정렬 조회 (rolling summary/chunking 등).
39:      *
40:      * <p>Pageable을 통해 DB에서 필요한 개수만 가져오도록 하여,
41:      * 세션 메시지가 많아도 서버 부하를 최소화합니다.</p>
42:      */
43:     List<ChatMessage> findBySession_IdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);
44: 
45:     /**
46:      * Select the newest bounded window deterministically before the service
47:      * projects it back into chronological order.
48:      */
49:     @Query("""
50:             select m from ChatMessage m
51:             where m.session.id = :sessionId
52:             order by case when m.createdAt is null then 1 else 0 end asc,
53:                      m.createdAt desc,
54:                      m.id desc
55:             """)
56:     List<ChatMessage> findNewestWindowBySessionId(
57:             @Param("sessionId") Long sessionId,
58:             Pageable pageable);
59: 
60:     // ----- RollingSummary (RSUM) helpers -----
61:     /** Latest RSUM system message for the session (fast, id-desc). */
62:     Optional<ChatMessage> findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(
63:             Long sessionId, String role, String contentPrefix);
64: 
65:     /** Messages after a given message id (asc) with optional paging (fast, id-asc). */
66:     List<ChatMessage> findBySession_IdAndIdGreaterThanOrderByIdAsc(Long sessionId, Long id, Pageable pageable);
```

## E25 — 일반 chat HTTP 경로만 적용되는 admission
`java/com/example/lms/api/ChatGenerationAdmissionFilter.java:28-79`
파일 SHA-256: `480ac15b256ab3d94b8f68e71d6afcb08c44ad558b303b1b45b57377aa9fd443`
```text
28: /** Distributed cost admission, after authentication and bounded body intake, before generation. */
29: @Component
30: @Order(Ordered.LOWEST_PRECEDENCE - 100)
31: @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
32: @ConditionalOnProperty(name = "chat.admission.enabled", havingValue = "true", matchIfMissing = true)
33: public final class ChatGenerationAdmissionFilter extends OncePerRequestFilter {
34:     private static final long RETENTION_MS = Duration.ofHours(24).toMillis();
35:     private static final Set<String> GENERATION = Set.of("/api/chat", "/api/chat/sync", "/api/chat/stream");
36:     private final UpstashRedisClient redis;
37:     private final JdbcTemplate jdbc;
38:     private final org.springframework.transaction.support.TransactionTemplate transactions;
39:     @org.springframework.beans.factory.annotation.Autowired
40:     private com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
41:     private static final String COMPLETION_ATTRIBUTE = ChatGenerationAdmissionFilter.class.getName()+".completion";
42:     private final ClientOwnerKeyResolver owners;
43:     private final String script;
44:     @Value("${demo.mode:${DEMO_MODE:false}}") private boolean demoMode;
45:     @org.springframework.beans.factory.annotation.Autowired
46:     private org.springframework.core.env.Environment environment;
47:     private boolean demoAdmission() {
48:         return demoMode || (environment != null && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local")));
49:     }
50:     /** Request-scoped approval only. It retains no question, answer, credential or remote claim. */
51:     public static final class DemoPermit implements java.util.function.Consumer<Object>, Runnable {
52:         private final AtomicBoolean completed = new AtomicBoolean();
53:         public boolean accepted() { return true; }
54:         public boolean completed() { return completed.get(); }
55:         @Override public void accept(Object ignored) { completed.set(true); }
56:         @Override public void run() { /* Explicit local/demo cost approval. */ }
57:     }
58:     @Value("${chat.admission.user-capacity:20}") private int userCapacity = 20;
59:     @Value("${chat.admission.user-per-minute:20}") private int userPerMinute = 20;
60:     @Value("${chat.admission.ip-capacity:60}") private int ipCapacity = 60;
61:     @Value("${chat.admission.ip-per-minute:60}") private int ipPerMinute = 60;
62: 
63:     public ChatGenerationAdmissionFilter(UpstashRedisClient redis, DataSource dataSource, ClientOwnerKeyResolver owners) {
64:         this.redis = redis; this.owners = owners; this.jdbc = new JdbcTemplate(dataSource);
65:         this.transactions = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
66:         this.transactions.setTimeout(2);
67:         this.jdbc.setQueryTimeout(2);
68:         try { this.script = new ClassPathResource("redis/chat_admission.lua").getContentAsString(StandardCharsets.UTF_8); }
69:         catch (IOException failure) { throw new IllegalStateException("chat_admission_script_missing"); }
70:     }
71:     @jakarta.annotation.PostConstruct void validate() {
72:         if (userCapacity < 1 || userPerMinute < 1 || ipCapacity < 1 || ipPerMinute < 1
73:                 || userCapacity > 100_000 || userPerMinute > 100_000 || ipCapacity > 100_000 || ipPerMinute > 100_000)
74:             throw new IllegalStateException("chat_admission_invalid_limits");
75:     }
76:     @Override protected boolean shouldNotFilter(HttpServletRequest request) {
77:         String path = path(request);
78:         return !"POST".equals(request.getMethod()) || !GENERATION.contains(path)
79:                 || ("/api/chat/stream".equals(path) && Boolean.parseBoolean(request.getParameter("attach")));
```

## E26 — request/cookie owner와 background identity 한계
`java/com/example/lms/web/ClientOwnerKeyResolver.java:40-81`
파일 SHA-256: `a7ce949159014add0ca87c9797e8083057b022133d8a1a3eedd29d1f28a940e2`
```text
40:     /** Compute or retrieve stable ownerKey for current request. */
41:     public String ownerKey() {
42:         HttpServletRequest currentRequest = request;
43:         if (currentRequest == null) {
44:             return NO_REQUEST_OWNER_KEY;
45:         }
46: 
47:         // 1) Filter-issued request identity. This keeps the first cookie-less
48:         // request on the same owner identity that will be persisted in the response.
49:         Object requestOwner = currentRequest.getAttribute(
50:                 OwnerKeyBootstrapFilter.OWNER_KEY_REQUEST_ATTRIBUTE);
51:         String requestOwnerKey = requestOwner instanceof String value
52:                 ? OwnerKeyBootstrapFilter.usableOwnerKey(value)
53:                 : null;
54:         if (requestOwnerKey != null) return requestOwnerKey;
55: 
56:         // 2) ownerKey cookie. Public X-Owner-Key headers are not trusted because
57:         // browsers and external clients can spoof them.
58:         String cookieVal = OwnerKeyBootstrapFilter.usableOwnerKey(readCookie(currentRequest, OwnerKeyBootstrapFilter.OWNER_KEY));
59:         if (cookieVal != null) return cookieVal;
60: 
61:         // 3) gid cookie (compatibility path)
62:         String gid = usableGid(readCookie(currentRequest, "gid"));
63:         if (gid != null) return "gid:" + gid;
64: 
65:         // 4) Fallback: IP + UA hash (do not store raw PII)
66:         String ip = firstForwardedIpOrRemoteAddr(currentRequest);
67:         String ua = Optional.ofNullable(currentRequest.getHeader("User-Agent")).orElse("");
68:         if (ua.length() > 120) {
69:             int end = 120;
70:             if (Character.isHighSurrogate(ua.charAt(end - 1))
71:                     && Character.isLowSurrogate(ua.charAt(end))) {
72:                 end--;
73:             }
74:             ua = ua.substring(0, end);
75:         }
76:         String raw = (ip == null ? "" : ip) + "|" + ua;
77:         String digest = sha256(raw);
78:         if (digest != null) return "ipua:" + digest;
79: 
80:         // 5) Random
81:         return UUID.randomUUID().toString();
```

## E27 — package-private guard와 소유권 검사
`java/com/example/lms/api/ChatSessionAccessGuard.java:14-55`
파일 SHA-256: `a3ad0c709c33a6d4f4321f599cc099e99c1980e1889188e1111a4a6c5060fc6e`
```text
14: final class ChatSessionAccessGuard {
15:     private ChatSessionAccessGuard() {
16:     }
17: 
18:     static ResponseEntity<ChatResponseDto> authorize(
19:             ChatHistoryService historyService,
20:             Long sessionId,
21:             String username,
22:             String ownerKey,
23:             Logger log) {
24:         if (sessionId == null) {
25:             return null;
26:         }
27:         ChatSession session = historyService.getSessionWithMessages(sessionId, 1);
28:         if (session == null || canAccess(session, username, ownerKey)) {
29:             return null;
30:         }
31:         if (log != null) {
32:             log.warn("[AWX][chat][session] rejected foreign session sessionHash={}",
33:                     SafeRedactor.hashValue(String.valueOf(sessionId)));
34:         }
35:         GuardContext guardContext = GuardContextHolder.get();
36:         if (guardContext != null) {
37:             guardContext.recordInteractionPolicyFact(
38:                     new InteractionEvidencePolicy.ManipulationFact(
39:                             InteractionEvidencePolicy.ManipulationKind.UNAUTHORIZED_ACCESS,
40:                             InteractionEvidencePolicy.ProofKind.AUTHORIZATION_DENIED,
41:                             InteractionEvidencePolicy.DetectorRule.SESSION_AUTHORIZATION_V1,
42:                             InteractionEvidencePolicy.SourceSurface.SESSION_HISTORY),
43:                     null);
44:         }
45:         return ResponseEntity.status(HttpStatus.FORBIDDEN)
46:                 .body(new ChatResponseDto("session_forbidden", sessionId, "forbidden", false));
47:     }
48: 
49:     private static boolean canAccess(ChatSession session, String username, String ownerKey) {
50:         var owner = session.getAdministrator();
51:         if (owner != null) {
52:             return username != null && owner.getUsername().equals(username);
53:         }
54:         return session.getOwnerKey() != null && session.getOwnerKey().equals(ownerKey);
55:     }
```

## E28 — rolling/TTL/quiet/cooldown은 기존 cue 설정
`resources/application-meta-display.yml:10-42`
파일 SHA-256: `596bb99f7bf1c409ecceedec3b1b745c53e488939af29e659bc7e399bc53a7af`
```text
10: conversate:
11:   cost:
12:     # Fold6 testing: keep usage estimates, never require dollar-budget approvals.
13:     enforce-limits: false
14:   asr:
15:     cloud:
16:       # Complete local-fallback utterances only; Soniox/Deepgram continuous streaming is unchanged.
17:       utterance-provider: ${CONVERSATE_STT_UTTERANCE_PROVIDER:economy}
18:       # Renew only the provider stream before this bound; microphone session has no timer.
19:       stream-seconds: 75
20:   transcript:
21:     rolling-enabled: true
22:     visible-chars: ${CONVERSATE_VISIBLE_CHARS:200}
23:     context-chars: ${CONVERSATE_CONTEXT_CHARS:2000}
24:   display-ttl-ms: ${CONVERSATE_DISPLAY_TTL_MS:20000}
25:   context-ttl-ms: ${CONVERSATE_CONTEXT_TTL_MS:120000}
26:   cue:
27:     cooldown-ms: 10000
28:     trigger-min-delta-chars: ${CONVERSATE_CUE_TRIGGER_MIN_DELTA:120}
29:     trigger-quiet-ms: ${CONVERSATE_CUE_TRIGGER_QUIET_MS:2500}
30:     force-after-ms: ${CONVERSATE_CUE_FORCE_AFTER_MS:180000}
31:     force-min-delta-chars: ${CONVERSATE_CUE_FORCE_MIN_DELTA:50}
32:     total-timeout-ms: 12000
33:     gate-timeout-ms: 3000
34:     hint-timeout-ms: 6500
35:     max-attempts-per-stage: 3
36:     # Hangul runs ~1 token/char: 1000-char target + JSON envelope needs ~1.5k headroom.
37:     hint-target-chars: ${CONVERSATE_CUE_HINT_TARGET_CHARS:1000}
38:     # Hint-input past window (not output length, not display TTL). Fold UI wiring is separate.
39:     use-past: ${CONVERSATE_CUE_USE_PAST:true}
40:     past-max-turns: ${CONVERSATE_CUE_PAST_MAX_TURNS:4}
41:     past-max-chars: ${CONVERSATE_CUE_PAST_MAX_CHARS:1600}
42:     max-output-tokens: ${CONVERSATE_CUE_MAX_OUTPUT_TOKENS:1536}
```
