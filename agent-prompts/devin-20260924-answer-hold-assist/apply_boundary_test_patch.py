# -*- coding: utf-8 -*-
"""ChatHarmonyFinalVerificationReleaseBoundaryTest.java 디스크 수술.

applyEvidenceReleasePolicy 가 4인자 계약이 되면서 리플렉션 시그니처를 갱신한다.
priorFallback 단독 release-lock 제거 후 동일 시나리오는 evidence-state 홀드로 표현된다:
METADATA_INCOMPLETE + evidenceReleaseRequired → 홀드 본문이 harmony shaping 을
그대로 통과하는지(바이트 보존) 검증하고, 지식 기록 차단도 함께 확인한다.
"""
import sys

PATH = r"src/test/java/com/example/lms/api/ChatHarmonyFinalVerificationReleaseBoundaryTest.java"
raw = open(PATH, "rb").read()
text = raw.decode("utf-8").replace("\r\n", "\n")


def rep(old, new, count=1):
    global text
    n = text.count(old)
    if n != count:
        print(f"FAIL anchor ({n} != {count}): {old[:90]!r}")
        sys.exit(1)
    text = text.replace(old, new, count)
    print(f"ok ({n}) {old[:70]!r}")


rep(
    """        Method compose = ChatWorkflow.class.getDeclaredMethod(
                "applyEvidenceReleasePolicy",
                decisionType,
                evidenceStateType,
                boolean.class,
                boolean.class,
                boolean.class);
        compose.setAccessible(true);
        Object locked = compose.invoke(null, base, incomplete, true, true, false);""",
    """        Method compose = ChatWorkflow.class.getDeclaredMethod(
                "applyEvidenceReleasePolicy",
                decisionType,
                evidenceStateType,
                boolean.class,
                boolean.class);
        compose.setAccessible(true);
        Object locked = compose.invoke(null, base, incomplete, true, false);""")

rep(
    """        assertEquals("safe fallback", visible);
        assertFalse((boolean) recordValue(locked, "releaseAllowed"));""",
    """        assertEquals(recordValue(locked, "content"), visible);
        assertFalse((boolean) recordValue(locked, "releaseAllowed"));
        assertFalse((boolean) recordValue(locked, "knowledgeWriteAllowed"));""")

open(PATH, "wb").write(text.encode("utf-8"))
print("WROTE", PATH, len(text))
