# JevSurfacePolicy: 허용 목록 밖 표면이 global demo.jev.mode 를 상속한다 (TOSS-JEVBET-01)
- card-id: jev-surface-global-inherit-7b1e
- kind: failure-signature
- status: still-true (line-verified 2026-10-03)
- date: 2026-09-30 KST
- evidence: main/java/com/example/lms/assist/JevSurfacePolicy.java:14-16 ; data/agent-handoff/clean-jev-bet-assist-20260930/FINDINGS-CLINE.md (§3-1)
- reverify: `Select-String -Path main\java\com\example\lms\assist\JevSurfacePolicy.java -Pattern 'known|override|effective'`

## 근거
```
known = Set.of("focus","cue","main").contains(surface==null?"":surface);   // :14
override = known ? env.getProperty("demo.jev.surface."+surface+".mode") : null;  // :15
effective = "off".equals(global) ? "off" : override==null ? global : mode(override);  // :16
```
`demo.jev.mode=on` + 표면 `"focus.x"`/`"FOCUS"`/`""`/`null` → `known=false` → `override=null`
→ **effective="on"**. 지시서 요구(허용 목록 밖 표면 → off)와 상반. ENV 키 조립은 안 됨(안전),
값 상속만 문제. 제안 테스트: `JevSurfacePolicyTest#unknownSurfaceNeverInheritsGlobalOn`.
경로 정정: 원문 인용 `assist/jev/JevSurfacePolicy.java` → 실제 `assist/JevSurfacePolicy.java`.
