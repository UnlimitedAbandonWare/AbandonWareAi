# Soniox·Deepgram 필요성 검토 보고서

작성일: 2026-09-15 KST · 검토 범위: 현재 Desktop 소스와 공식 공개 자료 · 분석 및 문서 작성만 수행

## 1. 결론

**현재 Meta Display의 기기 입력창 → 텍스트 전송 기능에는 Soniox가 필수가 아니다. 같은 이유로 Deepgram도 필수가 아니다. 그러나 프로젝트 전체에서 Soniox가 무용하다고 판단하여 삭제할 근거는 부족하다.**

권고: **Soniox 연동은 보존한다. 별도 마이크 전사를 사용할 때는 Deepgram의 실제 무료 잔액을 확인하고 우선 시험할 수 있다.** 한국어 실측 없이 “Deepgram이 압도적으로 우수하다”거나 “Soniox를 제거해도 동등하다”고 결론내리지 않는다. 원래 요청했던 자동 라우터 구현은 변경된 분석 요청에 따라 진행하지 않았다.

| 사용 목적 | 현재 소스의 관계 | 권고 |
|---|---|---|
| Display 입력창에서 말하거나 써서 질문 보내기 | 앱은 확정된 텍스트를 받는다. 두 STT 서비스 호출 없음 | 외부 STT를 붙일 필요 없음 |
| 휴대폰 마이크 PCM을 서버에서 전사하기 | Conversate 별도 오디오 경로에 Deepgram·Soniox 선택 분기 존재 | 하나로 운영 가능하나 다른 구현은 보존 |
| 주변 대화의 연속 자막·자동 청취 | 현재 Display 텍스트 입력과 다른 기능. 오디오 수집·중계가 먼저 필요 | provider 이름만 바꿔 해결되지 않음 |
| 비용·품질 자동 최적화와 양방향 폴백 | 현재 고정 선택·공유 예약 예산만 확인 | 구현 완료로 간주하지 않음 |

## 2. 현재 호출 경로

```mermaid
flowchart TD
    A[Display 기기 입력창 또는 추천 질문] --> B[확정된 텍스트]
    B --> C[/api/assist/display/input]
    C --> D[Conversate 질문 처리 및 답변 카드]
    E[별도 휴대폰 마이크] --> F[/api/assist/sessions/id/audio/start·chunk]
    F --> G[ConversateAsrBridge 및 클라우드 예산 검사]
    G --> H{설정된 provider}
    H --> I[Deepgram 전사]
    H --> J[Soniox 전사]
    I --> D
    J --> D
```

이는 **소스상 경로**다. 계정 인증·실제 provider 응답·실기기 동작을 확인한 실행도는 아니다.

### 소스 근거

| ID | 확인한 사실 | 현재 파일 |
|---|---|---|
| E1 | 입력칸 탭 → 기기 음성 입력 → 확정 후 전송을 안내하며 자동 녹음을 하지 않음 | [Display HTML](C:/AbandonWare/demo-1/demo-1/src/main/resources/static/assets/display/index.html:21) |
| E2 | client는 `text`를 `input`으로 보내고 서버는 확정 utterance로 처리. 오디오 DTO·STT 호출 없음 | [Display client](C:/AbandonWare/demo-1/demo-1/src/main/resources/static/assets/display/display-conversate.js:94), [controller](C:/AbandonWare/demo-1/demo-1/src/main/java/com/example/lms/assist/DisplayConversateController.java:47) |
| E3 | 별도 마이크 경로는 `provider=deepgram` 또는 `soniox`를 선택. `auto` 및 두 provider 간 재시도 분기는 없음 | [ASR bridge](C:/AbandonWare/demo-1/demo-1/src/main/java/com/example/lms/assist/ConversateAsrBridge.java:46), [cloud STT](C:/AbandonWare/demo-1/demo-1/src/main/java/com/example/lms/assist/ConversateCloudStt.java:34) |
| E4 | Soniox는 서버 WebSocket·16 kHz mono PCM, `ko,en` 언어 힌트, v4/v5 모델 및 US/EU/JP/IN endpoint 선택을 구현 | [Soniox service](C:/AbandonWare/demo-1/demo-1/src/main/java/com/example/lms/service/stt/SonioxSttService.java:30) |
| E5 | Deepgram도 같은 PCM 입력을 처리하며 `nova-3`, `language=ko`, interim 및 final 결과를 사용 | [Deepgram service](C:/AbandonWare/demo-1/demo-1/src/main/java/com/example/lms/service/stt/DeepgramSttService.java:48) |
| E6 | 기본 interview 모드의 허용 경로에는 `/conversate` 및 기존 `/audio/start·chunk`가 없음. 다른 모드·설정과 구분해야 함 | [security filter](C:/AbandonWare/demo-1/demo-1/src/main/java/com/example/lms/security/ChatOpenSecurityConfig.java:73), [기본 설정](C:/AbandonWare/demo-1/demo-1/src/main/resources/application.properties:982) |

따라서 **현재 Display에서 불필요함**과 **별도 음성 기능에도 가치가 없음**은 서로 다른 판단이다. Soniox 삭제는 후자의 선택지와 기존 테스트 자산까지 없앤다. 다만 이미 결제한 $10 자체는 보존을 강제하는 이유가 아니다.

## 3. 붙여 주신 Gemini 답변 검증

| 주장 | 판정 및 정정 |
|---|---|
| 일반 가입 $200 크레딧에 만료가 없다 | 공식 안내와 일치. 계정의 실제 지급·잔액 확인은 별개. [Deepgram 가격](https://deepgram.com/pricing) |
| Startup Program은 12개월 | 별도 프로그램 조건과 일치. 일반 가입 혜택과 혼동하지 않아야 함. [공식 조건](https://deepgram.com/startup-program-terms-of-service) |
| $200를 공개 STT·TTS·Voice Agent 등에 사용 가능 | 공개 모델 endpoint 접근 안내와 일치. 사용 모델에 따라 소진 속도는 다름. [Deepgram 가격](https://deepgram.com/pricing) |
| 카드 등록 시 무조건 $10 미만에서 $100 자동충전 | 단정은 과함. 공식 약관은 이 금액을 예로 들며 실제 임계값·금액은 가입 설정에 따름. 이번 작업은 계정 설정을 조회·변경하지 않음. [Deepgram 약관](https://deepgram.com/terms) |
| Soniox는 영어 중심이고 실시간 Display에 부적합 | 근거 부족. Soniox는 한국어를 포함한 60개 이상 언어의 실시간 API를 명시. Sonix.ai와 Soniox를 섞은 설명은 이 저장소 판단에 적용할 수 없음. [언어 지원](https://soniox.com/docs/stt/concepts/supported-languages), [WebSocket API](https://soniox.com/docs/api-reference/stt/websocket-api) |
| Display에서는 Deepgram 외 대안이 없다 | 틀린 일반화. 두 서비스 모두 실시간 STT이며 현재 앱은 기기 입력창의 텍스트를 사용. Meta도 특정 STT 업체를 필수로 지정하지 않음. [Meta FAQ](https://developers.meta.com/wearables/faq/), [공식 입력 안내](https://github.com/facebook/meta-wearables-webapp/blob/main/plugins/meta-wearables-webapp/references/display-guidelines.md) |
| Web App이 안경 마이크 PCM을 바로 받아 보낸다 | 현재 앱에는 그런 경로가 없음. 기기 입력창의 음성→텍스트와 DAT/Bluetooth를 통한 원시 오디오 중계는 별도 경로. [Meta FAQ](https://developers.meta.com/wearables/faq/) |
| 200~300 ms가 넘으면 심한 어지럼증이 발생 | 제시된 근거로 확인되지 않은 임계값. 자막 지연과 머리 움직임에 대한 화면 갱신 지연을 같은 지표로 취급하면 안 됨. 의학적 사실이나 설계의 절대 기준으로 채택하지 않음 |
| 600×600·검정 배경·스크롤 불가·최소 20~24 px | 600×600dp와 검정 페이지 배경은 공식 지침에 있음. 그러나 지침은 스크롤 컨테이너도 다루고 본문 16/14dp를 제시. 모든 표면을 검정으로 만들라는 뜻도 아님. [공식 Display 지침](https://github.com/facebook/meta-wearables-webapp/blob/main/plugins/meta-wearables-webapp/references/display-guidelines.md) |

## 4. 비용 비교

현재 코드의 Deepgram 한국어 단일 언어 스트리밍과 Soniox 실시간 API를 비교했다. batch 요금이나 이 코드가 사용하지 않는 Deepgram 부가기능을 섞지 않았다.

| 비교 기준 | 분당 비용 | 시간당 비용 | 기준 크레딧의 단순 환산 |
|---|---:|---:|---:|
| Deepgram Nova-3 단일 언어, 현재 프로모션 | $0.0048 | $0.288 | $200 → 약 694.4시간 |
| 같은 Deepgram, 표시된 정규 요금 | $0.0077 | $0.462 | $200 → 약 432.9시간 |
| Soniox 실시간, 공식 시간 환산치 | 약 $0.002 | 약 $0.12 | $10 → 약 83.3시간 |

출처: [Deepgram 가격](https://deepgram.com/pricing), [Soniox 가격](https://soniox.com/pricing). 계산식은 `시간당 요금=분당 요금×60`, `환산 시간=크레딧÷시간당 요금`. 공개 요금의 산술이며 실제 계정 잔액이나 청구액이 아니다. Soniox는 토큰 기반 과금이라 시간 환산은 근사치다. 프로모션 변경·다른 API 사용·재시도·부가기능에 따라 달라진다.

- 유효한 무료 크레딧이 남아 있다면 Deepgram부터 시험하는 것은 합리적이다.
- 크레딧 이후에는 현재 비교 요금상 Soniox가 약 2.4배 저렴하다. 무료 혜택과 장기 단가를 구분해야 한다.
- “700시간 이상”은 모든 모델에 고정된 혜택이 아니다. 만료가 없으므로 서둘러 소비할 이유도 없다.
- 사용자가 말한 Soniox $10 충전은 사용자 제공 사실로 취급했다. 현재 잔액, Deepgram $200 지급 완료, 자동충전 상태는 미확인이다.

## 5. 성능은 어느 쪽이 좋은가

2026-09-15 조회한 Pipecat 공개 표의 수치는 다음과 같다.

| 모델 | 최종 구간 지연 P50 | P95 | pooled semantic WER |
|---|---:|---:|---:|
| Deepgram nova-3-general | 247 ms | 298 ms | 1.62% |
| Soniox stt-rt-v5 | 260 ms | 305 ms | 1.27% |

Deepgram은 이 표의 중앙 지연이 낮고 Soniox는 의미 기반 단어 오류율이 낮다. “한쪽이 압도적”이라는 결론은 아니다. [Pipecat 현재 표](https://www.pipecat.ai/benchmarks/speech-to-text)

원래 벤치마크는 영어 1,000개 샘플·미국 측정·발화 종료 후 최종 전사 구간 도착까지의 TTFS를 사용한다. **한국어 우열, 한국 네트워크, Meta HUD 전체 지연을 증명하지 않는다.** 인용문의 50~80/80~100 ms와도 측정 정의가 다르다. [Daily 방법론](https://www.daily.co/blog/benchmarking-stt-for-voice-agents/)

Soniox의 한국어 비교 페이지는 공급자 자체 주장이다. 이를 독립적인 최신 한국어 A/B 결과로 채택하지 않았다. [Soniox 한국어 비교](https://soniox.com/compare-stt/soniox-vs-deepgram/korean)

## 6. 기존 디버깅 수치로 경제성을 판단할 수 있는가

아직 부족하다. [현재 예산 클래스](C:/AbandonWare/demo-1/demo-1/src/main/java/com/example/lms/assist/ConversateSttBudget.java:20)는 공통 분당 $0.008로 최대 연결 시간을 **보수적으로 예약**한다. [cloud 코드](C:/AbandonWare/demo-1/demo-1/src/main/java/com/example/lms/assist/ConversateCloudStt.java:59)는 스트림 시작에 601초를 예약한다. 이는 실제 발화 시간·실제 청구액·provider별 누적 비용이 아니다. `freeCredit=not_assumed`도 의도적으로 구분한다.

따라서 기존 예약 총액만 비교하여 “Soniox가 많이 소모된다”는 결론을 내리면 잘못이다. 향후 계정 대조에는 [Deepgram 프로젝트 잔액 API](https://developers.deepgram.com/reference/manage/billing/list)와 [Soniox 사용량 요약 API](https://soniox.com/docs/guides/usage-summary)가 근거가 될 수 있다. 이번 분석에서는 인증된 계정 API를 호출하지 않았다.

## 7. 판단 가설과 다음 선택

| 가설 | 판정 | 이유 |
|---|---|---|
| Soniox는 현재 Display 입력에 필수다 | 기각 | E1·E2에 STT 호출이 없음 |
| Soniox는 프로젝트 전체에서 죽은 코드다 | 기각 | E3·E4의 조건부 전사 호출이 존재 |
| Deepgram만으로 별도 PCM 전사를 구현할 수 있다 | 소스상 지지, 실사용 동등성 미확인 | E5의 동일 입력 계약, 품질·계정·실행 증거는 별도 |
| Soniox가 한국어에서 불리하므로 제거해야 한다 | 미확인 | 해당 환경의 비교 측정 없음 |

**채택안: 연동을 보존하고 현재 Display에 외부 STT를 억지로 추가하지 않는다.** 별도 마이크 전사가 필요해지면 Deepgram 우선 운영을 검토하고, 같은 한국어 샘플에서 Soniox의 오류율·P95·실제 비용이 더 유리한지 비교한다. 이는 수동 비교·대체 선택지를 보존하자는 권고이며, 현재 양방향 자동 폴백이 완성됐다는 뜻이 아니다.

다음 단일 확인: **별도 마이크 전사를 사용할 경우, 변경 중인 ASR 소스가 정리된 뒤 기존 두 provider의 binding/transport 집중 검사를 실행한다.** 품질 비교는 그 이후의 별도 유한 표본 시험이다. 현재 Display 질문 입력만 사용할 경우 STT 변경 작업은 필요 없다.

## 8. 검증 범위와 관측 한계

- 실제 수행: 현재 Java/JS/HTML·설정·관련 테스트 소스 조회, 호출 경로 교차 확인, 공개 공식 자료 확인, 비용 산술 검산. 테스트 소스는 검토했으며 Gradle 테스트를 실행한 것으로 보고하지 않는다.
- 검토 중 다른 작성자가 STT/Display 파일을 수정하고 있었다. 2026-09-15 16:00:56 KST에는 bridge가 `SonioxSidecarManager`·`FailoverAsrTransport`를 참조하지만 두 선언 파일이 없는 상태를 관측했다. 이는 **그 시점의 소스 의존성 불일치**이며, 빌드 실패를 직접 재현한 결과나 영구 결함 판정은 아니다. Soniox 제거의 근거로 사용하지 않았다.
- 같은 시점 `display-voice.js`도 없었고 Display HTML은 해당 파일을 로드하지 않았다. 이는 미연결 테스트·작성 중 변경과 제품의 현재 경로를 구분해야 함을 보여준다.
- 8080/8081 listener는 검사 시 관측되지 않았다. 다른 포트·외부 런타임 부재까지 뜻하지 않는다. provider 실제 전사, 계정 잔액 및 Meta 실기기 증거는 `not_observed`다.
- 이번 작업의 애플리케이션 소스 수정·삭제·키 변경·STT 유료 호출·배포는 0건이다. 보고서만 작성했다.

소스 기준점: branch `codex/owned-runtime-browser-restart`, HEAD `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`. dirty checkout이므로 아래 파일 해시가 검토 근거다.

| 파일 | SHA-256 |
|---|---|
| ConversateAsrBridge.java | `7cea09a8963e283eb84a12e205b55d5718bb2ac0d43f7d812d61ca125d3f153f` |
| ConversateCloudStt.java | `3c20eb41f9ce55f9da76f8c63abb2d401b4ab4f0c26ad0a74dbd62f41e2e73c9` |
| DisplayConversateController.java | `324a6dd1e61167932c2c0526d3caafc89e926677d64d3ff4b54708118caaacb1` |
| SonioxSttService.java | `fc2fac8ab41ed2af13840039d81fe88b2acbd9d7ea988b7e32b0cf3a667068e5` |
| DeepgramSttService.java | `2ef10170da91df3bc0b395ebfd79e792eb348d4049ee39f6b194e131004fbf2e` |

분석 신뢰도: 현재 호출 경로와 공식 지원·가격 사실은 높음. 현재 한국어 품질 우열·계정 혜택 잔여량·실기기 성능은 미확인. 기능 보존 목적은 소스 구조에서 추론했으며 제작자의 의도라고 단정하지 않았다.
