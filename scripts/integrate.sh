#!/usr/bin/env bash
set -euo pipefail
echo "[Zero Break] 통합 체크리스트:"
echo "1) ops/zerobreak/* 경로를 리소스 또는 런타임 경로로 배치"
echo "2) Spring WebMvc에 RuleBreakInterceptor 등록"
echo "3) 게이트 호출 지점 연결: Preflight→Citation→PII→FinalSigmoid"
echo "4) 프로필 실행: --spring.profiles.active=zerobreak"
echo "5) 내부 점검: GET /internal/zerobreak/plan; POST /internal/zerobreak/dry-run"
