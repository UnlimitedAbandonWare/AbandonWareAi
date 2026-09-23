# Meta Ray-Ban Display 설정 화면 설계 브리프

작성일: 2026-09-19 · 상태: 설정 연동으로 갱신(표시 시간 + 생성 주기; YAML 숫자는 공장 기본) · 용도: Fold 설정 설계 배경

이 문서는 **Fold6 개발자 설정 화면**을 설계할 때 필요한 모든 실측값·계약·제약을 정리한다.
대상 독자는 이 코드베이스를 모르는 외부 AI(또는 개발자)이므로, 파일 경로·필드명·수치를
그대로 인용 가능한 형태로 적는다.

---

## 1. 안경 디스플레이 원리 (OFFICIAL vs FIELD_TESTED 구분)

### OFFICIAL (Meta 공식 사양)
- 600×600px **additive(가산) 디스플레이** — 검정은 투명(비발광), 글자·도형만 빛으로 "더해져" 보임.
  밝기/배경 개념이 없다 → 가독성은 **글자 크기·굵기·색·줄간**으로만 결정된다.
- 입력은 D-pad + Enter 포커스 이동뿐. 터치·키보드 없음 → **렌즈 화면 자체에 조작 UI를 둘 수 없다**.
- 공식 스크롤/컴포저 기능은 존재하나 이 프로젝트의 렌즈는 read-only로 유지한다.

### FIELD_TESTED (이 프로젝트의 실측 기본값)
- 렌즈 페이지는 600×600 고정 뷰포트. 실제 텍스트 영역: 가로 ~560px, 세로 ~552px
  (body padding 8px + stage padding 10/12/18px + border 1px + row gap 10px 차감).
- 폰트 "Malgun Gothic" 기본 26px, line-height 1.25 → **줄 하나 ≈ 32.5px**.
- 이론상 최대 ~17줄이지만 실측 권장 **공유 줄 예산 13줄**(전사+힌트 합산, 시인성 여유 포함).
- 26px 한글은 한 줄에 **약 18자**(영문·숫자는 더 들어감 — 가변폭이므로 항상 실측 기준).
- 레이아웃 고정: 위=힌트(auto), 중간=전사(남은 공간, 아래쪽 정렬), 아래=상태 1줄.
- 색 고정: 힌트 `#fff` 굵기 700 / 전사 `#a8adb3` 회색 500 / 상태 `#8ab4f8` 파랑 16px.
- 스크롤 없음. 길면 **페이지 분할**(스크롤이 아니라 전체가 `힌트 n/m` 페이지로 교체).

### 렌즈 계약 요약
- 힌트 수명: **최초 표시부터 TTL**(공장 기본 20초, Fold 안경 표시 설정으로 바뀌고 **저장값을 기억**). 재폴링·전사 갱신·페이지 이동은 연장하지 않음.
  만료된 힌트는 재수신돼도 재표시되지 않음. 페이지들은 같은 수명을 공유.
- 자동 넘김 공장 기본 5초 — **안경에 입력 수단이 없어 유일한 페이지 전환 경로**. 간격은 설정값 그대로(마지막 페이지에 맞춘 자동 단축 금지).
- 힌트 **생성 주기**도 같은 메뉴의 저장값을 따른다. YAML `display-ttl-ms` 20s / `trigger-quiet-ms` 2.5s / `cooldown-ms` 10s / `force-after-ms` 180s 는 공장 기본일 뿐, 저장 후와 재접속 후 **실제 트리거 주기가 바뀌어야** 한다.
- 렌즈는 서버를 ~1초 간격으로 폴링(`POST /api/assist/display/lens/text`).

---

## 2. 현재 이미 구현·검증된 조절 항목 (설정 화면이 "만드는" 게 아니라 "연결할" 대상)

서버 `LensDisplayPrefs` 7개 필드 — 전부 **서버 검증 + 라이브 안경 도달 확인됨**:

| 필드 | 범위 | 기본 | 조정하면 생기는 일 |
|---|---|---|---|
| `transcriptFontPx` | 20–36 px | 26 | 전사 글자 크기. 크면 전사 각 줄이 더 높아져 같은 줄 수에서 더 많은 높이 차지 |
| `hintFontPx` | 20–36 px | 26 | 힌트 글자 크기(굵기 700 고정). 키우면 페이지당 줄 수 상한이 실측으로 자동 축소됨 |
| `transcriptMaxLines` | 1–8 줄 | 4 | 전사 최대 표시 줄. 힌트와 공유 예산(13줄) 안에서 자동 상한 조정됨(힌트 옆 최소 3줄 보장) |
| `hintPageLines` | 4–13 줄 | **11** | 페이지당 힌트 줄 상한. 크면 페이지 수↓, 작으면 페이지 수↑ |
| `hintTtlMs` | 설정(공장 20,000 ms) | 20,000 | 힌트 표시 수명 **및** `hintHoldUntil` 생성 hold. 저장값을 기억하고 라이브 주기에 반영 |
| `autoPageMs` | 설정(0=끄기, 공장 5,000 ms) | **5,000** | 자동 넘김 간격. 예전 2,000 ms 하한은 숨은 고정이 아니라 설정으로 연결 |
| `hintTargetChars` | 240–1,100 자 | 1,000 | 힌트 생성 목표 길이(모델 프롬프트에 전달). 표시 상한과 별개 — 길면 페이지 수만 증가 |

### 값의 상호작용 (설계 시 가장 중요)
- **글자 크기 ↔ 줄 수**: px가 커지면 같은 `hintPageLines`라도 실측 높이(`clientHeight`)가 페이지를 더 작게 자른다.
  즉 사용자가 글자를 키우면 자동으로 페이지가 쪼개짐 — 충돌이 아니라 실측 보정.
- **`hintTargetChars` ↔ `hintTtlMs`/`autoPageMs`**: 생성 길이를 늘리면 페이지 수가 늘어난다. 간격과 수명은 **서로 독립** — TTL보다 간격이 길면 다음 페이지 전에 힌트가 사라져도 값을 당기지 않는다. 필요하면 설정 화면에 관계 안내만.
- **`transcriptMaxLines` ↔ `hintPageLines`**: 합산이 ~13줄 예산을 넘으면 전사가 최소 3줄까지 눌린다.
- **생성 주기 필드** (`trigger-quiet-ms`, `cooldown-ms`, `force-after-ms`, `display-ttl-ms`/`hintHoldUntil`): Fold 안경 표시 설정에 연결하고 저장·복원한다. 공장 기본은 2.5s / 10s / 180s / 20s. 입력 범위는 **현재 기본값을 포함**해야 한다(저장된 180s force를 100s로 조용히 자르지 말 것). 저장 후 실제 rolling/force 트리거가 바뀌어야 한다.

### 현재 UI 상태 (이미 존재)
Fold6 `display/index.html`에 `<details id="lens-display">안경 표시 설정</details>` 블록이 이미 있음:
- 입력 id: `ld-cap-font`, `ld-hint-font`, `ld-cap-lines`, `ld-hint-lines`, `ld-hint-ttl`, `ld-auto-page`, `ld-hint-chars`
- 버튼: `ld-apply`(안경 표시 적용), `ld-reset`(기본값 복원), 상태 표시 `ld-status`
- 동작: 적용 → 서버 검증·저장 → 다음 렌즈 폴링부터 안경에 적용. READY 시 저장값 재적용.
- `localStorage`는 Fold6 측 저장소 — **저장 성공 ≠ 안경 적용**. 안경 적용은 서버 `display` 필드가 렌즈에 도달해야 성립.

### 전송 계약 (실제 JSON)
```
POST /api/assist/display/relay/lens-settings   (X-Display-Client:1 + same-origin)
{clientId, assistId, epoch, display:{<부분필드>}, restoreDefaults?:true}
→ 200: View 응답(standalone이면 testStatus.lensDisplay에 적용값 에코)
→ 400: {"reason":"invalid_lens_settings:<필드명>"}

POST /api/assist/display/lens/text  (렌즈가 ~1초 폴링)
→ {conversation, hint, hintId, hintExpiresAt,
   display:{transcriptFontPx,hintFontPx,transcriptMaxLines,hintPageLines,
            hintTtlMs,autoPageMs,hintTargetChars}}
```

---

## 3. 조절 불가능한 것 (설정 화면에 넣으면 안 되는 항목)

| 항목 | 현재 값 | 이유/위치 |
|---|---|---|
| 전사 대화 길이 | 280자 | 서버 `lens-conversation-chars` — 표시가 아니라 맥락 전송량 |
| 힌트 하드 상한 | 1,180자 / 24줄 | 서버 검증 상수 `HINT_TEXT_MAX/HINT_LINE_MAX` — 넘으면 카드 자체가 거부됨 |
| 줄간·폰트·색 | 1.25 / Malgun Gothic / 흰·회색·파랑 | CSS 고정 — additive 디스플레이 가독성 기준 |
| 공유 줄 예산 | 13줄 | `LENS_LINE_BUDGET` 상수 — 실측 기준 안전값 |
| 전사 최소 줄 | 3줄 | `MIN_CAPTION_LINES` — 힌트와 공존 시 하한 |
| 레이아웃 구조 | 힌트 위 / 전사 아래 / 상태 최하단 | 그리드 고정 |
| 배경/테두리 | 검정 + 흰색 1px | additive 고정 |
| 수명 연장 규칙 | 재수신·전사 갱신·페이지 이동으로 연장 불가 | 계약 — 끄기/켜기 대상 아님. **초 단위 시간값 자체는 설정·저장·복원** |

### 새 조절 항목을 추가하려면 (체크리스트 — 모두 해야 안경에 도달)
1. `LensDisplayPrefs`: 필드+범위 상수+`defaults`+`patch`+`describe`+`Patch` 레코드
2. `receiver.js`: `DISPLAY_DEFAULTS`+`normDisplay`+사용 지점
3. `meta/index.html`: CSS 변수 연결 + `?v=` 캐시 버전 상향
4. `display/index.html`: input 추가 + `app.js` `readLensPatch`/`fillLensInputs`/`ld-status` 반영
5. 테스트: JS `normDisplay`/경계 + `DisplayConversateHttpTest` 라운드트립
6. 문서: `SKILL.md` + windsurf 룰 + `META_DISPLAY_CUE_LLM_SPEC.md`. AGENTS.md DEMO1-META-RAYBAN-DISPLAY-RUNTIME이 옛 “생성 주기 고정” 문장보다 이긴다.

---

## 4. GPT Pro에게 물어볼 설계 질문 (배경 포함)

### Q1. 입력 컨트롤 형태
안경을 쓴 채 Fold6 화면을 보고 조절하는 상황 → number input(현재)은 미세 조작에 불편.
큰 stepper/slider가 나은지, 프리셋 버튼(예: "읽기 편함"=글자 30px·줄 8 / "정보 많음"=22px·줄 13 / "기본")이 나은지.
- 제약: px 범위 20–36, 줄 4–13, TTL 5–120초, 자동 넘김 0 또는 2–30초.

### Q2. 미리보기
폰 화면에 600×600 렌즈 미리보기(실제 CSS 그대로, 스케일 다운)를 두고
"이 조합이면 힌트 몇 페이지·페이지당 몇 초"를 계산해 보여주는 방식이 실용적인지.
- 필요한 데이터: 폰트 px → 줄높이(1.25em) → 페이지당 예상 줄 수 → hintTargetChars로 페이지 수 추정 → TTL 대비 읽기 시간.

### Q3. 읽기 시간 경고
`hintTargetChars`·`hintPageLines`·`hintTtlMs` 조합이 "페이지당 최소 ~3초"를 밑돌 때
경고(또는 자동 상호 보정 제안)를 넣는 게 좋은지. 예: 1,100자·9줄·TTL 10초 → 6페이지 → 1.6초/페이지 = 읽기 불가.

### Q4. 적용 시점 UX
현재: 적용 즉시 서버 저장 → 다음 렌즈 폴링(~1초)부터 반영. 이미 표시 중인 힌트는 그대로.
"지금 보이는 힌트에 즉시 적용"이 필요한지, 아니면 다음 힌트부터가 더 자연스러운지.

### Q5. 저장/복원 UX
서버는 메모리 per-owner(재시작 시 기본값), Fold6는 localStorage+READY 재적용.
"이 조합을 내 기본값으로" 개념이 필요한지, 아니면 매 세션 튜닝만으로 충분한지.

### Q6. 위험 조합의 안내
`autoPageMs=0`(끄기)는 안경에서 페이지 2 이상을 볼 수 없음 → UI가 "끄면 뒷페이지 수동 이동 불가(안경 키 없음)"를
명시해야 하는지, 아예 0을 숨기고 최소 2초만 허용할지(서버는 0 허용 — 개발자 옵트아웃용).

### Q7. 진단 정보 노출
`ld-status`에 현재 적용값(서버 에코)을 보여주는 것 외에, 요청값≠적용값일 때
"서버가 범위로 보정함" 표시를 둘지. (서버는 범위 밖을 400으로 거부하므로 보정 케이스는 없지만,
클램프된 값은 `testStatus.lensDisplay`에 실제 적용값으로 옴.)

---

## 5. 참고 파일 (검증된 현재 상태)

| 역할 | 경로 |
|---|---|
| 설정 모델·검증·기본값 | `main/java/com/example/lms/assist/LensDisplayPrefs.java` |
| 설정 엔드포인트·lens/text 응답 | `main/java/com/example/lms/assist/DisplayConversateController.java` (`relay/lens-settings`, `lens/text`) |
| 소유·수명 단일 소스 | `main/java/com/example/lms/assist/ConversateSessionService.java` (`displayTtl`, `hintTargetChars`, `HINT_TEXT_MAX`) |
| 렌즈 렌더러(페이지 분할·TTL·자동 넘김·페이싱) | `main/resources/static/assets/display/meta/receiver.js` |
| 렌즈 레이아웃·CSS 변수 | `main/resources/static/assets/display/meta/index.html` (`?v=lens-band-13`) |
| Fold6 설정 UI | `main/resources/static/assets/display/index.html` (`#lens-display`) + `app.js` |
| 생성 목표 기본값 | `main/resources/application-meta-display.yml` (`conversate.cue.hint-target-chars:1000`, `max-output-tokens:1536`) |
| 계약 문서 | `docs/META_DISPLAY_CUE_LLM_SPEC.md`, `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` |

---

## 6. 미검증·주의 사항 (GPT Pro에게도 명시할 것)

- **실기 광학 검증 미완료**: 11줄·26px·20초 조합이 실제 렌즈에서 읽히는지는 착용 확인 필요.
  브라우저 600×600 렌더링과 안경의 실제 가독성은 다를 수 있음.
- **힌트 생성량 vs 표시량은 별개**: `hintTargetChars`를 늘려도 `HINT_TEXT_MAX`(1,180) 이상은 거부되고,
  표시는 페이지 수로만 흡수됨. "더 길게"가 "더 잘 읽힘"을 보장하지 않음.
- **알려진 별개 문제**: 전사 연결 대기 반복 / 후반 발화 미반영(미진단, 표시 계층 아님),
  힌트 내용 품질(반복적 안내 — 프롬프트 영역, 표시 설정과 무관).
