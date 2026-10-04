#!/usr/bin/env python3
"""Tests for scripts/brief_save.py `backfill` — temp fixtures only.

Covers the registry backfill contract on this checkout:
  t1 idempotent — a second backfill adds zero rows
  t2 name-parse-failure handling — a PASTE_*.txt whose name does not match
     PASTE_<AGENT>_<topic>_<yyyymmdd>.txt is still recorded, but with
     agent=UNKNOWN and topic=<stem> (existing contract — recorded, not dropped)
"""
import argparse
import json
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import brief_save


def _args(downloads: str, registry: str, repo_root: str) -> argparse.Namespace:
    return argparse.Namespace(repo_root=repo_root, registry=registry,
                            downloads_dir=downloads)


def _rows(registry: pathlib.Path) -> list:
    if not registry.exists():
        return []
    return [json.loads(l) for l in
            registry.read_text(encoding="utf-8").splitlines() if l.strip()]


class BackfillTest(unittest.TestCase):
    def _setup(self, tmp: str):
        downloads = pathlib.Path(tmp) / "Downloads"
        downloads.mkdir(parents=True)
        registry = pathlib.Path(tmp) / "briefs.jsonl"
        repo = pathlib.Path(tmp) / "repo"
        repo.mkdir()
        (downloads / "PASTE_DEVIN_alpha-topic_20261003.txt").write_text(
            "alpha\n", encoding="utf-8")
        (downloads / "PASTE_CODEX_beta-topic_20261002.txt").write_text(
            "beta\n", encoding="utf-8")
        return downloads, registry, repo

    def test_t1_idempotent_second_run_adds_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            downloads, registry, repo = self._setup(tmp)
            args = _args(str(downloads), str(registry), str(repo))
            self.assertEqual(brief_save.cmd_backfill(args), 0)
            first = _rows(registry)
            self.assertEqual(len(first), 2)
            self.assertEqual(brief_save.cmd_backfill(args), 0)
            self.assertEqual(_rows(registry), first)

    def test_t2_unparsed_name_recorded_as_unknown_not_dropped(self):
        with tempfile.TemporaryDirectory() as tmp:
            downloads, registry, repo = self._setup(tmp)
            (downloads / "PASTE_CLEAN_agent_ops_thin.txt").write_text(
                "odd name\n", encoding="utf-8")
            args = _args(str(downloads), str(registry), str(repo))
            self.assertEqual(brief_save.cmd_backfill(args), 0)
            rows = _rows(registry)
            self.assertEqual(len(rows), 3)
            odd = [r for r in rows if "agent_ops_thin" in r["downloadsPath"]]
            self.assertEqual(len(odd), 1)
            self.assertEqual(odd[0]["agent"], "UNKNOWN")
            self.assertEqual(odd[0]["topic"], "PASTE_CLEAN_agent_ops_thin")
            self.assertIsNone(odd[0]["sha12"])
            self.assertEqual(odd[0]["sha12Note"], "backfill-meta-only")


if __name__ == "__main__":
    unittest.main(verbosity=2)
