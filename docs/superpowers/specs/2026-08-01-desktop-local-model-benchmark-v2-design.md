# Desktop Local Model Benchmark v2 설계

> **MODEL LOCK (2026-09-17, noted 2026-09-19):** This dated document may reference retired model tags (`qwen3:8b`, `qwen3:30b`, `qwen3-coder:*`, `gemma3:*`, `qwen2.5:7b-instruct`). Do not execute or wire those tags — live SoT: repo `AGENTS.md` model lock + `configs/api-routing.yaml` + `ollama ls`.

**상태:** 설계 및 Family A evidence-binding 교정 승인 완료
**설계 승인:** 2026-08-01
**문서 작성:** 2026-08-01
**실행 소유자:** Desktop
**관련 설계:** `docs/superpowers/specs/2026-07-31-desktop-local-model-autopilot-design.md`
**관련 계획:** `docs/superpowers/plans/2026-07-31-desktop-ollama-model-tooling-implementation.md`

## 1. 결정

schema-v1의 prose-only assertion을 **bounded-ontology typed-envelope v2**로 교체한다. 모델은 정답 하나가 아니라 해당 case에 허용된 전체 어휘를 전달받고, 그중 의미에 맞는 값을 선택한다. 정답은 별도 scorer oracle에만 존재하며 Ollama 요청 본문에는 들어가지 않는다.

이 변경은 벤치마크 계약의 교정이다. 모델 설치 성공, HTTP 200, JSON parse 성공, 응답 hash 일치만으로 품질이나 승격을 주장하지 않는다. v2 전체 행렬과 기존 GPU·digest·계보·품질 gate가 모두 통과할 때만 recommendation을 만들며 실제 role binding 변경은 계속 별도 승인 대상이다.

Family A의 위협 모델은 단일 Desktop runner가 한 실행에서 수집한 bounded observation을 정직하게 제공한다는 전제다. module-owned canonical SHA-256은 동일 실행의 bounded facts가 바뀌거나 다른 실행의 packet이 섞이는 것을 탐지하는 무결성 결속 수단이다. **unkeyed SHA-256은 인증, 독립 provenance, 또는 명령이 실제로 정직하게 실행되었다는 proof가 아니다.** 이 5-file 범위에는 독립 trust root나 보호된 key가 없으므로 HMAC/signature는 추가하지 않으며, authenticity를 늘리지 못하는 hash chain도 v2에는 추가하지 않는다.

## 2. 변경 근거

2026-08-01 진단에서 fast/main 6개 모델에 schema-v1 case 3개씩을 실행한 18회 요청은 모두 HTTP 성공, exact response model, `think=false`, GPU resident ratio 1.0을 만족했지만 prose-only assertion은 0/18이었다.

- fast 공통 실패: `missing-required`, `json-parse-failed`, `exact-mismatch`
- main 공통 실패: `missing-citation`, `missing-any`, `json-parse-failed`
- exact 정답을 `const`로 넣은 JSON Schema는 통과했지만 정답 유출이므로 false green이다.
- shape-only schema는 JSON parse를 복구했지만 Qwen은 의미 mismatch, Gemma는 port만 맞고 GPU·role을 틀렸다.
- 정답을 표시하지 않고 `gpu`, `role`, `port`의 전체 허용 어휘를 제공한 bounded ontology에서는 `qwen3.5:9b`와 `gemma4:12b`가 독립적으로 세 필드를 모두 맞혔다.
- schema-v1의 `p95Ms`는 모델별 3개 표본의 최댓값이며 cold load를 포함했다. canonical p95나 자동 승격 근거로 사용할 수 없다.

따라서 JSON 모양과 실제 의미 성공을 분리하고, cold load와 반복 warm latency를 분리해야 한다.

## 3. 범위

### 3.1 포함

- fast/main 6개 case의 request-only schema-v2 corpus
- scorer-only oracle과 outbound request의 물리적 분리
- `const`, 단일값 enum, case별 정답 축소 enum, 정답 개수 노출 방지 validator
- typed-envelope parse, schema, semantic scoring
- cold-load 1회와 case별 warm 7회 분리
- warm role 표본 21개 기반 nearest-rank p95
- 기존 점수 가중치와 승격 hard gate를 사용하는 recommendation
- prompt/options/schema/oracle/response hash와 digest·VRAM·latency의 redacted report
- 독립 `SUPPORT_CONTRACT`, `SUPPORT_SCENARIO`, `FALSIFY` packet 및 입력 순서가 바뀌어도 동일해야 하는 `NEUTRAL` 판정

### 3.2 제외

- 모델 pull, 삭제, 재설치 또는 digest 변경
- `LLM_FAST_MODEL`, `LLM_CHAT_MODEL`, `LLM_HIGH_MODEL`, `LLM_CODER_MODEL` 변경
- Java/Spring 애플리케이션 소스 또는 Browser UI 변경
- DB, Supabase, credential, machine-scope 환경 변수, embedding model 변경
- raw prompt/response를 report, TraceStore 또는 UI에 저장
- schema-v1 진단 결과를 삭제하거나 v2 결과로 가장
- Browser 응답을 direct Ollama 응답으로 대체해 인증

### 3.3 coder 범위 보류

schema-v1의 coder case 2개는 code substring만 검사한다. 이를 실제 pass-rate로 바꾸려면 모델 생성 코드를 안전하게 격리 실행하는 별도 sandbox 계약이 필요하다. Windows Desktop에서 임의 PowerShell/Java를 실행하는 기능을 이번 벤치마크 교정에 끼워 넣지 않는다.

첫 v2 wave는 fast/main만 평가한다. `LLM_CODER_MODEL`은 `qwen3-coder:30b`를 유지하고, `qwen3.6:27b`의 coder 승격은 별도 설계와 승인을 받기 전까지 `HOLD`다.

## 4. 고정 모델 행렬

| 순서 | 역할 | 모델 | endpoint/GPU | exact digest |
| ---: | --- | --- | --- | --- |
| 1 | fast baseline | `qwen3:8b` | `11435` / RTX 3060 | `500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41` |
| 2 | fast primary | `qwen3.5:9b` | `11435` / RTX 3060 | `6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7` |
| 3 | fast challenger | `gemma4:12b` | `11435` / RTX 3060 | `4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c` |
| 4 | main baseline | `gemma4:26b` | `11434` / RTX 3090 | `5571076f3d70050487b26b341705799e0ab29b808164f90d20d4cf84f699d251` |
| 5 | main primary | `qwen3.6:27b` | `11434` / RTX 3090 | `a50eda8ed977ab48a12431878896b27ffd5cef552c17af3317d9623b939a7f1e` |
| 6 | main challenger | `gemma4:31b` | `11434` / RTX 3090 | `6316f0629137b426c9d9b853ffc4c8209589f30ee39aebede6285096c0ff47e7` |

실행 전에 `/api/tags` 또는 `/api/show`의 exact digest를 다시 읽는다. tag가 있어도 digest가 다르면 `candidate-digest-changed`로 전체 recommendation을 `HOLD`한다.

## 5. 불변 조건

1. **정답 비유출:** expected value는 scorer oracle에만 존재한다.
2. **완전한 허용 어휘:** request enum은 해당 field에 선언된 전체 ontology와 정확히 같아야 한다.
3. **실제 선택:** `const`, 단일값 enum, 정답 하나만 남긴 enum을 금지한다.
4. **의미와 모양 분리:** JSON parse/schema 성공은 semantic 성공이 아니다.
5. **계보 분리:** HTTP 200, UI 출력, response hash는 model/digest/wire 의미 성공의 대체 증거가 아니다.
6. **직렬 실행:** 두 GPU를 사용하더라도 한 번에 하나의 model block만 실행한다.
7. **cold/warm 분리:** cold load는 기록하되 promotion p95와 품질 점수에서 제외한다.
8. **원문 비보존:** report에는 prompt/response 본문을 쓰지 않는다.
9. **무자동 승격:** benchmark는 recommendation까지만 만들며 binding을 변경하지 않는다.
10. **기준선 보존:** 후보가 gate를 통과하지 못하면 설치된 기준선과 role binding을 그대로 둔다.

## 6. 구성 요소와 경계

```text
Request Corpus v2 ──> Contract Validator ──> Pure Request Builder
                                               │
                                               v
                                         Ollama /api/chat
                                               │
                                               v
Scorer-only Oracle ─────────────────> Response Validator/Scorer
                                               │
                                               v
GPU + digest + lineage evidence ────> Cold/Warm Aggregator
                                               │
                                               v
                  SUPPORT_CONTRACT / SUPPORT_SCENARIO / FALSIFY
                                               │
                                               v
                                  Order-stable NEUTRAL
                                               │
                                               v
                                      Recommendation HOLD/APPLY
```

각 단위의 책임은 다음과 같다.

| 단위 | 책임 | 금지 |
| --- | --- | --- |
| Request Corpus | prompt, options, 전체 ontology, response shape | expected answer 저장 |
| Contract Validator | answer-leakage와 schema 축소 탐지 | 모델 호출 |
| Pure Request Builder | request case와 model/options만 받아 canonical body 생성 | oracle parameter 수신 |
| Response Validator | JSON parse와 schema 적합성 판정 | 품질 성공으로 자동 승격 |
| Oracle Scorer | case id로 숨겨진 expected와 semantic 비교 | outbound body 생성 |
| Aggregator | warm 품질·latency·VRAM·계보 집계 | raw prompt/response 보존 |
| NEUTRAL | 제공된 세 packet만으로 `APPLY | HOLD | REJECT` 판정 | 새 evidence 수집, 다수결 |

## 7. 계획된 파일 계약

구현 계획은 작성된 스펙 승인 후 별도로 만든다. 현재 설계가 지정하는 파일 책임은 다음과 같다.

| 파일 | 책임 |
| --- | --- |
| `scripts/config/desktop-ollama-model-benchmark.json` | schemaVersion 2 request-only corpus와 ontology |
| `scripts/config/desktop-ollama-model-benchmark-oracle.json` | case id별 scorer-only expected value |
| `scripts/modules/DesktopOllamaModelTools.psm1` | canonical hash, schema validator, scorer, atomic/redacted report helper |
| `scripts/desktop_ollama_model_benchmark.ps1` | 직렬 cold/warm 실행과 recommendation orchestration |
| `scripts/tests/DesktopOllamaModelBenchmark.Tests.ps1` | leakage, scorer, timing, serialization, redaction TDD |

새 gateway, 새 Java adapter, 새 공개 endpoint를 만들지 않는다.

## 8. Request Corpus v2

### 8.1 공통 options

모든 모델은 role 안에서 동일한 options를 사용한다.

```json
{
  "temperature": 0,
  "seed": 42,
  "num_ctx": 8192,
  "num_predict": 192,
  "stream": false,
  "think": false,
  "keep_alive": "5m"
}
```

응답 envelope는 192 token 안에 들어오도록 제한한다. `done_reason=length`는 `response-truncated`, 필수 field 누락은 `response-schema-invalid`이며 둘 다 semantic score를 계산하기 전의 contract hard failure다.

### 8.2 선언된 ontology

다음 목록은 첫 v2 wave가 허용하는 전체 선택 공간이다. case schema의 enum은 선언된 ontology와 값과 순서가 모두 같아야 하며, 목록을 줄이거나 재정렬해 정답을 암시할 수 없다.

| ontology | 전체 값 |
| --- | --- |
| `gpu` | `RTX 3060`, `RTX 3090` |
| `role` | `fast`, `main`, `high`, `coder`, `rewrite`, `explore`, `vision`, `embedding` |
| `port` | `11434`, `11435` |
| `instructionToken` | `PASS`, `FAIL`, `HOLD` |
| `meaningUnit` | `DOWNLOAD_MODEL`, `VERIFY_BEFORE_DEFAULT_CHANGE`, `CHANGE_DEFAULT_ONLY_AFTER_PASS`, `CHANGE_DEFAULT_BEFORE_VERIFY`, `AUTO_DELETE_BASELINE` |
| `groundedClaim` | `QWEN35_IS_3060_CANDIDATE`, `GPU_LOAD_CHECK_BEFORE_PROMOTION`, `QWEN35_IS_3090_BASELINE`, `PROMOTION_BEFORE_GPU_CHECK` |
| `evidenceState` | `SUPPORTED`, `INSUFFICIENT`, `CONFLICTING` |
| `answerKind` | `NUMERIC`, `NO_VALUE` |
| `unit` | `MIB`, `GIB`, `NONE` |
| `uncertaintyReason` | `EVIDENCE_MISSING`, `EVIDENCE_CONFLICT`, `EVIDENCE_PRESENT`, `OUT_OF_SCOPE` |
| `promotionDecision` | `PROMOTE`, `HOLD`, `REJECT` |
| `promotionReason` | `CPU_OFFLOAD`, `QUALITY_REGRESSION`, `DIGEST_MISMATCH`, `INSUFFICIENT_VRAM`, `LATENCY_REGRESSION`, `LINEAGE_MISSING`, `ALL_GATES_PASS` |

`citation` ontology는 case가 제공하는 모든 evidence label에 `NONE`을 더해 파생한다. `main-grounded-ko`에서는 `A`, `B`, `NONE` 전체를 전달한다.

### 8.3 case별 typed envelope

| case id | 필수 response field | semantic scorer |
| --- | --- | --- |
| `fast-rewrite-ko` | `rewrite:string`, `meaningUnits:meaningUnit[]` | hidden expected meaning-unit set exact match; rewrite는 non-empty, 160자 이하, newline 없음 |
| `fast-json-extract` | `gpu:gpu`, `role:role`, `port:port` | 세 field hidden expected exact match |
| `fast-instruction` | `token:instructionToken` | hidden expected token exact match |
| `main-grounded-ko` | `summary:string`, `claims:{claimCode:groundedClaim,citation:citation}[]` | hidden expected claim/citation pair set exact match; summary는 Hangul 포함, 400자 이하 |
| `main-uncertainty-ko` | `evidenceState`, `answerKind`, `value:number|null`, `unit`, `reasonCodes:uncertaintyReason[]` | hidden expected state/kind/value/unit/reason set exact match |
| `main-policy-ko` | `decision:promotionDecision`, `reasonCodes:promotionReason[]` | hidden expected decision/reason set exact match |

배열 비교는 순서와 무관한 exact set 비교다. expected에 없는 추가 claim/reason/meaning unit은 hallucination이므로 실패한다. enum 문자열은 case-sensitive exact match다. free-text `rewrite`와 `summary`는 hash와 길이만 report에 남고 본문은 즉시 폐기한다.

### 8.3.1 exact request prompt

request corpus에는 다음 UTF-8 문자열을 그대로 저장한다. 구현 중 표현을 자연스럽게 다듬거나 모델별로 바꾸지 않는다.

| case id | exact prompt |
| --- | --- |
| `fast-rewrite-ko` | `다음 문장을 의미를 유지한 한 문장으로 간결하게 고쳐라: 시스템은 모델을 다운로드한 뒤 검증을 통과한 경우에만 기본값을 바꾼다. rewrite에는 고친 문장을, meaningUnits에는 문장이 보존한 의미 단위를 선택해라.` |
| `fast-json-extract` | `문장 'RTX 3060은 fast 역할이고 포트는 11435다'에서 gpu, role, port를 추출하라.` |
| `fast-instruction` | `오직 PASS라는 의미를 token field로 표현하라.` |
| `main-grounded-ko` | `근거 [A]: qwen3.5:9b는 3060 후보이다. 근거 [B]: 승격 전 GPU 적재를 확인한다. 근거만 사용해 summary를 작성하고, claims에는 각 주장과 그 근거 label을 연결하라.` |
| `main-uncertainty-ko` | `제공된 근거에는 RTX 3060의 실제 VRAM 용량이 없다. evidenceState, answerKind, value, unit, reasonCodes로 답하라. 근거가 지원하는 숫자만 value에 넣어라.` |
| `main-policy-ko` | `다운로드 성공, CPU offload 발견, 기준선보다 품질 하락이라는 조건에서 승격 결정을 decision과 reasonCodes로 답하라.` |

### 8.3.2 scorer-only oracle

다음 expected는 oracle file에만 저장한다. request corpus, `format`, system/user message, model options에는 복사하지 않는다.

| case id | hidden expected |
| --- | --- |
| `fast-rewrite-ko` | `meaningUnits = {DOWNLOAD_MODEL, VERIFY_BEFORE_DEFAULT_CHANGE, CHANGE_DEFAULT_ONLY_AFTER_PASS}` |
| `fast-json-extract` | `gpu=RTX 3060`, `role=fast`, `port=11435` |
| `fast-instruction` | `token=PASS` |
| `main-grounded-ko` | `{QWEN35_IS_3060_CANDIDATE,A}`, `{GPU_LOAD_CHECK_BEFORE_PROMOTION,B}` exact pair set |
| `main-uncertainty-ko` | `evidenceState=INSUFFICIENT`, `answerKind=NO_VALUE`, `value=null`, `unit=NONE`, `reasonCodes={EVIDENCE_MISSING}` |
| `main-policy-ko` | `decision=REJECT`, `reasonCodes={CPU_OFFLOAD, QUALITY_REGRESSION}` |

`rewrite`는 trim 후 1~160자, CR/LF 없음으로 검증한다. `summary`는 trim 후 1~400자이고 Unicode Hangul syllable 범위 `U+AC00..U+D7A3` 문자를 하나 이상 포함해야 한다. 이 두 free-text field의 의미 판정은 대응하는 typed meaning/claim field가 담당한다.

### 8.4 request와 oracle 분리 예시

request corpus에는 전체 선택 공간만 있다.

```json
{
  "id": "fast-json-extract",
  "role": "fast",
  "prompt": "문장 'RTX 3060은 fast 역할이고 포트는 11435다'에서 gpu, role, port를 추출하라.",
  "responseSchema": {
    "type": "object",
    "additionalProperties": false,
    "required": ["gpu", "role", "port"],
    "properties": {
      "gpu": {"type": "string", "enum": ["RTX 3060", "RTX 3090"]},
      "role": {"type": "string", "enum": ["fast", "main", "high", "coder", "rewrite", "explore", "vision", "embedding"]},
      "port": {"type": "integer", "enum": [11434, 11435]}
    }
  }
}
```

별도 oracle만 expected를 가진다.

```json
{
  "id": "fast-json-extract",
  "expected": {"gpu": "RTX 3060", "role": "fast", "port": 11435}
}
```

`New-AwxBenchmarkRequestBody`에 해당하는 future builder는 request case, exact model tag, 공통 options만 인자로 받는다. `responseSchema`는 prompt text에 합치지 않고 Ollama `/api/chat` body의 `format` field에 canonical JSON Schema로 넣는다. builder는 oracle path, oracle object 또는 expected value를 인자로 받을 수 없다.

## 9. Answer-leakage validator

벤치마크 실행 전 corpus 전체를 fail-closed 검증한다.

1. 모든 schema node에서 `const`, `default`, `examples`를 금지한다.
2. enum은 두 값 이상이어야 하며 선언된 ontology 전체와 exact sequence가 같아야 한다.
3. case-specific enum 축소를 금지한다.
4. semantic array의 `minItems`와 `maxItems`를 expected 정답 개수와 같게 고정하지 않는다. `minItems`는 구조상 0 또는 1, `maxItems`는 전체 ontology 크기다.
5. numeric range, regex, description 같은 annotation으로 expected를 암시하지 않는다.
6. request corpus에는 key 이름 `expected`, `oracle`, `correctAnswer`가 존재할 수 없다.
7. oracle의 sentinel expected 값을 바꿔도 canonical outbound request hash는 동일해야 한다.
8. ontology를 바꾸면 schema hash가 바뀌고, oracle만 바꾸면 oracle hash만 바뀌어야 한다.
9. outbound body byte scan에서 oracle sentinel이 발견되면 `request-body-oracle-contamination`으로 호출 전 중지한다.

validator 실패는 특정 모델 실패가 아니라 benchmark contract 실패다. 모든 모델 실행과 recommendation을 `HOLD`한다.

## 10. Response 검증과 semantic scoring

검증 단계는 다음 순서를 바꿀 수 없다.

1. HTTP와 exact response model 확인
2. `done_reason`과 timeout 확인
3. response JSON parse
4. `additionalProperties=false`를 포함한 response schema 검증
5. ontology 값 확인
6. scorer oracle semantic 비교
7. raw response hash·length 계산 후 본문 폐기

각 case 결과는 다음을 독립적으로 기록한다.

- `transportPassed`
- `lineagePassed`
- `jsonParsed`
- `schemaPassed`
- `semanticPassed`
- 단일 reason code

`jsonParsed=true` 또는 `schemaPassed=true`만으로 `semanticPassed=true`를 만들 수 없다. shape-only probe에서 관찰한 semantic mismatch가 이 회귀를 검증하는 고정 negative fixture다. `const` schema false green도 validator 고정 negative fixture다.

## 11. Cold/Warm 실행 프로토콜

각 모델 block은 전체 포트에 대해 직렬로 실행한다.

1. 대상 endpoint에 `keep_alive=0`을 보내 이전 model을 unload한다.
2. `/api/ps`에서 대상 model이 없음을 확인한다.
3. role의 첫 case를 한 번 보내 cold load를 측정한다.
4. cold response의 transport/lineage/schema는 검증하지만 latency와 semantic 결과는 promotion score에서 제외한다.
5. `/api/ps`에서 exact model, context, GPU resident ratio, loaded free VRAM을 확인한다.
6. role의 case 3개를 각각 7회 실행한다. 총 warm 표본은 모델당 21개다. 물리 실행 순서는 case별 grouped 또는 repetition별 round-robin 중 하나일 수 있다.
7. block 마지막에 `/api/ps`와 GPU free VRAM을 다시 확인한다.
8. `keep_alive=0`으로 해당 endpoint model을 unload하고 빈 `/api/ps`를 확인한 뒤 다음 모델로 이동한다.

동시에 두 endpoint를 호출하지 않는다. model block 제한은 1,800초, 전체 portfolio 제한은 7,200초다. cold request 제한은 300초, warm request 제한은 120초다. 제한 초과 시 미완료 표본을 재전송해 수를 맞추지 않고 전체 verdict를 `HOLD`한다.

### 11.1 latency 통계

- `coldLoadMs`: 모델당 cold 1회, 진단 전용
- `warmCaseP50Ms`: case별 warm 7회 nearest-rank p50, 진단 전용
- `warmRoleP95Ms`: role 전체 warm 21회 nearest-rank p95, promotion 비교용

nearest-rank는 정렬된 N개 표본에서 `ceil(P * N)`번째 값을 사용한다. N=21인 p95는 20번째 값이다. cold 표본과 unload 시간은 `warmRoleP95Ms`에 넣지 않는다.

`Get-AwxModelMetrics`는 물리 row 순서를 계약으로 오인하지 않는다. role의 case-sensitive exact case ID 세 개가 각각 정확히 7개이고, 각 case의 `repetition`이 exact integer `1..7`을 중복 없이 한 번씩 갖는 **multiset**만 허용한다. 따라서 grouped와 round-robin은 모두 허용하지만 missing, duplicate, wrong-case, wrong-role, scalar/numeric-string/fractional repetition은 구조 실패다. 수집 row 순서는 block evidence hash를 위해 그대로 보존한다.

## 12. 점수 계산

기존 가중치와 promotion threshold는 유지한다. semantic metric만 typed-envelope 결과로 재정의한다.

```text
fast.rewriteAndExtractionQuality = mean(
  semanticRate(fast-rewrite-ko),
  semanticRate(fast-json-extract)
)
fast.instructionFollowing = semanticRate(fast-instruction)
fast.toolAndStructuredOutput = semanticRate(fast-json-extract)

main.koreanRagQuality = semanticRate(main-grounded-ko)
main.factualityAndCitationDiscipline = mean(
  semanticRate(main-grounded-ko),
  semanticRate(main-uncertainty-ko)
)
main.instructionFollowing = semanticRate(main-policy-ko)
main.codingAndToolUse = semanticRate(main-policy-ko)
```

`semanticRate(case) = semanticPassedWarmCount / 7`이다.

공통 운영 metric은 동일 role에서 hard gate를 통과한 모델끼리 계산한다.

```text
latencyEfficiency(model) = minEligibleWarmRoleP95Ms / modelWarmRoleP95Ms
vramHeadroom(model) = modelLoadedFreeMiB / maxEligibleLoadedFreeMiB
runtimeStability(model) = completedWarmCount / 21
```

모든 입력 semantic metric과 `runtimeStability`는 cast 전부터 finite number `0.0..1.0`이어야 한다. `NaN`, infinity, numeric string, 음수, 1 초과 값은 점수 계산 전 구조 실패이며 clamp로 복구하지 않는다. 유효한 입력으로 계산한 `latencyEfficiency`, `vramHeadroom`, `runtimeStability`와 최종 weighted component만 0.0부터 1.0으로 clamp한다. schema/lineage/GPU hard gate를 통과하지 못한 모델은 점수를 계산하지 않는다.

`balancedScore`의 단일 canonical domain은 decimal `MidpointRounding.ToEven`으로 소수점 여섯 자리까지 반올림한 값이다. `Get-AwxRoleScore`는 이 canonical 값만 방출하고 selector는 입력 score가 finite `0.0..100.0`이면서 자신의 six-decimal ToEven representation과 decimal exact-equal할 때만 받는다. `80.0000004`처럼 여섯 자리보다 미세한 값은 반올림해 복구하지 않고 `benchmark-contract-invalid`다. 내부 비교는 canonical score를 exact signed `Int64` microunits(`score * 1,000,000`)로 변환해 수행한다.

가중치는 기존 설계와 같다.

```text
balancedMainScore = 100 * (
  0.35 * koreanRagQuality
  + 0.20 * instructionFollowing
  + 0.15 * codingAndToolUse
  + 0.10 * factualityAndCitationDiscipline
  + 0.10 * latencyEfficiency
  + 0.05 * vramHeadroom
  + 0.05 * runtimeStability
)

balancedFastScore = 100 * (
  0.30 * rewriteAndExtractionQuality
  + 0.30 * latencyEfficiency
  + 0.15 * instructionFollowing
  + 0.10 * toolAndStructuredOutput
  + 0.10 * vramHeadroom
  + 0.05 * runtimeStability
)
```

## 13. Hard gate와 승격 조건

### 13.1 strict `HardGateEvidence`

`Test-AwxModelHardGate`는 caller의 truthy/coercive 값을 신뢰하지 않는다. 입력은 다음 property만 정확히 가져야 한다.

| property | exact type/range |
| --- | --- |
| `digestPassed` | `System.Boolean` |
| `lanePassed` | `System.Boolean` |
| `lineagePassed` | `System.Boolean` |
| `hashesComplete` | `System.Boolean` |
| `completedColdCount` | integer `0..1` |
| `completedWarmCount` | integer `0..21` |
| `caseSampleCountsPassed` | `System.Boolean` |
| `warmJsonSchemaPassedCount` | integer `0..21` |
| `nonEmptyThinkingCount` | integer `0..21` |
| `truncatedCount` | integer `0..21` |
| `gpuResidentRatio` | finite number `0.0..1.0` |
| `loadedFreeMiB` | finite number `>= 0` |
| `endpointRestarted` | `System.Boolean` |
| `timeoutCount` | integer `0..21` |

Property 누락/추가, string 또는 number Boolean 대체, numeric string, `NaN`, infinity, fractional count, 음수, 범위 초과, `completedWarmCount`보다 큰 warm-derived count와 같은 모순은 threshold 계산 전에 구조 실패다. 결과는 정확히 `passed=false`, `reasonCodes=["benchmark-contract-invalid"]`이며 값을 cast해서 복구하지 않는다. `Select-AwxRoleRecommendation`의 각 row에 있는 `hardGatePassed`도 `System.Boolean`만 허용한다.

구조가 유효한 모든 모델의 공통 hard gate는 다음과 같다.

- exact installed tag/digest 일치
- 지정 endpoint와 GPU lane 일치
- request/response model 계보 일치
- cold 1회와 warm 21회 transport 성공
- case별 warm 7회와 warm 21회 JSON parse/schema 성공
- `thinkingLength=0`, `done_reason` length 아님
- GPU resident ratio `>= 0.99`
- fast loaded free VRAM `>= 1536MiB`
- main loaded free VRAM `>= 2048MiB`
- CPU offload, endpoint restart, timeout 없음
- prompt/options/schema/oracle/response hash 완전성

fast 후보는 기존 기준대로 다음을 모두 만족해야 한다.

- `balancedFastScore >= baseline`
- rewrite/extraction 품질이 baseline보다 낮지 않음
- `warmRoleP95Ms <= baseline`
- 공통 hard gate 통과

main 후보는 다음을 모두 만족해야 한다.

- `balancedMainScore - baseline >= 5`
- Korean RAG quality가 baseline보다 낮지 않음
- `warmRoleP95Ms <= 1.5 * baseline`
- 공통 hard gate 통과

동점 또는 승자 없음은 정상적인 `keep-baseline`이다. recommendation이 `APPLY`여도 실제 role binding은 별도 명시적 promotion 승인 없이는 바뀌지 않는다.

`Select-AwxRoleRecommendation`은 선택한 role의 고정 Family A identity matrix와 각 row의 `modelTag`/`classification`을 case-sensitive exact match하고, 중복 identity를 거부하며, 정확히 하나의 baseline을 요구한다. baseline을 포함한 primary/challenger 부분집합은 허용하지만 다른 role의 model 또는 classification/model mismatch는 `benchmark-contract-invalid`로 fail closed한다. `hardGatePassed=true`인 row의 `balancedScore`는 canonical six-decimal ToEven finite `0.0..100.0`, role quality(`rewriteAndExtractionQuality` 또는 `koreanRagQuality`)는 finite `0.0..1.0`, `warmRoleP95Ms`는 finite positive여야 한다. baseline hard gate가 false이거나 baseline score/role quality/positive latency가 null 또는 invalid이면 산술 전에 `HOLD/baseline-evidence-incomplete`를 반환한다. hard-gate-passed nonbaseline candidate의 해당 metric이 빠지거나 invalid하면 추천 산술을 수행하지 않고 `benchmark-contract-invalid`로 fail closed한다.

Selector는 baseline과 candidate score를 microunits로 한 번만 변환한다. fast score gate, main의 `>= 5,000,000` microunit delta gate, strict-positive winner gate, score ordering과 score tie는 모두 integer 연산을 사용한다. Candidate는 `deltaMicros > 0`일 때만 `recommend-candidate`가 될 수 있고 공개 `scoreDelta`는 정확히 `deltaMicros / 1,000,000.0`이다. 따라서 canonical `80.000001` 대 `80.0`은 `scoreDelta=0.000001`인 promotion이지만, zero 또는 negative delta promotion은 internal recommendation이나 public report로 받아들이거나 projection할 수 없다. Quality와 latency gate 및 latency tie-breaker는 기존 규칙을 그대로 유지한다.

## 14. Triadic adjudication

### 14.1 current-run binding

`New-AwxRunBinding`은 module-owned constructor다. caller가 완성된 hash envelope를 제출할 수 없고, 다음 값만 받는다.

```text
benchmarkId
corpusSha256
oracleSha256
ontologySha256
schemaSha256
optionsSha256
modelBlocks
```

`benchmarkId`는 정확히 `awx.desktop-ollama-benchmark.v2`다. 다섯 static hash와 모든 digest/block hash는 lowercase 64-hex다. `modelBlocks`는 제4절의 모델과 digest를 동일한 순서로 정확히 여섯 개 포함하며 각 block shape는 정확히 다음과 같다.

```text
modelTag, digest, blockEvidenceSha256
```

각 `blockEvidenceSha256`은 module-owned `awx.desktop-ollama-model-block-evidence.v2` hash contract로 strict bounded block facts만 결속한다. `blockFacts`의 property tree는 정확히 다음과 같다.

```text
modelTag, role, classification,
cold: {
  caseId, requestSha256, responseSha256, latencyMs,
  transportPassed, lineagePassed, jsonParsed, schemaPassed,
  semanticPassed, reasonCode
},
warm: exactly 21 acquisition-order records {
  caseId, repetition, requestSha256, responseSha256, latencyMs,
  transportPassed, lineagePassed, jsonParsed, schemaPassed,
  semanticPassed, reasonCode
},
lane: { gpuResidentRatio, loadedFreeMiB, lanePassed },
cleanup: { psEmpty, gpuReleased, endpointRestarted }
```

`modelTag`, `role`, `classification`, cold case와 warm case는 고정 matrix/role과 일치해야 한다. 모든 SHA는 lowercase 64-hex, latency와 free MiB는 finite `>=0`, resident ratio는 finite `0.0..1.0`, 모든 passed/cleanup field는 `System.Boolean`, `repetition`은 exact integer `1..7`, `reasonCode`는 sample policy의 단일 case-sensitive code다. Warm은 제11.1절의 exact case/repetition multiset을 만족해야 하며 acquisition order는 hash input에서 보존한다. 추가/누락 property나 coercive scalar는 허용하지 않는다. raw prompt/response/error body, path, environment value, GPU UUID, credential, command output은 block preimage에 들어갈 수 없다.

`blockEvidenceSha256`은 다음 exact domain-separated object의 UTF-8 canonical JSON 전체에 대한 lowercase SHA-256이다. 여기서 `{}`는 위 exact tree의 실제 `blockFacts` object로 치환한다.

```json
{"hashContract":"awx.desktop-ollama-model-block-evidence.v2","blockFacts":{}}
```

정확한 `runBinding` shape는 다음과 같다.

```text
benchmarkId, corpusSha256, oracleSha256, ontologySha256,
schemaSha256, optionsSha256, modelBlocks
```

`New-AwxRunBinding`은 정확히 `{runBinding,runBindingSha256}`를 반환한다. `runBindingSha256`은 다음 domain-separated object의 UTF-8 canonical JSON 전체에 대한 lowercase SHA-256이다.

```json
{"hashContract":"awx.desktop-ollama-run-binding.v2","runBinding":{}}
```

이 hash는 canonical integrity와 stale/mixed-run detection을 제공할 뿐 producer 인증, provenance, execution proof를 제공하지 않는다.

### 14.2 exact internal lane evidence

`New-AwxEvidencePackets`는 validated run-binding envelope와 아래의 세 bounded evidence object만 받는다. caller-authored `complete`, `passed`, `reasonCodes`, `evidenceSha256`, `packetSha256`를 받지 않으며 status/reason은 module이 fixed policy order로 파생한다.

`SUPPORT_CONTRACT.evidence`의 exact shape:

```text
contractValidated: System.Boolean
digestChecks: exactly six fixed-order {modelTag,passed:System.Boolean}
hashCompletenessChecks: exactly six fixed-order {modelTag,passed:System.Boolean}
lineageChecks: exactly six fixed-order {modelTag,passed:System.Boolean}
```

`SUPPORT_SCENARIO.evidence`의 exact shape:

```text
models: exactly six fixed-order records {
  role, classification, modelTag,
  hardGatePassed:System.Boolean,
  hardGateReasonCodes: exact HARD_GATE policy array,
  warmRoleP95Ms: finite number >= 0,
  loadedFreeMiB: finite number >= 0,
  balancedScore: null or canonical six-decimal ToEven finite number 0.0..100.0,
  semanticRates: exactly three fixed-role-order {caseId,rate:finite 0.0..1.0}
}
recommendations: exactly two records in fixed role order fast, main {
  role, status, modelTag:null-or-bounded, scoreDelta:null-or-canonical-six-decimal,
  reasonCode: one bounded recommendation code
}
```

Scenario recommendation은 caller-authored 결론이 아니라 같은 scenario model facts의 deterministic projection이다. 각 role의 fixed model rows에서 `balancedScore`, `warmRoleP95Ms`, 그리고 fast는 `fast-rewrite-ko`와 `fast-json-extract` semantic rate의 산술평균인 `rewriteAndExtractionQuality`, main은 `main-grounded-ko` semantic rate인 `koreanRagQuality`를 정확히 selector input으로 재구성한다. 각 scenario row의 `modelTag`와 `classification`은 선택한 role의 fixed Family A matrix identity와 exact match해야 한다. Module은 role별 `Select-AwxRoleRecommendation`을 다시 실행하고 반환된 `role,status,modelTag,scoreDelta,reasonCode` 다섯 field를 supplied recommendation과 exact compare한다. 하나라도 다르면 scenario evidence는 invalid이며 packet/NEUTRAL 경계에서 `HOLD`한다.

`hardGateReasonCodes` 자체의 fixed policy order는 다음과 같다.

```text
HARD_GATE:
pass, benchmark-contract-invalid, candidate-digest-changed,
warm-sample-insufficient, response-schema-invalid, response-truncated,
lineage-missing, gpu-lane-evidence-incomplete, cpu-offload-detected,
insufficient-vram
```

배열은 case-sensitive, unique, exact array type이어야 하며 이 순서의 subsequence여야 한다. `hardGatePassed=true`이면 정확히 `["pass"]`, false이면 non-empty이고 `pass`가 없어야 한다. Malformed `HardGateEvidence`는 model record에 정확히 `["benchmark-contract-invalid"]`로 남는다. `lanePassed=false`는 정확히 `gpu-lane-evidence-incomplete`이며 `lineage-missing`으로 대체하지 않는다.

Packet constructor는 model별 hard-gate array를 버리거나 임의로 relabel하지 않고 다음 deterministic projection을 적용한다. `benchmark-contract-invalid`와 `candidate-digest-changed`는 scenario lane의 `baseline-evidence-incomplete`로 매핑하고, 나머지 HARD_GATE failure code는 같은 이름의 `SUPPORT_SCENARIO` code로 매핑한다. 여섯 model의 결과를 합친 후 duplicate를 제거하고 SUPPORT_SCENARIO fixed policy order로 정렬한다. 따라서 malformed hard-gate도 lane-allowed packet failure로 표현되며 빈 배열이나 false `passed`와 `pass`의 조합을 만들 수 없다.

`FALSIFY.evidence`는 다음 정확한 아홉 `System.Boolean` property만 가진다.

```text
constRejected, shapeOnlyRejected, extraValueRejected,
truncationRejected, timeoutRejected, missingHashRejected,
orderRejected, oracleIsolated, p95Correct
```

각 internal packet의 exact shape는 다음과 같다.

```text
name, complete, passed, reasonCodes, runBindingSha256,
evidence, evidenceSha256, packetSha256
```

`complete`는 exact structural validation의 결과이고 `passed`와 `reasonCodes`는 evidence 의미에서 파생한다. Evidence와 packet hash preimage는 각각 다음 exact domain-separated object다.

```json
{"hashContract":"awx.desktop-ollama-lane-evidence.v2","runBindingSha256":"","name":"","evidence":{}}
```

```json
{"hashContract":"awx.desktop-ollama-packet.v2","runBindingSha256":"","name":"","complete":false,"passed":false,"reasonCodes":[],"evidenceSha256":""}
```

### 14.3 exact `CommandEvidence`

`New-AwxCommandEvidence`도 module-owned constructor다. validated run binding과 다음 evidence만 받는다.

```text
digestChecks:             exactly six fixed-order {modelTag,passed:System.Boolean}
completedBlockChecks:     exactly six fixed-order {modelTag,passed:System.Boolean}
finalEmptyPsChecks:       exactly two records in fixed endpoint order fast-11435, primary-11434 {endpointLabel,passed:System.Boolean}
privacyProjectionChecked: System.Boolean
privacyProjectionPassed:  System.Boolean
bindingGuardChecked:      System.Boolean
bindingGuardUnchanged:    System.Boolean
```

반환 exact shape:

```text
complete, passed, reasonCodes, runBinding, runBindingSha256,
evidence, commandEvidenceSha256
```

Hash preimage:

```json
{"hashContract":"awx.desktop-ollama-command-evidence.v2","runBindingSha256":"","complete":false,"passed":false,"reasonCodes":[],"evidence":{}}
```

Public report projection 전 provisional evidence는 `privacyProjectionChecked=false`를 사용하며 module은 반드시 `complete=false`, `passed=false`, `reasonCodes=["command-evidence-incomplete"]`를 파생한다. Exact public projection이 allowlist와 privacy scan을 통과한 뒤에만 두 privacy Boolean을 `true`로 둔 final CommandEvidence를 새로 구성한다.

### 14.4 NEUTRAL fail-closed validation

`NEUTRAL`은 internal packets와 internal CommandEvidence만 받으며 새 evidence를 수집하지 않는다. 판정 전에 exact property/type/cardinality/reason policy를 검증하고, CommandEvidence의 `runBinding`으로 `runBindingSha256`를 다시 계산하고, 세 lane evidence hash, 세 packet hash, command evidence hash를 모두 다시 계산한다. 네 envelope는 같은 current-run `runBindingSha256`를 가져야 한다. missing/extra/duplicate/stale/mutated fact 또는 hash, coercive type, malformed command evidence, reason policy 위반은 semantic `REJECT`가 아니라 `HOLD`다.

`Get-AwxOrderStableNeutralVerdict`는 malformed CommandEvidence에서도 throw하지 않는다. StrictMode에서 optional `runBindingSha256`/`commandEvidenceSha256` property를 `PSObject.Properties`로 먼저 존재 여부와 lowercase 64-hex 형식까지 safe-read하고, 없는 hash는 null로 유지한 exact nine-field internal NEUTRAL envelope를 `HOLD/command-evidence-incomplete`로 반환한다. Valid CommandEvidence에서는 두 hash를 그대로 유지한다. Null 또는 malformed hash를 가진 internal result는 public projection 경계를 통과할 수 없다.

판정은 packet name으로만 하며 array position이나 다수결을 사용하지 않는다. `SUPPORT_CONTRACT` 또는 `FALSIFY` failure는 `REJECT/contract-or-counterexample-failed`, incomplete scenario는 `HOLD/scenario-evidence-incomplete`, 모두 통과하면 `APPLY/all-packets-pass`다. Forward와 reverse evaluation은 **verdict와 reason code를 모두** 비교한다. 둘 중 하나라도 다르면 `HOLD/order-unstable`이다.

## 15. Report와 privacy 계약

report schema version은 2다. 공개 가능한 field는 다음으로 제한한다.

- corpus, ontology, schema, oracle, prompt, options, response SHA-256
- case id, role, bounded model tag, exact digest
- endpoint label `primary-11434` 또는 `fast-11435`
- GPU lane label, total/free MiB, resident ratio
- cold/warm timing aggregate, token count, response length
- parse/schema/semantic booleans과 reason code
- score, delta, `keep-baseline | recommend-candidate | HOLD`
- current-run binding hash, triadic packet hash와 order-stable verdict/reason

Public packet projection은 다음 일곱 property만 정확히 가진다.

```text
name, complete, passed, reasonCodes,
runBindingSha256, evidenceSha256, packetSha256
```

Public NEUTRAL projection은 다음 아홉 property만 정확히 가진다.

```text
forward, forwardReasonCode,
reverse, reverseReasonCode,
orderStable, finalVerdict, finalReasonCode,
runBindingSha256, commandEvidenceSha256
```

Public packet의 `reasonCodes`도 lane-specific allowlist, uniqueness, case, fixed policy order, status consistency를 다시 통과해야 한다. Internal `runBinding`, three lane `evidence` preimages, CommandEvidence `evidence`, bounded block facts는 public report에 쓰지 않는다. Public `runBindingSha256`은 current-run 결속 identifier일 뿐 authentication/provenance/execution claim으로 표시하지 않는다.

`ConvertTo-AwxPublicPacket`은 projection 전에 full internal `Assert-AwxPacketEnvelope`를 호출한다. `ConvertTo-AwxPublicNeutral`은 exact internal `Assert-AwxNeutralEnvelope`를 호출해 exact nine-property set, strict `System.Boolean` `orderStable`, allowed verdict/reason pairs, forward/reverse/final consistency, lowercase 64-hex hashes를 검증한다. Unknown, wrong-case, secret-shaped, raw-shaped reason, extra/missing property, null/malformed hash는 copy 전에 throw하며 public DTO로 정화하지 않는다.

Public report validator는 하나의 global union을 여러 field에 재사용하지 않는다. 모든 reason-bearing field는 exact array/scalar type, case, uniqueness, policy order와 상태 조합을 자기 scope에서 다시 검증한다.

- top-level validate-only: `mode=validate-only`, `verdict=HOLD`, `reasonCodes` exact array `["live-evidence-not-requested"]`
- top-level complete live: reason array는 정확히 한 개이며 `neutral.finalReasonCode`와 case-sensitive 동일하고 verdict도 `neutral.finalVerdict`와 동일하다.
- top-level failed live: `verdict=HOLD`, empty model/recommendation/packet, null neutral, reason array는 정확히 하나이며 `candidate-digest-changed | response-json-invalid | response-schema-invalid | response-semantic-mismatch | response-truncated | lineage-missing | cpu-offload-detected | insufficient-vram | warm-sample-insufficient | portfolio-timeout | model-block-timeout | model-unload-unproven | gpu-lane-evidence-incomplete | gpu-release-unproven | ps-evidence-incomplete | tags-evidence-incomplete | native-probe-timeout | role-binding-mutated | concurrent-model-block-detected | benchmark-runtime-failed` 중 하나다.
- model: `reasonCodes`는 exact HARD_GATE policy array이고 `hardGatePassed`와 pass/non-pass 상태가 일치한다.
- sample: scalar `reasonCode`는 `pass | response-json-invalid | response-schema-invalid | response-semantic-mismatch | response-truncated | lineage-missing | model-block-timeout | portfolio-timeout` 중 하나다. `semanticPassed=true`이면 정확히 `pass`; `pass`이면 transport/lineage/json/schema/semantic이 모두 true이고 `doneReason=stop`이다.
- recommendation: `HOLD/baseline-evidence-incomplete/null/null`, `keep-baseline/(no-eligible-candidate|candidate-tie)/bounded-baseline/0.0`, `recommend-candidate/promotion-approval-required/bounded-nonbaseline/canonical-positive-delta` 조합만 허용한다. `recommend-candidate`의 delta는 canonical six-decimal score microunits로 검증했을 때 반드시 `> 0`이어야 한다. 배열 role order는 정확히 `fast, main`이다.
- packet: 해당 lane의 policy와 `complete/passed` 상태를 제16절대로 검증한다.
- NEUTRAL: `APPLY/all-packets-pass`, `REJECT/contract-or-counterexample-failed`, `HOLD/(packet-set-invalid|packet-incomplete|command-evidence-incomplete|scenario-evidence-incomplete)`만 forward/reverse pair로 허용한다. `orderStable=true`이면 final verdict/reason은 forward와 동일하고 forward/reverse가 동일해야 한다. false이면 정확히 `HOLD/order-unstable`이며 forward/reverse pair가 달라야 한다.

다음을 저장하지 않는다.

- raw prompt 또는 raw response
- authorization header, cookie, credential, owner token
- 전체 환경 변수
- GPU UUID 원문
- model store backing path
- full error body

원문은 assertion에 필요한 동안 메모리에만 두고 hash·length 계산 뒤 폐기한다. secret-shaped source/output scan 실패는 report write 전 `secret-scan-failed`로 중지한다.

## 16. 실패 reason code

| reason | 의미 |
| --- | --- |
| `benchmark-contract-invalid` | corpus/oracle/schema 기본 계약 실패 |
| `const-forbidden` | request schema에 `const` 발견 |
| `single-valued-enum` | 한 값만 가진 enum 발견 |
| `ontology-narrowed` | case enum이 전체 ontology보다 작음 |
| `answer-cardinality-leaked` | schema가 정답 배열 길이를 노출 |
| `request-body-oracle-contamination` | outbound body에 oracle sentinel 발견 |
| `candidate-digest-changed` | exact tag digest 불일치 |
| `response-json-invalid` | JSON parse 실패 |
| `response-schema-invalid` | typed envelope schema 실패 |
| `response-semantic-mismatch` | parse/schema 성공 후 hidden expected 불일치 |
| `response-truncated` | `done_reason=length`로 output 종료 |
| `lineage-missing` | request/options/response model hash 계보 불완전 |
| `cpu-offload-detected` | GPU-only gate 실패 |
| `insufficient-vram` | role별 free VRAM gate 실패 |
| `warm-sample-insufficient` | warm 21회 미완료 |
| `portfolio-timeout` | 7,200초 제한 초과 |
| `promotion-approval-required` | recommendation은 있으나 binding 승인 없음 |

Reason code는 case-sensitive, unique, lane-allowlisted이며 아래에 적힌 policy order로만 방출한다. Packet 또는 command에서 `passed=true`이면 reason은 정확히 `["pass"]`다. `passed=false`이면 `pass`가 금지되고, `complete=false`이면 `passed=false`여야 한다. Unknown, duplicate, wrong-case, wrong-lane, secret-shaped, status-inconsistent code는 envelope를 무효화하여 `NEUTRAL`이 `HOLD`하도록 하며 semantic `REJECT`로 세지 않는다.

```text
HARD_GATE:
pass, benchmark-contract-invalid, candidate-digest-changed,
warm-sample-insufficient, response-schema-invalid, response-truncated,
lineage-missing, gpu-lane-evidence-incomplete, cpu-offload-detected,
insufficient-vram

SUPPORT_CONTRACT:
pass, benchmark-contract-invalid, const-forbidden, single-valued-enum,
ontology-narrowed, answer-cardinality-leaked,
request-body-oracle-contamination, candidate-digest-changed, lineage-missing

SUPPORT_SCENARIO:
pass, baseline-evidence-incomplete, warm-sample-insufficient,
response-json-invalid, response-schema-invalid, response-semantic-mismatch,
response-truncated, lineage-missing, cpu-offload-detected, insufficient-vram,
portfolio-timeout, model-block-timeout, model-unload-unproven,
gpu-lane-evidence-incomplete, gpu-release-unproven, ps-evidence-incomplete,
native-probe-timeout, concurrent-model-block-detected

FALSIFY:
pass, benchmark-contract-invalid

COMMAND:
pass, command-evidence-incomplete, candidate-digest-changed,
warm-sample-insufficient, model-unload-unproven, report-privacy-invalid,
secret-scan-failed, role-binding-mutated

NEUTRAL:
all-packets-pass, packet-set-invalid, packet-incomplete,
command-evidence-incomplete, contract-or-counterexample-failed,
scenario-evidence-incomplete, order-unstable
```

위 목록은 scope별 exact policy이지 global union이 아니다. Recommendation-only codes `promotion-approval-required`, `no-eligible-candidate`, `candidate-tie`는 recommendation record에만 허용하고 lane packet, sample, model, top-level reason으로 재사용하지 않는다. `report-privacy-invalid`는 internal COMMAND policy에만 허용하며 public packet/model/sample/recommendation/NEUTRAL에 허용하지 않는다. `order-unstable`은 NEUTRAL final reason과 complete-live top-level copy에만 허용한다.

## 17. TDD와 검증 계약

구현은 다음 RED를 먼저 증명해야 한다.

1. `const`가 있는 schema가 validator를 통과하는 기존 false-green fixture
2. 단일값 enum과 case별 축소 enum fixture
3. oracle sentinel이 request body에 섞이는 fixture
4. shape-only JSON이 parse되지만 semantic mismatch인 fixture
5. extra claim/reason을 정답으로 인정하는 fixture
6. cold sample을 p95에 포함하는 fixture
7. 두 model block을 동시에 실행하는 fixture
8. raw response가 report에 남는 fixture

GREEN은 다음을 증명해야 한다.

- bounded ontology known-good fixture는 parse/schema/semantic을 모두 통과한다.
- oracle expected를 바꿔도 request hash가 동일하다.
- ontology를 바꾸면 schema/request hash가 바뀐다.
- 배열 순서가 달라도 exact set이 같으면 semantic pass다.
- extra value 하나가 있으면 semantic fail다.
- cold 1회와 warm 21회가 통계에서 분리된다.
- nearest-rank p95가 21개 중 20번째 값이다.
- 최대 동시 model request 수가 1이다.
- 실패 후 endpoint unload가 실행되고 다음 model로 무단 진행하지 않는다.
- report에는 hash/count/reason만 있고 raw prompt/response와 secret-shaped 값이 없다.
- recommendation 단계는 환경 변수 write를 0회 수행한다.

계획의 Pester 총계는 정확히 30 `It`을 유지한다. Task 4에는 정확히 다섯 `It`만 두고, 다음 Family A 증명을 기존 다섯 test를 교체·강화하여 수행한다.

1. warm-only nearest-rank aggregation, exact case multiset, cold exclusion
2. direct hard-gate/role-score boundaries, strict Boolean, integer/finite/range/count validation, exact `0.99`, `1536`, `2048` thresholds
3. baseline-relative recommendation, tie retention, strict `hardGatePassed` Boolean
4. bounded preimage의 known Family A run/lane/packet/command hashes, `-ExecutionPolicy Bypass`를 명시한 두 fresh Windows PowerShell child의 byte-identical replay, facts-derived scenario recommendations, derived status/reasons, exact public projection privacy, forward/reverse clean `APPLY`
5. forged format-only hash, old hash 아래 evidence mutation, evidence hash만 갱신, stale run binding, missing/extra/duplicate fact, inconsistent scenario recommendation, null/invalid baseline selector metric, passed candidate missing metric, noncanonical `80.0000004` score rejection, canonical `80.000001` 대 `80.0`의 exact `0.000001` promotion, zero/nonpositive promotion rejection before projection, malformed CommandEvidence exported-wrapper no-throw, unknown/duplicate/wrong-case/wrong-lane/secret-shaped/raw-shaped/status-inconsistent reason, invalid packet/NEUTRAL projector input, verdict 또는 reason의 order disagreement를 모두 fail closed하는 mutation matrix

어떤 test나 완료 문구도 unkeyed hash가 악의적 producer를 인증하거나 command execution을 증명한다고 주장해서는 안 된다.

현재 CurrentUser scope의 Pester 5.5.0을 Windows PowerShell 5.1에서 사용한다. Controller와 두 nested replay child 모두 `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass`를 명시해야 하며, focused suite는 정확히 30개(그중 Task 4는 정확히 5개)를 발견해 30 passed, 0 failed, 0 skipped여야 한다. Pester 3.4 fallback, zero discovery, parse/import failure는 GREEN이 아니다.

## 18. 완료 조건

v2 benchmark 계약은 다음이 모두 현재 evidence로 증명될 때만 구현 완료다.

- request corpus와 oracle이 분리되고 leakage negative test가 모두 PASS
- fast/main 6개 모델 exact digest 재확인
- 모델당 cold 1회, warm 21회 직렬 실행 완료
- 모든 report privacy/secret scan PASS
- baseline/candidate/challenger semantic·warm p95·VRAM 비교 완료
- 모든 `balancedScore`가 canonical six-decimal ToEven domain을 만족하고 selector의 eligibility, main 5-point threshold, ordering/tie, emitted delta가 exact `Int64` microunits로 검증되며 `recommend-candidate` delta가 strictly positive
- six bounded block evidence hashes로 current-run binding을 만든 뒤에만 lane/command evidence 생성
- module-owned constructors가 세 lane status/reasons와 provisional/final CommandEvidence status/reasons를 파생
- NEUTRAL이 run/lane/packet/command hash를 모두 재계산하고 네 envelope의 current-run binding 일치를 증명
- forward/reverse NEUTRAL의 verdict와 reason이 모두 일치하고 public projection에 `finalReasonCode`가 그대로 복사됨
- public packet/NEUTRAL exact property allowlist와 reason-code lane policy가 모두 PASS
- recommendation은 생성되지만 role binding write는 0회
- endpoint별 `/api/ps`가 최종 empty이고 GPU memory가 회수됨
- Browser 실제 응답 증거는 별도 Browser lane에서만 주장

Task 5 adapters/model blocks는 strict Boolean/finite bounded block facts와 module-owned `blockEvidenceSha256` input만 만든다. Evidence packet 또는 public report ownership은 Task 5로 이동하지 않는다. Task 6은 여섯 block이 끝난 후 run binding을 한 번 구성하고, precomputed status/hash가 아니라 bounded facts를 constructors에 전달하며, provisional report privacy 검증 후 final CommandEvidence를 재구성한다. Report `schemaVersion=2`, corpus JSON, oracle JSON은 그대로 유지한다.

하나라도 빠지면 model promotion은 `HOLD`다. schema-v1의 0/18은 진단 evidence로 보존하지만 v2 결과를 대신하지 않으며, bounded-ontology probe 2건의 성공도 전체 행렬 성공으로 확대 해석하지 않는다. 이 설계는 registry/key file/database/Java/endpoint/role-binding/environment/model inventory/raw evidence migration을 승인하지 않는다.
