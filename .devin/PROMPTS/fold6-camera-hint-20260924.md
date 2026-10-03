# Devin paste — Fold6 폴드 카메라 사진 힌트 (2026-09-24)

`@objective-executor @demo1-devin-source-orchestrator`

작업 루트는 `<repo>` (정본, 다른 워크트리·복사본 아님). 애플리케이션 소스만 고친다.

0. 먼저 점검만 한다: `python -B scripts/agent_preflight.py --root .` 와 `python -B scripts/work_journal.py list --active` 결과 JSON을 그대로 붙인다(요약 금지).
1. `agent-prompts/devin-20260924-fold6-camera/setup_audit_20260924.md` 를 읽는다(현재 리스·파일 해시·금지 항목·전달물 상태).
2. `python -B scripts/devin_task_orchestrate.py plan --brief-file agent-prompts/devin-20260924-fold6-camera/brief.txt` — Downloads 원문으로 plan 하지 않는다. plan의 `write`는 플레이북 일반 목록이므로 브리프의 대상 파일로 좁힌다.
3. 타깃 리스: `nova-oneshot-camera`(만료 ≈09:22Z)와 `chat-release-admin-fix-devin`(만료 ≈09:47Z, `chatworkflow.java` 포함)이 살아 있다. **live 리스는 절대 빼앗지 않는다.** 겹치는 파일만 보류하고 나머지를 진행한 뒤 보류 목록을 한 줄로 보고한다. 내 촬영/포커스 소스용 리스는 내 taskId로 새로 `begin` 한다(그 리스의 기록 preimage는 현재 바이트와 달라 그대로 apply 하면 거부된다).
4. 실패 테스트를 먼저 고정한다(기존 확장, 새 하네스 신설 금지): `src/test/js/display-snapshot.test.cjs`, `NovaFocusStateTest`, `NovaFocusSnapshotStateTest` 등.
5. 라이브 반영 증명은 `[DEV-RELOAD] socket ready` 또는 ForceRestart 결과로만 주장한다. 구 PID 재시작·`-CheckOnly`·옛 프로세스 HTTP 200은 증거가 아니다.
6. 폴드 실기기 촬영·안경 렌즈 표시는 별도 등급이다. 실행 못 했으면 `NOT_RUN`으로 남긴다.
7. 금지: 새 카메라 시스템/새 업로드 서버/새 AI 프록시/`visionEnabled` 중복 플래그, 전역 harmony ENFORCE, 인증·예산 가드 완화, 사진 base64 로그·저장, 마이크/ASR 중단, 유료 모델·무단 pull, `META_GLASSES` 저장값의 조용한 폴드 치환.
8. 구버전 주의: 14:51/14:59 `Devin_Nova_OneShot_Camera_*` 는 쓰지 않는다. `m132sain(1).zip` 은 없다(실제 `m132sain.zip`). 지시서가 시키는 `repro/snapshot_observations.cjs`·`probe_state.py` 는 이 PC에 없다.

목적:
