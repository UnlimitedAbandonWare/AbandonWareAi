# -*- coding: utf-8 -*-
"""chat.js 디스크 수술 — selection replay 카드는 diagnostics 게이트 없이 렌더하지 않는다."""
import sys

PATH = r"main/resources/static/js/chat.js"
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


# diagnostics 비활성 시 selection replay 카드를 렌더하지 않는다 (방어적 게이트).
rep(
    """function renderSelectionEntropyTrace(payload, bubble) {
  if (!bubble) return null;
  const signal = selectionEntropySignal(payload);""",
    """function renderSelectionEntropyTrace(payload, bubble) {
  if (!bubble) return null;
  const diagnosticsEnabled = isChatTransitionDebugEnabled();
  if (!diagnosticsEnabled) {
    clearSelectionEntropyTrace(bubble);
    return null;
  }
  const signal = selectionEntropySignal(payload);""")

open(PATH, "wb").write(text.encode("utf-8"))
print("WROTE", PATH, len(text))
