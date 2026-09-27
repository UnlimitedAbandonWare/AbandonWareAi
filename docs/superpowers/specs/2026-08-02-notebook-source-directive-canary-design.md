# Notebook Targeted SourceDirective Canary 설계

**Date:** 2026-08-02  
**Decision:** 승인된 접근안 1 — 좁은 저장소 스킬과 결정적 Python 도구  
**Mode:** prompt_skill_tooling_only

## 목적

Notebook이 canonical workspace Y:\에서 명시된 소수의 파일만 읽어 무변경 SourceDirective Canary를 만들고, Desktop이 같은 패킷과 파일 해시를 독립 검증한 뒤 ACK를 생성할 수 있게 한다. 이 경로는 Notebook의 분석·지시서 역할과 Desktop의 Single Writer·최종 검증 역할을 분리한다.

첫 Canary는 의도적으로 소스 변경을 요청하지 않는다. 성공의 의미는 “Desktop이 패킷을 수신하고 해시 및 무변경 계약을 검증했다”이며, 빌드·런타임·provider 계보 성공을 의미하지 않는다.

## 범위

생성 대상은 다음 네 자산군으로 제한한다.

- .agents/skills/demo1-notebook-targeted-directive-canary/: 판단·절차를 담는 repo-local 스킬
- scripts/awx_notebook_source_directive_canary.py: 패킷 생성·검증을 수행하는 Python 표준 라이브러리 도구
- scripts/test_awx_notebook_source_directive_canary.py: 안전 경계와 결정성을 검증하는 단위 테스트
- data/agent-handoff/notebook/source-directive-canary-v1/: 최초 무변경 Canary 패킷

애플리케이션 소스, Gradle 설정, Git trust, 브랜치, 인덱스, PatchDrop, 환경 변수, credential은 수정하지 않는다. 빌드와 최종 ACK 생성도 Notebook 범위가 아니다.

## 기존 자산 재사용

- Y-drive 권한 판정 전에 scripts/verify_ydrive_backing_identity.ps1를 실행한다. 공개 결과는 canonicalWorkspace, backingShareIdentityVerified, backingShareIdentityReason 세 필드뿐이다.
- 목표·지시서의 3역할 판정과 출력 계약은 notebook-goal-directive-generator를 따른다.
- 일반 SMB 읽기 경계는 notebook-smb-network-workspace를 따른다.
- 새 스킬은 위 정책을 복제하지 않고 Canary 생성·검증의 좁은 절차만 연결한다.

## 경계와 불변식

1. 입력은 최대 16개의 명시적 repo-relative 파일이어야 하며 총 읽기 크기는 8 MiB 이하다.
2. 디렉터리, glob 문자, 빈 경로, 절대 경로, UNC, .., reparse/symlink 경유, 활성 source root 밖의 경로를 거부한다.
3. 허용된 활성 root는 main/java, main/resources, app/src/main/java_clean, app/src/main/resources뿐이다.
4. 도구는 application/source 경로에서 디렉터리 열거·glob·재귀 탐색 API를 호출하지 않고, 전달된 파일만 직접 열어 SHA-256과 크기를 계산한다.
5. Canary의 authorizedMutation=false, sourceWriteRoot=null, targetFiles=[], desktopFinalProof=evidence_needed는 고정값이다.
6. 실행 예산은 30초이며 제한을 넘거나 중간 검증이 실패하면 게시하지 않는다.
7. raw backing path, UNC-resolved Git root, secret 값은 출력·저장하지 않는다.

## 구성과 데이터 흐름

    Notebook identity proof
            |
            v
    explicit relative files -> prepare -> temporary sibling directory
                                          | hash + schema + secret checks
                                          v
                                  atomic file replacement
                                          |
                                      ready (last)
                                          v
    Desktop validate -> current preimage comparison -> Desktop-owned ACK

prepare는 최종 출력 디렉터리가 이미 있으면 덮어쓰지 않고 실패한다. 모든 payload를 같은 부모의 실행별 임시 디렉터리에 기록하고 flush/fsync, 재읽기 해시 검증을 마친다. ready를 staging 안에서 마지막 파일로 기록한 다음, Windows pinned directory chain 아래에서 NtSetInformationFile의 handle-relative/no-replace rename으로 ready를 포함한 디렉터리 전체를 최종 이름에 한 번만 게시한다. 이 도구의 publication 지원 플랫폼은 현재 운용 환경인 Windows다. 비-Windows는 안전하지 않은 pathname fallback을 사용하지 않고 bundle-publication-nonatomic으로 fail-closed한다. 각 file bytes의 flush와 rename 완료 응답은 검증하지만, remote SMB server의 전원 장애 후 directory-metadata durability는 성공으로 주장하지 않고 Desktop 외부 증명 범위에 남긴다. 이동 전 실패는 실행별 임시 디렉터리만 제거한다.

validate는 패킷 파일 집합, manifest 해시, 고정 무변경 필드, 상대 경로 정책, 현재 파일 preimage를 재검증한다. 정확한 packet file set 확인을 위해서만 명시된 packet directory의 직계 항목을 최대 6개까지 한 번 비재귀 열거할 수 있다. application/source directory는 열거하지 않는다. 공유 도구에는 ACK 생성·수정 명령이 전혀 없다. Desktop은 validate 결과를 증거로 별도의 Desktop-owned consumer에서 packet 밖의 ACK를 생성해야 하며, 그 전까지 template의 상태는 evidence_needed다.

## 패킷 계약

출력 디렉터리는 정확히 다음 기본 파일을 가진다.

- source-directive.json: GoalContract 요약, SourceDirective, inspectionTargets[path,size,sha256]
- source-directive.sha256.txt: source-directive.json bytes의 소문자 SHA-256
- desktop-ack.template.json: sourceDirectiveSha256을 참조하지만 status는 evidence_needed, 수신 시각은 null
- manifest.json: 사용자 지정 bundle ID, schema version, 고정 역할/권한 값, 위 세 payload 각각의 byte hash
- ready: manifest.json bytes의 SHA-256을 담는 staging의 마지막 파일

해시 도메인은 비순환이다. source-directive hash가 sidecar와 ACK template에 들어가고, manifest가 directive·sidecar·template을 해시하며, ready가 manifest만 해시한다. bundle ID는 directive ID에서 가져오며 hash로 유도하지 않는다. 향후 Desktop ACK는 packet directory 밖에 생성되고 manifest 대상이 아니므로 sealed packet을 변경하지 않는다.

JSON은 UTF-8, 정렬된 키, LF 종료의 결정적 직렬화를 사용한다. CLI 표준 출력도 경로 원문 대신 canonical workspace, 상대 파일 수, byte 수, 해시, reason code만 반환한다.

## 실패 분류

- broad-scan-forbidden: 디렉터리 또는 glob 입력
- target-not-explicit: 빈 경로, 절대/UNC, traversal
- wrong-sourceset: 허용된 활성 root 밖
- reparse-traversal-risk: symlink/junction/reparse 경유
- smb-root-identity-changed: identity가 true/match가 아님
- input-budget-exceeded: 파일 수, 총 byte, 시간 제한 초과
- packet-hash-mismatch: payload, sidecar, manifest, ready 불일치
- changed-preimage: Desktop 검증 시 명시 파일의 현재 해시가 다름
- secret-leak-risk: 생성 JSON에서 민감 패턴 발견
- desktop-proof-missing: Desktop ACK가 아직 없음
- output-exists 또는 bundle-publication-nonatomic: 안전한 새 게시를 보장할 수 없음

모든 실패는 fail-closed이며 부분 최종 디렉터리를 남기지 않는다. 임시 디렉터리는 해당 실행이 만든 경로만 제거한다.

## TDD 전략

스킬은 생성 전에 fresh agent 압박 시나리오로 baseline을 기록한다. 시나리오는 빠른 네트워크·마감·관리자 요청을 이유로 directory scan, source write, Git trust 변경, Notebook 성공 ACK를 선택하는지 검사한다. 새 스킬은 실제로 관찰된 실패와 합리화만 교정한다.

Python 도구는 구현 전에 다음 실패 테스트를 먼저 실행한다.

- 디렉터리, glob, traversal, 절대 경로, wrong sourceSet 거부
- 16개 초과와 8 MiB 초과 거부
- identity mismatch 거부
- reparse 경유 거부
- secret 패턴 거부
- 무변경 필드와 결정적 해시 생성
- tamper 및 changed preimage 검출
- ready-last 구조와 기존 출력 디렉터리 덮어쓰기 거부
- source 경로의 glob·walk·directory enumeration API 미호출과 선언 파일 외 open 부재
- application/source write 부재와 공유 도구의 ACK write interface 부재
- 주입된 monotonic clock으로 30초 cutoff 검증
- payload·ready·rename 각 게시 단계의 fault injection과 최종 부분 디렉터리 부재
- packet directory의 단일 비재귀·6개 제한 exact-set 검사

구현 후 같은 테스트를 통과시키고, fresh agent가 새 스킬을 사용한 동일 압박 시나리오를 다시 수행한다.

## 수용 기준

- 모든 목표 패킷 계약 검증과 Python 단위 테스트가 통과한다.
- 새 스킬이 quick validation과 repo skill-family 검증을 통과한다.
- 최초 Canary가 하나의 명시 파일만 읽어 생성되고 Notebook validate를 통과한다.
- 애플리케이션 source write가 0이며 Git trust·build·runtime 작업도 0이다.
- Desktop ACK는 생성하지 않고 desktopFinalProof=evidence_needed, runtimeLineageVerdict=HOLD를 유지한다.

## 롤백

새 스킬 디렉터리, Python 도구·테스트, 설계·계획 문서, Canary 출력 디렉터리만 제거하면 된다. 기존 스킬, 프롬프트, source, Git 설정에는 복구할 변경이 없다.
