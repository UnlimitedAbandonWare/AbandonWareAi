"""Targeted tests for model_default_probe --ready / --restarts-since (no network, no DB, no live probes).
Run: python -B scripts\\test_model_default_probe_ready.py -v   (targeted only)"""
import datetime as dt, json, os, pathlib, sys, tempfile, time, types, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model_default_probe as s

KST = s.KST
NOW = dt.datetime(2026, 10, 9, 20, 30, 0, tzinfo=KST)
CAT = [{'id': 'chatgpt-oauth:gpt-5.6-luna', 'selectable': True}, {'id': 'llmrouter.openai-economy', 'selectable': False}]
def prof(model='chatgpt-oauth:gpt-5.6-luna', mode='AUTO'):
    sj = json.dumps({'answerSelection': {'mode': mode, 'modelId': model, 'routing': {'fallbackAllowed': True}}})
    return lambda: (0, json.dumps({'ok': True, 'columns': ['OWNER_HASH', 'SETTINGS_VERSION', 'SETTINGS_JSON'],
                                   'rows': [['33d5f21b86f3', 7, sj]]}))
def http(url):
    if url.endswith('/api/chat/models'): return CAT
    if 'debug/events' in url: return []
    raise OSError(url)
def ev(hh, mm, outcome='success', stage='focus_terminal', reason='none', act='hash:a1'):
    ts = dt.datetime(2026, 10, 9, hh, mm, 0, tzinfo=KST).astimezone(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.000Z')
    return json.dumps({'ts': ts, 'data': {'stage': stage, 'outcome': outcome, 'reasonCode': reason,
                                          'ownerHash': 'hash:33d5f21b86f3', 'activationHash': act}})
LOG = '2026-10-09 19:36:52 [DEV-RELOAD] Spring restart → start_rag_stack -MetaDisplay -ForceRestart'
A = types.SimpleNamespace(base='http://x', owner_hash='', paths=[], static_prefix='main/resources/static/')
def probes(health='UP', mutex=False, alive=True, port=59544):
    return {'health': lambda: health, 'mutex': lambda r: mutex, 'alive': lambda p: alive,
            'port': lambda p: port, 'windows': lambda: [{'pid': 1, 'since': '2026-10-09 18:38', 'bat': 'Start-Meta-Display.bat'}]}

class Ready(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = pathlib.Path(self.tmp.name)
        run = self.root / 'var/rag-launcher/20261009-195643-c6ba302f'; run.mkdir(parents=True)
        (run / 'result.json').write_text(json.dumps({'status': 'ready', 'springReused': False, 'springPid': 59544,
            'role': 'dev', 'caller': 'devin', 'completedAtUtc': '2026-10-09T11:00:32.5Z'}), encoding='utf-8')
        (run / 'spring-owned.json').write_text(json.dumps({'listener': {'processId': 59544}}), encoding='utf-8')
        (self.root / 'var/rag-launcher/LATEST.json').write_text(json.dumps({'status': 'ready', 'runId': '20261009-195643-c6ba302f',
            'springPid': 59544, 'runtimeRole': 'dev', 'completedAtUtc': '2026-10-09T11:00:32.5Z',
            'runDirectory': str(run)}), encoding='utf-8')
        f = self.root / 'var/rag-launcher/20261009-184023-449d586c'; f.mkdir()
        (f / 'result.json').write_text(json.dumps({'status': 'failed', 'failurePoint': 'launcher'}), encoding='utf-8')
        (f / 'launcher.log').write_text('2026-10-09 18:40:24 [PREFLIGHT] FAILED reason=launcher-already-running line=747', encoding='utf-8')
        old = time.mktime((2026, 10, 9, 19, 0, 0, 0, 0, -1))
        self.js = self.root / 'main/resources/static/assets/display/display-focus-controls.js'; self.js.parent.mkdir(parents=True)
        self.js.write_text('presetModel([/luna/i])\n', encoding='utf-8'); os.utime(self.js, (old, old))
    def tearDown(self): self.tmp.cleanup()
    def gate(self, lines, **kw):
        return s.ready_gate(A, self.root, http=http, profile_runner=prof(), log_text=LOG,
                            probes=probes(**kw), event_lines=lines, now=NOW)

    def test_ready_only_with_real_focus_success_after_restart(self):
        out, code = self.gate([ev(20, 26)])
        self.assertEqual((out['verdict'], code), ('READY', 0))
        self.assertEqual(out['lastRestart'][:16], '2026-10-09 19:56')   # launcher run beats older DevWatch line
        self.assertIn('테스트해도 됩니다', out['koreanOneLiner'])

    def test_chat_ok_but_no_focus_answer_is_not_proven(self):
        out, code = self.gate([ev(19, 50)])                              # success BEFORE the 19:56 restart
        self.assertEqual((out['verdict'], code), ('NOT_PROVEN_FOCUS', 1))
        self.assertNotIn('테스트해도 됩니다:', out['koreanOneLiner'])

    def test_last_focus_error_blocks(self):
        out, _ = self.gate([ev(20, 10), ev(20, 20, 'error', reason='provider_not_configured')])
        self.assertEqual(out['verdict'], 'FOCUS_LAST_FAILED'); self.assertIn('provider_not_configured', out['koreanOneLiner'])

    def test_launcher_busy_and_down_and_record_stale(self):
        self.assertEqual(self.gate([ev(20, 26)], mutex=True)[0]['verdict'], 'LAUNCHER_BUSY')
        out, code = self.gate([], health=None)
        self.assertEqual((out['verdict'], code), ('RUNTIME_DOWN', 2))
        self.assertEqual(self.gate([ev(20, 26)], port=1234)[0]['verdict'], 'RUNTIME_RECORD_STALE')
        self.assertEqual(self.gate([ev(20, 26)], alive=False)[0]['verdict'], 'RUNTIME_RECORD_STALE')

    def test_java_change_after_restart_is_stale_static_change_is_sha_checked(self):
        j = self.root / 'main/java/com/example/lms/assist/NovaFocusState.java'; j.parent.mkdir(parents=True); j.write_text('x')
        self.assertEqual(self.gate([ev(20, 26)])[0]['verdict'], 'STALE_RUNTIME')
        j.unlink(); self.js.write_text('presetModel([/luna/i]) // new\n', encoding='utf-8')
        A.fetch = lambda url: self.js.read_bytes()                     # served == disk -> not stale
        try: self.assertEqual(self.gate([ev(20, 26)])[0]['verdict'], 'READY')
        finally: del A.fetch
        def down(url): raise OSError('refused')
        A.fetch = down                                                  # unreachable static -> stale
        try: self.assertEqual(self.gate([ev(20, 26)])[0]['verdict'], 'STALE_RUNTIME')
        finally: del A.fetch

    def test_probe_owner_success_is_not_user_proof(self):
        out, _ = self.gate([ev(20, 26).replace('33d5f21b86f3', '1d0fa9c5dc11')])
        self.assertEqual(out['ownerHash'], '33d5f21b86f3'); self.assertEqual(out['verdict'], 'NOT_PROVEN_FOCUS')
        self.assertEqual(out['focusProof']['otherOwnersSuccessSinceRestart'], 1)

    def test_profile_unread_is_unverified_not_blocked(self):
        out, _ = s.ready_gate(A, self.root, http=http, profile_runner=lambda: (1, 'nope'), log_text=LOG,
                              probes=probes(), event_lines=[], now=NOW)
        self.assertEqual(out['verdict'], 'FOCUS_MODEL_UNVERIFIED')

    def test_static_no_restart_line_is_not_a_restart(self):
        log = LOG + '\n2026-10-09 20:20:51 [DEV-RELOAD] source changed (static) → no Spring restart → client refresh :: x.js'
        out, _ = s.ready_gate(A, self.root, http=http, profile_runner=prof(), log_text=log, probes=probes(),
                              event_lines=[ev(20, 10)], now=NOW)
        self.assertEqual(out['lastRestart'][:16], '2026-10-09 19:56'); self.assertEqual(out['verdict'], 'READY')

    def test_model_blocked(self):
        out, _ = s.ready_gate(A, self.root, http=http, profile_runner=prof('llmrouter.openai-economy', 'FIXED'),
                              log_text=LOG, probes=probes(), event_lines=[ev(20, 26)], now=NOW)
        self.assertEqual(out['verdict'], 'FOCUS_MODEL_BLOCKED')

    def test_restarts_since_merges_devwatch_and_launcher(self):
        evs = s.restarts_since(self.root, s.parse_since('17:00', NOW), log_text=LOG)
        kinds = [e['kind'] for e in evs]
        self.assertEqual(kinds, ['LAUNCHER_FAILED', 'DEVWATCH_RESTART_START', 'LAUNCHER_START', 'LAUNCHER_READY_NEW_JVM'])
        self.assertIn('LAUNCHER_FAILED', kinds); self.assertIn('LAUNCHER_READY_NEW_JVM', kinds); self.assertIn('DEVWATCH_RESTART_START', kinds)
        self.assertTrue(any('launcher-already-running' in e['detail'] for e in evs))
        self.assertIn('재시작 완료', s.restart_notice_ko(evs))
        self.assertEqual(s.parse_since('30m', NOW), NOW - dt.timedelta(minutes=30))
        self.assertEqual(s._kst_from_iso('2026-10-09T09:41:06.0514421Z').strftime('%H:%M:%S'), '18:41:06')  # .NET 7-digit fraction

if __name__ == '__main__': unittest.main()
