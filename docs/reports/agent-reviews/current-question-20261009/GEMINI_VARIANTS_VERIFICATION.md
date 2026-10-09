# Gemini Flash / Flash-Lite 현재 경로 검증

확인일 2026-10-09. 기존 제공자·카탈로그·선택 저장·Focus 경로를 그대로 사용했고 추가 application source 변경은 하지 않았다. 새 공급자/키/유료 서비스 활성화, 본인 성공 프로필 변경, 재기동은 0이다.

| 모델 | 현재 selectable route | 카탈로그 및 실제 응답 모델 | 실제 고립 Focus 결과 |
|---|---|---|---|
| Flash | llmrouter.gemini-pro | gemini-3.8-flash | ANSWER_READY, 약 5097ms, 27자, fallback false |
| Flash-Lite | llmrouter.gemini-cue | gemini-3.5-flash-lite | ANSWER_READY, 약 3079ms, 27자, fallback false |

새 브라우저 context 두 개, task-owned 고정 test channel, 각 1회로 generation ceiling 2를 지켰다. 각각 일반 API_ONLY/FIXED, search OFF, fallback OFF, STANDARD, 200자 설정을 정상 settingsVersion CAS로 저장·다시 읽은 뒤 질문했다. 본인 live 프로필에 저장하지 않았다. 검색 ON/native grounding 또는 실제 마이크/안경 검증으로 확대하지 않는다.

Flash requestHash `hash:d3a50a991601`, sessionHash `hash:f3bf8cad9114`, epoch 1. Lite requestHash `hash:ee53ebe99673`, sessionHash `hash:47dd898658fb`, epoch 1. 각 고립 session/epoch에서 accepted request 하나를 확인해 question_confirmed→generation_started→model_attempt→terminal success/none을 연결했다. requested/selected route hash와 actual result model hash가 각각 정확한 route/model의 SHA-256과 일치하고 fallbackCount=0이다. 서로를 대신 사용한 성공이 아니다.

원래 helper가 제출한 client input UUID의 hash를 내부 accepted requestHash로 가정해 diagnostics=0 / FAIL을 기록했다. 원본 `focus-gemini-variants/evidence.json`은 유지했다. 실제 내부 requestHash를 동일 고립 session/epoch의 단일 accepted request에서 확인한 `focus-gemini-variants/correlated-runtime.json`이 두 경로의 runtime PASS 근거다. 추가 generation은 하지 않았다. 답변의 사실 정확도·기기 가시 시간·provider wire/HTTP는 이 경로 검증의 PASS 대상이 아니다.

기존 Gemini 프리셋은 현재 selectable gemini-pro를 우선 선택한다. Flash-Lite는 같은 모델 목록의 gemini-cue를 직접 선택할 수 있다. 프리셋은 편집값만 바꾸며 정상 설정 저장 후 다음 확정 질문에 적용된다. 프리셋의 웹검색 전용 설정과 이번 일반 API_ONLY/search OFF probe는 구분한다. 본인 OAuth Luna profile v7을 Gemini 시험으로 덮어쓰지 않았다. 카탈로그에서 사라진 route를 하드코딩하거나 선택 불가 row를 허용하지 않는다.

Exa 공식 문서 확인 2026-10-09: [Gemini 3.8 Flash](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash), [Gemini 3.5 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite). 각각 현재 exact stable model code가 있으며 Flash thinking은 low/medium/high를 지원한다. 공개 규격은 로컬 실제 결과와 별도 근거다. meta-display.yml의 pro/cue default와 live catalog의 modelId 및 실제 결과를 모두 대조해 base config/environment override 추정만으로 판단하지 않았다.

기존 fallback OFF/허용 목록/배너/정상 초기화/lens 경고 제외 계약과 최종 Java 190 / JS 188 증거를 유지했다. 모델 경로의 실패가 재현되지 않았으므로 별도 repair나 리팩터링을 추가하지 않았다. 사용자 발언 “잘되더라”는 USER_REPORTED_SUCCESS이며 특정 Gemini/Luna 또는 실제 렌즈 출력의 직접 증거로 확대하지 않는다.

Luna 단계 정리 helper는 empty disposable list와 deleteAuthorized=false에 completion-authorization-missing을 반환해 completion receipt를 만들지 않았다. 삭제 0, source 재적용 0이다. 초기 HOLD와 함께 기록 lane에 남기며 소스/테스트/runtime 실패로 확대하지 않는다. 원본·diff·최종 보고·로그·회복 bytes를 모두 보존했다.

외부 API: 공식 Exa 문서 2개 fetch; 기존 Gemini 고립 generation 정확히 2회. 본인 프로필 쓰기 0, 추가 source edit 0, 재기동 0.
PLUGIN_USAGE:
- Superpowers: USED(existing boundary/one-cause verification discipline; no new product defect reproduced)
- Browser: USED(fresh isolated Playwright Focus contexts; no auth state restored/persisted)
- Exa: USED(two official exact-model documents)
- GitHub: NOT_USED(no new diff/source mutation in this phase)
- AWX Control Tower: NOT_USED(no compile/test/start failure in this phase)
- AWX Control Tower Recovery: NOT_USED
- GLM=NOT_USED(read-only verification phase; no new patch)
- Computer: NOT_USED
- Vercel: NOT_USED
- Sites, Plugin Management, Data, Visualize, Meta Wearables Webapp, Supabase: NOT_USED
