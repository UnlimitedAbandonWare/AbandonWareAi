<!-- HISTORICAL SESSION LOG — NOT API / MODEL SoT. Do not copy gemma3:4b / qwen2.5:7b-instruct / qwen3:8b. Use qwen3.5:9b + AGENTS.md MODEL LOCK.
Do not copy model names, ports (e.g. 11436), or $/min transcription prices from this file into code or skills.
Current API SoT: docs/API_ROUTING_SPEC.md + configs/api-routing.yaml + .agents/skills/demo1-api-routing-inventory/
-->

# Meta Ray-Ban Display Conversate v1

작성·조사 기준: 2026-09-13 KST. 전체 목표는 미완료이며 추가 설계 응답·실제 연결 환경을 기다리는 상태다.

## 이름과 목표

이 저장소에서 **메타 Conversate**, **레이벤 대화 보조**, **G2처럼 대화 힌트**는 다음 목표를 가리킨다.

> 사용자가 명시적으로 시작한 대화를 음성으로 받아 한국어로 이해하고, 준비 자료와 허용된 근거를 확인하여 필요한 순간에 짧은 답변·설명·다음 질문을 Meta Ray-Ban Display에 보여 주는 실시간 대화 보조 기능.

일반 챗봇 답변, 음성 받아쓰기, Display 동기화 화면만 각각 성공했다고 전체 기능을 완료로 판정하지 않는다. 휴대폰 입력, 실제 ASR, 발화 수정·취소, 근거 확인, 카드 의미, 안경 표시를 같은 실행 흐름에서 검증한다.

## 기존 승인 범위와 추가 설계

기존 승인된 실행 계약은 `docs/superpowers/plans/2026-09-12-conversate-integration.md`다. 기존의 로컬 음성 인식, 준비 자료 선택, 근거 답변, 중복 억제, 짧은 카드, 일시정지·종료·미저장 경로는 계속 구현·검증할 수 있다.

이번에 제안한 아래 네 종류의 힌트와 제안 생성은 **설계 응답 대기**다. 기존 계약이 금지한 새 제안을 승인받은 것으로 간주하지 않는다.

| 카드 | 의미와 표시 조건 | 예시 |
| --- | --- | --- |
| 답변 | 현재 질문에 답하는 사실. 선택한 근거와 수치·조건·부정 표현 일치 필수 | 미개봉 제품은 7일 이내 환불이 가능합니다. |
| 용어 | 대화에서 나온 용어의 짧은 설명. 출처가 없으면 확인 요청 | 준비 자료에 있는 SLA의 뜻 |
| 인물 | 자료에서 식별된 인물의 관련 소개. 동명이인 불명확하면 보류 | 선택한 회의 자료의 발표자 소개 |
| 다음 질문 | 사실과 분리한 대화 진행 제안. 화면에 반드시 ‘제안’ 표시 | 제안: 적용 예외도 확인해 볼까요? |

사실 카드는 기존 최대 120자·한국어 2~3줄 계약을 유지한다. 근거 부족·충돌·기한 만료·음성 수정 이후의 오래된 결과는 표시하지 않는다. 초기에는 번역, 회의록 영구 저장, 인물 자동 검색·식별, 자동 외부 검색을 범위에 넣지 않는다. 대화 원문·오디오·카드는 세션 종료 후 저장하지 않는 기존 정책을 유지한다.

## G2와 Meta 공식 자료에서 확인한 점

Even G2의 Conversate는 실시간 전사와 대화 중 Concepts/Bios/Answers/Suggestions 큐, 준비 노트, 요약 기능을 설명한다. 여기서는 짧은 상황별 힌트를 제품 목표로 참고하고, 저장·번역까지 동일하게 구현한 것으로 주장하지 않는다. [Even 공식 Conversate 설명](https://support.evenrealities.com/hc/en-us/articles/14273795154319-Conversate)

Meta는 Display용 HTML/CSS/JS 웹 앱과 iOS/Android Device Access Toolkit을 구분한다. 공식 FAQ에서 안경 마이크·스피커 접근은 모바일 Bluetooth 프로필 경로를 명시한다. 공개 웹 앱 기능 목록만으로 연속 원시 마이크 스트림을 사용할 수 있다고 확인할 수는 없다. 이는 웹 마이크가 모든 환경에서 불가능하다는 단정도 아니다. [Meta Wearables FAQ](https://developers.meta.com/wearables/faq/)

Meta 웹 앱의 텍스트 입력기는 사용자가 입력 필드를 선택·탭하면 음성/필기 입력을 열어 완성된 텍스트를 전달한다. 이 입력 흐름은 주변 대화를 계속 수집하는 PCM 스트림과 구별해야 한다. [Meta 공식 텍스트 입력 가이드](https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/skills/add-text-input/SKILL.md)

안경 웹 앱 배포에는 공개 HTTPS가 필요하고, 600×600 화면과 검은 배경의 표시 특성을 고려해야 한다. 일반 Chrome 검사와 공식 Simulator·실기 검사는 별도 단계다. [Meta 웹 앱 저장소](https://github.com/facebook/meta-wearables-webapp)

따라서 초기 구현은 **휴대폰 마이크 → 서버 ASR → 서버의 기존 Conversate → 안경 웹 출력**을 기준으로 제안한다. 안경 마이크까지 직접 쓰려면 실제 연결 휴대폰의 OS와 Bluetooth 오디오 라우팅을 확인한 뒤 모바일 동반 앱 경로를 추가한다. Mac mini는 필수 구성품이 아니다. 현재 Windows의 설치된 Python·Whisper 경로로 합성 한국어 음성을 실제 인식했다.

## 재사용할 실제 소스

| 책임 | 현재 소유 파일 |
| --- | --- |
| Ollama 시작·기존 서버 재사용·모델 예열 | `main/java/com/example/lms/config/LocalLlmProcessManager.java` |
| 세션·인증·CSRF·음성 입력 API | `main/java/com/example/lms/assist/ConversateController.java` |
| epoch·중복 억제·발화 수정·SSE 카드 | `main/java/com/example/lms/assist/ConversateSessionService.java` |
| 질문 선택 | `main/java/com/example/lms/assist/ConversateQuestionPolicy.java` |
| 준비 자료 권한 경계 | `main/java/com/example/lms/assist/ConversatePreparedMaterials.java` |
| 근거 검색·선택과 선택적 요약 | `main/java/com/example/lms/assist/ConversateAnswerPipeline.java` |
| 휘발성 전용 프롬프트·로컬 생성·검증 | `main/java/com/example/lms/assist/ConversateCardPrompt.java`, `ConversateLocalCardGenerator.java`, `ConversateCardVerifier.java` |
| 음성 자식 프로세스 경계 | `main/java/com/example/lms/assist/ConversateAsrBridge.java` |
| 한국어 VAD·Whisper 스트림 | `tools/conversate-asr/stream.py` |
| 휴대폰 입력·출력 화면 | `main/resources/static/conversate/` |

이 경로는 선택한 준비 자료의 BM25/어휘 검색이다. 전체 서비스의 모든 RAG 자료·인터넷 검색과 연결된 것으로 설명하지 않는다. 과거 삭제된 OpenAI 오디오 구현이나 비활성 소스 트리는 복구하지 않는다.

## 초기 음성 선택

로컬 현재 선택: 기존 faster-whisper 1.2.1 + webrtcvad-wheels 2.0.14에 격리 설치한 small 모델. CPU/int8, 한국어, PCM16LE 16kHz mono. 기존 tiny 모델은 보존했다. tiny가 환불 질문의 핵심 단어를 놓쳤고 small이 같은 문장을 정확히 인식해 모델 설정을 바꿨다. 다만 small의 현재 CPU 지연은 길며, 한 개 합성 발화의 통과는 소음·다화자·장시간·고유명사 정확도 보증이 아니다.

유료 대안은 별도의 음성 어댑터로 선택한다. 현재 공식 가격의 `gpt-4o-mini-transcribe` 예상 비용은 분당 $0.003, 60분 약 $0.18이다. 현재 실시간 전사용 `gpt-live-transcribe`는 분당 $0.017, 60분 약 $1.02다. 전자는 후자와 동일한 실시간 상품 요금이 아니며, 세금·네트워크·후속 LLM 비용은 별도다. 실제 과금 호출은 실행하지 않았다. [OpenAI 공식 가격](https://developers.openai.com/api/docs/pricing)

실시간 어댑터를 선택하면 서버 WebSocket 또는 브라우저 WebRTC, 발화별 delta/completed 처리, item_id 기반 순서 복구, 취소 시 전송 종료가 필요하다. 공식 예제 PCM은 24kHz이므로 기존 16kHz 경로와 형식을 맞춰야 한다. API 키는 서버의 기존 비밀 관리 경로만 사용한다. [OpenAI 실시간 전사 가이드](https://developers.openai.com/api/docs/guides/realtime-transcription)

Wolfram 계산 확인: 16,000 sample/s × 16bit × mono = 32,000 byte/s, 1시간 약 115.2MB의 비압축 음성이다. 이는 전송량 계산이며 실제 저장·과금·대역폭 측정이 아니다. 이 구현은 원시 음성을 저장하지 않는다.

선택적으로 필요한 순간에 짧게 개입하는 설계는 ProMemAssist 연구의 방향과도 맞는다. 이 연구의 12명 평가가 우리 장치의 품질을 증명하지는 않는다. [ProMemAssist 원 논문](https://arxiv.org/abs/2507.21378)

## 현재 구현 및 증거

실행 기록·설정·검증 결과: `data/agent-handoff/conversate/20260913-01a097d6/SETUP.md`. 후속 소스 수정은 `CONTINUATION-2.md`, 지연·브라우저 증거는 `CONTINUATION-3.md`, 꺼진 Ollama의 실제 시작과 Display 클라이언트 재검증은 최신 `CONTINUATION-4.md`에 기록했다.

- 현재 소스에서 컴파일한 인증된 로컬 개발 런타임을 Chrome에 열었다.
- 합성 한국어 음성 → 실제 ASR → 준비 자료 → 의미·출처가 맞는 카드 → 종료를 관찰했다. 휴대폰·안경 실물 입력은 미검증이다.
- Ollama 11435의 초기 gemma3:4b 검사 후, 설치된 qwen2.5:7b-instruct를 검증하고 작업용 생성·예열 모델로 선택했다. 실제 매니저가 서버 재사용 및 chat 예열 후 READY를 반환했다. OS 부팅 자동 실행, 꺼진 서버의 실제 시작, GPU 지정 증거와는 구분한다.
- 기존 `ConversateCardPrompt.java` 한 파일에서 후보의 0 기반 번호, 실제 반례의 의미, 생성 순서를 명확히 했다. 검증기는 유지했다. 수정된 소스의 브라우저 수동 입력 사례가 GENERATED, 실제 생성 1회·2164ms, 의미 일치로 통과했다. 이전 실패 기록은 유지한다.
- 관련 48개 Java 검사가 통과했다. tiny를 쓴 음성 환불 사례는 NO_MATCH였고, 원문을 저장하지 않는 비교로 ASR의 핵심 단어 누락을 확인했다. 격리 설치한 small을 적용한 재검증에서는 실제 ASR → 실제 Ollama 생성 → 의미·출처가 맞는 카드까지 통과했다. 음성 종료부터 카드까지8847ms로 아직 지연 개선이 필요하다. 브라우저는 같은 세션에 연결됐지만 관찰 시점에는 이미 종료돼, 이 오디오 사례의 표시 중 스크린샷은 확보하지 못했다.

## 완료 조건과 다음 작업

1. 네 종류 힌트와 로컬 우선·선택적 유료 음성 설계에 대한 사용자 응답을 반영한다. 기존 범위에는 승인을 반복 요구하지 않는다.
2. 실제 Ollama 응답이 자료가 충분한 한국어·수치·부정·조건 사례에서 검증을 통과하고, 부적절한 출력은 계속 보류하는지 증명한다. 다음 실험은 합성 입력만 사용하며 단순/복합 요청을 구분하고, 설치된 모델을 바꾸는 경우 한 모델·한 요청씩 명시적으로 제한한다. 동일 실패를 반복 호출하지 않는다.
3. 변경이 필요하면 현재 sourceSets와 소유권을 확인하고 기존 세 역할 사전 검토·lease·preimage 경계를 통과하여 최소 소스를 수정한다.
4. 현재 외부 설정을 실제 서비스 기동에 적용할 때 기존 인증·JDBC·admission 구성 준비를 확인한다. 테스트 계정과 fixture를 실서비스에 연결하거나 admission을 해제하지 않는다.
5. 공개 HTTPS와 실제 휴대폰의 마이크·중단·재연결을 확인한다. 안경 직접 마이크 경로는 휴대폰 OS·페어링·BT 권한·장치 선택 증거를 요구한다.
6. 공식 Simulator에서 표시·입력, 실제 안경에서 가독성·입력·연결 복구·종료를 확인한다. 실제 사용자 음성을 저장하지 않고 유형·횟수·지연·오류 분류만 기록한다.

최신 지연 개선: `conversate.asr.cpu-threads`를 기존 음성 자식 프로세스에 전달하도록 추가했다. 기본 2, 허용 1~8이며 범위를 벗어나면 시작하지 않는다. 이 Desktop의 작업 설정은 8이다. 같은 오디오 해시의 CPU 2/4/8 비교에서 세 경우 모두 정확히 인식했고, 최종 인식까지8172/6250/5781ms였다. 수정 후 새 서버의 실제 음성→Ollama→카드 검증은 음성 종료 뒤5501ms, 생성1회2337ms, 일시정지65ms로 통과했다. 이 실행은 브라우저 스크린샷에 답변 카드가 실제 표시되었고, 근거 버튼 이후 AX에 근거 ID가 나타났다. 근거 ID 자체의 스크린샷 표시는 확인하지 못했다. 같은 입력의 한 번 비교이며 전체 지연 차이에는 LLM 응답시간 변동도 포함된다. Java48개·Python6개가 통과했고, 종료 뒤 활성 ASR 자식0을 확인했다.

꺼진 서버의 자동 시작도 별도로 검증했다. 기존 `LocalLlmProcessManager`가 비어 있던 11436 포트에 실제 Ollama를 시작하고 설치된 Qwen 모델을 예열해13835ms에 READY가 됐다. CPU 메모리 적재를 확인했고, 매니저 종료42ms 뒤 소유 프로세스 종료·포트 해제를 확인했다. 기존11434/11435/18088 서버는 유지했다. Windows 부팅 자동 실행이나 GPU 시작 검증은 아니다.

전체 root 서비스는 아직 미검증이다. 기존 verification 프로필은 임시 H2를 쓰지만 Ollama 자동 시작을 금지하므로 현재 설정을 그대로 덧씌워 전체 기동을 주장하지 않는다. 최신 read-only 진단에서 Upstash URL/token은 process·User·Machine 환경 범위에서 모두 확인되지 않았다. 별도 Meta sync의 E0~E2는 실제 Desktop resolver, 새41개 클라이언트 테스트, 600×600 CSS 브라우저 탐색 검증으로 갱신했다. 이전 기록은 보존했고 현재 선택기는 E3 실제 sync/session 증거를 요구한다. 합성 UI 검증으로 E3~E4를 완료 처리하지 않았다.

현재 `goalStatus=blocked`. 추가 설계 응답과 실제 연결 환경의 미확인 조건이 세 차례 이상 이어졌고, 알려진 독립 로컬 검증을 마친 뒤 목표 도구에 대기 상태를 기록했다. 전체 목표는 미완료다. 기본 음성 카드 성공, Ollama 모델 준비, 생성 힌트 의미 성공, 서비스 통합, HTTPS, 휴대폰, Simulator, 실기 상태를 따로 보고한다. 목표를 로컬 데모 통과로 축소하지 않는다.
