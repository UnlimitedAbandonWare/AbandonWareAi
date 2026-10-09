# 이 실험은 철회됨 — 일반 OpenAI API admission 변경은 현재 소스에 없음

아래 내용은 비용·경로 확인 전의 역사적 실험 기록이다. 사용자가 원하는 Luna는 Codex OAuth Luna였고 일반 OpenAI API 활성화가 아니어서, task `codex-focus-luna-admission-4b1e8e6a` cycle-01의 검증된 preimage 복구로 YAML 및 catalog test 변경을 모두 철회했다. 일반 API generation은 0회였다. 142 GREEN은 이 철회된 실험 소스의 과거 결과이며 현재 최종 검증 수로 사용하지 않는다.

현재 YAML SHA256=263c73b012e8206f877896b6142bdc7c45304065935ba60b25dbda1b3397faa4; catalog test SHA256=22fa395959e2aed985b81b3bc02ba1b5375083ebfe032eeca77c5b521b34e3cd. 현재 수정은 선택 불가 API alias를 선택하던 Luna 프리셋을 selectable OAuth로 교정하는 별도 source patch다. 최신 결과는 `LUNA_PRESET_FALLBACK_REPAIR.md`를 확인한다.

---

# 실제 Focus Luna 실패의 개별 경로 허용 누락 — 2026-10-09

앞선 AUTO 기본 모델 변경은 이번 실사용 실패를 해결하지 못했다. 실제 사용 프로필은 FIXED `llmrouter.openai-economy`였으므로 AUTO 기본값을 참조하지 않았다. 해당 경로는 현재 화면의 Luna 프리셋이 지원하는 기존 OpenAI API 경로인데, meta-display profile의 개별 허용 목록에서 빠져 있었다. 이번 수정은 `app.ai.remote-model-selection-routes`의 기본 목록에 `openai-economy` 한 항목을 추가한다.

## 실제 요청과 원인

새 dev runtime의 안전한 진단에서 5건의 실사용 실패를 관측했다. 대표 요청은 18:45:26.988 KST 확정 → 18:45:27.016 KST 종료, requestHash `hash:8266950dac85`, ownerHash `hash:33d5f21b86f3`, sessionHash `hash:5d6a2e884f59`, serverInstanceHash `hash:5427a7a1ecf3`, epoch 3이다. 같은 identity tuple의 확정/생성 시작/모델 시도/실패/terminal을 연결했다. terminal은 `provider_not_configured`, exception/root는 `ModelSelectionException`, latency 25ms다. 다른 4건도 같은 서버/사용자 세션의 25–111ms 실패였다. 사용자 질문·답변 본문, owner key, 인증 자료는 출력/보존하지 않았다.

기존 `db_agent.py query --via live`를 사용한 읽기 전용 projection에서 live channel의 저장 프로필을 owner hash로 연결했다. settingsVersion=6, FIXED `llmrouter.openai-economy`, API_ONLY, fallback=false, 검색=false, STANDARD, 답변 200자, quick=false, snapshot=false, recall/remember=false다. 현재 catalog의 정확 ID는 provider=openai, modelId=gpt-5.6-luna, apiSurface=responses, selectable=false, reason=`remote_selection_disabled`였다. 다른 catalog 선택 가능 경로의 존재는 이 명시 선택에 대한 성공 증거가 아니다.

`NovaFocusAnswerService.primaryModel`의 FIXED 분기는 저장 ID를 유지한다(472–475). `executeModels`는 같은 catalog row의 selectable=false를 모델 호출 전 거절한다(545–546). `ChatModelCatalogService.failureCode`는 disabled 이유를 `provider_not_configured`로 변환한다(420–421). 따라서 이번 동일 설정의 첫 실패 경계는 catalog admission이며, 실제 credential 누락 또는 provider HTTP 실패를 의미한다고 확대하지 않는다. 현재 row는 remote-selection 이유만 표시했고 provider 실행은 일어나지 않았다.

`display-focus-controls.js:146`의 Luna 프리셋은 openai-economy/API_ONLY/searchOFF를 저장 가능한 form으로 만든다. nonempty model을 FIXED로 저장하는 기존 제출 경로가 있다. 이 구조는 관측 설정의 가능한 출처이며 누가 언제 선택했는지의 증거는 아니다. 저장 FIXED를 AUTO로 바꾸거나 다른 모델로 치환하지 않는다.

## 변경과 검증

- production: `main/resources/application-meta-display.yml:203`의 기존 route 기본 목록에 `openai-economy` 추가 1줄.
- regression: `ChatModelCatalogServiceTest`에 실제 profile을 읽는 Luna preset admission 회귀 1개, 21줄 추가.
- RED: `luna-admission-red/run.json`, 1개 실행/1개 assertion 실패/오류0/skip0, runId `e1a24034-a68d-487c-98d3-8486f41c49f8`.
- GREEN: `luna-admission-green/run.json`, 영향 있는 7개 클래스 142개 실행/실패0/오류0/skip0, runId `ff151776-9a3c-4e08-a185-933b0c2d873a`.
- 새 회귀는 Luna admission, 다른 openai-balanced 경로 차단, missing_api_key 차단, 명시 환경 override 보존을 검사한다. 기존 FIXED 보존/history 회귀와 질문 보존 회귀도 재실행했다. blanket test를 실행하지 않았다.
- 글로벌 allow-remote-model-selection, 인증/role, credentials, model ID, provider 계약, stored selection, fallback/search/reasoning 설정은 변경하지 않았다. 새 provider나 키를 추가하지 않았다. OpenAI API Responses와 ChatGPT OAuth Luna를 구분한다.

## 소유와 기록

작업 `codex-focus-luna-admission-4b1e8e6a`의 2개 target에 OWNER/CLEAR preflight와 source lease를 확보했다. checkpoint preimages를 저장한 뒤 테스트/설정을 수정했다. 중간 lease verify의 preimage-changed는 자신이 추가한 test 21줄과 일치했다. 미수정 YAML은 원래 preimage 해시를 별도로 검사하고 지원된 checkpoint apply로 교체했다. 같은 lease의 seal/finish가 postimage를 검증해 verified다. 외부 writer의 hunk를 덮어쓰거나 기존 lease를 강제 교체하지 않았다.

`cycle-01/change.diff`는 production 1줄 교체와 test 21줄 추가만 포함한다. profile postimage SHA-256 `92abcd8a0864461a2a71e0bde8614fbad7f1d6483e570ad07085487b3fdbf62f`, test postimage SHA-256 `3941c92293ee7fb535652bc41830f07b393190b2f21dd3d75a115534f963adcd`이며 GREEN sourceIdentity와 연결된다. 최초 질문 보존 단계의 별도 checkpoint bookkeeping HOLD는 역사적 기록으로 보존한다.

## 외부 근거와 한계

2026-10-09 [OpenAI 공식 reasoning 문서](https://developers.openai.com/api/docs/guides/reasoning)에서 Responses API의 reasoning.effort가 모델별 계약임을 확인했고, [공식 conversation-state 문서](https://developers.openai.com/api/docs/guides/conversation-state)에서 stateless/store=false 경계를 확인했다. 현재 gateway/model 계약을 새 문서 예제로 재작성하지 않았다. 본 변경은 API payload가 아닌 기존 route admission 설정이다.

새 영상 Library metadata는 별도 지원 경로에서 확인됐지만 영상 원문은 열람하지 못했다. 영상을 직접 봤다고 주장하지 않는다. 기존 AUTO 또는 FIXED cue 합성 성공과 OAuth 채팅 성공은 이번 실사용 FIXED/API Luna 실패의 해결 증거로 계산하지 않는다. 실제 같은 설정의 Focus 생성 및 현재 서버 반영 결과는 아래에 별도로 기록한다.

외부 API: Exa 공식 OpenAI 규격 조회; 기본 AWX actual RED 로그 분류(primaryClass=other, 판단은 XML/소스); 실사용 본문 외부 전송 없음.
PLUGIN_USAGE:
- Superpowers: USED(실제 요청 상관관계→단일 원인→RED/GREEN→실행 증거)
- Browser: NOT_USED_IN_THIS_SLICE(기존 격리 Focus API 경로의 합성 검증 도구 재사용)
- GitHub: NOT_USED_IN_THIS_SLICE(직전 local/remote SHA 보조 증거만 보존, Git mutation 없음)
- Exa: USED(OpenAI 공식 Responses/reasoning 규격 확인)
- AWX Control Tower: USED(기존 기본 MCP로 실제 RED 로그만 분류)
- AWX Control Tower Recovery: NOT_USED
- GLM=SESSION_UNAVAILABLE(이전 독립 검토 timeout; fallback explorer 읽기 검토, 동의는 PASS 아님)
- Computer, Vercel, Sites, Plugin Management, Data, Visualize, Meta Wearables Webapp, Supabase: NOT_USED
