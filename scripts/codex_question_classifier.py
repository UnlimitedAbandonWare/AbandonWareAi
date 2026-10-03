#!/usr/bin/env python3
"""
codex_question_classifier.py
============================
Codex가 사용자에게 묻기 직전의 "질문 문장"을 분류해 기본 판정을 JSON으로 출력.

  verdict : AUTO | ASK_ONCE | HOLD
  rule    : D1..D20 (기본 답 표) | ask-* 범주 | SELFASK | ask-compound
  default_answer / log_line

SSOT 표: .agents/skills/demo1-codex-auto-decide/SKILL.md
규칙: ASK_ONCE 키워드가 하나라도 있으면 ASK_ONCE가 우선 — 단, 좁은 예외
(D13 인증 예외·D14 스크립트 우선순위·D17 실패 테스트 삭제 금지)는 조건
키워드가 함께 있고 가드 키워드(관리자·전역·역할·CSRF·사용자/시스템
환경변수·레지스트리)가 없을 때만 AUTO로 뒤집는다. ASK_ONCE 범주가
2개 이상 겹치면 질문 1개 원칙 위반이므로 HOLD. 그 외 표 매치는 AUTO,
아무것도 없으면 AUTO + SELFASK(판정 루프 수행 지시) — 기본이 질문이 되지
않게 한다. LLM/네트워크 호출 없음 (한·영 키워드 정규식).

Usage:
  python -B scripts/codex_question_classifier.py --text "질문 문장"
  python -B scripts/codex_question_classifier.py --file question.txt

Exit codes: AUTO=0, ASK_ONCE=3, HOLD=4
"""

import argparse
import json
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# ASK_ONCE 범주 — 이 키워드가 하나라도 있으면 AUTO 표보다 우선한다.
# (스킬의 "ASK_ONCE로 남는 것" 목록과 1:1 대응)
# ---------------------------------------------------------------------------
ASK_ONCE_RULES = [
    ("ask-delete-data",
     r"삭제|지워도|지울|날려도|drop\b|truncate\b|delete\b|제거해도",
     "데이터·데이터셋 삭제"),
    ("ask-schema-change",
     r"스키마.{0,6}(변경|수정|바꿀)|schema.{0,20}(change|alter|migration)|db\s*구조\s*변경",
     "DB 스키마 변경"),
    ("ask-git-remote",
     r"\bpush\b|\bpull\b|\bfetch\b|\bmerge\b|\brebase\b|git\s*(commit|push|add)|커밋|푸시|원격\s*(변경|저장소|반영)",
     "push/commit/원격 변경"),
    ("ask-auth-policy",
     r"인증\s*정책|보안\s*정책|auth\w*\s*policy|security\s*policy|권한\s*(정책|변경|부여)|proto.?open|role\s*gate|접근\s*권한",
     "보안·인증 정책 변경"),
    ("ask-paid-limit",
     r"유료|paid|상한\s*초과|credit|비용\s*초과|과금|요금",
     "유료 호출 상한 초과"),
    ("ask-public-deploy",
     r"배포|deploy\b|cloudflared|tunnel\b|공개\s*(설정|배포|전환)|외부\s*공개",
     "공개 배포·cloudflared"),
    ("ask-flag-default-on",
     r"기본값.{0,8}(true|\bon\b|켜)|default.{0,16}(true|\bon\b)|true로\s*(바꿀|바꾸|변경|설정|할까)|\bon\b으로\s*(바꿀|변경|설정)|켜도\s*(될까|됩니까)|turn.{0,6}on\s+by\s+default",
     "기능 플래그 기본값 ON"),
    ("ask-account-oauth",
     r"oauth|계정\s*(배정|연결|할당|등록)|account\s*(assign|link|provision)|사용자\s*계정",
     "사용자 소유 계정/OAuth 전역 배정"),
    ("ask-env-global",
     r"(사용자|시스템)\s*환경변수|user\s*env(ironment)?\s*var|system\s*env(ironment)?|"
     r"setx\b|레지스트리|registry\s*(key|값|항목|entry)|"
     r"전역\s*설정.{0,10}(변경|수정|바꿀|설정)|global\s*(config|env|setting).{0,16}(change|set|modify)",
     "사용자·시스템 환경변수·전역 설정 변경"),
    ("ask-admin-scope",
     r"관리자.{0,16}(인증|권한|해제|우회|허용|변경|수정|제거)|"
     r"admin.{0,20}(auth|bypass|disable|unlock)|"
     r"역할\s*(라우팅|게이트|권한).{0,10}(변경|추가|수정)|role\s*(routing|gate).{0,16}(change|add)|"
     r"csrf|인증.{0,4}범위.{0,4}(확대|넓히)|proto.?open.{0,10}(변경|수정|on|off|켜|끄)",
     "관리자·전역·역할·CSRF·인증 범위 변경"),
]

# ---------------------------------------------------------------------------
# ASK_ONCE 우선의 좁은 예외 — ask 범주가 1개만 매치됐을 때, 조건 패턴이
# 함께 있고 NARROW_GUARD 키워드가 없으면 AUTO(D13/D14/D17)로 뒤집는다.
# 가드 키워드(관리자·전역·역할·CSRF·사용자/시스템 환경변수·레지스트리)가
# 있으면 예외는 적용되지 않고 ASK_ONCE가 유지된다.
# ---------------------------------------------------------------------------
NARROW_GUARD = (
    r"관리자|admin\b|전역|global\b|역할|role\b|csrf|proto.?open|"
    r"사용자\s*환경변수|시스템\s*환경변수|user\s*env|system\s*env|"
    r"레지스트리|registry|인증.{0,4}범위.{0,4}(확대|넓히)"
)

NARROW_OVERRIDES = [
    # (이 예외를 허용하는 ask 범주, D규칙, 좁은-조건 패턴, default_answer)
    ({"ask-auth-policy"}, "D13",
     r"좁은|narrow|단일|owner별|본인|PATCH만|GET만|POST만|"
     r"하나의.{0,6}(경로|메서드|엔드포인트)|(경로|메서드|endpoint).{0,4}(하나|1개)|"
     r"자기\s*(데이터|설정)",
     "조건부 허용: owner는 서버 쿠키·세션에서만, 경로·메서드 1개, 저장 키 "
     "허용 목록, 테스트 3개(자기 PATCH 성공·타 owner 거부·관리자/전역 응답 동일)"),
    ({"ask-env-global", "ask-flag-default-on"}, "D14",
     r"스크립트|script|런처|launcher|배치|프로세스\s*우선순위|"
     r"우선순위.{0,8}(수정|변경|env|환경)|process.{0,10}priority|"
     r"읽는\s*순서|read.{0,6}order",
     "스크립트만 수정 (프로세스 우선순위·경로·플래그) — 사용자·시스템 "
     "환경변수·레지스트리·전역 설정은 건드리지 않음; lease 겹치면 해당 부분 HOLD"),
    ({"ask-delete-data"}, "D17",
     r"(실패|fail|가드|guard|변이|mutation|통과).{0,20}(테스트|test)|"
     r"(테스트|test).{0,20}(실패|fail|가드|guard|변이|mutation)|"
     r"failing\s*test|test\s*guard|mutation\s*test",
     "테스트 삭제·약화 금지 — 원본 테스트는 그대로 두고 원인을 고치거나 PARTIAL로 기록"),
    # D24/D25: 실측 카드 오탐 — 경로·파일명 속 git 동사, diff 통계 속 '삭제'는
    # 실제 삭제·원격 작업 지시가 아니다 (add-only narrow override).
    ({"ask-git-remote"}, "D24",
     r"[\w-]+(push|pull|commit|merge|rebase|fetch)[\w-]*\."
     r"(md|py|txt|json|ya?ml|java|js|diff)",
     "경로·파일명 속 git 동사는 git 작업 지시가 아님 — 본문 D 표로 AUTO"),
    ({"ask-delete-data"}, "D25",
     r"\bdiff\b|numstat|sha-?256|\d+\s*줄.{0,6}(추가|삭제)|"
     r"(추가|삭제).{0,6}\d+\s*줄",
     "diff 통계 속 '삭제'는 삭제 작업이 아님 — baseline 해석으로 AUTO"),
    # D26~D28: 부정형 언급("X는 하지 않습니다") 속 키워드는 실제 작업 지시가
    # 아니다 — 실측 카드의 면책 문구 오탐 (add-only narrow override).
    ({"ask-git-remote"}, "D26",
     r"(commit|push|커밋|푸시|merge|pull|rebase|fetch).{0,15}"
     r"(하지\s*않|않습|금지|없음|없이|생략|하지\b|skip|without|no\b|not\b)",
     "'~하지 않습니다/없이' 언급 속 git 키워드는 지시가 아님 — 본문 D 표로 AUTO"),
    ({"ask-delete-data"}, "D27",
     r"(삭제|지우|날리|제거|drop\b|truncate\b|delete\b).{0,15}"
     r"(하지\s*않|않습|금지|없음|없이|생략|하지\b|skip|without|no\b|not\b)",
     "'~하지 않습니다/없이' 언급 속 삭제 키워드는 지시가 아님 — 본문 D 표로 AUTO"),
    ({"ask-paid-limit"}, "D28",
     r"(유료|paid|과금|요금|credit).{0,15}"
     r"(하지\s*않|않습|금지|없음|없이|생략|안\b|skip|without|no\b|not\b)",
     "'~하지 않습니다/없이' 언급 속 유료 키워드는 지시가 아님 — 본문 D 표로 AUTO"),
]


def _narrow_override(text, hit_rule):
    """ask_hit 1개일 때 좁은 예외 판정 -> (d_rule, answer) or None."""
    for allowed, d_rule, cond, answer in NARROW_OVERRIDES:
        if hit_rule in allowed and re.search(cond, text, re.IGNORECASE):
            if re.search(NARROW_GUARD, text, re.IGNORECASE):
                return None
            return d_rule, answer
    return None


# ---------------------------------------------------------------------------
# 기본 답 표 D1~D20 — 위에서 아래 순서로 첫 매치를 쓴다.
# D13~D20은 좁은 예외·운영 패턴이라 D1~D12보다 먼저 검사한다
# ("범위 확대" 같은 단어가 D1의 범용 `범위`를 먼저 잡지 않게).
# ---------------------------------------------------------------------------
D_RULES = [
    ("D13",
     r"좁은.{0,8}(인증|auth|예외)|narrow.{0,16}(auth|exception)|"
     r"owner별.{0,16}(patch|get|post|허용|메서드)|"
     r"본인.{0,6}(데이터|설정).{0,10}(엔드포인트|경로|api)|"
     r"단일.{0,6}(경로|엔드포인트|메서드).{0,12}(인증|허용)",
     "조건부 허용: owner는 서버 쿠키·세션에서만, 경로·메서드 1개, 저장 키 "
     "허용 목록, 테스트 3개 — 보고서에 CONFLICT 한 줄"),
    ("D14",
     r"스크립트.{0,24}(우선순위|순서|환경변수|env|경로|플래그|수정|변경)|"
     r"시작\s*스크립트|런처|launcher|프로세스\s*우선순위|"
     r"우선순위.{0,8}(수정|변경|env|환경변수)|process.{0,10}priority|"
     r"읽는\s*순서|read.{0,6}order",
     "스크립트만 수정 — 사용자·시스템 환경변수·레지스트리·전역 설정은 ASK_ONCE; "
     "lease 겹치면 해당 부분 HOLD"),
    ("D15",
     r"첨부.{0,16}(금지|충돌)|금지.{0,8}충돌|금지와.{0,8}충돌|"
     r"attachment.{0,16}(forbid|conflict)|forbidden.{0,12}conflict",
     "되돌릴 수 있고 D13/D14 조건 안이면 진행 + CONFLICT 기록; 비가역이면 ASK_ONCE"),
    ("D16",
     r"전체\s*(검증|테스트|스위트|재검증|재실행)|모든\s*테스트|테스트.{0,4}전부|"
     r"full\s*(test|suite|verification|regression)|entire\s*(test|suite)",
     "회귀·집중 테스트·Verify-RAG만 실행 — 전체 스위트는 NOT_RUN(정책)으로 기록"),
    ("D17",
     r"실패\s*테스트.{0,12}(삭제|약화|지우|제거|재작성|다시)|"
     r"테스트.{0,8}(삭제|약화|지우|제거).{0,16}(실패|가드|변이)|"
     r"테스트\s*가드.{0,8}(실패|막|때문)|가드.{0,6}실패.{0,8}테스트|"
     r"변이|mutation\s*(test|guard)|failing\s*test|test\s*guard|weaken.{0,8}test",
     "테스트 삭제·약화 금지 — 원본 테스트는 그대로 두고 원인을 고치거나 PARTIAL로 기록"),
    ("D18",
     r"관리자.{0,12}(로그인|로그아웃|차단)|차단\s*검사|"
     r"admin.{0,16}(login|logout|block)|로그인.{0,6}로그아웃.{0,6}차단",
     "관리자 로그인·로그아웃 차단 검사는 참고 관찰만 — 완료 조건 아님 "
     "(demo1-codex-plugin-roles)"),
    ("D19",
     r"(원격|remote|github|깃허브).{0,24}(diff|sha|근거|증거|차이|비교)|"
     r"sha.{0,10}(관계|연관).{0,10}(미확인|모르|불명|unknown|모름|안)",
     "원격 SHA 관계 미확인 — GitHub diff를 증거로 쓰지 않고 로컬 증거만 "
     "(demo1-codex-plugin-roles)"),
    ("D20",
     r"ask_?once.{0,8}카드|선택지.{0,8}(권장|추천|표시)|권장.{0,6}선택지|"
     r"recommended.{0,10}option|option.{0,10}recommend|카드.{0,6}(권장|띄우|표시)",
     "ASK_ONCE 카드: 권장 선택지를 1번에 '(권장)' 표시 — 답 대기 중 다른 WP 계속"),
    # D21~D23: F2 실측 카드 중 기존 표에 없던 패턴 (add-only)
    ("D21",
     r"다른\s*(활성\s*)?(codex\s*|코덱스\s*)?(채팅|세션|에이전트).{0,30}메시지|"
     r"메시지.{0,12}(분담|다른\s*(채팅|세션))|채팅\s*간\s*메시지|"
     r"분담\s*메시지|cross.?chat",
     "메시지 없이 이 채팅만 계속; 겹치는 파일은 HOLD "
     "(채팅 간 메시지는 사용자 명시 승인이 필요한 도구라 자동 발송 금지)"),
    ("D22",
     r"합성.{0,8}(프롬프트|생성|호출|질의|계정|데이터)|synthetic|"
     r"\bollama\b.{0,16}(생성|호출|합성|generate|1회)|"
     r"로컬.{0,16}(합성|생성.{0,4}요청|LLM\s*호출)",
     "자기 패치 검증용 로컬 호출 +1 진행 — 지시서당 추가 +2까지, "
     "그 뒤는 묻지 않고 HOLD"),
    ("D23",
     r"오탐|false\s*positive|공유.{0,12}가드|공유.{0,8}체크포인트|"
     r"공용.{0,8}(가드|스캐너|검사|체크)|"
     r"(가드|스캐너|검사기).{0,10}(오인|오탐|차단|막)|비밀값으로\s*오인|"
     r"가드가.{0,40}(차단|막)",
     "회귀 테스트와 함께 스캐너·가드 자체를 수정 "
     "(DEMO1-AGENT-GUARD-COMMON 방향); 다른 세션 lease 겹치면 해당 파일만 HOLD"),
    ("D1",
     r"범위|scope|읽기만|읽기\s*[·/]?\s*확인\s*까지|까지인가|구현\s*[·/]?\s*검증\s*까지|"
     r"just\s+(read|review|inspect)|read\s*(only|ing)?.{0,20}(or|vs|versus)\b|"
     r"(or|vs)\s*(implement|구현)",
     "구현·검증까지 진행 (질문 금지)"),
    ("D2",
     r"diff|sha-?256|다른\s*세션|foreign|baseline|기준선|numstat|양립|"
     r"이미.{0,12}(변경|수정|diff|hunk)|기준.{0,4}충돌",
     "기준선 대비 해석: 이번 세션 변경 0 = 시작·종료 SHA-256 동일, 남의 hunk 보존"),
    ("D3",
     r"라이브\s*트리|live\s*(tree|source)|실제\s*(트리|소스|코드)|"
     r"문서.{0,16}(불일치|다른|달라|어긋)|경로.{0,6}다르|라인.{0,6}다르|"
     r"(stale|outdated)\s*doc|doc.{0,12}mismatch",
     "라이브 트리·심볼 기준"),
    ("D4",
     r"테스트\s*(클래스|폴더|디렉터리|케이스).{0,10}(없|만들|생성)|"
     r"test\s*(class|dir|folder|suite).{0,20}(missing|not\s*exist|creat)|"
     r"create.{0,20}test\s*(class|dir|folder)|sourceSet",
     "실제 sourceSet(src\\test\\java, src\\test\\js)에 새로 만든다"),
    ("D5",
     r"이름을?\s*(뭘|무엇|어떻게|어느)|패키지\s*위치|naming|"
     r"package\s*(location|placement|name)|what\s*should\s*i\s*name|which\s*(name|package)|"
     r"어디에\s*(둘|만들|넣|생성)|어느\s*(폴더|패키지|디렉터리)",
     "인접 기존 관례를 따른다"),
    ("D6",
     r"\b(400|401|403|429)\b|모델\s*미지원|unsupported\s*model|"
     r"api\s*(실패|fail|error|denied)|외부\s*(도구|보조|api).{0,6}(실패|오류)|"
     r"aux.{0,10}fail|glm|provider.{0,12}(fail|error|denied|reject)",
     "원인 기록, 재시도 없음, 보고서 맨 위, 작업 계속"),
    ("D7",
     r"phase.{0,12}(gate|실패|fail|blocked)|게이트.{0,8}실패|일부\s*실패|"
     r"부분\s*실패|partial|gate.{0,8}fail",
     "해당 WP만 PARTIAL/BLOCKED, 의존 없는 다음 WP 진행"),
    ("D8",
     r"(기존|관련\s*없는|무관한).{0,12}테스트.{0,6}실패|"
     r"unrelated\s*test|pre.?existing\s*(test\s*)?(fail|failure)|"
     r"테스트.{0,6}실패.{0,8}(기존|무관)",
     "대상만 1회 재실행, 고치지 않고 기록"),
    ("D9",
     r"서버.{0,6}재(기동|시작)|재기동|재시작|18180|"
     r"restart.{0,16}(server|서버)|(server|서버).{0,16}restart",
     "지시서가 허용하면 진행, 끝나면 local,meta-display·interview OFF 상태로 복구"),
    # D12는 D10보다 먼저 검사 — "어느 쪽"이 지시서-충돌 문장을 가로채지 않게.
    ("D12",
     r"지시(서)?(끼리|가|와)\s*충돌|conflict(ing|s)?\s*(directive|instruction)s?|"
     r"이전.{0,8}지시.{0,8}(최신|새)|older.{0,16}(newer|latest)",
     "최신 사용자 지시 우선, 둘 다 기록"),
    ("D10",
     r"두\s*가지.{0,8}(방식|방법|구현|쪽)|어느\s*쪽|어느\s*(방식|방법)|"
     r"(방식|방법|구현).{0,8}두\s*가지|which\s*(approach|implementation|way|option)|"
     r"a\s*/?\s*b\s*(구현|방식)",
     "변경 줄 수가 적고 기존 동작을 덜 건드리는 쪽"),
    ("D11",
     r"기본값|default\s*(value|값|동작)",
     "새 기능 기본값은 OFF (true로 바꾸는 건 ASK_ONCE)"),
]

SELFASK_ANSWER = (
    "demo1-vibe-selfask-judge-auto 루프 수행 → "
    "판정이 ASK여도 ASK_ONCE 목록에 없으면 가장 보수적인 가역 선택으로 AUTO"
)

EXIT_CODES = {"AUTO": 0, "ASK_ONCE": 3, "HOLD": 4}

# ---------------------------------------------------------------------------
# --options 선택지 자동 선택 (add-only, NO-WAIT) — 카드 옵션 중 기본값을 고른다.
# 순서: 규칙 힌트 → (권장) 표시(가역만) → 점수(진행-보류) → 좁은 쪽.
# 되돌릴 수 없는(OPT_IRREV 매치) 옵션은 어떤 verdict에서도 자동 선택하지 않는다.
# ---------------------------------------------------------------------------
OPT_IRREV = (
    r"삭제|지우|날리|drop\b|truncate|\bpush\b|커밋|푸시|\bcommit\b|delete\b|"
    r"reset\b|전역|유료|과금|배포|cloudflared|원격\s*(반영|저장소)|"
    r"관리자.{0,8}(해제|우회|인증)"
)
OPT_SAFE = (
    r"유지|보류|hold|건너|skip|없이|without|아니|않|그대로|거절|미루|나중|"
    r"defer|기존|현재|관측|보고|중지|정리|\bno\b"
)
OPT_PROCEED = (
    r"허용|승인|진행|계속|실행|추가|수정|재기동|재시작|허가|포함|확장|생성|"
    r"proceed|allow|continue|apply|\byes\b"
)
OPT_MARK = r"권장|recommended"
OPT_NARROW = r"좁은|최소|minimal|narrow|단일|부분|partial|1개만|하나만"

# 규칙별 우선 힌트 — 카드의 (권장) 표시가 규칙의 안전 답과 반대일 때도
# 안전 쪽을 고르게 한다 (예: D21 다른 채팅 메시지 → "메시지 없이 계속").
PICK_HINTS = {
    "D18": ["관측", "결과만", "보고"],
    "D21": ["메시지 없이", "이 채팅만"],
    "D22": ["1회", "합성"],
    "D23": ["승인", "수정", "허용"],
}


def _opt_kind(text):
    return {
        "irrev": bool(re.search(OPT_IRREV, text, re.IGNORECASE)),
        "safe": bool(re.search(OPT_SAFE, text, re.IGNORECASE)),
        "proceed": len(re.findall(OPT_PROCEED, text, re.IGNORECASE)),
        "mark": bool(re.search(OPT_MARK, text, re.IGNORECASE)),
        "narrow": bool(re.search(OPT_NARROW, text, re.IGNORECASE)),
    }


def pick_option(options, verdict="AUTO", rule=None):
    """options(str 리스트) -> (picked_option|None, picked_reason).

    AUTO: 규칙 힌트 → 가역 (권장) → 진행 점수 최고 → 좁은 쪽 → 첫 가역.
    ASK_ONCE/HOLD: 보여줄 안전 기본값만 — 진행 쪽은 절대 고르지 않는다.
    """
    if not options:
        return None, "no-options"
    info = [(str(o), _opt_kind(str(o))) for o in options]
    for hint in PICK_HINTS.get(rule or "", []):
        for text, k in info:
            if hint in text and not k["irrev"]:
                return text, "rule-hint:" + hint
    reversible = [(t, k) for t, k in info if not k["irrev"]]
    if not reversible:
        return None, "all-irreversible"
    safe = [(t, k) for t, k in reversible if k["safe"]]
    if verdict in ("ASK_ONCE", "HOLD"):
        for t, k in safe:
            if k["mark"]:
                return t, "recommended-safe"
        for t, k in safe:
            if k["narrow"]:
                return t, "narrow-safe"
        if safe:
            return safe[0][0], "safe-default"
        return None, "no-safe-option"
    for t, k in reversible:
        if k["mark"]:
            return t, "recommended-reversible"
    best, best_score = None, None
    for t, k in reversible:
        score = (k["proceed"] * 2 - (2 if k["safe"] else 0)
                 + (1 if k["narrow"] else 0))
        if best_score is None or score > best_score:
            best, best_score = t, score
    if best_score <= 0:
        return reversible[0][0], "auto-first-reversible"
    return best, "auto-scored"


def _hits(rules, text):
    return [(rule, answer) for rule, pat, answer in rules
            if re.search(pat, text, re.IGNORECASE)]


def classify(text):
    """질문 문장 -> 판정 dict(verdict, rule, default_answer, log_line)."""
    excerpt = re.sub(r"\s+", " ", (text or "").strip())[:80]
    ask_hits = _hits(ASK_ONCE_RULES, text)
    if len(ask_hits) >= 2:
        cats = ",".join(r for r, _ in ask_hits)
        return {
            "verdict": "HOLD",
            "rule": "ask-compound",
            "default_answer": "질문은 한 번에 하나 — ASK_ONCE 범주 "
                              f"{len(ask_hits)}개({cats})가 한 문장에 섞임",
            "log_line": f"HOLD: ask-compound | {cats} | {excerpt}",
        }
    if len(ask_hits) == 1:
        rule, answer = ask_hits[0]
        override = _narrow_override(text, rule)
        if override:
            d_rule, d_answer = override
            return {
                "verdict": "AUTO",
                "rule": d_rule,
                "default_answer": d_answer,
                "log_line": f"AUTO_DECISION: {d_rule} | {excerpt} → {d_answer} "
                            f"| CONFLICT: {rule} narrow-override | "
                            f"evidence: codex_question_classifier",
            }
        return {
            "verdict": "ASK_ONCE",
            "rule": rule,
            "default_answer": f"{answer} — 사용자 승인 필요 (질문 1개만)",
            "log_line": f"ASK_ONCE: {rule} | {answer} | {excerpt}",
        }
    d_hits = _hits(D_RULES, text)
    if d_hits:
        rule, answer = d_hits[0]
        return {
            "verdict": "AUTO",
            "rule": rule,
            "default_answer": answer,
            "log_line": f"AUTO_DECISION: {rule} | {excerpt} → {answer} "
                        f"| evidence: codex_question_classifier",
        }
    return {
        "verdict": "AUTO",
        "rule": "SELFASK",
        "default_answer": SELFASK_ANSWER,
        "log_line": f"AUTO_DECISION: SELFASK | {excerpt} → self-ask loop "
                    f"then conservative reversible | "
                    f"evidence: codex_question_classifier",
    }


def classify_with_options(text, options):
    """classify + 카드 옵션 자동 선택. picked_option/picked_reason 필드 추가."""
    result = classify(text)
    picked, reason = pick_option(list(options), result["verdict"],
                                 result["rule"])
    result["picked_option"] = picked
    result["picked_reason"] = reason
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="Codex question classifier")
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("--text", help="question text")
    src.add_argument("--file", help="file containing the question (utf-8)")
    parser.add_argument("--options",
                        help="card options joined by | (e.g. \"진행|보류\")")
    args = parser.parse_args(argv)

    text = args.text if args.text is not None else Path(args.file).read_text(
        encoding="utf-8")
    result = classify(text)
    if args.options:
        result = classify_with_options(
            text, [o for o in args.options.split("|") if o.strip()])
    print(json.dumps(result, ensure_ascii=False))
    return EXIT_CODES[result["verdict"]]


if __name__ == "__main__":
    sys.exit(main())
