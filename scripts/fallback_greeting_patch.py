# -*- coding: utf-8 -*-
"""NoEvidenceChatFallback: isGreeting null-safe + isCasualGreetingOnly 추가."""
import sys

PATH = r"main/java/com/example/lms/service/NoEvidenceChatFallback.java"
text = open(PATH, "rb").read().decode("utf-8").replace("\r\n", "\n")

def rep(old, new, count=1):
    global text
    n = text.count(old)
    if n != count:
        print(f"FAIL ({n}!={count}): {old[:70]!r}")
        sys.exit(1)
    text = text.replace(old, new, count)
    print("ok", old[:50])

rep(
"""    static boolean isGreeting(String query) {
        String lower = query.toLowerCase(Locale.ROOT);""",
"""    static boolean isGreeting(String query) {
        if (query == null) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);""")

helper = '''    /**
     * 인사 토큰과 문장부호만으로 이루어진 순수 인사인지 판정한다.
     * "안녕, 오늘 주가 어때?"처럼 인사 뒤에 실제 질의가 붙은 입력은
     * casual 스킵 대상이 아니다 — 그런 질의는 정상 검색/검증 경로를 탄다.
     */
    static boolean isCasualGreetingOnly(String query) {
        if (!isGreeting(query)) {
            return false;
        }
        String remainder = query.toLowerCase(Locale.ROOT)
                .replaceAll("\\\\b(hi|hello|hey)\\\\b", " ")
                .replace("\\uC548\\uB155\\uD558\\uC2ED\\uB2C8\\uAE4C", " ")
                .replace("\\uC548\\uB155\\uD558\\uC138\\uC694", " ")
                .replace("\\uC548\\uB155", " ")
                .replace("\\uBC18\\uAC11\\uC2B5\\uB2C8\\uB2E4", " ")
                .replace("\\uBC18\\uAC00\\uC6CC\\uC694", " ")
                .replace("\\uBC18\\uAC00\\uC6CC", " ")
                .replace("\\uBC18\\uAC00", " ")
                .replace("\\uD558\\uC774", " ")
                .replace("\\uD5EC\\uB85C", " ")
                .replaceAll("[\\\\s\\\\p{Punct}~_]+", "");
        return remainder.isEmpty();
    }

'''
anchor = "    private static boolean containsGreetingToken(String text, String token) {"
rep(anchor, helper + anchor)

out = text.replace("\n", "\r\n")
open(PATH, "wb").write(out.encode("utf-8"))
print("DONE")
