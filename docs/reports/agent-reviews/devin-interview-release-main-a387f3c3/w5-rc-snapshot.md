# W5 — RC 스냅샷 + b2eaba4 비교 + main 교체 제안서

## 1. RC worktree

- 경로: `C:\AbandonWare\demo-1\rc-worktree`
- 브랜치: `release/rc-20261013` (신규 로컬 브랜치, push 없음)
- 기준 커밋: `a3754a3f8faad760e90c2c23409a4064f3ae09da` (2026-10-07 12:02, 원격 codex/owned-runtime-browser-restart 와 동일)
- 생성: `git worktree add C:\AbandonWare\demo-1\rc-worktree -b release/rc-20261013 a3754a3...` — exit 0, 7,155 파일 체크아웃
- src 체크아웃은 전환하지 않았다 (현재 브랜치 유지).

## 2. b2eaba4 (옛 main) 대비 diff

`git diff --stat b2eaba4679f7 release/rc-20261013`:

- **3,087 파일 변경, +388,031 / −34,983 줄**
- 두 이력은 공통 조상 없음(merge-base exit 1) → 트리 전체 비교 결과.
- 상세: `var/w5-rc-diff-stat.txt`, `var/w5-rc-diff-names.txt` (로컬, 미커밋)

### main에만 있는 파일 (D = RC에 없음, 총 121)

- `~107건`: `.agents/skills/**` — 옛 스킬 파일들 (RC 시점에 이미 재편/삭제된 구 스킬)
- `14건`: 레거시 SelfAsk 중복 Java 소스
  (`com.abandonware.ai.*.SelfAskPlanner`, `service/rag/planner/*`,
  `strategy/service/selfask/*`, `config/RagLightAdapters.java` 등 구 패키지 잔재)

### 살려야 할 것 후보: **없음**

- `README.md`, `LICENSE`, `.gitignore`, `Start-RAG.bat`, `build.gradle.kts`,
  `settings.gradle.kts` — **양쪽 트리에 모두 존재** (확인: git cat-file -e).
- main-only 121건은 전부 stale 스킬/레거시 중복 코드 — 사용자 대상 손실 없음.

## 3. main 교체 제안서 (실행 없음 — force push 없는 경로 1순위)

두 이력에 공통 조상이 없어 merge/빨리감기 불가 → 교체는 ref 전환 방식이어야 한다.

### 방법 A (권장 1순위): archive 보존 + 기본 브랜치 전환
1. `git branch archive/main-b2eaba4 b2eaba4679f7` (+ 선택: 태그 `archive/main-b2eaba4`)
2. `git push origin archive/main-b2eaba4` — 옛 main 보존 완료
3. GitHub에서 default branch를 `release/rc-20261013` 로 변경 후 RC push
   또는 `main` 이름 유지가 필요하면 B로.
- 장점: main ref를 덮지 않음, force push 0, 이력 보존 완전.
- 단점: 브랜치 이름이 main이 아니게 됨(또는 B 필요).

### 방법 B: archive 보존 + 승인된 main 교체 (non-force 경로)
1. A-1과 동일하게 `archive/main-b2eaba4` 생성·push
2. `git push origin release/rc-20261013:refs/heads/main`
   — 단, main 현재값이 b2eaba4와 같아 non-FF여서 **서버가 거부**함.
   그래서 실제로는 GitHub 측에서:
   a) repo settings → branches → rename/기본 브랜치 재지정, 또는
   b) 관리자 권한으로 main ref 재지정 (gh api -X PATCH 또는 UI).
   force push 없이 하려면 "기존 main 삭제 후 재생성" 경로:
   `git push origin :main` (삭제) → `git push origin release/rc-20261013:main`
   — push --delete는 force가 아니며 이력 재작성도 아님.
   (브랜치 보호 규칙이 main 삭제를 막으면 settings에서 일시 해제 필요 — 사용자 작업)
- 장점: main 이름 유지, force push 없음.
- 주의: main 삭제~재생성 사이 짧은 공백, archive 브랜치로 복구 경로 확보 필수.

### 방법 C: 10/13까지 보류
- RC를 `release/rc-20261013`로만 유지하고 main 교체는 마감 뒤.
- 공개 데모가 kro.kr 터널(로컬 18180)을 쓰므로 main 교체와 별개로 데모 가능.

### 비권장: force push (`--force`/`+ref`), merge, rebase, filter-repo — 금지 목록.

## 4. 남은 결정 (ASK_ONCE로 이관)

- 교체 방식 A / B / C
- RC 기준 커밋: `a3754a3` (현재 RC) / 로컬 HEAD (미푸시 자동커밋 포함) / 추가 수정 후 새 커밋
