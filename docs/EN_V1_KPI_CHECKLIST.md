# EN_V1 KPI 체크리스트 (AUTO/STRICT/LENIENT 비교용)

이 문서는 **EN_V1.txt 같은 Search Trace 로그**에서, 실제로 찍히는 키를 기준으로
- fallback / extraSearch / skip 을 **정확히 라인 단위로 분류**하고
- **NOFILTER_SAFE 쏠림(비율)**을 **안정적으로 파싱**하는 방법을 정리합니다.

> ✅ “키 이름”은 EN_V1.txt에 등장한 것만 우선 사용하고, 필요한 경우에만 *추가 키*를 별도로 표기했습니다.

---

## 1) 라인 분류 규칙 (fallback / extraSearch / skip)

### 1-A) fallback 라인(= fail-soft ladder 발동)

**(A1) fallback 발생 여부(최상위 신호)**
- `web.failsoft.starvationFallback` 값이 존재하면 fallback 발생
  - 예: `web.failsoft.starvationFallback	officialOnly->NOFILTER_SAFE`

**(A2) fallback 트리거(원인 분해)**
- `web.failsoft.starvationFallback.trigger`
  - 예: `BELOW_MIN_CITATIONS`, `OUT_ZERO`, `ALL_SKIPPED` 등

**(A3) fallback 실행 단위(run)에서 확인(정밀)**
- `Web FailSoft Runs` 테이블의 `fallback` 컬럼
  - 예: `officialOnly->NOFILTER_SAFE`

**(A4) 후보(candidate) 레벨에서 확인(진짜로 fallback으로 뽑힌 건지)**
- `Web FailSoft Runs` → `cands=` 하위 테이블의
  - `overridePath=starvationFallback` 또는 `rule=fallback`

---

### 1-B) extraSearch 라인(= 근거/품질 구제용 추가 탐색)

extraSearch는 “min citations 확보” 또는 “품질 게이트(UNVERIFIED 과다)” 대응으로 발생합니다.

**(B1) 품질 게이트 기반 extraSearch(UNVERIFIED 쏠림 구제)**
- `web.failsoft.starvationFallback.qualityGate.needRescueExtraSearch`
- `web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.attempted`
- 후보 확인(있으면 디버깅에 유용)
  - `web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.candidates`

**(B2) minCitationsRescue 기반 extraSearch(최소 근거 수 구제)**
- `web.failsoft.minCitationsRescue.budget`
- (있으면) `web.failsoft.minCitationsRescue.attempted`
- (있으면) `web.failsoft.minCitationsRescue.calls.issued`
- `web.failsoft.minCitationsRescue.citeableCount.after`
- `web.failsoft.minCitationsRescue.satisfied`

> ⚠️ EN_V1에서는 `attempted`/`calls.issued`가 **없거나**(=출력 필터링) **false면 라인이 안 찍힐 수 있음**.
> 이 경우에도 `budget / satisfied / citeableCount.after` 조합으로 “실패한 구제”인지 판정 가능.

**(B3) run 관점(정량 비교 팁)**
- `Web FailSoft Runs` 테이블에서
  - `opts.kind=extraSearch` (이번 패치로 **추가됨**) → extraSearch 성격의 run

---

### 1-C) skip 라인(= 호출 스킵/쿨다운/브레이커)

**(C1) Provider 스킵(최상위 신호)**
- `web.<provider>.skipped`
  - 예: `web.brave.skipped	true`

**(C2) 스킵 원인(파싱 핵심)**
- `web.<provider>.skipped.reason`
  - 예: `cooldown`, `breaker_open`, `hedge_skip`

**(C3) 스킵 카운트(정량 비교 핵심)**
- `web.<provider>.skipped.count`

**(C4) Brave 레이트리밋(backoff) 연쇄 스킵 분해**
- `web.failsoft.rateLimitBackoff.brave.skipped`
- `web.failsoft.rateLimitBackoff.brave.justStarted`
- `web.failsoft.rateLimitBackoff.brave.skipped.logOnce`

**(C5) 쿨다운 시간(ms) 파싱(Brave 폭주/연쇄 스킵의 직관 KPI)**
- `web.brave.cooldownMs`

---

## 2) NOFILTER_SAFE 쏠림 파싱 (안정 버전)

### 2-A) 최종 선택(out) 기준(요약 KPI)
- `web.failsoft.stageCountsSelectedFromOut` (Map)
- `web.failsoft.stageCountsSelectedFromOut.outCount` (정수)

**NOFILTER_SAFE ratio 계산식**
- `ratio = stageCountsSelectedFromOut[NOFILTER_SAFE] / outCount`

### 2-B) run 단위(분해 KPI)
- `Web FailSoft Runs` 테이블의
  - `stageCountsSelected` (Map)
  - `outCount`

**이번 패치로 추가된 안정 KPI**
- `Web FailSoft Runs` 테이블의 `opts` 안에
  - `nofilterSafeRatio=0.00~1.00`

> 테이블 파싱 시, Map 파싱 없이 `nofilterSafeRatio=`만 잡아도 됨.

---

## 3) AUTO / STRICT / LENIENT 정량 비교 체크리스트

정량 비교는 “한 요청” 단위로 다음을 체크하면 됩니다.

### 3-A) fallback 빈도
- `web.failsoft.runs` summary 라인에서
  - `runs=N`
  - `fallback=K` (이번 패치로 *정확한 count* 출력)

**권장 KPI**
- `fallbackRate = K / N`

### 3-B) NOFILTER_SAFE 쏠림
- 최종 out 기준: `web.failsoft.stageCountsSelectedFromOut` + `outCount`
- run 기준: `Web FailSoft Runs` 테이블의 `opts.nofilterSafeRatio`

**권장 KPI**
- `nofilterSafeRatio_last`
- `nofilterSafeRatio_p95` (run 단위 분포로 보는 걸 추천)

### 3-C) extraSearch 호출량
- `web.failsoft.minCitationsRescue.calls.issued` (있으면 1순위)
- `Web FailSoft Runs`에서 `opts.kind=extraSearch` run 수
- `web.failsoft.starvationFallback.qualityGate.rescueExtraSearch.attempted=true`면 “품질 게이트 구제 발생”

**권장 KPI**
- `extraSearchRuns = count(kind=extraSearch)`
- `minCitationsRescueCalls` (있으면)

### 3-D) skip 빈도(Provider별)
- `web.<provider>.skipped.count`
- `web.<provider>.skipped.reason`
- `web.brave.cooldownMs` (연쇄 스킵 강도)

**권장 KPI**
- `braveSkipCount`, `braveSkipReason`, `braveCooldownMs`

---

## 4) EN_V1 runId=1 한 줄 KPI 예시(참고)

(예)
- `runIdx=1/runId=1 | fallback=Y(officialOnly->NOFILTER_SAFE, trigger=BELOW_MIN_CITATIONS) | extraSearch=Y(qualityGate.rescueExtraSearch.attempted=true) | skip=Brave:cooldown(count=21,cooldownMs=2000,backoffSkipped=true) | NOFILTER_SAFE_ratio=1.00 (NOFILTER_SAFE=5/outCount=5)`

> `extraSearchRuns`는 `Web FailSoft Runs`에서 `kind=extraSearch` 개수를 세면 됨.
