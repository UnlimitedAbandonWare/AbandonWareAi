import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools' / 'conversate-asr'))
from managed_backend import ManagedBackend

WORKER = '''import json, os, sys, time
config=json.loads(sys.stdin.readline())
device=os.environ.get('CONVERSATE_ASR_DEVICE','cpu')
print(json.dumps({'type':'ready','device':device,'errors':0,'reason':'local_ready'}),flush=True)
for line in sys.stdin:
    event=json.loads(line)
    if device=='cuda' or os.environ.get('SYNTHETIC_CPU_HANG')=='1': time.sleep(60)
    print(json.dumps({'type':'result','text':'synthetic-complete','device':device,'errors':0,'reason':'local_ready'}),flush=True)
'''


class NativeProcessContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.script = self.path / 'worker.py'
        self.script.write_text(WORKER, encoding='utf-8')

    def tearDown(self):
        self.temp.cleanup()

    def backend(self, **options):
        return ManagedBackend(self.path, 2, worker_script=self.script,
                              decode_seconds=2, primary_seconds=.15, startup_seconds=5, **options)

    def test_hung_gpu_is_gone_before_cpu_replacement_and_no_partial_is_joined(self):
        with patch.dict(os.environ, {'CONVERSATE_ASR_DEVICE':'cuda','CONVERSATE_ASR_CPU_MODEL':str(self.path)}):
            backend = self.backend()
        old = backend.worker.process
        try:
            began = time.monotonic()
            self.assertEqual('synthetic-complete', backend.transcribe(bytes(640)))
            self.assertLess(time.monotonic()-began, 2.5)
            self.assertIsNotNone(old.poll())
            self.assertEqual('cpu', backend.device)
            self.assertEqual('gpu_timeout_cpu_fallback', backend.reason)
            current = backend.worker.process
            self.assertEqual('synthetic-complete', backend.transcribe(bytes(640)))
            self.assertIs(current, backend.worker.process)
        finally:
            backend.close()
        self.assertIsNotNone(current.poll())

    def test_hung_cpu_fails_once_and_disposes_worker(self):
        with patch.dict(os.environ, {'CONVERSATE_ASR_DEVICE':'cpu','SYNTHETIC_CPU_HANG':'1'}):
            backend = self.backend()
        owned = backend.worker.process
        try:
            with self.assertRaisesRegex(ValueError, 'asr_native_failed'):
                backend.transcribe(bytes(640))
            self.assertIsNone(backend.worker)
            self.assertIsNotNone(owned.poll())
        finally:
            backend.close()

    def test_close_is_idempotent_and_invalid_audio_does_not_start_more_workers(self):
        with patch.dict(os.environ, {'CONVERSATE_ASR_DEVICE':'cpu'}):
            backend = self.backend()
        owned = backend.worker.process
        with self.assertRaises(ValueError):
            backend.transcribe(bytes(320640))
        self.assertIsNone(owned.poll())
        backend.close()
        backend.close()
        self.assertIsNotNone(owned.poll())

    def test_nonreading_worker_cannot_block_maximum_pcm_send(self):
        self.script.write_text("import sys,json,time\nsys.stdin.readline()\nprint(json.dumps({'type':'ready','device':'cpu'}),flush=True)\ntime.sleep(60)\n", encoding='utf-8')
        # The outer process deadline makes this regression safe even if the pipe fence breaks.
        code = (f'import sys,time;sys.path.insert(0,{str(ROOT / "tools" / "conversate-asr")!r});'
                'from managed_backend import ManagedBackend;'
                f'b=ManagedBackend({str(self.path)!r},2,worker_script={str(self.script)!r},decode_seconds=.25,primary_seconds=.1);'
                'p=b.worker.process;began=time.monotonic()\n'
                'try: b.transcribe(bytes(320000));raise AssertionError("unexpected_success")\n'
                'except ValueError: pass\n'
                'assert time.monotonic()-began<1.5;assert p.poll() is not None;assert b.worker is None;b.close()')
        result = subprocess.run([sys.executable,'-B','-c',code],capture_output=True,text=True,
                                env=dict(os.environ,CONVERSATE_ASR_DEVICE='cpu'),timeout=4)
        self.assertEqual(0,result.returncode, 'bounded synthetic worker contract failed')

    def test_unconfirmed_exit_is_retained_without_a_second_cleanup_or_cpu_launch(self):
        with patch.dict(os.environ, {'CONVERSATE_ASR_DEVICE':'cuda'}):
            backend = self.backend()
        owned = backend.worker
        try:
            with patch.object(backend,'_decode',side_effect=TimeoutError()), patch.object(owned,'close',return_value=False) as close:
                with self.assertRaises(ValueError):
                    backend.transcribe(bytes(640))
                self.assertEqual(1,close.call_count)
                self.assertIs(owned,backend.worker)
                self.assertEqual('cuda',backend.device)
        finally:
            backend.close()

    @unittest.skipUnless(os.name == 'nt', 'Windows Job Object contract')
    def test_hard_parent_exit_closes_worker_job(self):
        code = (f'import sys,os;sys.path.insert(0,{str(ROOT / "tools" / "conversate-asr")!r});'
                'from managed_backend import ManagedBackend;'
                f'b=ManagedBackend({str(self.path)!r},2,worker_script={str(self.script)!r});'
                'print(b.worker.process.pid,flush=True);os._exit(0)')
        env = dict(os.environ, CONVERSATE_ASR_DEVICE='cpu')
        owner = subprocess.run([sys.executable,'-B','-u','-c',code], capture_output=True,text=True,env=env,timeout=10)
        self.assertEqual(0, owner.returncode)
        pid = int(owner.stdout.strip())
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD,wintypes.BOOL,wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE,wintypes.DWORD]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x00100000,False,pid)
        if handle:
            try:
                self.assertEqual(0,kernel.WaitForSingleObject(handle,2000))
            finally:
                kernel.CloseHandle(handle)


if __name__ == '__main__':
    unittest.main()
