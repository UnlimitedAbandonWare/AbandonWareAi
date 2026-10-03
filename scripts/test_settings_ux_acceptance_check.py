#!/usr/bin/env python3
"""test_settings_ux_acceptance_check.py — acceptance 도구 단위 테스트.

임시 폴더에 가짜 파일 트리를 만들어 PASS와 FAIL을 각각 재현한다.
실제 제품 소스는 읽지 않는다.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "settings_ux_acceptance_check.py"
spec = importlib.util.spec_from_file_location("settings_ux_acceptance_check", SCRIPT)
mod = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)

REAL_PROBE = Path(__file__).resolve().parent / "settings_page_probe.py"

BRIDGE = """(function(root,factory){const api=factory();
const STORAGE_KEY = 'awx.settings.v1.preferences';
const MAX_BYTES = 16384;
const KEYS = Object.freeze(['model','modelSelectionMode','searchMode','useRag']);
const DEFAULTS = Object.freeze({modelSelectionMode:'preferred',searchMode:'OFF',useRag:false});
return {STORAGE_KEY,MAX_BYTES,KEYS,DEFAULTS};})(window,function(){});
"""

PAGE_JS = """(function(){
document.getElementById('local-reset').addEventListener('click', () => {
  try { core.writeSettings(window.localStorage,{});render(); } catch { localStatus.textContent='초기화 실패'; }
});
for (const field of fields) field.addEventListener('change', () => {
  try { core.writeSettings(window.localStorage,values); }
  catch { localStatus.textContent='저장 실패 · 값의 형식을 확인하세요.'; }
});
})();
"""

ROUTING_JS = """(function(){
if(response.status===409){current={...current,kind:'conflict'};return current;}
const reload=button('서버 값 다시 읽기',async()=>show(await client.read(identity()),true));
status.textContent='다른 저장이 먼저 반영되었습니다. 선택값은 유지했습니다.';
})();
"""

HTML = """<html><body>
<select data-preference="model"></select>
<button id="local-reset">초기화</button>
</body></html>
"""

CONTROLLER = """package com.example.lms.api;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/settings/routing")
public class RoutingSettingsController {
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody String body){
        return respond(()->{
            var view=new LinkedHashMap<String,Object>();
            view.put("externalCalls",0);view.put("writes",0);
            return view;
        });
    }
    @PostMapping("/save")
    public ResponseEntity<?> save(@RequestBody String body){
        return respond(()->service.save(profile,revision,hash));
    }
}
"""

SECURITY = """public class AppSecurityConfig {
    void chain(){
        http.requestMatchers(HttpMethod.GET, "/api/settings", "/api/settings/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/settings", "/api/settings/**").hasRole("ADMIN");
    }
}
"""

FRONTEND = 'export default function ChatPage() {\n  const [useRag, setUseRag] = useState(true);\n}\n'

FILES = {
    "main/resources/static/js/chat-settings-bridge.js": BRIDGE,
    "main/resources/static/js/settings-page.js": PAGE_JS,
    "main/resources/static/js/settings-routing.js": ROUTING_JS,
    "main/resources/templates/settings.html": HTML,
    "main/resources/static/js/chat.js": "// chat fixture\n",
    "main/resources/static/js/chat-model-picker.js": "// picker\n",
    "main/resources/templates/chat-ui.html": "<html></html>\n",
    "main/resources/static/css/settings-page.css": ":root{}\n",
    "main/java/com/example/lms/api/RoutingSettingsController.java": CONTROLLER,
    "main/java/com/example/lms/config/AppSecurityConfig.java": SECURITY,
    "frontend/src/app/chat/page.js": FRONTEND,
}


def write_tree(root: Path, files: dict):
    for rel, content in files.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")


def make_baseline(root: Path) -> Path:
    """현재 트리를 기준선으로 복사 + baseline.json 생성."""
    bdir = root / "ledger" / "baseline"
    bdir.mkdir(parents=True, exist_ok=True)
    rows = []
    for rel in FILES:
        src = root / rel
        name = rel.rsplit("/", 1)[-1]
        dst = bdir / name
        shutil.copy(src, dst)
        rows.append({"path": rel,
                     "sha256": hashlib.sha256(src.read_bytes()).hexdigest(),
                     "baselineCopy": f"baseline/{name}"})
    doc = {"files": rows}
    bpath = root / "ledger" / "baseline.json"
    bpath.write_text(json.dumps(doc), encoding="utf-8")
    return bpath


class AcceptanceTest(unittest.TestCase):
    def _tree(self, mutate=None):
        td = tempfile.TemporaryDirectory()
        root = Path(td.name)
        write_tree(root, FILES)
        # C3가 임포트하는 probe 모듈 — 진짜 파일을 복사해 사용
        (root / "scripts").mkdir(exist_ok=True)
        shutil.copy(REAL_PROBE, root / "scripts" / "settings_page_probe.py")
        baseline = make_baseline(root)
        if mutate:
            mutate(root)
        return td, root, baseline

    def test_unchanged_tree_passes(self):
        td, root, baseline = self._tree()
        self.addCleanup(td.cleanup)
        rep = mod.run_all(root, str(baseline))
        self.assertEqual(rep["checks"]["C1"]["status"], "PASS")
        self.assertEqual(rep["checks"]["C2"]["status"], "PASS")
        self.assertEqual(rep["checks"]["C3"]["status"], "PASS")
        self.assertEqual(rep["checks"]["C4"]["status"], "HINT")
        self.assertEqual(rep["checks"]["C5"]["status"], "PENDING")
        self.assertEqual(rep["checks"]["C6"]["status"], "HINT")
        self.assertEqual(rep["checks"]["C7"]["status"], "PASS")
        self.assertEqual(rep["checks"]["C8"]["status"], "PASS")
        self.assertEqual(rep["checks"]["C9"]["status"], "PASS")
        self.assertEqual(rep["checks"]["C10"]["status"], "OBSERVED")
        self.assertNotEqual(rep["status"], "FAIL")

    def test_codex_regressions_fail(self):
        def mutate(root: Path):
            (root / "main/resources/static/js/chat.js").write_text(
                "// codex touched\n", encoding="utf-8")
            (root / "main/resources/static/js/chat-settings-bridge.js").write_text(
                BRIDGE.replace("'preferred'", "'strict'"), encoding="utf-8")
            (root / "main/resources/templates/chat-ui.html").write_text(
                "<html>changed</html>\n", encoding="utf-8")
            (root / "main/resources/templates/settings.html").write_text(
                HTML + '<label>JEV 스위치<input type="checkbox"></label>\n',
                encoding="utf-8")
            (root / "main/java/com/example/lms/config/AppSecurityConfig.java").write_text(
                SECURITY.replace(
                    'hasRole("ADMIN");',
                    'hasRole("ADMIN");\n            .requestMatchers(HttpMethod.GET, "/api/other").permitAll();'),
                encoding="utf-8")
            (root / "main/resources/static/js/settings-page.js").write_text(
                PAGE_JS + "// codex refactor without reset guard\n",
                encoding="utf-8")

        td, root, baseline = self._tree(mutate)
        self.addCleanup(td.cleanup)
        rep = mod.run_all(root, str(baseline))
        self.assertEqual(rep["checks"]["C1"]["status"], "FAIL")
        self.assertEqual(rep["checks"]["C2"]["status"], "FAIL")
        self.assertEqual(rep["checks"]["C5"]["status"], "FAIL")
        self.assertEqual(rep["checks"]["C7"]["status"], "FAIL")
        self.assertEqual(rep["checks"]["C8"]["status"], "WARN")
        self.assertEqual(rep["checks"]["C9"]["status"], "FAIL")
        self.assertEqual(rep["status"], "FAIL")

    def test_legit_patch_permit_and_cookie_owner_passes(self):
        def mutate(root: Path):
            (root / "main/java/com/example/lms/config/AppSecurityConfig.java").write_text(
                SECURITY.replace(
                    'hasRole("ADMIN");',
                    'hasRole("ADMIN");\n'
                    '            .requestMatchers(HttpMethod.PATCH, "/api/settings/preferences")\n'
                    '            .permitAll();'),
                encoding="utf-8")
            ctrl = root / "main/java/com/example/lms/api/PreferencesController.java"
            ctrl.write_text(
                "package com.example.lms.api;\n"
                "@RestController\n"
                "public class PreferencesController {\n"
                "  @PatchMapping(\"/api/settings/preferences\")\n"
                "  public Object patch(javax.servlet.http.HttpServletRequest req){\n"
                "    String owner = null;\n"
                "    for (var c : req.getCookies()) if (c.getName().equals(\"awx_owner\")) owner = c.getValue();\n"
                "    return Map.of(\"ok\", true);\n"
                "  }\n"
                "}\n", encoding="utf-8")

        td, root, baseline = self._tree(mutate)
        self.addCleanup(td.cleanup)
        rep = mod.run_all(root, str(baseline))
        self.assertEqual(rep["checks"]["C9"]["status"], "PASS",
                         rep["checks"]["C9"])

    def test_guarded_reset_passes(self):
        def mutate(root: Path):
            (root / "main/resources/static/js/settings-page.js").write_text(
                PAGE_JS.replace(
                    "try { core.writeSettings(window.localStorage,{});render(); }",
                    "if(!window.confirm('초기화할까요?'))return;\n"
                    "  try { core.writeSettings(window.localStorage,{});render(); }"),
                encoding="utf-8")

        td, root, baseline = self._tree(mutate)
        self.addCleanup(td.cleanup)
        rep = mod.run_all(root, str(baseline))
        self.assertEqual(rep["checks"]["C5"]["status"], "PASS",
                         rep["checks"]["C5"])


if __name__ == "__main__":
    unittest.main()
