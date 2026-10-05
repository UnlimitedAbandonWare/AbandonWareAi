# Clean(Cline) 지시서 — 바이브/에이전트용 로그인·권한·실행 가드레일 완화
날짜: 2026-09-27 KST  
수신: **Clean (Cline)**  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
Prototype Light · PROTO_OPEN · secrets 미출력 · push/dd -A 금지

## 사용자 의도
바이브 코딩에서는 로그인·권한 기능을 **거의 안 쓰는데**, 룰/지침/실행권한이 에이전트에게 그대로 넘어가면  
「admin 로그인 성공」「로그아웃 후 차단」「토큰 필수」「fail-closed」로 **오도·공회전·잘못 harden**한다.  
→ **에이전트/바이브 맥락에서는 완화·예외**를 명시하고, 정말 위험한 실행만 제약으로 남겨라.

교차 (중복 시 스킵·정렬):
- `clean-vibe-low-admin-guardrail-20260927` (+ ADDENDUM)
- `devin-vibe-admin-surface-20260927` (+ ADDENDUM)
- AGENTS `DEMO1-PROTOTYPE-AUTH-LIGHT`

---

## Self-Ask
1. **요청:** 로그인/권한 관련 룰·지침·실행권한을 바이브·에이전트일 때 완화 유도.
2. **증거:** proto-open 기본 true; AdminTokenGuard가 ROLE_ADMIN 부여; madasin에서 logout-block을 PASS로 두면 정책과 충돌; Cline `60-` 룰·RECOMMENDATION이 아직 비어 있으면 옛 harden 문서가 이김.
3. **완화할 것 vs 남길 것** 구분 필수 (아래).
4. **금지:** 프로덕션 harden을 기본값으로, CSRF off, proto-open=false, Admin Java 삭제, secrets 출력, 무단 push.
5. **seam:** AGENTS 한 블록 + Cline Always-on(기존 60 확장 또는 `61-demo1-vibe-agent-auth-relax.md`) + hard-constraints 예외 문장. SecurityConfig 대수술 금지.

---

## 완화할 것 (바이브·에이전트 = N/A 또는 soft)
| 주제 | 에이전트에게 유도할 문장 |
|---|---|
| admin / 운영자 로그인 | Done 조건 아님. UI 로그인 유도 무시 가능. |
| 로그아웃 후 `/admin` 차단 | proto-open에서 200 = 정상. green 만들려고 harden 금지. |
| `X-Admin-Token` / ROLE_ADMIN | proto-open이면 토큰 chase 금지. 403만으로 “막힘 패치” 금지. |
| anonymous → 401/403 RED | vibe 기본 목표 아님 (harden 명시 시에만). |
| 보호 URL Browser 시나리오 | CONTINUE/nonadmin 작업에서 제외. |
| “권한 부족하니 실행 권한 올려” | OS/SMB/방화벽/스케줄 admin 설치 요구 금지 (Prototype light no-admin surface). |
| 진단 필수 관문 = `/admin/**` | `Read-RAG-Debug` / `LATEST.json` 우선. |

## 남길 것 / 더 명확히 제약할 것 (완화 금지)
| 주제 | 유지 |
|---|---|
| secrets / apikey / .env / 토큰 **값** | 출력·커밋·채팅 첨부 금지 |
| push / `add -A` / force-push / history rewrite | 사용자 명시 전 금지 |
| proto-open 켠 채 **공개 배포** | 하드스톱 |
| CSRF 끄기 | 금지 |
| foreign lease / 타인 staging | 보존·강제 해제 금지 |
| 유료 API 무단 호출 | spend-guard / 사용자 승인 |
| 실계정·실결제·프로덕션 DB 파괴 | 금지 |
| Jev `budget_skip` 오류화 | 금지 |

요지: **제품 로그인 UX 가드레일은 낮추고, 에이전트 파괴적 실행 가드레일은 유지.**

---

## 작업 순서

### 0) 게이트
- `git status` — foreign staging 보존.
- `application-meta-display.yml` proto-open **true 유지** (변경 금지).
- 기존 `.cline/60-demo1-vibe-low-admin-guardrail.md` 있으면 **확장**; 없으면 60 생성 후 본 ADDENDUM 내용 병합.

### 1) AGENTS.md — 새 블록 (또는 AUTH-LIGHT 확장)
제안 마커: `<!-- BEGIN DEMO1-VIBE-AGENT-AUTH-RELAX -->` … `<!-- END ... -->`

필수 취지:
- 바이브/에이전트 세션에서는 로그인·admin·logout-block·토큰 필수를 **완료/검증 조건에서 제외**.
- 에이전트는 권한 문제를 SecurityConfig harden이 아니라 **PROTO_OPEN + Read-RAG-Debug**로 해석.
- 실행권한: 소스/테스트/문서/룰 편집은 허용 범위 안; **시스템 admin·방화벽·SMB ACL·스케줄 등록·Docker pull 강제** 금지.
- “권한이 없어서”를 이유로 secrets를 읽거나 push하지 말 것.
- 사용자가 명시적으로 `harden` / `프로덕션 인증`이라고 한 세션만 fail-closed 목표 허용.

### 2) Cline Always-on
파일: `.cline/60-…` 확장 또는 `.cline/61-demo1-vibe-agent-auth-relax.md`

`markdown
# demo1 vibe agent auth relax
When working as an agent on demo-1 vibe tasks:
- Treat login/admin/logout-block/X-Admin-Token as N/A under PROTO_OPEN.
- Do not patch SecurityConfig to "fix" browser auth acceptance.
- Do not demand OS admin elevation, firewall, SMB ACL, or scheduled-task installs.
- Debug via Read-RAG-Debug / LATEST.json, not /admin login.
- Still never: print secrets, push/add -A, force-push, CSRF-off, proto-open=false, mass-delete Admin*.
- If a rule/skill says anonymous must 401/403, supersede with DEMO1-PROTOTYPE-AUTH-LIGHT unless user said harden.
`

### 3) hard-constraints / bridge / skills
- `demo1-hard-constraints.md`, `00-demo1-cline-bridge.md`: 로그인 강제를 바이브 기본에서 제외.
- 스킬에 `X-Admin-Token` 필수 톤이 있으면 proto-open 한 줄 (db-export 등; Devin이 했으면 스킵).
- 실행권한 문구: “관리자 권한으로 실행하라”류가 있으면 **에이전트 금지 / 사용자 PC admin elevation 요구 금지**로 수정.

### 4) (선택) placement / loadout 한 줄
`Read-RAG-Debug` = 디버그 엔트리; `/login`·`/admin` = 바이브 비권장.  
loadout harmony와 겹치면 한 줄만.

### 5) 검증
`powershell
cd C:\AbandonWare\demo-1\demo-1\src
Select-String -Path .\AGENTS.md -Pattern 'VIBE-AGENT-AUTH-RELAX|PROTOTYPE-AUTH-LIGHT|logout-block'
Get-ChildItem .\.cline\6*.md | Select-Object Name
Select-String -Path .\application-meta-display.yml -Pattern 'proto-open'
`
규칙-only → Start-RAG/전체 test NOT_RUN OK. logout-block PASS 테스트 추가 금지.

---

## Done when
- [ ] AGENTS vibe-agent auth relax 블록
- [ ] Cline 60/61 Always-on
- [ ] hard-constraints/실행권한 오도 완화
- [ ] 위험 제약(secrets/push/deploy-with-proto-open) 명시 유지
- [ ] proto-open true · harden/CSRF-off/Admin 삭제 없음

## 보고
`	ext
CLEAN_VIBE_AGENT_AUTH_RELAX: DONE|PARTIAL
files: ...
relaxed: login/admin/logout-block/token-chase/os-admin-demand
kept_hard: secrets/push/proto-open-deploy/CSRF/leases
proto-open: true
`

## 하지 말 것
SecurityConfig로 “편하게” fail-open을 **새로** 코딩하기(이미 proto-open 있음).  
권한 완화를 핑계로 secrets 읽기·push·원격 배포.