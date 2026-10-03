# jev model defaults가 3곳에 흩어져 smoke가 다른 모델의 증거를 냈었다 (F1/F2/F3)
- card-id: jev-model-defaults-triple-j77v
- kind: root-cause
- status: stale (F1/F2 기본값은 교정됨 — jev_api_smoke.py:135 가 AWX_JEV_MODEL, 기본 typesafe-ai/jev;
  F3 HTTP-smoke의 model 일치 검증 여부는 확인 필요)
- date: 2026-09-30 KST
- evidence: data/agent-handoff/clean-jev-v2-redteam-20260930/ADDENDUM_FINDINGS.md (F1–F4) ; scripts/jev_api_smoke.py:135 ; scripts/apikit/providers/jev.py
- reverify: `Select-String -Path scripts\jev_api_smoke.py -Pattern 'AWX_JEV_MODEL|require-field'`

## 근거 (원래 발견)
- F1: jev_api_smoke.py 기본 `--provider openai` → 제품 기본 `typesafe-ai/jev`와 다른 모델로 검증.
- F2: apikit/providers/jev.py 기본 `"jev"`(bare alias) — 세 번째 기본값.
- F3: HTTP smoke는 `--require-field model` 때 키 존재만 확인, 요청값과 비교 안 함.
- F4: JevGatewayClient 가드 `reported.contains(alias)||alias.contains(reported)` — 당시 작업트리에서
  이미 제거됨(REMEDIATED); 현재 :96-100 case-insensitive full-id 또는 마지막 `/` 세그먼트 일치 요구.
- 2026-10-03 재확인: jev_api_smoke.py:135 = `os.environ.get("AWX_JEV_MODEL","typesafe-ai/jev")`,
  jev.py는 `typesafe-ai/jev present/absent` 검사 → F1/F2 stale, F3 잔여 여부 확인 필요.
