# 배치 실행 체크리스트 (demo1-gdrive-docx-inplace-edit)

본문 SSOT는 `../SKILL.md`. 이 카드는 배치 1건마다 확인하는 실행 칸이다.

## 시작 전 (한 번)

- [ ] 동시 편집자 1명 확인(journal/lease)
- [ ] 원본 링크 '편집하기' 모드로 열림 (보기 전용·로그인 벽 → HOLD)
- [ ] 금지 확인: 사본 / 빈 문서 / 다운로드 없음

## 배치마다

- [ ] 배치 크기: 이미지 ≤5개 또는 섹션 1개
- [ ] 편집 적용 → 저장 표시 멈춤까지 ≥60초 대기
- [ ] 경고 표시 여부 `warnShown` 기록(실패 아님)
- [ ] 새 탭 같은 링크 재열기 → Ctrl+F 표본 문장·삭제 이미지 확인
- [ ] `PERSISTED` / `LOST` / `PARTIAL` 판정 → `BATCHES.jsonl` 한 줄
- [ ] `LOST`면 더 작은 배치로 1회 재시도 / 연속 3배치 `LOST` → HOLD

## 내용 판정마다

- [ ] 기능명 → `main/`에 `rg` → EXISTS / SIMILAR / ABSENT / UNSURE
- [ ] SIMILAR는 교정, ABSENT만 삭제, UNSURE는 남김
- [ ] 출처 없는 수치 삭제, `configs/stack-fit.yaml` story와 모순 없음
- [ ] 개인정보·회사/기간·동작 코드 링크·실제 기능 이미지 보존

## 재접속 후

- [ ] 마지막 `PERSISTED` 배치를 재열기로 재확인 → 다음 배치부터
- [ ] 반영된 편집 재적용 금지

## 마감

- [ ] `REPORT.md`: 반영됨 / 반영 안 됨 / UNSURE + 삭제 수치·이미지 수
- [ ] 저장 경고를 근거로 한 중단·승인 대기 이력 없음
