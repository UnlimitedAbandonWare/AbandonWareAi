<!-- moved-from: AGENTS.md L454-L462 sha256=105f526f09b76533c46aa9cc42e17aa3a0bc662fd7cf058315ff666969f476a1 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-P6-RESILIENCE-RULES -->
## P6 복원력·검증·데이터 정합성 5대 지침 (2026-10-01, SSOT)
상세 규격: `.agents/rules/subagent-resilience-and-common-verifier.md`. 아래 5원칙은 요약 SSOT.
- **원칙 1 — 에이전트 독립 공통 검증기**: 에이전트 자체 "PASS"는 claim일 뿐. `scripts/common_verifier.py`가 직접 실행해 exit≠0·testCount=0·100% skip·assertion/@Disabled 약화·post-test digest 변경이면 FAIL/INCOMPLETE/REJECTED/INVALIDATED. all-clear는 `VERIFIED_PENDING_APPROVAL`(승인 별도 단계).
- **원칙 2 — 서브에이전트 무한 대기 차단·삼중 경계**: 부모 deadline 상속(단계별 새 시계 금지), 대기열 상한·`CallerRunsPolicy` 금지(메인 스레드 지연 전파 차단), `CompletableFuture.cancel(true)` ≠ 실제 worker 종료(슬롯 반환은 실제 종료 시), 오류 결과 생성에 LLM 체이닝 금지, 늦은 결과는 폐기(중복 final 차단). 모의 하네스: `scripts/fault_matrix_harness.py` + `data/fixtures/fault_matrix_28.json`.
- **원칙 3 — 영수증 없는 checkpoint 전진 금지**: `VectorFlushOutcome.durable()` 없이 batch success/checkpoint 전진 금지(backoff·store_failure·source_rejected는 미커밋), `ATOMIC_MOVE` 실패 은폐 금지, `readOffset`≠`committedOffset`, 미종결 JSONL tail은 재처리(마지막 완성 경계까지만 커밋). 진단: `scripts/check_vector_checkpoint_receipt.py`.
- **원칙 4 — 1차 분류·역할 분리**: 5축(성격·복잡도·문맥·근거·실행가능성) 분류; `max_output_tokens` 단독 승격 금지; 게이트와 `RouterPolicy`는 동일 룰셋(정책 내 `new QueryComplexityGate()`는 알려진 불일치 결함); 모델 경로 파일 존재 여부로 verdict가 갈리는 분기는 결함; 명시적 사용자 모델/검색 설정 불변. 탐침: `scripts/probe_front_router_consistency.py`.
- **원칙 5 — Jev 후보 선별 한정·OAuth 크레딧**: Jev는 검색 후 후보 rerank/filter 보조 신호로 한정(필수 단계·본문 생성 아님, 보호 후보 유지 필수); 70ms는 TypeSafe US-west 벤치이므로 강제 하드 타임아웃 금지; `confidenceAccepted`(확률≥임계)와 scorer confidence는 별개량; ChatGPT 플랜 OAuth는 `chatgpt.tokens.use.direct` + `store:false` + `stream:true` 필수·미지원 필드 격리, 일반 API 키 결제와 별개 레인.
<!-- END DEMO1-P6-RESILIENCE-RULES -->
