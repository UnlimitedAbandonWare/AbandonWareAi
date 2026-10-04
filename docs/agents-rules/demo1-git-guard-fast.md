# demo1-git-guard-fast (SSOT)

세 도구 — 커밋 검사를 수 분에서 수 초로, 해시만 나오는 결과를 경로+분류로,
지시서의 `파일:행` 사실을 라이브 트리와 대조. 모두 읽기 전용(index/HEAD 불변).
원본 `scripts/git_staged_guard.py`·hook·`git_ship.py`·`brief_lint.py`는
건드리지 않는다. 적용 개선은 `var/codex-assist-git-guard-fast/proposed-*.patch`
제안으로만 존재한다.

## git_guard_fast.py — 빠른 검사기

- 규칙 단일 출처: `git_staged_guard`의 PATTERNS/MAX_BYTES/path_rule/snapshot을
  import 해 그대로 쓴다(복사 금지). 규칙이 바뀌면 scanner_version이 바뀌어
  캐시가 자동 무효된다.
- blob 읽기는 `git cat-file --batch` 프로세스 하나. 파일 수와 무관하게
  git 프로세스는 상수(staged 5개 이하).
- 캐시 `var/git-guard-cache/clean-oids.jsonl`: "아무것도 안 걸린 blob"의
  (scanner_version, oid)만 기록한다. 걸린 결과·path_rule은 캐시하지 않는다.
  캐시 파일이 깨지면 무시하고 전부 재검사한다(오류 통과 금지).
- 허용 목록 `configs/git-guard-allow.json`: {path, rule, oid, reason} 전부
  일치 + reason 비어있지 않을 때만 allowed로 옮긴다. 내용이 바뀌면 oid가
  달라져 자동 재차단. provider-key/github-token/slack-token/aws-access-key/
  private-key는 `scripts/test_*`, `*/fixtures/*`, `src/test/**` 경로에서만
  허용 가능 — 다른 경로 항목은 있어도 계속 막는다.
- 출력은 `awx.git-staged-scan.v1` 호환(pathHash+rule만, 경로·값 출력 금지) +
  `fast{elapsedMs,cacheHit,cacheMiss,gitProcesses}` + `allowed[]` + `--reference`
  시 옛 방식 대비 동등성·시간.
- 종료코드 0 ok / 1 차단할 것 있음 / 2 검사 실패(git 오류, index 변경).
  **검사 실패는 통과가 아니다** — 실패 시 절대 0을 내지 않는다.

## git_guard_explain.py — 결과 설명기

- 입력: 두 검사기의 JSON(파일 또는 stdin). 후보 경로를 `--staged`(index),
  `--diff A B`, `--all`에서 모아 pathHash→실제 경로로 되돌린다.
- 분류: fake(테스트·fixture 경로이거나 값에 fake 표시) / placeholder
  (__MISSING__, ${…}, dummy, changeme, <…>) / path-rule / binary / size /
  suspect / unmapped. 키 규칙은 줄 번호만 표시한다 — 값이나 그 조각은 절대
  출력하지 않는다.
- `--suggest-allow`: fake + 경로 조건 충족 항목을 후보 JSON으로 출력(쓰기 없음).
  `--write-allow --reason "<사유>"`만 configs\git-guard-allow.json에 추가한다.
- 결과는 콘솔과 `var/codex-assist-git-guard-fast/explain-<utc>.md`에 남는다.
- **suspect가 1건 이상이면 멈추고 경로·규칙·줄 번호만 보고한다** — 파일 내용을
  열거나 옮기지 않고 사용자 판단에 넘긴다.

## brief_fact_check.py — 지시서 사실 점검기

- 지시서의 `path:N`, `path:N-M`, `path(N행|줄)`, `path N행` 참조를 뽑아
  라이브 트리와 대조: OK / DRIFT(같은 줄의 백틱·따옴표 기대 문구가 인용 행
  ±3 안에 없음) / MISSING / OUT_OF_RANGE / AMBIGUOUS(파일명만으로 여러 개 매칭).
- 경로는 루트 기준 → %ENV% 확장 → 파일명만이면 git ls-files 고유 매칭일 때만.
- 종료코드 0 전부 OK / 1 비정상 있음 / 2 입력 오류.

## 사용 예

```bat
Git-Guard-Fast.bat scan --staged
Git-Guard-Fast.bat scan --diff "@{u}" HEAD --reference
Git-Guard-Fast.bat explain %TEMP%\guard_out.txt --all
Git-Guard-Fast.bat explain var\...\live-parity.json --diff "@{u}" HEAD --suggest-allow
Git-Guard-Fast.bat facts <지시서.txt>
```

> `main`은 역사가 끊긴 별도 공개 스냅샷이라 비교 기준이 아니다 — diff 기준은
> 항상 `@{u}`(upstream). `docs/agents-rules/DEMO1-GIT-BRANCH-TOPOLOGY.md` 참조.

`GIT_GUARD_GIT` 환경변수로 git exe 지정 가능(미지정 시 PATH의 git).
