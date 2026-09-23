# {스터프1~7} → {스터프2(src111_merge15)} 통합 이식 가이드

이 문서는 **LOGXW.txt(세션별 패치 지시서/산출물)** 기준으로, {스터프1~7}의 장점만 {스터프2} 베이스(`src111_merge15`)에 **체리픽(cherry-pick) + 리베이스(rebase)** 스타일로 통합하는 절차를 정리합니다.

핵심 전략은 다음 3가지입니다.

1) **패치들을 “스택(stack)”으로 분해**한다. (관측/안전/동작변경/성능)
2) 각 스택을 **작은 커밋 시리즈로 분리**해서 `cherry-pick`으로 이식한다.
3) 마지막에 스택들을 **하나의 직선 히스토리로 rebase**하여 통합한다.

---

## 0. 베이스와 입력물

- 베이스: `{스터프2}` = `src111_merge15` (본 zip)
- 참고:
  - `LOGXW.txt` : {스터프1~7} 세션별 패치 지시서/산출물
  - `LOG_R.txt` : 실행 로그(증상/지표 확인용)

---

## 1. “장점만 이식”을 위한 분해 규칙

세션별 패치는 접근 방식과 우선순위가 다르므로, 먼저 아래 기준으로 분해합니다.

### A. 관측/디버깅(Observability) 스택
- 로그/TraceStore 키의 **스키마 일치**, 누락된 원인(reason) 드러내기
- 실패 시에도 **fail-soft**로 동작(로깅이 시스템을 죽이지 않음)

### B. 안전/가드(Safety & Guard) 스택
- 고위험 플래그/차단 로직은 기본적으로 보수적으로 유지
- “거짓 양성(false-positive)” 완화는 **옵션화**(토글 가능) 우선

### C. 동작 변경(Behavior) 스택
- 검색 결과/쿼리 수/모드 전환 등 사용자 경험에 직접 영향을 줌
- 반드시 **관측 스택이 먼저** 들어간 뒤에 이식

### D. 성능/부하(Performance) 스택
- 쿼리 버스트, 재시도, 추가 검색 호출 등은 상한/예산을 둔다.
- 기본값은 **작게** (예: maxQueries=3)

---

## 2. 권장 스택 구성(스터프1~7 기반)

> 아래는 “세션 단위”가 아니라 “충돌 최소화/리스크 최소화” 기준으로 재배치한 통합 순서입니다.

### Stack-1) Trace/await 이벤트 스키마 정합 + 요약(관측)
- 목적: `web.await.events`가 Map 구조여도 콘솔/테이블에서 원인이 보이게
- 핵심 포인트
  - `engine/stage/step/cause` 중심으로 요약
  - timeout / missing_future / budget_exhausted / interrupted 카운트 제공

### Stack-2) Web Fail-soft starvation top-up(동작)
- 목적: officialOnly 모드에서 “1개만 남는” starvation을 자동 완화
- 핵심 포인트
  - 트리거를 `BELOW_MIN_CITATIONS`로 둬서 **minCitations**를 맞추도록 top-up

### Stack-3) 오케스트레이션 stage policy 정리(동작/안전)
- 목적: COMPRESSION에서도 planner/transformer의 “저가 경로”가 돌 수 있게
- 핵심 포인트
  - `query-planner`, `query-transformer`를 `enableIn: [NORMAL, COMPRESSION]`
  - `query-transformer`의 irregularityDelta를 낮춰 연쇄 압축/스트라이크를 완화

### Stack-4) FaultMask → Irregularity 연쇄 완화(안전)
- 목적: optional(aux) 단계의 faultmask로 irreg가 과도 상승해 모드가 급격히 바뀌는 문제를 완화
- 핵심 포인트
  - (요청당 1회) cap 또는 delta 상한
  - TraceStore로 사용 여부/클램프 여부를 남김

### Stack-5) Planner bypass 상황에서의 “실패-안전 쿼리 증식”(동작/성능)
- 목적: planner가 건너뛰어진 경로에서도 단일 쿼리 → 2~3개로 확장해 recall 회복
- 핵심 포인트
  - 조건부 활성화: auxDown/compression/strike/bypass + allowWeb=true + singleQuery
  - 상한: maxQueries=3

---

## 3. 통합 이식 절차 (cherry-pick + rebase)

### 3.1 브랜치/스택 준비
1) 베이스 체크아웃
```bash
git checkout -b port/stuff1-7 origin/src111_merge15
```

2) 충돌 반복을 줄이기 위한 rerere 활성화(권장)
```bash
git config rerere.enabled true
```

3) 스택별 브랜치 생성(선택)
- `stack/trace` / `stack/failsoft` / `stack/stage-policy` / `stack/irreg-cap` / `stack/query-augment`

### 3.2 스택별 cherry-pick
각 스택은 가능한 한 **작은 커밋 단위**로 나눠서 이식합니다.

권장 순서:
1) Stack-1 (관측)
2) Stack-2 (fail-soft)
3) Stack-3 (stage policy)
4) Stack-4 (irreg cap)
5) Stack-5 (query augment)

### 3.3 최종 rebase로 직선화
스택들을 합친 뒤(또는 feature 브랜치를 합친 뒤) 최종적으로 rebase로 히스토리를 직선화합니다.
```bash
git rebase origin/src111_merge15
```

---

## 4. 충돌 해결 규칙(우선순위)

1) **빌드/런타임 크래시 방지**가 최우선
2) Trace/로그 스키마는 **하위 호환 유지**(구 키를 없애지 말고 추가)
3) 동작 변경은 **프로퍼티 토글**로 되돌릴 수 있게
4) 도메인/안전 정책은 기본 보수(필요 시 완화는 옵션으로)

---

## 5. 통합 후 검증 체크리스트

### 5.1 빌드
- `tools/gptpro_build_fast.sh` 또는 `./gradlew :app:classes`

### 5.2 Trace 키 확인(대표)
- `web.await.events.summary.*`
- `web.failsoft.starvationFallback.*`
- `orch.failsoft.queryAugment.*`
- `faultmask.irregularityCap.*`

### 5.3 로그 기반 증상 완화 확인(LOG_R 관점)
- Brave await skipped가 발생했을 때 **원인(cause/step)**이 Trace에 남는지
- officialOnly starvation에서 minCitations를 충족하도록 top-up 되는지
- compression/strike가 불필요하게 연쇄 발생하지 않는지

---

## 6. 롤백 전략

- 스택 단위로 되돌릴 수 있게 커밋을 쪼갰다면:
  - 문제가 생긴 스택만 `git revert`로 제거
  - 또는 토글 프로퍼티로 비활성화(우선)

---

## 7. 운영 토글(권장)

- `nova.orch.failsoft-query-augment.enabled=true|false`
- `nova.orch.failsoft-query-augment.maxQueries=3`
- `nova.orch.faultmask-irregularity-cap.enabled=true|false`
- `nova.orch.faultmask-irregularity-cap.maxDelta=0.04`

