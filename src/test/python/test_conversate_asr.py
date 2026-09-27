import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools' / 'conversate-asr'))
from stream import StreamAssembler, TranscriptRevision, PendingDecodes, UtteranceDecoder, parse_cpu_threads


class StreamingContracts(unittest.TestCase):
    def test_cpu_budget_defaults_to_two_and_rejects_unbounded_values(self):
        self.assertEqual(2, parse_cpu_threads(None))
        for count in (1, 4, 8):
            self.assertEqual(count, parse_cpu_threads(str(count)))
        for value in ('0', '-1', '9', '999999', 'auto', '2.5', ''):
            with self.assertRaisesRegex(ValueError, 'asr_cpu_threads_invalid'):
                parse_cpu_threads(value)

    def make(self):
        return StreamAssembler(lambda frame: frame[0] == 1)

    def feed(self, stream, voiced, frames, jobs):
        for _ in range(frames):
            jobs.extend(stream.accept(stream.next_seq, bytes([1 if voiced else 0]) + bytes(639)))

    def test_live_partial_and_final_share_id_and_increase_revision(self):
        stream, jobs = self.make(), []
        self.feed(stream, True, 120, jobs)
        self.feed(stream, False, 30, jobs)
        self.assertTrue(any(not j.final for j in jobs))
        self.assertTrue(jobs[-1].final)
        self.assertEqual(1, len({j.utterance_id for j in jobs}))
        self.assertEqual(sorted(set(j.revision for j in jobs)), [j.revision for j in jobs])
        self.assertLessEqual(stream.buffered_bytes, 320000)

    def test_duplicate_frames_are_not_transcribed_twice_and_conflicts_rejected(self):
        s = self.make()
        data = bytes([1]) + bytes(639)
        s.accept(0, data)
        self.assertEqual([], s.accept(0, data))
        self.assertRaisesRegex(ValueError, 'sequence_conflict', s.accept, 0, bytes(640))
        self.assertRaisesRegex(ValueError, 'sequence_gap', s.accept, 2, data)
        self.assertRaisesRegex(ValueError, 'audio_chunk_limit', s.accept, 1, bytes(8001))

    def test_continuous_speech_uses_bounded_overlap_then_cancel_discards_audio(self):
        s, jobs = self.make(), []
        self.feed(s, True, 1100, jobs)
        finals = [j for j in jobs if j.final]
        self.assertGreaterEqual(len(finals), 2)
        self.assertLessEqual(max(len(j.pcm) for j in jobs), 320000)
        self.assertTrue(any(j.overlap for j in jobs))
        s.clear()
        self.assertEqual(0, s.buffered_bytes)

    def test_ten_second_decode_boundary_does_not_end_the_complete_question(self):
        s, jobs = self.make(), []
        self.feed(s, True, 1100, jobs)
        self.feed(s, False, 30, jobs)
        finals = [j for j in jobs if j.final]
        self.assertEqual(3, len(finals))
        self.assertEqual(['asr-1'] * 3, [j.group_id for j in finals])
        self.assertEqual([False, False, True], [j.utterance_end for j in finals])
        self.assertLessEqual(max(len(j.pcm) for j in jobs), 320000)
        self.feed(s, True, 20, jobs)
        self.feed(s, False, 30, jobs)
        self.assertNotEqual('asr-1', jobs[-1].group_id)

    def test_stop_flushes_short_tail_once_without_waiting_for_silence(self):
        s, jobs = self.make(), []
        self.feed(s, True, 50, jobs)
        self.assertEqual([], jobs)
        jobs = s.finish()
        self.assertEqual(1, len(jobs))
        self.assertTrue(jobs[0].final and jobs[0].utterance_end)
        self.assertEqual(50 * 640, len(jobs[0].pcm))
        self.assertEqual([], s.finish())
        self.assertEqual(0, s.buffered_bytes)

    def test_exact_decode_boundary_then_silence_or_stop_commits_without_redecode(self):
        for stop in (True, False):
            s, jobs = self.make(), []
            self.feed(s, True, 500, jobs)
            if stop:
                jobs.extend(s.finish())
            else:
                self.feed(s, False, 30, jobs)
            endpoint = jobs[-1]
            self.assertTrue(endpoint.utterance_end)
            self.assertEqual(b'', endpoint.pcm)
            calls = []
            event = UtteranceDecoder(lambda pcm: calls.append(pcm)).decode(endpoint)
            self.assertEqual([], calls)
            self.assertEqual('', event['text'])
            self.assertTrue(event['utteranceEnd'])
            self.assertEqual('asr-1', event['groupId'])

    def test_text_normalization_preserves_repeats_changed_number_and_negation(self):
        r = TranscriptRevision()
        self.assertEqual('보증 기간은 2년입니다', r.clean('u1', '보증 기간은 2년입니다', True, False))
        self.assertEqual('보증 기간은 2년입니다 침수는 보증하지 않습니다', r.clean('u2', '보증 기간은 2년입니다 침수는 보증하지 않습니다', True, True))
        self.assertEqual('보증 기간은 3년이 아닙니다', r.clean('u3', '보증 기간은 3년이 아닙니다', True, True))
        r.clear()
        self.assertEqual('', r.previous_final)

    def test_decode_backpressure_coalesces_partial_but_preserves_final_or_fails(self):
        q = PendingDecodes()
        s, jobs = self.make(), []
        self.feed(s, True, 220, jobs)
        self.feed(s, False, 30, jobs)
        for j in jobs:
            q.offer(j)
        self.assertLessEqual(q.size, 2)
        self.assertTrue(q.take_nowait().final)
        q.clear()
        self.assertEqual(0, q.size)


if __name__ == '__main__':
    unittest.main()
