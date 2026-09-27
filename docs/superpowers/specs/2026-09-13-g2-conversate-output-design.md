# G2 Conversate 출력 연결

상태: 진행하지 않음. 2026-09-13 사용자가 대상은 G2가 아니라 Meta Ray-Ban Display이며, G2 Conversate는 참고할 기능이라고 정정했다. G2 SDK·Simulator 설치나 G2 소스 구현은 승인되지 않았고 수행하지 않았다. 아래 내용은 채택되지 않은 제안 기록으로만 보존한다. 현재 실행 대상은 기존 Meta Conversate 앱과 그 기기 설치 경로다.

## 사용할 앱

기존 Conversate에서 사용자가 대화를 시작하고, 준비 자료를 선택한 뒤, 휴대폰 마이크 또는 텍스트로 확정 발화를 보낸다. 서버가 확인한 짧은 한국어 답변을 기존 Meta 화면과 G2 출력에 전달한다. G2 첫 버전은 답변·대기·일시정지·종료를 표시하며, 부정·수치·조건·출처·유효기간을 보존한다. 새 답변이 없거나 근거가 부족하면 임의 내용을 채우지 않는다.

검토한 선택지는 다음과 같다.

| 선택 | 얻는 기능 | 추가 조건 |
| --- | --- | --- |
| 기존 Conversate + G2 출력 어댑터 (권장) | 이미 있는 음성·근거 검증을 재사용하고 G2 표시/지우기를 독립 시험 | 공식 SDK, 인증된 G2 웹앱 맥락, Simulator/실기 연결 |
| G2 마이크 직접 입력까지 확장 | 휴대폰 마이크 대신 G2 PCM 수신 | 마이크 권한, SDK 이벤트 형식, 기존 ASR 청크 경계·중지 시험 |
| 별도 G2 전체 앱/백엔드 | 별도 제품 구조 | 인증·세션·ASR·검증 로직이 중복되므로 이번 제안에서 제외 |

이번 구현 제안은 첫 번째다. 마이크 직접 연결, 번역, 영구 요약 저장, 인물 자동 식별, 4종 힌트 생성 확장은 후속 결정으로 남긴다. 현재 준비된 근거 답변 경로를 다른 작업과 중복 수정하지 않는다.

## 실제 연결 경계

기존 `ConversateController`의 인증된 `/api/assist/bootstrap`과 `/api/assist/sessions`를 사용한다. 변경 요청은 bootstrap이 제공한 CSRF 헤더를 사용하고, 쿠키를 읽거나 복사하지 않는다. G2 웹앱 자체의 인증 맥락에서 로그인해야 하며, 휴대폰/PC의 쿠키가 자동으로 공유된다고 가정하지 않는다. 공개 HTTPS와 기기 접근 설정은 실제 권한·접근제어를 확인한 뒤 별도로 수행한다.

출력은 `/api/assist/sessions/{assistId}/output?epoch={epoch}`의 `assist` SSE 이벤트를 사용한다. 스냅샷의 `assistId`, `epoch`, `version`, `state`와 `card(decision,kind,text,sourceIds,expiresAt)`만 필요한 범위에서 소비한다. 기존 `main/resources/static/conversate/app.js`의 상태·만료·종료 판단을 재사용하는 작은 출력 호출 지점을 만들고, G2 파일이 서버 수명주기를 다시 구현하지 않도록 한다.

예상 변경 표면은 기존 `main/resources/static/conversate/app.js`의 출력 호출 지점, G2 연결을 명시적으로 여는 최소 UI, 별도 G2 출력 어댑터 및 집중 계약 검사다. 실제 파일 목록은 현재 preimage와 병행 작업 소유권을 확인한 뒤 기존 세 역할 사전검토·선언 대상 임대로 고정한다. 새 Java API·서비스·보안 예외는 계획하지 않는다.

## G2 화면과 SDK

공식 ASR 예제는 `waitForEvenAppBridge()`로 연결하고, `TextContainerProperty`를 `CreateStartUpPageContainer`에 넣어 `createStartUpPageContainer`를 호출한다. 표시 영역은 576×288이며, 한 개의 텍스트 컨테이너에 `isEventCapture: 1`을 둔다. 생성 결과 0만 성공으로 처리한다. 일반 브라우저에서 브리지가 없으면 연결되지 않은 상태로 남기고, 기기 연결 성공을 표시하지 않는다. [공식 ASR 예제](https://github.com/even-realities/evenhub-templates/blob/main/asr/src/main.ts)

내용 갱신은 `textContainerUpgrade(new TextContainerUpgrade(...))`로 수행한다. 한 번에 하나만 갱신하며, 대기 중 여러 새 카드가 오면 가장 최신 유효 카드 하나로 합친다. 메모리의 카드와 전송 중인 카드의 세대가 달라졌다면 오래된 완료 결과를 수용하지 않는다. 중지·만료·epoch 변경에서는 화면 지우기를 우선하며, 실패하면 연결 상태를 실패로 표시하고 마지막 카드가 지워졌다고 주장하지 않는다. [공식 SDK 참조](https://github.com/even-realities/everything-evenhub/blob/main/plugins/everything-evenhub/skills/sdk-reference/SKILL.md)

패키지는 `@evenrealities/even_hub_sdk`의 실제 레지스트리 버전과 라이선스를 확인한 뒤 정확한 버전을 고정한다. 아직 패키지 설치나 production dependency 추가는 수행하지 않았다. 아래 설계 승인 범위에는 이 출력 기능에 필요한 공식 SDK 하나와 개발용 공식 Simulator의 격리 설치를 포함한다. 비공식 SDK나 유료 음성 서비스를 대신 선택하지 않는다.

## 데이터와 종료

대화 음성·전사·카드 원문은 기존 휘발성 메모리 정책을 따른다. 새로운 localStorage, 파일 로그, 분석 서버, RDB/Redis 기록을 추가하지 않는다. 화면 숨김은 표시만 바꾸며, 일시정지는 캡처·새 추론을 멈추고, 중지는 기존 세션 종료를 호출한다. 출력 구독 연결이 모두 끊기면 기존 5초 유예 후 일시정지하는 계약을 유지한다. STOPPED 세션을 재접속으로 되살리지 않는다.

직접 G2 마이크 입력은 공식 예제의 `audioControl(true)`와 `g2-microphone` 선언으로 지원 가능성이 확인됐다. 예제의 전사 서비스는 빈 연결부이므로 내장 ASR 완료로 해석하지 않는다. 기존 ASR는 16 kHz·16 bit·mono를 사용한다. 이 형식은 이론상 32,000 byte/s, 100 ms에 3,200 byte이며 JSON/base64 및 네트워크 오버헤드는 별도다. 이는 실제 전송량이나 지연 측정이 아니다. [공식 ASR 프로젝트](https://github.com/even-realities/evenhub-templates/tree/main/asr)

## 검증과 완료 판정

1. SDK 경계의 합성 검사: 첫 초기화 1회, 최신 카드 합치기, 같은 버전 무시, 이전 epoch 무시, stop/expiry 우선 지우기, 연결 실패, 잘못된 카드, HTML이 텍스트로만 처리되는지 확인한다.
2. 기존 Spring 브라우저: 인증된 자기 세션에서 현재 계약으로 실제 답변·출처·일시정지·종료를 관찰한다. 합성 성공 카드를 실제 모델 답변으로 보고하지 않는다.
3. 공식 G2 Simulator: 해당 버전이 Windows에서 실행되는지 확인하고 `evenhub-simulator http://localhost:<실제포트>`로 표시·갱신·지우기를 관찰한다. 실행이 불가능하면 SDK 합성 검사와 구분해 기록한다. [공식 Quickstart](https://hub.evenrealities.com/docs/get-started/quickstart/index)
4. 실제 G2: 승인된 HTTPS, Even 앱 로그인·사이드로드, 실제 렌즈에서 표시와 종료를 확인한다. 이 단계는 브라우저나 Simulator 결과로 대신하지 않는다.

첫 로컬 구현 완료는 1~2의 통과와 3의 실제 결과/정확한 blocker를 요구한다. 실제 G2 제품 완료는 4까지 요구한다. provider 호출·대화 의미·기기 표시를 각각 분리해 보고한다. 기존 실행기나 다른 작업 프로세스를 종료하지 않고, 이번 파일·출력만 검증된 postimage 기준으로 되돌릴 수 있게 보존한다.

## 근거와 제한

Meta는 HTML/CSS/JavaScript 웹앱, 브라우저 개발, 실제 기기 시험 경로를 별도로 설명한다. Display용 페이지의 성공은 G2 SDK 연결의 증거가 아니다. [Meta FAQ](https://developers.meta.com/wearables/faq/)

Even의 Conversate는 전사와 여러 종류의 cue 기능을 설명한다. 본 제안은 공식 Conversate 제품을 복제하거나 그 내부 API를 호출하는 작업이 아니라, 이 저장소의 근거 답변을 G2로 표시하는 연결이다. [Even Conversate 설명](https://support.evenrealities.com/hc/en-us/articles/14273795154319-Conversate)

확인되지 않은 조건은 G2 실기 보유·펌웨어, 실제 Even 앱의 인증 쿠키 동작, Windows Simulator의 설치/실행 결과, 렌즈에서의 한국어 가독성이다. 설계 승인 후에도 각 조건을 실제 출력으로 확인하며 추정값을 PASS로 쓰지 않는다.
