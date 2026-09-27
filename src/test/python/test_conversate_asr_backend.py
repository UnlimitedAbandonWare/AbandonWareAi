import os
import sys
import unittest
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'tools/conversate-asr'))
from local_backend import LocalBackend, select_device
from stream import TranscriptRevision, Decode, pcm_for_decode

GPU = 'GPU-12345678-1234-1234-1234-123456789abc'
OTHER = 'GPU-aaaaaaaa-1234-1234-1234-123456789abc'
ROWS = f'{OTHER}, NVIDIA GeForce RTX 3090, 24000\n{GPU}, NVIDIA GeForce RTX 3060, 9000\n'


class BackendContracts(unittest.TestCase):
    def test_model_metadata_tracks_cpu_fallback_without_exposing_custom_paths(self):
        from model_worker import metadata
        model = SimpleNamespace(device='cuda',gpu_uuid=None,reason='primary',errors=0,
                                model_path=str(Path('private')/'turbo'),cpu_model_path=str(Path('private')/'base'),
                                _sampled_device='cuda',_vram_free=None,_vram_at=None)
        self.assertEqual('turbo', metadata(model)['model'])
        model.device = 'cpu'
        self.assertEqual('base', metadata(model)['model'])
        model.cpu_model_path = str(Path('private')/'unpublished-model-directory')
        self.assertEqual('not_observed', metadata(model)['model'])

    def env(self):
        return {'CONVERSATE_ASR_DEVICE': 'cuda', 'CONVERSATE_ASR_GPU_UUID': GPU}

    def test_cpu_default_does_not_probe_gpu(self):
        def forbidden():
            self.fail('CPU must not probe CUDA')
        self.assertEqual(('cpu', None), select_device({}, forbidden))

    def test_uuid_is_bound_before_cuda_import_even_when_device_order_changes(self):
        env = self.env()
        self.assertEqual(('cuda', GPU), select_device(env, lambda: ROWS))
        self.assertEqual(GPU, env['CUDA_VISIBLE_DEVICES'])

    def test_missing_low_memory_or_3090_never_silently_selects_another_gpu(self):
        for rows in (ROWS.replace('9000', '10'), ROWS.replace(GPU, OTHER), ROWS.replace('3060', '3090')):
            with self.subTest(rowsHash=len(rows)):
                with self.assertRaisesRegex(ValueError, 'asr_gpu_unavailable'):
                    select_device(self.env(), lambda: rows)
        with self.assertRaisesRegex(ValueError, 'asr_device_invalid'):
            select_device({'CONVERSATE_ASR_DEVICE': 'auto'}, lambda: ROWS)

    def test_failed_gpu_partial_is_discarded_and_cpu_result_used_once(self):
        calls = []
        class Model:
            def __init__(self, device):
                self.device = device
            def transcribe(self, audio, **kwargs):
                def segments():
                    if self.device == 'cuda':
                        yield SimpleNamespace(text='discarded partial')
                        raise RuntimeError('simulated GPU failure')
                    yield SimpleNamespace(text='complete CPU result')
                return segments(), None
        def factory(path, **kwargs):
            calls.append((path, kwargs))
            return Model(kwargs['device'])
        with patch.dict(os.environ, self.env(), clear=True):
            backend = LocalBackend('primary', 2, factory=factory, gpu_probe=lambda: ROWS)
            self.assertEqual('complete CPU result', backend.transcribe(object()))
            self.assertEqual('complete CPU result', backend.transcribe(object()))
        self.assertEqual(['cuda', 'cpu'], [c[1]['device'] for c in calls])
        self.assertTrue(all(c[1]['num_workers'] == 1 for c in calls))
        self.assertEqual('gpu_decode_failed', backend.reason)
        self.assertEqual('cpu', backend.device)

    def test_gpu_load_failure_uses_explicit_smaller_cpu_model(self):
        calls = []
        def factory(path, **kwargs):
            calls.append((path, kwargs['device']))
            if kwargs['device'] == 'cuda':
                raise RuntimeError('simulated allocation failure')
            return object()
        env = {**self.env(), 'CONVERSATE_ASR_CPU_MODEL': 'small-existing'}
        with patch.dict(os.environ, env, clear=True):
            backend = LocalBackend('turbo-existing', 2, factory=factory, gpu_probe=lambda: ROWS)
        self.assertEqual([('turbo-existing', 'cuda'), ('small-existing', 'cpu')], calls)
        self.assertEqual('gpu_load_failed', backend.reason)

    def test_cpu_failure_does_not_retry_or_return_partial(self):
        class Model:
            def transcribe(self, *args, **kwargs):
                raise RuntimeError('CPU unavailable')
        calls = []
        def factory(*args, **kwargs):
            calls.append(1)
            return Model()
        with patch.dict(os.environ, {}, clear=True):
            backend = LocalBackend('existing', 2, factory=factory)
            with self.assertRaises(RuntimeError):
                backend.transcribe(object())
        self.assertEqual(1, len(calls))

    def test_native_library_search_preserves_process_path(self):
        with tempfile.TemporaryDirectory() as native:
            with patch.dict(os.environ, {'CONVERSATE_ASR_NATIVE_LIB': native, 'PATH': 'retained'}, clear=True):
                backend = LocalBackend('existing', 2, factory=lambda *a, **k: object())
                self.assertEqual(native + os.pathsep + 'retained', os.environ['PATH'])
                for handle in backend._dll_handles:
                    handle.close()

    def test_repeated_words_are_not_deduplicated_by_string_overlap(self):
        revisions = TranscriptRevision()
        sample = 'again please'
        self.assertEqual(sample, revisions.clean('one', sample, True, False))
        self.assertEqual(sample, revisions.clean('two', sample, True, True))
        self.assertEqual(sample + ' now', revisions.clean('three', sample + ' now', True, True))

    def test_only_proven_carried_pcm_samples_are_removed(self):
        carried = bytes([1]) * 6400
        fresh = bytes([2]) * 12800
        job = Decode('u2', 1, True, carried + fresh, True)
        self.assertEqual(fresh, pcm_for_decode(job))
        job.overlap = False
        self.assertEqual(carried + fresh, pcm_for_decode(job))


if __name__ == '__main__':
    unittest.main()
