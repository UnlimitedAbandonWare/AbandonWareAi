"""Self-check for main_chat_target_probe.py — offline verdict cases only.

Feeds the committed synthetic fixtures under scripts/tests/fixtures/ (main /
interview / empty HTML) plus synthetic status codes through classify(); no
network is touched.

Run: python -B scripts/tests/test_main_chat_target_probe.py
Exit 0 = all cases behaved as expected; 1 = a case disagreed.
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts" / "main_chat_target_probe.py"
FIXTURES = ROOT / "scripts" / "tests" / "fixtures"

spec = importlib.util.spec_from_file_location("main_chat_target_probe", TOOL)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def load(name):
    return (FIXTURES / name).read_text(encoding="utf-8")


def check(label, got, want):
    ok = got == want
    print(("PASS" if ok else "FAIL"), label,
          f"(got={got!r} want={want!r})" if not ok else "")
    return ok


def main():
    main_html = load("chat_main_sample.html")
    interview_html = load("chat_interview_sample.html")
    empty_html = load("chat_empty_sample.html")

    results = [
        check("main-200-verdict",
              probe.classify(200, main_html)["verdict"], "MAIN_OK"),
        check("interview-200-verdict",
              probe.classify(200, interview_html)["verdict"],
              "INTERVIEW_OVERRIDE"),
        check("empty-200-verdict",
              probe.classify(200, empty_html)["verdict"], "UNKNOWN"),
        check("conn-error-verdict",
              probe.classify(None, "")["verdict"], "UNREACHABLE"),
        check("http-502-verdict",
              probe.classify(502, "<html>bad gateway</html>")["verdict"],
              "UNREACHABLE"),
        check("http-404-verdict",
              probe.classify(404, empty_html)["verdict"], "UNKNOWN"),
        check("main-title",
              probe.classify(200, main_html)["title"], "AbandonWare AI"),
        check("main-markers>=2",
              probe.classify(200, main_html)["chatUiMarkersFound"] >=
              probe.MIN_MARKERS, True),
        check("interview-marker",
              probe.classify(200, interview_html)["interviewDemoMarker"], True),
        check("empty-chatjs-ref",
              probe.classify(200, empty_html)["chatJsRef"], False),
    ]
    print(f"{sum(results)}/{len(results)} cases ok")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
