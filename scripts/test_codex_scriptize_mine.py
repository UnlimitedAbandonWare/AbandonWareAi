#!/usr/bin/env python3
"""codex_scriptize_mine 단위 테스트 (가짜 rollout 생성, 실제 세션 미사용).

실행: python -B scripts/test_codex_scriptize_mine.py -v
"""
import io
import json
import sys
import tempfile
import unittest
from collections import Counter
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_scriptize_mine as m  # noqa: E402

DEMO_CWD = "C:\\AbandonWare\\demo-1\\demo-1\\src"
FAKE_KEY = "gsk_" + "Ab3dEf9" * 5 + "Zz"   # 조립 가짜 키 (패턴 일치용)


def rec(ts, rtype, payload):
    return json.dumps({"ordinal": 0, "timestamp": ts, "type": rtype, "payload": payload},
                      ensure_ascii=False)


def exec_call(ts, call_id, cmds_js):
    return rec(ts, "response_item", {
        "type": "custom_tool_call", "name": "exec", "call_id": call_id,
        "input": cmds_js, "status": "completed"})


def exec_out(ts, call_id, text):
    return rec(ts, "response_item", {
        "type": "custom_tool_call_output", "call_id": call_id,
        "output": [{"type": "input_text", "text": text}]})


def js_cmd(cmd, i=0):
    # cmd 를 JS 문자열 리터럴로 인코딩
    esc = cmd.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    return 'const r%d = await tools.exec_command({cmd:"%s"}); text(r%d);' % (i, esc, i)


def make_session(dirpath, uuid, cwd, seqs, extra_lines=None, day=None):
    """seqs: 턴 리스트, 각 턴은 [(call_id, [cmd,...], out_text)] 리스트."""
    day = day or date.today()
    fname = "rollout-%sT10-00-00-%s.jsonl" % (day.strftime("%Y-%m-%d"), uuid)
    lines = [rec("2026-10-03T01:00:00.000Z", "session_meta", {"cwd": cwd, "id": uuid})]
    t = 1
    for turn in seqs:
        lines.append(rec("2026-10-03T01:00:%02d.000Z" % t, "event_msg",
                         {"type": "task_started", "turn_id": "t%d" % t}))
        t += 1
        for call_id, cmds, out_text in turn:
            body = "".join(js_cmd(c, i) for i, c in enumerate(cmds))
            lines.append(exec_call("2026-10-03T01:00:%02d.100Z" % t, call_id, body))
            lines.append(exec_out("2026-10-03T01:00:%02d.900Z" % t, call_id, out_text))
            t += 1
    if extra_lines:
        lines.extend(extra_lines)
    path = Path(dirpath) / fname
    path.parent.mkdir(parents=True, exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))
    return path


def run_mine(sessions_dir, cwd_filter="AbandonWare\\demo-1", **kw):
    tmp = tempfile.mkdtemp(prefix="mine-out-")
    jout = str(Path(tmp) / "out.json")
    argv = ["--sessions-dir", str(sessions_dir), "--days", "7",
            "--cwd-filter", cwd_filter, "--json-out", jout]
    for k, v in kw.items():
        argv += ["--" + k.replace("_", "-"), str(v)]
    rc = m.main(argv)
    assert rc == 0
    data = json.loads(io.open(jout, encoding="utf-8").read())
    md = io.open(jout.replace(".json", ".md"), encoding="utf-8").read()
    return data, md


class TestExtract(unittest.TestCase):
    def test_m1_two_exec_commands_and_escapes(self):
        inp = ('const a = await tools.exec_command({cmd:"Get-Content \\"C:\\\\x\\\\y.md\\""});'
               'const b = await tools.exec_command({cmd:"git status --porcelain"});')
        cmds = m.extract_cmds_from_input(inp)
        self.assertEqual(len(cmds), 2)
        self.assertEqual(cmds[0], 'Get-Content "C:\\x\\y.md"')
        self.assertEqual(cmds[1], "git status --porcelain")

    def test_m2_normalize_merges(self):
        a = m.normalize(r'Select-String -Path C:\repo\a\one.py -Pattern "foo"')
        b = m.normalize(r"Select-String -Path D:\other\b2\three.py -Pattern 'bar'")
        self.assertEqual(a, b)
        self.assertIn("<PATH>.py", a)
        self.assertIn("<STR>", a)
        c = m.normalize("git log -5 --oneline")
        d = m.normalize("git log -42 --oneline")
        self.assertEqual(c, d)
        e = m.normalize("python -B scripts/x.py --task-id 01a100a2-7e77-7762-b433-433bd7acc6ae --since 2026-10-01")
        self.assertIn("<ID>", e)
        self.assertIn("<DATE>", e)


class TestMine(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="mine-sess-")

    def _three_sessions_common(self):
        """세 세션에 동일 3-명령 턴 + 한 세션에만 있는 묶음."""
        common = [("c1", ["Get-Location"], "Script completed\nWall time 0.1 seconds\nOutput:\n"),
                  ("c2", ["git status --porcelain"], "Script completed\nWall time 0.3 seconds\nOutput:\n"),
                  ("c3", ["python -B scripts/work_journal.py list --active"], "Script completed\nOutput:\n")]
        for i in range(3):
            make_session(self.dir, "aaaaaaa%d-0000-0000-0000-000000000000" % i,
                         DEMO_CWD, [common])
        # 한 세션에만 있는 고유 묶음 (3회 반복해도 세션 1개 → 탈락해야 함)
        solo = [("s1", ["ollama ps"], "Script completed\nOutput:\n"),
                ("s2", ["nvidia-smi --query-gpu=name"], "Script completed\nOutput:\n")]
        make_session(self.dir, "bbbbbbb9-0000-0000-0000-000000000000",
                     DEMO_CWD, [solo, solo, solo])

    def test_m3_multisession_bundle(self):
        self._three_sessions_common()
        data, _md = run_mine(self.dir)
        shapes = [" → ".join(b["shape"]) for b in data["bundles"]]
        joined = "\n".join(shapes)
        self.assertIn("Get-Location", joined)
        self.assertIn("git status --porcelain", joined)
        hit = [b for b in data["bundles"] if any("Get-Location" in s for s in b["shape"])]
        self.assertTrue(hit)
        self.assertGreaterEqual(hit[0]["session_count"], 3)
        self.assertNotIn("ollama ps", joined)

    def test_m4_secret_values_never_in_output(self):
        cmds = ["$env:FOO_TOKEN = '%s'; git status" % "sekret-VALUE-12345",
                "curl -H \"Authorization: %s\" http://x" % FAKE_KEY]
        make_session(self.dir, "ccccccc1-0000-0000-0000-000000000000",
                     DEMO_CWD, [[("k1", cmds, "Script completed\nOutput:\n")]])
        # 세션 두 개 더(같은 묶음) → 묶음이 결과에 올라오게
        for i in (2, 3):
            make_session(self.dir, "ccccccc%d-0000-0000-0000-000000000000" % i,
                         DEMO_CWD, [[("k1", cmds, "Script completed\nOutput:\n")]])
        data, md = run_mine(self.dir)
        blob = json.dumps(data, ensure_ascii=False) + md
        self.assertNotIn(FAKE_KEY, blob)
        self.assertNotIn("sekret-VALUE-12345", blob)
        self.assertNotIn("FOO_TOKEN", blob.replace("$env:<ENV>", ""))

    def test_m5_encrypted_and_broken_counted(self):
        enc = rec("2026-10-03T01:00:01.000Z", "response_item",
                  {"type": "custom_tool_call", "name": "exec", "call_id": "e1",
                   "input": "gAAAAABhAAAA"})
        broken = '{"type":"response_item","payload":{broken'
        make_session(self.dir, "ddddddd1-0000-0000-0000-000000000000",
                     DEMO_CWD, [], extra_lines=[enc, broken])
        data, _ = run_mine(self.dir)
        s = data["summary"]
        self.assertEqual(s["encrypted_inputs"], 1)
        self.assertEqual(s["bad_json_lines"], 1)

    def test_m6_state_change_risk(self):
        risky = [("r1", ["git add -A", "git commit -m x"], "ok"),
                 ("r2", ["Remove-Item -Recurse build"], "ok")]
        for i in range(3):
            make_session(self.dir, "eeeeeee%d-0000-0000-0000-000000000000" % i,
                         DEMO_CWD, [risky])
        data, _ = run_mine(self.dir)
        rb = [b for b in data["bundles"] if any("git add" in s or "Remove-Item" in s
                                               for s in b["shape"])]
        self.assertTrue(rb)
        self.assertTrue(all(b["risk"] == "state-change" for b in rb))

    def test_m7_existing_tool_verdict(self):
        seq = [("t1", ["Get-Location"], "ok"),
               ("t2", ["python -B scripts\\work_journal.py list --active"], "ok")]
        for i in range(3):
            make_session(self.dir, "fffffff%d-0000-0000-0000-000000000000" % i,
                         DEMO_CWD, [seq])
        data, _ = run_mine(self.dir)
        hits = [b for b in data["bundles"]
                if any("list --active" in s for s in b["shape"])]
        self.assertTrue(hits)
        self.assertTrue(any(b["verdict"] == "기존 도구 사용 중" for b in hits))
        self.assertIn("scripts/work_journal.py", hits[0].get("tools_used", []))

    def test_m8_cwd_filter_excludes(self):
        other = [("o1", ["unique-cmd-a"], "ok"), ("o2", ["unique-cmd-b"], "ok")]
        for i in range(4):   # 다른 cwd 세션 4개 — 반복되지만 제외돼야 함
            make_session(self.dir, "8888888%d-0000-0000-0000-000000000000" % i,
                         "C:\\other\\project", [other])
        for i in range(2):   # demo-1 세션 2개, 같은 묶음
            make_session(self.dir, "9999999%d-0000-0000-0000-000000000000" % i,
                         DEMO_CWD, [other])
        data, _ = run_mine(self.dir)
        s = data["summary"]
        self.assertEqual(s["files_excluded_cwd"], 4)
        self.assertEqual(s["files_demo1"], 2)
        hit = [b for b in data["bundles"] if any("unique-cmd-a" in x for x in b["shape"])]
        if hit:
            self.assertEqual(hit[0]["session_count"], 2)


if __name__ == "__main__":
    unittest.main()
