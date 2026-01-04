# PR 2 — Alias: 9‑Tile Alias Corrector (Overlay, Context‑aware)

**What**  
- 문맥 힌트(예: ‘냄새’→동물, ‘대사’→게임) 기반 9‑Tile 사전에서 별칭 교정  
- 확신도 낮으면 입력 그대로 통과(오버레이 구조)

**How**  
- `TileDictionaries` (seed 사전), `TileAliasCorrector.correct(text)`  
- 통합 시 ConceptResolver **앞단**에서 호출 권장

**Flags**  
- `features.alias.corrector.enabled` (기본 false)

**Tests**  
- “스커크 냄새”→“스컹크 냄새”, “스커크 대사”→“Skirk 대사”  
- 미매칭/다의성: 원문 통과

**Risk**  
- 과교정 가능성 → 오버레이·시드 축소·현장 피드백으로 점진 확대