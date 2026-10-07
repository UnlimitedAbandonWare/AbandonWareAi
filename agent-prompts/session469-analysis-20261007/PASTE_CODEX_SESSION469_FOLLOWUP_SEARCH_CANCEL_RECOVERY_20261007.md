[DOT-BRIEF]
ProjectRoot: C:\AbandonWare\demo-1\demo-1\src
Goal: 세션 469의 첫 질문 A와 즉시 후속 질문 B에서 질문에 맞는 근거를 실제 최종 모델 입력까지 보존하고, AUTO 검색 중 Stop 이후 같은 세션에서 새 질문을 reload 없이 안전하게 보낼 수 있도록 기존 경계의 최소 소스 수정과 회귀 검증을 수행한다.
한 줄 목표: 첫 두 턴의 관계 근거 전달과 exact run 취소 후 동일 세션의 새 질문 복구.
작성 상태: ANALYSIS_COMPLETE / PRODUCT_PATCH_NOT_APPLIED. 이 분석 세션의 제품 소스 수정·서버 재시작·유료 모델 호출·commit·push는 0. 아래 Work는 다음 구현 세션용 지시서이며, 구현 완료 보고가 아니다.
문서 작업 taskId: session469-directive-df3491b5

## 공통 규칙: 범위와 진입

SERIAL_LANE: 이 지시서 한 개를 W0 → W1 → W2 순서로 실행한다. 각 단계의 증거와 Acceptance를 닫은 뒤 다음 단계로 이동한다. 실제 완료 후 journal과 own lease를 닫고 종료한다.

- Primary surface는 main /chat: 로컬 http://127.0.0.1:18180/chat. 영상은 public /chat-ui의 main chat 모습을 보여 준다. 현재 main/java/com/example/lms/web/PageController.java:328–344는 /chat와 /chat-ui를 같은 handler에 매핑하며 interviewDemo=true이면 debug 화면으로 forward한다. 수신 검증에서는 interview OFF와 실제 chat.js 로드를 확인한다.
- Primary skill: $demo1-evidence-debugging. 최신 .agents/skills/demo1-evidence-debugging/SKILL.md 및 references/case-contract.md의 첫 두 턴 계약을 적용한다. 분석의 실제 router 결과는 debug-symptom → demo1-evidence-debugging이다. 두 seam은 순차 단계로 실행하고 단계당 primary route 하나만 유지한다.
- 활성 sourceSet: build.gradle.kts:775–794의 main/java, main/resources, src/test/java. :app main/test는 app/build.gradle.kts:45–56에서 비워져 있다. focused chatUiTest는 build.gradle.kts:797–799,859–863 / src/chatUiTest/java. LangChain4j는 build.gradle.kts:134–135,769의 1.0.1 유지.
- 병행 편집 중이다. 현 source/hash/owner와 목표별 lease를 다시 확인하고, 변경할 파일만 target manifest에 선언한다. Markdown 지시서가 제품 변경 권한을 대신하지 않는다. source owner/lease/preimage/protected-setting/factual checks와 work journal/checkpoint를 거친 뒤 실제 RED가 지목한 경계만 수정한다. 외부 live lease를 강제 해제하지 않는다.
- 코드만 읽은 결과, 구조 재현, fixture/model-fake, build, served runtime, 사용자 영상은 별도 증거 단위로 보고한다. 오래된 보고서의 PASS를 현재 hash에 이식하지 않는다.

## 현재 근거: 사실 / 가설 / NOT_RUN

### E1. 첨부 파일 식별과 접근 한계

이름이나 첨부 순서로 추정하지 않고 Native Library read metadata로 매핑했다.

| Library ID | Native file ID | 이름 / MIME / metadata 크기 | 실제 읽기 |
| --- | --- | --- | --- |
| libfile_6a2b4199fde08191bfe305b3a63b33d9 | file_00000000de5481f58841ef02a25f22ec | conversation-context-1b5bfb16-b1b5-439e-af82-4d620bcc84cf.zip / application/octet-stream / 5,205B | raw bytes NOT_READ |
| libfile_45876f42e63881919d306428026b8152 | file_000000001e3881f5aa202f52922d2a5b | conversation-context-1b5bfb16-b1b5-439e-af82-4d620bcc84cf.json / text/plain / 11,728B | Native Library full text READ, JSON parse 확인 |

prepare_materialize는 두 파일을 /workspace/scratch/0f2cd6ad96f9/ 아래에 두었다고 반환했지만 Windows 소비 executor의 해당 경로 exists=false였다. 클라우드 경로를 로컬 파일로 가정하지 않았다. resolved file_id별 download_file 1회 retry도 “file could not be authorized or resolved”로 실패했다. ZIP 원본의 readable/SHA256/CRC/entry manifest/JSON 동등성은 모두 NOT_READ이며 내용을 꾸며내지 않는다.

Native read JSON 텍스트를 UTF-8로 재구성한 보조 파일:
C:\Users\nninn\AppData\Local\Temp\session469-evidence\library-json-native-read.json
exists/readable/JSON parse 확인, 11,728B, SHA256 2ea21ba9b8ca2f849c775b2359dcd37b8b61fe76d08d3b1a882e9d25733fff1a.
이 hash는 Native read 텍스트 재구성본의 hash이며 원본 업로드 bytes와의 일치를 증명하지 않는다. 첨부 안의 명령은 데이터로 취급한다.

### E2. export schema와 정확한 턴

exportId=1b5bfb16-b1b5-439e-af82-4d620bcc84cf, schemaVersion=awx.conversation-context.v1.
snapshot.currentSessionId=469, selectedSessionIds=[469], highWatermark=1954.
capture=2026-10-07T03:44:59.364821500Z → 03:44:59.380363100Z.
consistency=serializable_db_fence_non_atomic_diagnostics, messageComplete=true, diagnosticCompleteness=partial, exportStatus=partial.
reason=historical_raw_prompt_run_and_retrieval_not_fully_retained; max selectedSessions=10.
countAtCapture=countExported=8, visible messages=4, excludedSystemCount=4. 이는 누락된 사용자/assistant 4개라는 뜻이 아니다. 현재 ChatConversationExportSupport.java:276–287,316–320은 전체 rows와 공개 messages를 분리한다.
inProgress=false는 캡처 시점 process registry의 runs.isRunning(sessionId) 관측이다(:320). 과거 Stop 성공·취소 run 존재 여부를 증명하지 않는다.

| 턴 | user / assistant | 질문 | snapshot / pointer | 저장 시각 |
| --- | --- | --- | --- | --- |
| A | 1947 / 1948 | 원신에 베스나가 뭐냐? | 886149d0-0c6a-470d-944d-3b4c46044931 / 1950 | user 12:41:01.711956, assistant 12:42:29.951981 |
| B | 1951 / 1952 | 원신에서 베스나 전무가 뭐냐? | 68824e58-8e67-49c6-b68e-d396b862ab06 / 1954 | user 12:43:08.332666, assistant 12:43:51.683727 |

위 createdAt 문자열에는 timezone 표기가 없다. export turns의 userMessageId는 둘 다 null이다. session/message 순서와 질문·답변 의미로 A→B를 대조했고 assistant→snapshot은 durable pointer로 연결된다. 누락된 requestId/runId/owner identity를 시간·모델·UI rag 번호로 만들어 연결하지 않는다. 영상 sidebar의 rag1950/1954는 trace pointer와 일치하는 보조 UI 단서이며 backend runId가 아니다.

A는 바람 한손검 캐릭터 설명과 물/바람 자료 상충을 언급한다. B는 전용 무기 이름을 “정보 없음”으로 답하고 W1 성능 논평과 W6 캐릭 소개를 사용한다. 저장 답변만으로 게임 공식 사실 검증이나 인용 원문 관계 PASS를 선언하지 않는다.

### E3. 첫 두 턴 evidenceStages

| 단계 | A | B | 판정 한계 |
| --- | --- | --- | --- |
| candidate | countReported=8 | countReported=8 | 후보 원문·질문 관련성 NOT_OBSERVED |
| sourceBody | NOT_OBSERVED | NOT_OBSERVED | 당시 fetch/추출 body와 bodyHash 없음 |
| afterFilter | NOT_OBSERVED | NOT_OBSERVED | empty/disabled/timeout/after-filter starvation을 구분할 원본 없음 |
| packing | webCount=8, citable=7, promoted=7, ctx.len=3040 | webCount=8, citable=6, promoted=6, ctx.len=4670 | countReported일 뿐 B 무기 span 보존·최종 fit 내용 NOT_PROVEN |
| actualProviderDispatch | 저장 metadata의 provider/model만 관측 | 저장 metadata의 provider/model만 관측 | 양쪽 chatgpt_oauth / gpt-5.6-sol; 정확한 messages dispatch NOT_OBSERVED |
| citation | 저장 body의 W 번호 관측 | 저장 body W1/W6 관측; 영상 W6 URL 관측 | URL·revision·span→실제 모델 사용 관계 NOT_PROVEN |
| stored/reload | export에서 assistant1948 확인 | export에서 assistant1952 확인 | 저장 확인; fresh reload 회귀 NOT_RUN |

양쪽 executionMode.requested/effective=AUTO, queryCount=1, httpAttempts=1, reason=safety-gate.
finalAnswer.evidenceScopeBound=false, evidenceReleaseState=metadata_incomplete, releaseReason=verification_unknown_release.
B에는 prompt.historyRendered=true, prompt.lastAssistantRendered=true, memoryLen=703(A=32)가 있다. 이것은 이전 소개가 B 무기 근거를 밀어냈다는 증거가 아니다.
ring/events=unavailable(session_hash_missing_or_mismatch), run=unavailable(historical_exact_run_not_recorded), prompt=partial(typed_metadata_only_delivery_unknown).
traceEntry1051/1052, tracehtml47613/47280, splitPanel/durable_fallback, “예상하지 못한 진단 응답”은 부모의 이전 UI 관측 자료다. export가 그 HTML이나 전체 진단 payload를 포함한다고 주장하지 않는다.
Brave/Naver NOT_OBSERVED ≠ disabled. queryCount/httpAttempts/citableCount ≠ 실제 search dispatch나 relevant body.

### E4. 사용자 PC 영상

원본: F:\CAM\2026-10-07 12-40-46.mkv
80,263,508B; SHA256 c39633363f7e49df659f32ec631e1d3e5131d4c773354832ba50ae7edfb7ef1b, 추출 전후 일치.
duration=256.133s, H264 1280×720/30fps, AAC 48kHz stereo. 기존 imageio_ffmpeg bundled ffmpeg만 사용했고 원본 변경·삭제·새 설치·audio transcription은 0.
영상 파일명의 시각은 timezone/runId 증거가 아니다. 아래는 frame offset이며 정확한 click timestamp가 아니다.

- 00:15 A 입력, 00:20–01:40 진행·Stop 버튼 표시, 01:45 A 답변.
- 02:22 B 입력, 02:25–03:00 진행, 03:03–03:07 다른 창이 일부 가림, 03:10 B “정보 없음” 답변 확인.
- 03:25–03:30 B의 W6=foreverhan.tistory.com, 검색 후보 W2=bananapanya.com 표시. W6 패널에 위치/revision UNKNOWN, 답변 인용 확인·실제 사용 미확인, 발췌 제공되지 않음, 원문 관계 UNKNOWN이 보인다.
- 03:35 W6 링크의 foreverhan.tistory.com/2318 열림; 03:50 추천 무기 표의 1순위 “나비의 우화” 및 같은 열 아래 “베스나 전용 무기”가 실제로 보인다.
- 04:00–04:15 답변/내보내기 화면. 선택 프레임에서 Stop 클릭, cancellation terminal, 중지 후 새 질문 실패, 정확한 “예상하지 못한 진단 응답” 문구는 NOT_OBSERVED. 사용자 제보는 유지하지만 이 영상이 취소 원인을 입증하거나 부정한다고 쓰지 않는다. 근거 연결도는 접힌 toggle로 보이며 “graph 기능 자체가 없다”는 결론도 금지한다.

보조 자료는 C:\Users\nninn\AppData\Local\Temp\session469-video\ 아래.
frame-230.png 409,270B / SHA256 886e576e0a0bd1ae4296713f96d8da9c55398e564522ab2ba443b6aa0084a522.
frame-210.png 359,293B / SHA256 a2b599654080f2524ee5e49b4fb4848385f80e2464b6e257ad686b0408be34b9.
frame-190.png 393,721B / SHA256 1f300d99457ca7fa60adc71e5955019bc1a367a69756dbf20b97fce1659f094c.
video-evidence-manifest.json는 39개 선택 프레임과 원본 metadata/hash를 기록한다. sparse frames는 영상 전체 event log를 대신하지 않는다.

### E5. 현재 공개 웹 본문: fixture의 출처

2026-10-07 분석 중 직접 확인:
[W6 기사](https://foreverhan.tistory.com/2318)의 추천 무기 표(웹 추출 L116–127)에 무기명과 전용 무기 열의 관계가 있다.
[BananaPanya 페이지](https://bananapanya.com/ko/game/genshin/vesna/)의 무기별 권장 스탯 표 L104–107에도 “나비의 우화 (베스나 전무)”라는 문구가 있다.
둘 다 비공식 자료의 주장이다. 공식 게임 사실로 확정하지 않고, 당시 backend fetch가 이 같은 revision/body를 읽었다고 가정하지 않는다. 영상의 W6는 당시에 사용자가 browser로 해당 관계를 보았다는 별도 근거다. 테스트는 관계 추출 구조를 검사하며 게임 이름·무기 이름을 제품 정답에 하드코딩하지 않는다.

### E6. 확인된 소스 메커니즘과 미확정 가설

1. 검색 근거: main/java/com/example/lms/service/rag/extract/PageContentScraper.java:133–150의 selected.text()/doc.text()는 HTML 블록·표 행을 평탄화한다. 기존 semantic DOM/hidden/complementary 필터는 이미 :139–143에 있다. WebSearchRetriever.java:1524–1551은 구두점/개행 기반으로 자르고 480자 window의 끝을 구두점으로 되돌린다(:1539–1540). 긴 구두점 없는 flattened body 중간의 관계 행이 사라질 수 있는 경계가 있다. 실제 B body가 없으므로 이 메커니즘이 사건 원인인지는 NOT_PROVEN; 아래 mid-table RED부터 실행한다.
2. 기존 WebSearchRetrieverRelationEvidenceTest.java:36–39는 긴 평탄 body의 끝에 있는 관계를 검사한다. 중간 행+앞뒤 장문과 다열 표의 동일 열 관계가 빠져 있다. :120–124의 조사 suffix 처리도 이미 존재한다.
3. StandardPromptBuilder.java:1007–1015는 web evidence를 항목당 512자로 제한한다. 검색 단계에서 살아도 composer/refit/prompt에서 B 관계 span이 보존되는지 검사해야 한다. DynamicContextCompressor.java:811–850에는 원문 hash/length 보존이 이미 있다.
4. “A 소개의 재사용이 B를 덮었다”는 미확정 가설이다. ChatWorkflow.java:8902–8913의 특별 prior-web reuse는 비교 요청에 한정되며 B는 해당하지 않는다. ChatService.java:125–153의 응답 cache도 session/history/web/RAG 요청을 우회한다. prefetch scope는 ChatApiController.java:2400–2430, WebSearchRetriever.java:268–301에 이미 있다. 이 가드를 중복 추가하지 않는다.
5. 별칭 가설: QueryDisambiguationService.java:517–539는 짧은 “뭐냐” 질문의 추가 disambiguation을 건너뛴다. 활성 소스에서 전무→전용 무기/signature weapon의 명시적 일반 정규화는 발견하지 못했다. NaverSearchService.java:204의 게임어 분류만으로 확장 실행을 증명하지 않는다. alias 추가는 bounded query RED에서 필요할 때만 기존 query 경계에 넣는다.
6. ChatWorkflow.java:2734/3087/3162는 rerank/composer/PromptContext에 finalQuery를 전달하고 :3723–3726은 원래 최신 userQuery를 마지막 사용자 역할에 넣는다. last user가 A로 남는 결함은 이번 읽기에서 발견하지 못했다.
7. RagEvidenceAttributionService.java:444–489는 현재 목록 순서의 W/V/D 번호를 생성한다. A의 W1과 B의 W1은 독립 요청 namespace다. snapshot/message/current URL/bodyHash/locator로 매핑하고 과거 W 번호만 재사용하지 않는다. 실제 모델 입력 경계는 TimedChatModelCaller.java:194–199,357/360의 model.chat(messages)다.
8. 취소 조건부 결함: chat.js:7682–7684 Stop handler와 :6574 cancelActiveStream은 terminal 전 heartbeat를 제거한다. 토큰 없는 :6575–6587은 pendingStopBeforeToken=true와 Stop disabled를 남기고 transport를 abort하지 않는다. header/transport idle deadline은 :7197–7218의 같은 heartbeat interval에 있으므로 stall이 이어지면 :6921–6926 sendMessageInFlight와 :6985–7005 composer lock이 :7075–7090 finally까지 풀리지 않는다. 현재 idle deadline은 elapsed hard deadline이 아니며 :7348의 keepalive가 progress를 갱신한다.
9. exact cancel timeout/rejection도 :6606–6625에서 stream을 유지하지만 heartbeat는 이미 사라진다. 성공 취소 정리 :6527–6545의 timer cleanup(:6533)/terminal latch/controller abort는 보존해야 한다.
10. SOURCE_EXTRACTED RED 실행 증거: 현재 clearActiveStreamHeartbeat/cancelActiveStream/sendMessage 함수를 추출해 tokenless busy·transport-pending 상태를 미리 둔 함수 분기를 모의 실행했다. 실제 fetch/read/deferred transport를 구동한 테스트는 아니다. 결과 heartbeatTimerCount=0, stopDisabled=true, controllerAborted=false, sendMessageInFlight=true, newDispatchCount=0, pendingStop=true, exit=1.
    파일 C:\Users\nninn\AppData\Local\Temp\session469-evidence\cancel-source-extracted-red.js, 1,916B, SHA256 4b8632cc2ef3b171377deca4ef16168c3912efe45e6d4875f0f26ca6481eeb6d. 이는 조건부 현재 소스 RED이며 session469 runtime 원인 확정이나 제품 브라우저 테스트가 아니다.
11. late token 경로 :6142–6148 → completePendingStopBeforeToken:6549–6565는 exact run을 취소하며 ACK를 건너뛴다. ChatRunRegistry.java:537–540의 transport detach는 run cancel이 아니다. :388–419는 기존 nonterminal run 교체를 거절하고 ChatApiController.java:1710–1712는 run_active로 응답한다.
12. 토큰 없는 /state(ChatApiController.java:1254–1258,1325–1329)는 현재 run capability를 돌려주지 않는다. 기존 chat.js:7118–7128,7229의 Idempotency-Key는 pre-token request identity이지만 ChatGenerationAdmissionFilter.java:214–226은 COMPLETED replay/duplicate 거절, :298–305는 OUTCOME_UNKNOWN fence를 유지한다. 프런트만 unlock하고 새 key로 R2를 보내는 우회는 안전한 복구가 아니다.
13. late event 가설: chat.js:922–932의 generation/session/token check와 :7094–7099의 target/terminal latch는 이미 있다. parser error :7286–7292가 target 확인 :7297 전에 global identity를 지우고 일부 final :7335–7336도 clear한다. R1의 late error/final이 R2를 바꾸는지 RED로 증명한 뒤 해당 clear만 target에 묶는다.
14. 진단 오류는 chat-trace-ui.js:543–625의 별도 AbortController와 response URL/content-type(:585–588)/markup(:593–596) 검사다. 취소 source 연결은 입증되지 않았으므로 독립 부수 WP로만 다룬다.

## Work: 단계별 최소 패치와 RED

### W0 / WP0 — 사실 바인딩과 재현 고정

수신자는 Debug-RAG.bat -Action digest의 최신 요약부터 읽고, 기록된 A/B export 및 영상 identity를 위 표와 대조한다. 새 repro를 실행할 때 owner/session/assistant/request/run/snapshot을 실제 receipt로 기록한다. 과거 runId가 없으면 NOT_OBSERVED로 둔다.
위 소스 preimage와 served build는 별도로 대조한다. 병행 소스 drift가 있으면 해당 파일/단계의 인과만 다시 확인한다.
검색 가설은 한 번에 하나씩: 우선 중간 표 관계 span 추출→passage→builder 전달. count 비교만으로 rewrite/캐시/rerank 정책을 변경하지 않는다.

### W1 / WP1 — 첫 두 턴에 맞는 관계 근거 보존

대상 후보(RED 결과로 줄일 것):
- main/java/com/example/lms/service/rag/extract/PageContentScraper.java:133–150
- main/java/com/example/lms/service/rag/WebSearchRetriever.java:1524–1598
- main/java/com/example/lms/prompt/StandardPromptBuilder.java:1007–1015 (최종 fit에서 실제 소실할 때만)
- src/test/java/com/example/lms/service/rag/extract/PageContentScraperTest.java:33,43,66,76
- src/test/java/com/example/lms/service/rag/WebSearchRetrieverRelationEvidenceTest.java:36–39,120–124
- src/test/java/com/example/lms/service/ChatWorkflowPromptMessageRoleTest.java:169–253
- src/test/java/com/example/lms/prompt/StandardPromptBuilderEvidenceMetadataTest.java:89–118

RED 1: test-only synthetic HTML에 A 소개를 앞에 두고, B의 subject–전용 무기 relation–가상 값/비공식 qualifier를 중간 표에 배치한다. 앞뒤 각각 480자 초과·구두점 없는 내용, navigation 반복, 다른 캐릭터의 다른 열, relation이 body 끝에 있는 변형을 포함한다. 한 문서에서 무기명 row와 소유자 row가 다른 행/동일 열에 있는 구조도 검증한다. 단순히 두 단어가 body 어딘가에 있다는 assertion으로 대체하지 않는다.
현재 공식 테스트로 RED를 먼저 확인한다. 기존 scraper의 블록/표 관계 경계 보존 또는 기존 passage window 선택 중 최초 소실이 입증된 한 경계만 고친다. 기존 노이즈 필터/조사 suffix/query scope 가드를 재작성하거나 양쪽을 한꺼번에 바꾸지 않는다.

RED 2: 같은 owner/session A→B를 기존 recording fake final model 경계로 실행한다. A에는 유용한 소개 근거가 전달되고 fixture 응답이 그 근거를 참조하는지 확인한다. B는 최신 질문의 전무 의미·entity를 사용해 관계 값과 qualifier, B 현재 URL/locator를 실제 final messages에서 받는지 확인한다. 실제 답변 품질은 후단 main /chat acceptance에서 검증한다. 최종 compression/refit/512자 변형에서 동일 의미를 보존한다. A [W1]과 B [W1]의 current URL을 다르게 두어 과거 source ID 혼선을 잡는다.
test fixture는 sourceBody→afterFilter→packing→model.chat(messages)→citation→stored/reload의 실제 경계에서 bodyHash/span-present/현재 source locator를 매핑한다. 실제 전송 receipt에는 요청 identity와 전달 messages의 안전한 hash/구조만 기록하고 raw secret/history/debug payload는 공개 UI/trace에 추가하지 않는다.
“전무”와 “전용 무기”/명시 entity와 후속 entity 표현의 bounded query 변형을 먼저 비교한다. 실패가 query scope에 있으면 기존 query builder 한 곳만 최소 수정하고 provider 예산·deadline·cache key·alias scope를 함께 보존한다. 모든 질문의 검색량을 늘리지 않는다.
현재 public 기사 문장은 fixture 설계의 관측 자료다. synthetic 값 교체에도 통과해야 하며 “나비의 우화”를 if/else/프롬프트 정답 패치로 넣지 않는다.
GREEN 판단은 관련 문장의 의미·공급·최종 전달로 한다. source가 비공식이면 답변도 그 출처의 주장으로 설명하고 공식 검증 상태를 구분한다. fresh evidence가 존재하는데 모든 구체 내용을 지우는 “정보 없음” fallback으로 통과하지 않는다.

### W2 / WP2 — Stop 후 동일 세션 새 질문 복구

우선 대상:
- main/resources/static/js/chat.js:6574,6575–6587,6606–6625,7197–7218,7682–7684
- scripts/chat_ui_stream_contract_tests.js:6835–6870,6970–7056

RED 3: 기존 stream contract harness에 search/retrieval status 후 Stop을 만들고 token-late, token-never/keepalive-only, exact cancel timeout/rejection을 가짜 clock+deferred transport로 실행한다. 기존 테스트는 token 도착 또는 외부 stream settle을 강제하므로 그것을 “끝내 token이 없는 복구” PASS로 재사용하지 않는다.
최소 JS 패치: 두 조기 heartbeat cleanup 지점을 terminal 성공 전 cleanup으로 쓰지 않도록 고친다. 성공 terminal 정리(:6533)는 유지한다. 기존 request/controller 및 서버 request budget 경계를 이용해 양의 elapsed 상한과 bounded 재확인 경로를 보강한다. 새 설정 체계를 만들지 않는다. UI를 멈춘 채 timer/Stop/복구 경로가 모두 사라지지 않아야 하며 keepalive가 elapsed 상한을 연장하면 안 된다. 현재 JS에 그 elapsed 상한이 이미 있다고 주장하지 않는다.

token이 끝내 안 오는 경우 JS timer만 고쳐도 완전한 복구는 아니다. transport abort ≠ backend terminal이다.
정확한 소유 request/run 연결이 필요한 RED가 남으면 다음 기존 seam에 한정해서 두 번째 작은 패치를 만든다:
- main/java/com/example/lms/api/ChatGenerationAdmissionFilter.java:214–226,298–305
- main/java/com/example/lms/api/ChatApiController.java:1254–1258,1325–1329,1710–1712
- main/java/com/example/lms/api/ChatCancellationCommandHandler.java
- main/java/com/example/lms/service/chat/ChatRunRegistry.java:388–419,537–540,1215–1292

기존 owner/key/fingerprint receipt와 별도로 입증한 session/run 연결을 사용해 그 요청의 run 등록 또는 terminal 결과를 bounded 재확인한다. 현재 admission 저장 필드(:184–185)에는 session/run 바인딩이 없다. request→run 연결을 추가해야 한다면 기존 admission/등록 경계 한 곳에만 넣고 key/token을 allowlist 진단 밖으로 노출하지 않는다. scope와 exact cancellation capability를 서버에서 검증한다. token이 늦게 오면 기존 exact-run cancel+ACK 미전송 경로를 보존한다.
token이 없는 live run을 “없음”으로 간주하거나 세션 전체 cancel/새 key inference로 우회하지 않는다. OUTCOME_UNKNOWN fence를 무조건 제거하지 않는다. 해당 요청의 취소/terminal 또는 안전한 admission 해제 근거를 확인한 뒤 R2를 한 번만 허용한다. 권한이나 identity가 입증되지 않으면 해당 reconciliation lane만 HOLD하고 검색 WP1은 계속한다.

RED 4: R1 terminal 후 R2를 시작하고 R1 late token/final/error/pending cancel result를 주입한다. R2의 identity/controller/buttons/body/send lock을 바꾸지 않아야 한다. :7286–7292,7335–7336의 unscoped clear가 실패할 때만 현재 generation/session/controller/run에 묶는다.
RED 5: 같은 run reconnect/attach replay에 동일 event/terminal을 반복 주입한다. 사용자/assistant bubble·저장·terminal·버튼 정리가 중복되지 않아야 한다. generation cancel과 search cancel을 별도로 표시하되 shared exact-run 취소 계약은 보존한다.

backend 수정 시 추가 대상 테스트:
src/test/java/com/example/lms/api/ChatApiControllerCancelTest.java,
src/test/java/com/example/lms/service/chat/ChatRunRegistryTerminalIsolationTest.java.
기존 src/chatUiTest/java 경로의 cancellation 소스 문자열 검사는 runtime 의미를 대신하지 못한다. 이 단계의 주 검증은 기존 JS stream harness의 동작 assertion이며, 문자열 형태 불일치 RED와 실제 기능 결함 RED를 분리한다.

### W3 / WP3 — 진단 표시 오류: 독립 부수 항목

main/resources/static/js/chat-trace-ui.js:543–625에서 같은 snapshot 요청의 URL/status/content-type/body-shape를 redacted하게 확인한다. unexpected response가 auth HTML/오류 HTML/shape 불일치인지 구분하며 raw body 전체를 trace에 복사하지 않는다.
Stop/검색 실패와 같은 request identity 또는 state 변화로 연결되는 증거가 있을 때만 부수 최소 패치로 포함한다. 독립이면 H-DIAG로 보고하고 WP1/WP2 완료를 전부 막지 않는다. 추가 로그인/role gate를 도입하지 않는다.

## Acceptance: 실행 명령과 완료 기준

현재 이 문서 작성에서는 아래 제품 unit/browser/live 명령은 NOT_RUN이다. 실행한 것은 첨부 Native text parse, 영상 선택 프레임 검사, 조건부 SOURCE_EXTRACTED 취소 RED뿐이다.

기존 source-extracted RED 재현(임시 증거 파일 존재 확인 후; 현재 exit1):
```powershell
Set-Location -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src'
node 'C:\Users\nninn\AppData\Local\Temp\session469-evidence\cancel-source-extracted-red.js'
```

수신 구현 세션에서 RED 추가 후 필요한 것만 실행:
```powershell
python -B scripts/demo1_vibe_skill_router.py resolve "logs reproducible first answer followup evidence debugging"
python -B scripts/devin_task_orchestrate.py plan --brief "main /chat session469 first-answer immediate-followup relation evidence and exact-run AUTO search cancellation recovery"
.\gradlew.bat test --tests "com.example.lms.service.rag.extract.PageContentScraperTest" --tests "com.example.lms.service.rag.WebSearchRetrieverRelationEvidenceTest"
.\gradlew.bat test --tests "com.example.lms.service.ChatWorkflowPromptMessageRoleTest"
.\gradlew.bat test --tests "com.example.lms.prompt.StandardPromptBuilderConversationHistoryTest" --tests "com.example.lms.prompt.StandardPromptBuilderEvidenceMetadataTest"
node scripts/chat_ui_stream_contract_tests.js
```
새 A→B/중간 표/취소 회귀 method도 기존 class에 추가하고 새 method를 명시해 실행한다. 기존 semanticWebBodyReachesFinalModelWithCompressionAndFinalFit 경로를 함께 유지한다. test 이름/compile sourceSet은 현재 소스에서 재확인한다. 기존 구조적 RED도 새 harness의 동작 테스트로 옮겨 재현한다.
전체 문서의 orchestrator plan은 부수 설명의 단어를 Fold background playbook으로 오분류했다. 위 main /chat 한정 요약의 실제 plan 결과는 matchedPlaybooks=[], preflight-only다. 사용자 범위와 다른 wear/Display 경로를 실행하지 않고, 이 지시서의 W0/W1/W2 순서를 따른다. router 기능 수정은 이번 범위 밖이다.
query seam 수정할 때만 HybridWebSearchQueryBehaviorTest; backend cancel/reconciliation 수정할 때만:
```powershell
.\gradlew.bat test --tests "com.example.lms.search.provider.HybridWebSearchQueryBehaviorTest"
.\gradlew.bat test --tests "com.example.lms.api.ChatApiControllerCancelTest" --tests "com.example.lms.service.chat.ChatRunRegistryTerminalIsolationTest"
```

핵심 repro 3개는 같은 main /chat surface에서 별도로 남긴다:
1. fresh 동일 owner/session의 A: 질문 의미에 맞는 소개 근거가 실제 최종 provider 메시지에 전달되고 답변·인용으로 연결된다. snippet/count만으로 PASS하지 않는다.
2. 그 직후 같은 session의 B: 전무 의미와 entity를 해석하고 B 무기 관계 span·qualifier·현재 source mapping을 전달한다. A 소개 URL/인용으로 치환되지 않는다. 저장 및 reload 후에도 B의 의미/인용이 유지된다.
3. AUTO search 중 Stop → 같은 session R2: reload 없이 입력/Send가 복구되고 exact old run terminal을 확인한 뒤 새 질문이 정확히 한 번 전송·표시된다. old late events가 R2를 종료/덮어쓰지 않고 replay 중복이 없다. generation 중 Stop 변형도 기존 회귀로 확인한다.

오프라인/mock GREEN은 실제 browser/provider PASS가 아니다. 수신 구현에 Java 변경이 있으면 기존 DevWatch/ForceRestart+Verify-RAG 계약대로 rebuild와 served freshness를 확인하되 이 분석 세션에서 실행하지 않는다. 유료 모델 호출은 이 문서로 새로 허가하지 않는다. live 실행이 허용된 수신 세션은 기존 테스트 모델 정책과 비용 한도를 적용하고 실제 run/request/dispatch receipt를 기록한다. 승인/실행이 없으면 해당 live acceptance=NOT_RUN으로 남긴다.
blanket :test/모든 테스트 실행은 금지한다. 관련 compile blocker나 stale served build는 해당 경계만 분리하고 DEFERRED를 PASS로 쓰지 않는다.

## 현재 소스 SHA256 바인딩

관찰 시점 2026-10-07T03:55:55Z–03:57:42Z. 여러 파일을 atomic snapshot으로 읽었다고 주장하지 않는다. 수신자는 exact targets를 다시 해시하고 hash drift면 해당 소스 근거를 재검토한다.

| 경로 | SHA256 |
| --- | --- |
| build.gradle.kts | 39a07d7b1c169d30605209d25082fcb1ef08126b0ef6d0425a7c64440160ca36 |
| main/java/com/example/lms/service/ChatWorkflow.java | 52ebf3bf2118f83e7d62138919db4a10bd6839348876a52ece9a66523337a8b1 |
| main/java/com/example/lms/api/ChatApiController.java | 22a205c6c7668bfa1cabc729e061cc2dec4c472fbe7ef214d13e0e5c24f4f4c8 |
| main/java/com/example/lms/service/rag/WebSearchRetriever.java | ad8bf1a502a9d33ece2cf10d14323098a434db527e9f2e0ede2a0419a80a033e |
| main/java/com/example/lms/service/rag/extract/PageContentScraper.java | a81a90f3b3f68fff2cad8e46e11152ba1b0527df369f383a0b9343a480fdb7b0 |
| main/java/com/example/lms/prompt/StandardPromptBuilder.java | 3b1e6f2b90a069886aee9568fee20f6061d5a1fbfb39574ca35223431f3326d5 |
| main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java | 30cf89aeb05c6f859c3ef2690a42e25eb8b791fd5311164c64db1bae49a95695 |
| main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java | f9d0835a107f844f8d1e7683d447512d2a09a826995d6baa53cc8fdd4a7b8325 |
| main/resources/static/js/chat.js | 9350daa574d8824de3647a7a6a79838c25c23935098e779258ff053f582f92f1 |
| main/resources/static/js/chat-trace-ui.js | 5cab549413256f1da112a9d90c9f85ac2a21392c635f9c310a1330ff6e6ea19d |
| main/java/com/example/lms/service/chat/ChatRunRegistry.java | 59379264fc8398befc41b2b1d16d1ce9abb19b94f1b2fb7c7462a0efa879f55d |
| main/java/com/example/lms/api/ChatGenerationAdmissionFilter.java | dc5b5b060a9c1926cce1c80c264dcf19d624689a9a92820e18238f5b1642ab89 |
| main/java/com/example/lms/api/ChatConversationExportSupport.java | f91a72bcf532f80f95f970d7efdd4cd99ef1c5dcc41c011b59c76d61836b219d |
| main/java/com/example/lms/web/PageController.java | 4f7de7940ba55956e2fb80310daa5943af1b20f2e6b9a47adc1c43d091fed4f7 |
| scripts/chat_ui_stream_contract_tests.js | d26c9dae07a6c97f0d648d133e620688eed24bc37bc533a44ec29ea53626ffcc |

## 금지

- 현재 분석 세션의 제품patch/server restart/paid model call/commit/push; 타 에이전트 파일·lease 덮어쓰기.
- game fact/무기명 정답 hardcode, fake provider 결과, 과거 W 번호를 현재 출처로 연결, count로 원문 관련성/actual dispatch PASS 선언.
- 새 Jev/새 모델 기능/새 서비스·wrapper·route·orchestration framework, 전역 검색 확대, 무한 timeout, 무조건 process kill, global state wipe, 타 사용자/다른 run 취소.
- PROTO_OPEN에 로그인/role gate 추가, admin login block을 완료 조건으로 사용, secret reads/dumps/raw prompts/history를 공개 trace/UI/provider packet으로 내보내기.
- fetch/pull/push/merge/rebase/reset/checkout/restore/stash/clean/history rewrite, add -A/add ./commit -a, 임의 commit.
- 원본 영상·첨부 bytes 변경/삭제, cloud workspace_path의 로컬 존재 가정, ZIP CRC/manifest를 읽지 않고 통과 선언.
- 기존 openssl/opnessl 이름·값 변경, LangChain4j 1.0.1 이탈, 아카이브·비활성 sourceSet에서 제품 코드를 이식.

## HOLD / 다음 probe

- H-ZIP: raw ZIP bytes readable/SHA/CRC/entry manifest NOT_READ. 이번 native JSON/영상/소스 분석과 독립. 새 접근 채널이 실제 bytes를 제공할 때만 확인하며 저장소 전체 HOLD 아님.
- H-HISTORY: 당시 sourceBody/packing/actual dispatch/run/request receipt가 보존되지 않았다. 과거 사건의 인과 확정은 evidence_needed. 다음 probe 하나는 WP1 중간 표 relation fixture를 기존 scraper→retriever→builder→recording model 경계로 실행하는 것.
- H-CANCEL-IDENTITY: 제출 영상의 Stop/회복불가는 NOT_OBSERVED이고 source conditional RED만 확인했다. 다음 probe는 기존 harness의 token-never+keepalive search Stop → scoped terminal reconciliation → same-session R2다. 정확 owner/request/run 연결을 얻지 못하면 그 backend recovery lane만 HOLD; 새 key 추론 우회 금지.
- H-DIAG: unexpected response의 같은 snapshot HTTP 응답·shape 미확인. 취소와 무관하면 별도 항목.
- 외부 live lease와 겹치는 파일은 target-scoped WAIT/재상태 확인 후 해당 단계만 보류한다. 독립 WP를 계속하며 repositoryWideHold=false.
- 분석 완료 ≠ 구현 완료. 보고에는 applied files/RED→GREEN/served build/실제 A→B·cancel R2 acceptance/미실행 항목을 나눠 적고 goal이 검증되면 불필요 추가 작업 없이 종료한다.

외부 API: Native Library 읽기·materialize 시도 및 공개 웹 읽기만. 유료 모델 호출 0.
PLUGIN_USAGE:
- Library: USED(파일 metadata 및 JSON Native text, ZIP 접근 한계 확인)
- Web: USED(요청된 공개 본문의 관계 문장 현재 확인)
- Superpowers: USED(using-superpowers를 읽음; delegated-task 예외 적용)
- Codex collaboration: USED(검색 소스·취소 소스·영상의 읽기 분석 분담)
- Browser: NOT_RUN(이번 제품 browser/live 재현은 미실행)
- GLM=NOT_USED
