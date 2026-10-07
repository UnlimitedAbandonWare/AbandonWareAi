# DEMO1-ABLATION-DIAGNOSTICS-ASSIST-20261007

Ablation·Z 진단 복구 + Jev 평가 작업의 **조수(companion) 레일**. Codex 제품
패치(`PASTE_CODEX_ABLATION_DIAGNOSTICS_JEV_EVALUATION_20261007`)와 겹치지
않게 Devin이 만든 읽기 전용 점검 도구·픽스처·장부의 사용 규칙이다.

## 적용 시점

- Codex ablation/Jev 작업이 진행 중이거나 완료를 주장할 때
- "진단이 화면에는 나오는데 재방문/저장에서 사라진다" 류의 증상 재확인
- Jev 비용/계약 관련 판정 전

## 도구

| 도구 | 용도 | 명령 |
|---|---|---|
| 6대 함정 정적 점검기 | E2/E3/E2-2/E6/E6-2/E4 라이브 상태 | `python -B scripts/verify_ablation_diagnostics_assist.py --report` |
| Jev 오프라인 mock | `/v1/evaluate` 계약 9종 fixture | `python -B scripts/mock_jev_evaluation_gateway.py --smoke` |
| 계약 샘플 | Codex가 참조할 응답 JSON | `--write-samples data/agent-handoff/codex-ablation-jev-assist/jev_evaluation_contract_samples.json` |
| baseline 스냅샷 | 10개 추적 파일 SHA256-12 | `--write-baseline data/agent-handoff/codex-ablation-jev-assist/baseline_snapshot.json` |
| hold 장부 | Codex 미해결 범위·보류 기록 | `data/agent-handoff/codex-ablation-jev-assist/hold_ledger.md` |
| Invisible Eye 가이드 | 함정 상세·수정 방향·금지 | `docs/diagnostics/ablation_jev_diagnostics_invisible_eye_20261007.md` |

## 규칙

1. **STRICT_ZERO 제품 소스:** 조수는 `main/`, `src/test/`, `frontend/`,
   `static/js/chat.js`를 쓰지 않는다. 읽기·정적 스캔만 허용.
2. **타임스탬프 원칙:** 브리프의 줄 번호/SHA는 작성 시점 스냅샷이다. 판정 전
   점검기로 라이브 상태를 다시 확인한다 — Codex 진행 중 drift는 정상이며
   `baseline_snapshot.json`의 `match=false`는 신선도 표지다.
3. **exit 의미:** 점검기 exit 0 = "6개 검사가 실행됐다"이지 "함정이 없다"가
   아니다. 함정 유무는 각 check의 status 필드로 판정한다. `--strict`는
   PITFALL_PRESENT 시 exit 1.
4. **비용 계약:** 공식 `providerMetadata.gateway.cost`, legacy `gateway.cost`
   이중 경로. 누락은 **UNKNOWN** — 0원 보정·추정값 출력 금지.
5. **표시 분리:** `EXTREMEZ`(계획) / trigger 관측 / 실행 관측 /
   `extremez.activated`(문서 증가)를 한 값으로 합치지 않는다.
   declared/enabled만으로 ON 승격 금지.
6. **Jev 경계:** advisory label이지 truth/인과/검증 verdict 아님. OFF/
   disabled/budget/timeout/invalid이면 기존 진단 ordering 유지.
   한 질문에 진단probe·Z relevance 옵션 **하나**만.
7. **HOLD:** Codex R0 미재현 시 투기적 제품 패치 보류. foreign live lease
   겹치면 그 파일만 HOLD_LEASE — 다른 읽기/문서 작업은 계속.
8. **보고 분리:** mock 통과 ≠ 라이브 검증. 안 돌린 항목은 NOT_RUN.
   제품 diff 0 / 외부 API 0 / 재시작 0은 보고에 명시.

## 관련

- 지시서: `PASTE_DEVIN_ABLATION_DIAGNOSTICS_ASSIST_20261007`
- Codex 브리프: `PASTE_CODEX_ABLATION_DIAGNOSTICS_JEV_EVALUATION_20261007`
- 공식 계약 (2026-10-07 확인): Vercel `/v1/evaluate` + `typesafe-ai/jev`,
  primitives boolean/choice/score, 비용 `providerMetadata.gateway.cost`
- P6 복원력: `docs/agents-rules/DEMO1-P6-RESILIENCE-RULES.md`
