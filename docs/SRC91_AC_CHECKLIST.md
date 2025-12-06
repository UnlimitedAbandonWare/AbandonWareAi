# SRC91 수용(AC) 체크리스트

- [ ] 부트 실패 0건(키/프로바이더 미설정 포함)
- [ ] /bootstrap → ownerKey 발급/해석 OK
- [ ] /.well-known/ 경로에서 PKI/ACME 정적 파일 서빙
- [ ] 80/443 듀얼 포트 정상, 프록시 환경 server.forward-headers-strategy=framework
- [ ] RAG/Vector 미설정 시 in‑memory 폴백으로 응답
- [ ] 이미지·OAuth는 명시 enable일 때만 노출/동작
- [ ] 중복 클래스 제거 후 섀도잉/경고 없음
- [ ] Retrofit/OkHttp implementation 승격 적용됨
- [ ] application-secrets.yml 로 비밀 분리(레포 제외)
