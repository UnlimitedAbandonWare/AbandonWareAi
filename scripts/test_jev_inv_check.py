"""Synthetic PASS/FAIL pairs for jev_inv_check. The live tree is not used."""
from __future__ import annotations

import importlib.util
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("jev_inv_check.py")
SPEC = importlib.util.spec_from_file_location("jev_inv_check", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

YML_PASS = textwrap.dedent("""\
    demo:
      jev:
        mode: ${DEMO_JEV_MODE:off}
        connect-timeout-ms: ${DEMO_JEV_CONNECT_TIMEOUT_MS:250}
        request-timeout-ms: ${DEMO_JEV_REQUEST_TIMEOUT_MS:800}
        decision-wait-ms: ${DEMO_JEV_DECISION_WAIT_MS:150}
        free-only: ${DEMO_JEV_FREE_ONLY:true}
        allow-paid: ${DEMO_JEV_ALLOW_PAID:false}
        free-window-end: ${DEMO_JEV_FREE_WINDOW_END:2026-09-26T00:00:00Z}
    jev:
      gateway:
        zero-data-retention: ${DEMO_JEV_ZDR:false}
    """)

CLI_PASS = textwrap.dedent("""\
    class JevGatewayClient {
      JevGatewayClient(){ HttpClient.newBuilder().build(); }
      Object evaluate(Object req){ return fail(0,"timeout"); }
    }
    """)

CLI_FAIL = textwrap.dedent("""\
    class JevGatewayClient {
      Object evaluate(Object req){
        HttpClient.newBuilder().build();
        if(!reported.contains("jev")) return fail(0,"timeout");
        return fail(0,"brand-new-reason");
      }
    }
    """)

ADV_PASS = textwrap.dedent("""\
    class JevDecisionAdvisor {
      void advise(){
        if(reservation==null){inFlight.release();return Advice.defer("budget_skip",mode);}
        pool.execute(()->{ try { remember(res); } catch(RejectedExecutionException rejected) {} finally { inFlight.release(); } });
        catch(RejectedExecutionException rejected){ inFlight.release(); }
        supplyAsync(()->{ try { remember(res); } finally { inFlight.release(); } });
      }
      Advice defer(String reason){ return null; }
    }
    """)

ADV_FAIL = textwrap.dedent("""\
    class JevDecisionAdvisor {
      void advise(){
        pool.execute(()->{ try { inFlight.release(); } finally { remember(res); } });
        supplyAsync(()->{ try { inFlight.release(); } finally { remember(res); } });
      }
    }
    """)


def _tree(cli: str, advisor: str, yml: str) -> Path:
    root = Path(tempfile.mkdtemp())
    java = root / "main" / "java" / "com" / "example" / "lms" / "assist"
    java.mkdir(parents=True)
    (java / "JevGatewayClient.java").write_text(cli, encoding="utf-8")
    (java / "JevDecisionAdvisor.java").write_text(advisor, encoding="utf-8")
    resources = root / "main" / "resources"
    resources.mkdir(parents=True)
    (resources / "application-meta-display.yml").write_text(yml, encoding="utf-8")
    return root


def _status(root: Path, check_id: str) -> str:
    rows = {row["id"]: row["status"] for row in MOD.run_checks(root)}
    return rows[check_id]


class InvCheckTest(unittest.TestCase):
    def test_wp1_model_pair(self) -> None:
        self.assertEqual(_status(_tree(CLI_FAIL, ADV_PASS, YML_PASS), "WP1-model"), "FAIL")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, YML_PASS), "WP1-model"), "PASS")

    def test_wp2_client_pair(self) -> None:
        self.assertEqual(_status(_tree(CLI_FAIL, ADV_PASS, YML_PASS), "WP2-client"), "FAIL")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, YML_PASS), "WP2-client"), "PASS")

    def test_wp2_reject_pair(self) -> None:
        self.assertEqual(_status(_tree(CLI_PASS, ADV_FAIL, YML_PASS), "WP2-reject"), "FAIL")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, YML_PASS), "WP2-reject"), "PASS")

    def test_inv1_order_pair(self) -> None:
        self.assertEqual(_status(_tree(CLI_PASS, ADV_FAIL, YML_PASS), "INV1-order"), "FAIL")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, YML_PASS), "INV1-order"), "PASS")

    def test_inv3_reasons_pair(self) -> None:
        self.assertEqual(_status(_tree(CLI_FAIL, ADV_PASS, YML_PASS), "INV3-reasons"), "FAIL")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, YML_PASS), "INV3-reasons"), "PASS")

    def test_inv4_yml_pair(self) -> None:
        bad = YML_PASS.replace("${DEMO_JEV_MODE:off}", "${DEMO_JEV_MODE:on}")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, bad), "INV4-yml"), "FAIL")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, YML_PASS), "INV4-yml"), "PASS")

    def test_wp3_default_skip_pass_fail(self) -> None:
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, YML_PASS), "WP3-default"), "SKIP")
        added = YML_PASS.replace(
            "free-window-end: ${DEMO_JEV_FREE_WINDOW_END:2026-09-26T00:00:00Z}",
            "free-window-end: ${DEMO_JEV_FREE_WINDOW_END:2026-09-26T00:00:00Z}\n"
            "    budget:\n      enabled: ${DEMO_JEV_BUDGET_ENABLED:false}\n"
            "    daily-max-calls: ${DEMO_JEV_DAILY_MAX_CALLS:0}")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, added), "WP3-default"), "PASS")
        wrong = added.replace("${DEMO_JEV_BUDGET_ENABLED:false}", "${DEMO_JEV_BUDGET_ENABLED:true}")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, wrong), "WP3-default"), "FAIL")



    def test_cfg_overrides_defaults_pass(self) -> None:
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, YML_PASS), "CFG-overrides"), "PASS")

    def test_cfg_overrides_fails_each_setting(self) -> None:
        paid = YML_PASS.replace("${DEMO_JEV_ALLOW_PAID:false}", "${DEMO_JEV_ALLOW_PAID:true}")
        mode = YML_PASS.replace("${DEMO_JEV_MODE:off}", "${DEMO_JEV_MODE:on}")
        daily = YML_PASS.replace(
            "allow-paid: ${DEMO_JEV_ALLOW_PAID:false}",
            "allow-paid: ${DEMO_JEV_ALLOW_PAID:false}\n        budget:\n          daily-max-calls: 3")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, paid), "CFG-overrides"), "FAIL")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, mode), "CFG-overrides"), "FAIL")
        self.assertEqual(_status(_tree(CLI_PASS, ADV_PASS, daily), "CFG-overrides"), "FAIL")

    def test_cfg_ignores_unrelated_mode_and_jvm_override(self) -> None:
        root = _tree(CLI_PASS, ADV_PASS, YML_PASS)
        config = root / "config"
        config.mkdir()
        (config / "other.yml").write_text("server:\n  mode: on\n", encoding="utf-8")
        (root / "main" / "java" / "Ignore.java").write_text(
            "class Ignore { String flag = \"-Ddemo.jev.allow-paid=true\"; }\n", encoding="utf-8")
        (root / ".env").write_text("DEMO_JEV_ALLOW_PAID=true\n-Ddemo.jev.mode=on\n", encoding="utf-8")
        self.assertEqual(_status(root, "CFG-overrides"), "PASS")
        (root / "local.properties").write_text("demo.jev.allow-paid=true\n", encoding="utf-8")
        self.assertEqual(_status(root, "CFG-overrides"), "FAIL")



if __name__ == "__main__":
    unittest.main()
