# [CONFLICT] 지시서 "허용 목록 밖 표면 → off" 요구 vs JevSurfacePolicy의 global 상속 구현
- card-id: CONFLICT-jev-surface-off-requirement-j33s
- kind: CONFLICT
- status: conflict (요구 문서와 제품 코드 불일치 — 어느 쪽을 정답으로 볼지 제품 소유자 판단 필요)
- date: 2026-10-03 KST
- evidence: 아래 두 경로
- reverify: `Select-String -Path main\java\com\example\lms\assist\JevSurfacePolicy.java -Pattern 'effective'`

## 주장 A (지시서 요구, FINDINGS-CLINE §3-1 인용)
`data/agent-handoff/clean-jev-bet-assist-20260930/FINDINGS-CLINE.md`: 지시서 §3은
"허용 목록 밖 표면 → off"를 요구.

## 주장 B (제품 코드 현재)
`main/java/com/example/lms/assist/JevSurfacePolicy.java:14-16`: unknown surface →
`override=null` → `effective = "off".equals(global) ? "off" : global` → global 상속.
`demo.jev.mode=on`이면 `"focus.x"`/`"FOCUS"`/`""`/`null`도 effective="on".

## 누가 확인해야 하나
Codex(제품 소유 레인). 제안 테스트 `JevSurfacePolicyTest#unknownSurfaceNeverInheritsGlobalOn`
유무와 의도된 정책(대소문자 관용 포함)을 확인한 뒤 수정 여부 결정. 이 카드는 판정하지 않음.
