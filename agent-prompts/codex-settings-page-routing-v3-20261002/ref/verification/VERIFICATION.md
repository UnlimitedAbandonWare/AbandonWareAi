# 산출물 검증 기록 [초안]

기준: 2026-10-01 제공 입력. 검사 범위는 문서·JSON·근거 참조·파일 포장이다.

## 실제 실행

명령:
`python /mnt/data/settings-uaw-harmony-v3/verification/verify-artifacts.py --package-root /mnt/data/settings-uaw-harmony-v3 --input-dir /mnt/data`

종료 코드: 0. 검사 그룹14개 통과. 상세 출력은 artifact-check.json.

검사 내용: 원본 입력6개 해시 보존, md/txt 사본 일치, 보조 교정 사본 일치, v2 원문 보존, 신규 근거44개와 이전 근거52개의 행 범위·해시, 관련 소스35개 선별 열람 기록, UAW 대조18개, 신규32+이전64=96개 회귀 명세의 중복·참조·NOT_RUN 상태, v2 저장 예시 호환, 미관측null 보존, 다섯 WP, 원본 소스·시크릿·운영 데이터 비포장.

포장 이후에는 같은 명령에 `--zip /mnt/data/FIND_X_settings_uaw_harmony_v3_package.zip`을 추가하여 ZIP CRC·멤버 바이트·SHA256SUMS를 검사한다. 최종 포장 검증 출력은 ZIP 외부의 `FIND_X_settings_uaw_harmony_v3_verification.json`에 남긴다. 자기 자신의 해시를 포함하는 순환 기록은 만들지 않는다.

## 실행하지 않은 범위

Java/Node 애플리케이션 테스트: NOT_RUN.
실제 Gradle compile/test/bootJar: NOT_RUN.
서버 기동·Thymeleaf 렌더링·브라우저 조작·모델 호출·Display 실기기: NOT_RUN.
회귀96개는 구현 담당에게 주는 명세이며, 통과한 테스트96개가 아니다.
이전52개 근거는 해시·행 범위를 대조했으며 모든 의미를 이번에 재판독했다는 뜻이 아니다.

## 원본 보존

FIND_X·UAW·Abandon_X·이전 지시서 원본 변경0. 새 앱 코드 적용0. 실제 외부 모델 호출0. commit/push0.
해시 일치는 무결성 확인이며 코드 안전성·성능·런타임 성공 증명이 아니다.
