#!/usr/bin/env python3
"""AWX 공용 웹서치 융합기 — CLI 중립 래퍼.

Contract: PASTE_DEVIN_GROK_CLI_UAW_WEBSEARCH_20261002 (DV3, Q-2=A 공용 래퍼).
실제 구현은 같은 디렉터리의 agy_web_fuse.py. 이 파일은 argv를 그대로 넘기는
얇은 진입점이다 — Grok CLI, Codex, agy 등 어느 터미널에서든 동일 호출:

  $items | ConvertTo-Json | python -B scripts/awx_web_fuse.py --top 5
  python -B scripts/awx_web_fuse.py --in payload.json --format md

표준 라이브러리만, 네트워크 0, 파일 쓰기 0.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import agy_web_fuse  # noqa: E402  # 실제 융합 로직 (단일 SSOT)


if __name__ == "__main__":
    sys.exit(agy_web_fuse.main())
