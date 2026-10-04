---
name: demo1-web-search-probe
description: "코딩 중 모르는 에러 발생 시 기술 지문 정제 및 4대 특수 탐침 플레이트(공식 문서·GitHub 이슈·재현 코드·교차 검증)를 통해 Exa/웹서치 해결력을 극대화하고 3KB 이하 골든 컨텍스트를 합성하는 스킬"
---

# Demo1 Web Search Probe

에러 덤프를 그대로 검색창에 던지지 않는다. `scripts/web_search_probe_optimizer.py`로
기술 지문(fingerprint)을 정제하고 4대 특수 탐침 플레이트의 최적 쿼리를 얻은 뒤,
검색 결과를 3KB 이하 골든 컨텍스트로 압축해 주입한다. 로컬 경로·비밀값 유출과
버전 불일치 블로그 노이즈를 차단하는 것이 목적이다.

## 절차

1. **지문 정제 + 탐침 쿼리 획득**

   ```powershell
   python -B scripts/web_search_probe_optimizer.py probe --error-file <에러 로그>
   # 또는 에러 텍스트를 stdin으로: <텍스트> | python -B ... probe
   ```

   도구가 로컬 절대경로(`C:\...`, `/home/...`)와 비밀값(`sk-...`, `Bearer ...`,
   `api_key` 값)을 `<LOCAL_PATH>`/`<SECRET>`로 마스킹하고, 예외 FQCN·실패
   메서드·프레임워크 버전을 추출해 플레이트별 쿼리를 만든다. 출력된 쿼리 외의
   원문 덤프는 검색에 넣지 않는다.

2. **4대 플레이트로 검색 실행** — 출력 JSON의 `queries[]`를 그대로 사용한다.
   `engine: "exa"` 항목은 Exa 검색(`type:"deep"`, `category`, `includeDomains`,
   `contents.highlights:true` 포함), `engine: "web"` 항목은 일반 웹서치 쿼리다.

   | Plate | 목적 | 쿼리 형태 |
   |---|---|---|
   | T1 Official Docs | 공식 문서 직격, 블로그 차단 | `site:<docs 도메인> <예외> <프레임워크> <버전>` + `category:"documentation"`, `includeDomains` |
   | T2 GitHub Issues/PRs | 동일 예외 이슈·PR·breaking change | `site:github.com <라이브러리> <예외> <버전> issue OR "breaking change"` + `category:"github"` |
   | T3 Minimal Repro | 최소 재현/테스트 코드 | `site:github.com <라이브러리> <메서드> "@Test"` + 재현 패턴 쿼리 |
   | T4 Adversarial Cross-Check | 다중 출처 대조·비호환 버전 회피 | `<예외> <버전들> version compatibility` + `0.x OR legacy` 대조 쿼리 |

3. **골든 컨텍스트 합성** — 검색 결과를 JSON 배열(`title`, `url`, `score`,
   `highlights[]`, `text`, `plate` 필드)로 모아:

   ```powershell
   python -B scripts/web_search_probe_optimizer.py synthesize `
     --error-file <원본 에러> --results-file <results.json>
   ```

   버전 불일치(예: 지문은 1.0.1인데 0.x 문서)는 감점하고 `[FLAGS:version-mismatch:...]`
   로 표시한다. 출력 `markdown`(≤3000자)만 컨텍스트에 주입한다.

## 규칙

- **T1 공식 출처 우선.** 공식 문서와 블로그가 충돌하면 공식 문서를 따르고
  블로그는 T4 교차 검증 재료로만 쓴다.
- **버전 호환성 명시.** 채택한 답이 지문 버전과 같은 메이저인지 확인한다.
  다른 메이저 답은 `version-mismatch`로 표시된 채 인용하거나 폐기한다.
- **원문 재검색 금지.** 지문·플레이트 없이 에러 덤프를 통째로 검색하지 않는다.
- **비밀값·로컬 경로 금지.** 마스킹 결과물만 검색/기록에 사용한다.
- 도구 자체는 네트워크를 호출하지 않는다 — 쿼리 번들만 생성하며 검색 실행은
  호출자(에이전트의 Exa/웹서치 도구)가 한다. 검색 자체가 외부 유료 호출이면
  기존 `demo1-agent-api-spend-guard` 규칙을 따른다.
- `demo` 서브커맨드는 오프라인 샘플 검증용이다:
  `python -B scripts/web_search_probe_optimizer.py demo`

## 안 되는 것

- 이 스킬은 검색 품질 도구일 뿐이며, 그 결과로 얻은 패치는 별도의
  소스 편집 게이트(three-way preflight + lease + ledger)를 거친다.
- RAG/검색 파이프라인 자체 진단은 `rag-search-diagnosis`, 검색 0건 회수는
  `search-zero-result-recovery`의 영역이다.
