"""gpu_incident 합성 픽스처 테스트 — 실제 GPU/이벤트/네트워크 접근 없음."""
import json
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_incident as gi


def smi(rows, text="", ok=True, timed_out=False):
    out = {"ok": ok, "text": text, "timed_out": timed_out,
           "exit": 0 if ok else 9, "rows": rows}
    return out


R3060 = {"index": "0", "name": "NVIDIA GeForce RTX 3060",
         "bus_id": "00000000:05:00.0", "power_draw": "43.33 W",
         "throttle": "0x1"}
R3090 = {"index": "1", "name": "NVIDIA GeForce RTX 3090",
         "bus_id": "00000000:0A:00.0", "power_draw": "16.80 W",
         "throttle": "0x0"}

LOST_TEXT = ("0, NVIDIA GeForce RTX 3060, 00000000:05:00.0, 43.33 W\n"
             "Unable to determine the device handle for GPU1: "
             "0000:0A:00.0: GPU is lost.  Reboot the system to recover "
             "this GPU")


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.flag_path = Path(self.root) / "var" / "incident" / "gpu.json"

    def tearDown(self):
        self.tmp.cleanup()

    def _flag(self):
        return json.loads(self.flag_path.read_text(encoding="utf-8"))

    def test_lost_phrase_variants(self):
        for phrase in ("GPU is lost",
                       "Unable to determine the device handle",
                       "has fallen off the bus", "Xid 79", "ERR!"):
            flag, code = gi.probe(
                self.root, smi=smi([R3060, R3090], text="header\n" + phrase),
                events=[])
            self.assertEqual("GPU3090_LOST", flag["state"], phrase)
            self.assertEqual(3, code)
            self.assertTrue(flag["reboot_required"])
            self.assertEqual(["ollama:11434"], flag["skip_lanes"])

    def test_missing_3090_row_is_lost(self):
        flag, code = gi.probe(self.root, smi=smi([R3060], text="ok"), events=[])
        self.assertEqual("GPU3090_LOST", flag["state"])
        self.assertTrue(any("target_row_missing" in e
                            for e in flag["evidence"]))

    def test_healthy_two_gpu_is_ok(self):
        flag, code = gi.probe(self.root, smi=smi([R3060, R3090]), events=[])
        self.assertEqual("OK", flag["state"])
        self.assertEqual(0, code)
        self.assertFalse(flag["reboot_required"])
        self.assertEqual([], flag["skip_lanes"])
        self.assertTrue(flag["peer3060_ok"])

    def test_smi_timeout_is_degraded(self):
        flag, code = gi.probe(self.root, smi=smi([], timed_out=True,
                                                 ok=False), events=[])
        self.assertEqual("GPU3090_DEGRADED", flag["state"])
        self.assertEqual(3, code)
        self.assertFalse(flag["reboot_required"])

    def test_clear_refused_while_lost(self):
        gi.probe(self.root, smi=smi([R3060], text=LOST_TEXT), events=[])
        flag, code = gi.clear(self.root,
                              smi=smi([R3060], text=LOST_TEXT), events=[])
        self.assertEqual(3, code)
        self.assertEqual("GPU3090_LOST", self._flag()["state"])

    def test_clear_only_when_probe_ok(self):
        gi.probe(self.root, smi=smi([R3060], text=LOST_TEXT), events=[])
        flag, code = gi.clear(self.root, smi=smi([R3060, R3090]), events=[])
        self.assertEqual(0, code)
        self.assertEqual("OK", self._flag()["state"])

    def test_atomic_write_leaves_no_tmp(self):
        gi.probe(self.root, smi=smi([R3060], text=LOST_TEXT), events=[])
        left = [p for p in self.flag_path.parent.iterdir()
                if ".tmp-" in p.name]
        self.assertEqual([], left)
        self.assertEqual("awx.gpu_incident.v1", self._flag()["schema"])

    def test_since_kst_format_and_stability(self):
        f1, _ = gi.probe(self.root, smi=smi([R3060], text=LOST_TEXT),
                         events=[], now="2026-10-05T13:00:00+09:00")
        self.assertTrue(f1["since_kst"].endswith("+09:00"))
        f2, _ = gi.probe(self.root, smi=smi([R3060], text=LOST_TEXT),
                         events=[], now="2026-10-05T13:05:00+09:00")
        self.assertEqual("2026-10-05T13:00:00+09:00", f2["since_kst"])
        self.assertEqual("2026-10-05T13:05:00+09:00", f2["probed_at_kst"])

    def test_korean_event_message_passes_through(self):
        ev = [{"provider": "Display", "id": 4101,
               "time": "2026-10-05T04:10:56",
               "message": "디스플레이 드라이버가 응답을 중지한 후 복구되었습니다"}]
        flag, code = gi.probe(self.root, smi=smi([R3060], text=LOST_TEXT),
                              events=ev)
        joined = "\n".join(flag["evidence"])
        self.assertIn("응답을 중지", joined)
        self.assertIn("Display/4101", joined)


class StatusTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def test_status_missing_flag_exit4(self):
        flag, code = gi.status(self.root)
        self.assertIsNone(flag)
        self.assertEqual(4, code)

    def test_status_exit_codes(self):
        gi.probe(self.root, smi=smi([R3060, R3090]), events=[])
        self.assertEqual(0, gi.status(self.root)[1])
        gi.probe(self.root, smi=smi([R3060], text=LOST_TEXT), events=[])
        self.assertEqual(3, gi.status(self.root)[1])
        gi.probe(self.root, smi=smi([], ok=False, timed_out=True), events=[])
        self.assertEqual(3, gi.status(self.root)[1])


if __name__ == "__main__":
    unittest.main()
