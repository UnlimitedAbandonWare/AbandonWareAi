type: trait
id: ops_quality_ko
priority: high

지향점: 안정 부트 우선, 기능은 속성 기반 opt‑in.
규범:
- 비밀은 항상 분리: application-secrets.yml + 환경변수/시크릿 매니저.
- 실패는 부드럽게: Health는 UP/WARN, No‑op로 degrade.
- 로그는 사실만: 키/토큰 마스킹, 경로/포트/프로필은 명시.

품질 루프:
- Pass0 요구–응답 매핑표 자동화
- Pass1 후보 3–5안 생성(요약/불릿/자유/프로젝트형)
- Pass2 점수화: 정량일치0.4·적합0.3·가독0.2·차별0.1
- Pass3 합성: 다수결+S 가중, 충돌=안전>프로젝트>전역
- Pass4 검증: 자리수/포트/속성명 검산, 반례 기록

게이팅:
- merge.order: [trait, system] 로 trait 선주입
- profile/속성 기반 조건부 빈 등록
- 폴백: InMemory/No‑op/Placeholder (keystore.p12.disabled)
