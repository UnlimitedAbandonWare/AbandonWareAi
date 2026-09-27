import sys

sys.stdout.reconfigure(encoding="utf-8")
p = "main/java/com/example/lms/service/ChatWorkflow.java"
src = open(p, encoding="utf-8").read()
anchor = 'TraceStore.put("prompt.ctx.prefix.sha1", TextUtils.sha1(ctxText.substring(0, cap)));'
i = src.find(anchor)
assert i > 0, "anchor not found"
j = src.find("}", i)
assert j > 0
ins = """
            // 세션 대화 컨텍스트 단계별 진단: 조립(assembled)된 값과 실제 프롬프트
            // 전달(delivered) 여부를 구분해 "메모리 정상" 오판을 막는다.
            TraceStore.put("prompt.contextInjected.history", historyStr != null && !historyStr.isBlank());
            TraceStore.put("prompt.contextInjected.historyChars", historyStr == null ? 0 : historyStr.length());
            TraceStore.put("prompt.contextInjected.lastAssistant", lastAnswer != null && !lastAnswer.isBlank());
            TraceStore.put("prompt.contextInjected.memory", memoryCtx != null && !memoryCtx.isBlank());
            TraceStore.put("prompt.contextInjected.delivered",
                    ctxText != null && ctxText.contains("### RECENT CONVERSATION"));"""
src = src[:j] + ins + src[j:]
open(p, "w", encoding="utf-8", newline="").write(src)
print("inserted at", j)
