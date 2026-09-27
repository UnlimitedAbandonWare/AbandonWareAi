import base64
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'tools' / 'conversate-asr'))
from stream import Decode, UtteranceDecoder


class FallbackContracts(unittest.TestCase):
    def job(self, final, number=1, revision=1, overlap=False):
        return Decode('asr-' + str(number), revision, final, bytes([1]) * 12800, overlap)

    def test_partial_failure_waits_for_final_and_never_retries_bad_local_decoder(self):
        calls = []
        def broken(pcm):
            calls.append(len(pcm))
            raise RuntimeError('synthetic')
        decoder = UtteranceDecoder(broken, True)
        self.assertIsNone(decoder.decode(self.job(False)))
        self.assertIsNone(decoder.decode(self.job(False, revision=2)))
        final = decoder.decode(self.job(True, revision=3))
        self.assertEqual([12800], calls)
        self.assertEqual(('fallback', 'asr-1', 3, True), (final['type'], final['utteranceId'], final['revision'], final['final']))
        self.assertEqual(self.job(True).pcm, base64.b64decode(final['pcm']))
        self.assertNotIn('text', final)

    def test_local_success_preserves_true_repetition_without_remote_event(self):
        decoder = UtteranceDecoder(lambda pcm: '다시 다시 알려 주세요', True)
        for number in (1, 2):
            event = decoder.decode(self.job(True, number))
            self.assertEqual('transcript', event['type'])
            self.assertEqual('다시 다시 알려 주세요', event['text'])
            self.assertNotIn('pcm', event)

    def test_remote_disabled_or_invalid_final_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'asr_decode_failed'):
            UtteranceDecoder(None, False).decode(self.job(True))
        for pcm in (bytes(0), bytes(320640), bytes(641)):
            with self.assertRaises(ValueError):
                UtteranceDecoder(None, True).decode(Decode('asr-1', 1, True, pcm, False))

    def test_only_known_overlap_samples_are_removed_from_remote_audio(self):
        event = UtteranceDecoder(None, True).decode(self.job(True, overlap=True))
        self.assertEqual(self.job(True).pcm[6400:], base64.b64decode(event['pcm']))


if __name__ == '__main__':
    unittest.main()
