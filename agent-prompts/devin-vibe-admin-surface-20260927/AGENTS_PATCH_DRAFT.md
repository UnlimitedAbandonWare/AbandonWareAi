# AGENTS_PATCH_DRAFT — DEMO1-PROTOTYPE-AUTH-LIGHT 보강 초안 (미적용)

날짜: 2026-09-27 KST · 작성: Devin · **초안만** — 실제 `AGENTS.md` 적용은 Clean seam
(`agent-prompts/clean-vibe-low-admin-guardrail-20260927/CLEAN_KICKOFF.md` §1) 또는
사용자 승인 후. THE ONE = Opt E (`RECOMMENDATION.md`).

## 제안 삽입 위치

`AGENTS.md` `<!-- BEGIN DEMO1-PROTOTYPE-AUTH-LIGHT -->` 블록 안, 기존 bullets 뒤에 추가
(BEGIN/END 마커 유지, 기존 문장 수정·삭제 없음 — **초안 3~5줄**).

## 초안 (ko)

```markdown
- Vibe/agent verification: "admin login works" and "admin blocked after logout" are
  N/A under proto-open — never use them as Done/PASS criteria; a `hasRole("ADMIN")`
  surface returning 200 to anonymous requests is policy, not a defect.
- Do not add an extra AdminToken check, a second role gate, or a fail-closed admin
  matcher to a patch unless the user explicitly says "harden".
- Vibe admin chrome: keep `/chat` free of operator menus/logout links (containers
  may stay `hidden`); prefer `Read-RAG-Debug` / `var/rag-launcher/LATEST.json` over
  `/admin/**` as the default debug entry — do not document `/admin/**` as a required gate.
```

## 적용 시 확인 사항

- `demo.auth.proto-open`은 `application-meta-display.yml`에서 `true` 유지 — 이 초안은
  플래그를 건드리지 않는다.
- 공개 배포/push 하드스톱 문장(기존 블록)은 그대로 유지.
- madasin `CODEX_CONTINUE.md` C4와 모순되지 않음: proto-open 유지·fail-closed 금지는
  동일, "불충족 보고" 요구는 vibe 항목 N/A화로 흡수 (PASS 복원 아님).
