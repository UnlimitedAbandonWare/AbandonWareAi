# PR 3 — MPC: Mirror‑Transform Perfect Cube Preprocessor (No‑op Hook)

**What**  
- 3D/Voxel 전처리 확장 인터페이스 + No‑op 기본 구현

**How**  
- `MpcPreprocessor#normalizeVoxel(Object)`  
- 의도 감지 및 KG/Blob 인입 직후에만 호출 (기본 No‑op)

**Flags**  
- `features.mpc.enabled` (기본 false)

**Tests**  
- Null/임의 Blob 입력 통과, vCPU/TPS 영향 無

**Risk**  
- 후속 실제 전처리 도입 시 네이티브 의존 가능 → 별도 PR로 관리