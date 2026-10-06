<!-- moved-from: AGENTS.md L160-L165 sha256=30c8ce1b6cef2850be16ac761a273fbc6aad7b13290fd76992d41dc89fdcff5a movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-EVIDENCE-ZERO-RELEASE -->
## Useful answers with honest evidence status
- 최신 사용자 정책(2026-10-06): 근거 부족·검색 장애·검증 인프라 오류만으로 빈 답변이나 보류문 한 줄로 끝내지 않는다. 정책 요구와 현재 제품 구현의 완료 여부는 별개다.
- 일반 저위험 개념/설명 질문은 확인된 자료와 모델의 일반지식을 구분하여 유용하게 설명할 수 있다. 검색으로 확인한 범위, 일반 설명, 확신 수준과 미확인 범위를 간결하게 밝힌다. 검색 0을 내용이 거짓이라는 판정으로 바꾸지 않는다.
- 모델 일반지식을 검색으로 검증된 사실처럼 표시하지 않는다. 가짜 인용, URL/공식 domain만으로 내용 검증 주장, 표시된 후보를 실제 사용·인용 근거로 승격하는 행위를 금지한다.
- 최신 인물·새 캐릭터 등의 구체정보/수치/동일 대상 관계는 자료가 직접 지지하는 범위에서 설명한다. 자료가 지지하지 않는 구체 사실을 추측하여 공개하거나 단정하지 않는다. 알아낸 범위·모르는 부분·실제 관측된 장애 원인과 필요하면 확인 질문 하나를 짧게 제공한다. 미관측 장애를 단정하거나 같은 안내를 길게 반복하지 않는다.
- 기존 초안은 그대로 일괄 공개하지 않는다. 지원되는 내용은 보존하고 미지원 수치·특정 사실의 추측성 주장은 제거하며 미확인 범위를 밝힌다. 일반 설명은 모델 일반지식이라는 구분 안에서 보수적으로 정리한다. 비교는 확인된 대상·시점·조건의 설명과 모르는 비교 조건을 구분하며 미확인 우열을 만들어내지 않는다.

### 판정·공개·저장 경계
- 확인된 judge infra unavailable와 일반 unknown/관측 부족, known rejected/insufficient/inconsistent, 명시적 반증을 구분한다. outcomeKnown=false 하나를 모든 초안의 공개 권한이나 부정 판정으로 사용하지 않는다.
- 기존 source-supported partial release는 보존한다. 저위험 일반 설명도 위 계약 안에서 가능하며 직접 발췌만이 유일한 답변 형태가 아니다. known false/contradicted 주장 및 부정 판정 대상의 초안을 fallback/후처리로 다시 공개하지 않는다.
- 사용자 취소·terminal은 최종 종료다. 취소 뒤 추가 답변·fallback·검색/모델 호출·메모리 저장을 하지 않는다. 권한·개인정보·콘텐츠 안전 거부와 scope/owner·명시 must-cite 요구를 이 정책으로 해제하지 않는다. 진성 부정 판정(verification rejected·inconsistent·명시적 반증)과 명시 evidence_needed 지시에는 기존 HOLD/REJECT 계약을 유지한다. 반면 검증 insufficient·citation_miss·검색 근거 0만을 사유로 본문 전체를 보류문으로 치환하지 않는다 — 아래 "주의 표기 동반 공개" 절이 기본이다. 안전한 상황 설명과 허용된 대안은 구분한다.
- 전체 생성 모델이 불능이면 상황을 설명하는 짧은 복구 응답만 가능하며 정상 모델 생성/검증 성공으로 표시하지 않는다. 실제 관측되지 않은 provider/model/HTTP 상태는 unknown으로 남긴다.
- 공개, 검색 성공, 검증, 실제 인용, 모델 응답 관측, 메모리 편입은 별개 상태다. completed/errorClass=none/model label은 품질 verified의 증거가 아니다.
- 불확실한 응답은 자동 shared RAG/학습/장기 지식에 편입하지 않는다. 해당 경로에서 knowledgeWriteAllowed=false 및 기존 memory 차단 계약을 유지한다.
- 문구/marker/probe exit 0만으로 제품 완료를 선언하지 않는다. 변경된 경계의 실제 focused tests·최종 source/test SHA·receipt가 확정되기 전 구현 상태는 SOURCE_PRESENT/NOT_RUN/조건부 제안으로 보고한다.

### 근거 부족 시 주의 표기 동반 공개 (Release with Warning)
- 검증 상태 `insufficient`, 인용 부재 `citation_miss`, 검색 근거 0(evidence zero)만을 사유로 본문을 버리거나 보류문 한 줄로 치환하지 않는다. 모델 사전학습 지식 기반 답변에 표준 주의 표기를 붙여 공개하는 것이 기본 정책이다.
- 표준 주의 표기(본문 최상단 인라인 경고 블록):
  `> ⚠️ 주의: 외부 검색 근거가 충분하지 않아 모델의 사전학습 지식(Parametric Knowledge)을 바탕으로 작성된 답변입니다. 최신 사실이나 공식 발표는 반드시 1차 출처를 직접 확인해 주세요.`
- 계약 값: `releaseStatus="UNVERIFIED"`, `reasonCode="verification_insufficient_parametric_release"`, `releaseAllowed=true`, `knowledgeWriteAllowed=false` — 자동 메모리·shared RAG·장기 지식 편입 차단 경계는 유지한다.
- 사전학습 지식 기반 공개 답변을 검증 통과로 표시하지 않는다. `finalAnswer.releaseReason` 등 trace에는 위 reasonCode를 남긴다. rejected·inconsistent·명시적 반증·안전/권한 거부는 이 절의 대상이 아니다.
<!-- END DEMO1-EVIDENCE-ZERO-RELEASE -->
