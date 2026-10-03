# live scripts 성유물 등급표: S=47 / A=353 / B=5 / C=4 / D=0 / X=15 — 엄격 3중증명으로 죽은 스크립트 0건
- card-id: artifact-grades-a02g
- kind: measured-number
- status: still-true (기준선 참조)
- date: 2026-09-28 KST
- evidence: docs/diagnostics/artifact-refine-0928/02_ARTIFACT_GRADES.md (+ 02_ARTIFACT_GRADES.csv, grade_scan.py)
- reverify: `Get-Content docs\diagnostics\artifact-refine-0928\02_ARTIFACT_GRADES.md`

## 근거
- 등급: S 47(py CLI+test+참조 → KEEP+trigger) / A 353(참조 근거 → KEEP) / B 5(얇은 ps1 래퍼 →
  MERGE 후보) / C 4(14일+ stale-doc 언급만 → INDEX only) / **D 0**(3중증명 전부 0) /
  X 15(활성 lease 11 + Clean Kit 4 → OWNER_OTHER, 미접촉).
- D 조건 = p1 instructional/code corpus(10,197 files) + p2 scripts 상호참조(424) +
  p3 최근 14일 ledger/diagnostics(8,452) + p4 14일 이전 stale(3,874) 전부 0.
- 교훈: ledger 생태계가 최근 14일 안에 거의 모든 스크립트 경로를 기록 → 엄격 기준에서 죽은 파일
  없음. 과도 삭제 방지가 안전 방향.
