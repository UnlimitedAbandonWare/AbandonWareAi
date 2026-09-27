# Devin brief — RAG 근거 0개여도 답변 공개 (정책 + 코드 + 테스트) — 2026-09-24
# (user paste; section 5 "검증" was truncated in transit — substance complete)

## Goal
사용자가 원하는 동작: 근거(인용)가 0개여도 모델이 만든 답은 화면에내라.
Codex 실측: RAG ON + 인용 가능 근거 0 → HTTP 200이지만 evidence_release_metadata_incomplete로 본문 보류.

## Policy (SSOT 문장)
RAG ON이어도 인용 가능 근거가 0개이면, 모델 최종 답변을 보류하지 말고 공개한다.
evidenceReleaseRequired=false이거나 일반/개념/대화 모드면 근거 0 = 정상 경로.
근거가 있을 때만 인용·verification 강화. 근거 0일 때 releaseReason으로 본문을 막지 말 것.
(선택) UI/메타데이터에 evidenceCount=0 / unverified 표시는 OK. 본문 HOLD는 NG.
예외(명시적만): 사용자가 '반드시 출처 있는 답만'을 켠 모드 / admin force-evidence; 기존 safety 차단.

## Do
1) Lease: ChatWorkflow.java 및 테스트 예약 확인 → 만료/해제 정식 절차만. 우회 계층 금지.
2) 코드 (min-diff): evidence_release_metadata_incomplete / evidenceReleaseRequired / releaseAllowed /
   ChatWorkflow answer release gate; RagGuard/AnswerExpander 경로 분리.
   RAG ON + citable evidence == 0 → releaseAllowed=true, 본문 HOLD 제거. 근거 있을 때 인용 유지.
3) 테스트: 근거 0 보류 assert → 근거 0 공개로 뒤집기/분리. 회귀: RAG OFF 인사/일반 유지, 근거 있음 인용 경로 유지.
4) 지침/룰: AGENTS.md에 위 Policy 블록; 기존 룰/스킬의 "근거 0 = HOLD" 문구 교정;
   docs/PROJECT_STATUS.md 한 줄: 2026-09-24 evidence-0 release = allow.
5) 검증 (skill-free): [truncated in paste — compile + focused tests + record]

## Non-goals / hard stops
GPU 분리, Ollama 영구 설정, OBS, 영상 재분석, warmup 되돌리기, commit/push/secrets/add -A,
예약 파일 우회 패치, 환각 금지 완전 제거 (공개 정책만 완화).
