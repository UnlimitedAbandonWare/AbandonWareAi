# 설정·라우팅 UAW 조화 v3 전달 묶음 [초안]

목적은 모델 역할 설정을 현재 검색·정제·문맥·실행 관측에 연결하는 것이다. 앱 코드가 적용된 패치 ZIP이 아니다.

## 읽는 순서

1. `FIND_X_settings_uaw_harmony_v3_ko.md`: 통합 수정 기준과 WP1~WP5.
2. `reference/FIND_X_settings_routing_v2_ko.md`: 여섯 역할·영속 정책·대체 경계의 기존 상세 계약.
3. `Abandon_X_settings_routing_v3_addendum.txt`: 원본 문서를 덮어쓰지 않는 보조 교정 제안.
4. `contracts/`: UAW 대조18개, 신규 테스트 명세32개, 합성 읽기 응답 예시.
5. `evidence/`, `verification/`: 현재 소스 근거44개·입력 해시·선별 열람 목록·산출물 검증.

v3 WP는 v2 WP를 보강한 동일한 다섯 작업이다. 별도 열 작업으로 반복하지 않는다.
실제 checkout에 적용된 기능은 현재 diff·테스트로 확인해 재작성하지 않는다.
v1 페이지·전체 코드가 아직 적용되지 않았다면 이전 대화의 `FIND_X_settings_draft_package.zip`을 함께 참고한다. 이 묶음은 v1 코드를 재작성하거나 재검증한 것이 아니다.

## 중요한 구분

- 원본 FIND_X·UAW·Abandon_X·사용자 데이터·시크릿·운영 로그는 포함하지 않는다.
- source-anchors의 해시는 무결성 근거이며 안전성·동작 보증이 아니다.
- v2 테스트64개 + v3 테스트32개 = 회귀 명세96개, 모두 NOT_RUN.
- 실행된 검사는 산출물의 참조·해시·JSON·포장 정합성 검사다. 앱 실행은 검증하지 않았다.
- 신규 기능 off, 추가 유료 false, 새 추가 호출/비용 상한0. 기존 전역 설정의 기본값은 바꾸지 않는다.
- chat.js·DB 스키마·보안·기존 API 계약·스튜디오·원본 문서 수정0. 커밋·push·실호출 금지.

구현 전에 실제 빌드 루트·sourceSet·HEAD·현재 INV/DONE/HOLD·API_ROUTING_SPEC §6을 확인한다.
