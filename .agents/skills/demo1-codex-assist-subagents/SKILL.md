---
name: demo1-codex-assist-subagents
description: >-
  Use when Codex 본체가 소스 수정 중 브라우저 검증·원인 추적·라우팅 정리·패치 검토를
  repo-scoped assist 서브에이전트(.codex/agents/assist_*)에 맡길 때 — 패킷 발급,
  deliveryMarker 확인, 수집 판정, 사후 트리 가드 순서. 서브에이전트는 읽고 돌려
  보고 증거만 돌려준다; 소스 수정과 최종 판단은 부모 몫. Triggers: 조수 서브에이전트,
  assist subagent, 브라우저 검증 위임, 패치 리뷰 위임.
---

# Codex Assist Subagents — 조수 서브에이전트 위임 킷

Codex 본체가 소스를 고치는 동안 옆에서 증거를 모으는 4종 커스텀 서브에이전트
(`.codex/agents/assist_*.toml`, 공식 프로젝트 범위 위치)와 그것을 안전하게 부르는
`scripts/codex_assist.py` + `scripts/codex_assist_guard.py` 순서를 규정한다.
서브에이전트는 **증거 생산자**다 — 소스 수정과 최종 PASS 선언은 부모(Codex
본체)가 직접 재확인한 뒤에만 한다.

## 누구를 언제 부르나

| 상황 | 에이전트 | sandbox |
|---|---|---|
| `/chat` 시나리오를 로컬 브라우저로 확인 | `assist_browser_verifier` | workspace-write (장부 `browser/`만) |
| 실패 시나리오 1개의 코드 경로 원인 추적 | `assist_rag_trace_analyst` | read-only |
| WP1~WP5 등 라우팅 재편 착수 전 현재-vs-API우선 표 | `assist_routing_mapper` | read-only |
| 패치 직후 금지 목록 대조 | `assist_patch_reviewer` | read-only |

동시 실행 최대 **2개** (`agents.max_threads=3` 중 1개는 항상 여유로 둔다).
같은 파일/같은 장부를 두 서브에이전트에 동시에 맡기지 않는다.

## 순서 (위임 한 건)

```text
1. python -B scripts/codex_assist.py packet --role <4종> --objective "<한 줄>"
      --ledger <작업 장부> [--scenario C2] [--budget-sends 10]
   → stdout의 delegateText를 서브에이전트에게 그대로 붙인다
2. python -B scripts/codex_assist_guard.py snapshot --ledger <장부>
3. 서브에이전트 위임 (spawn_agent / 자연어 지명 "use the assist_* agent")
4. 결과 텍스트를 파일로 저장 →
   python -B scripts/codex_assist.py collect --packet <패킷.json> --result <결과.txt>
   (ACCEPT | REJECT — 4섹션·deliveryMarker·files_written 범위·비밀 패턴 검사,
    결과 본문은 저장하지 않고 해시만 남긴다)
5. python -B scripts/codex_assist_guard.py verify --ledger <장부> --packet <패킷.json>
   (snapshot 대비 allowed_write_paths 밖 변경 = FAIL; 다른 세션 lease 경로 = FOREIGN)
6. 부모가 증거를 직접 재확인 후 최종 판단
```

`status --ledger <장부>`: 위임 수, 역할별 ACCEPT/REJECT, 남은 브라우저 전송 예산.

## 하드 규칙

- 서브에이전트는 `main/**`·`chat.js`·설정·fixture를 절대 수정하지 않는다 —
  TOML `sandbox_mode`는 지침이고, **강제는 `codex_assist_guard.py verify`의
  사후 트리 검사**다(부모 live sandbox override가 TOML을 덮을 수 있음,
  OFFICIAL 근거 참조).
- `model`을 정의 파일에 지정하지 않는다(부모 상속). 유료 외부 모델 경로 0.
- 공개 URL(`abandonwareai.kro.kr`) 전송 0, 브라우저 전송은 패킷 예산 이내.
- 서브에이전트가 서브에이전트를 부르지 않는다(`max_depth=1` + 정의 파일의
  `multi_agent=false`).
- 서브에이전트 반환은 **증거**다. 최종 PASS 선언·소스 수정·커밋은 부모가
  직접 재확인 후에만 한다.

## 관련

- 정의 근거 정리: `data/agent-handoff/devin-codex-assist-subagents-15806b7d/OFFICIAL.md`
- 사용자 카드: `docs/codex/CODEX_ASSIST_SUBAGENTS_KO.md`
- 지시서 첨부 블록: `docs/codex/ASSIST_BRIEF_CLAUSE.txt`
- 알려진 upstream 리스크: `openai/codex#26408` (프로젝트 범위 spawn이 일부
  버전에서 `agent type is currently not available`로 실패 — 발생 시 카드의
  전역 설치 명령은 사용자 결정)
