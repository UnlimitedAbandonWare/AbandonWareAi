"""Behavioral catalog contracts for the existing MCP-style stdio bridge."""
import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import awx_mcp_stdio_server as server


class CatalogContractTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads(server.MANIFEST_PATH.read_text(encoding="utf-8"))


    def test_subscription_review_adds_one_tool_and_preserves_all_existing_contracts(self):
        tools = self.manifest['tools']
        self.assertEqual(30, len(tools))
        before_grok = [row for row in tools if row['name'] not in {'grok_review_change', 'kimi_review_change', 'device_work'}]
        preserved = json.dumps(before_grok, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
        self.assertEqual('696b68f29ea7e73c2f06409decb474f44bf41f6e1317b4cb1ccf19390a76ac4f', hashlib.sha256(preserved).hexdigest())
        previous = [row for row in before_grok if row['name'] != 'codex_review_change']
        encoded = json.dumps(previous, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
        self.assertEqual('802d7156186cec250d9c28fea6c2514c754b693b03cbf830d6c0084eff1aa307', hashlib.sha256(encoded).hexdigest())
        review = next(row for row in tools if row['name'] == 'codex_review_change')
        self.assertFalse(review['input_schema']['additionalProperties'])
        self.assertFalse(review['output_schema']['additionalProperties'])

    def with_manifest(self, manifest):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        replacement = patch.object(server, "MANIFEST_PATH", path)
        replacement.start()
        self.addCleanup(replacement.stop)

    def catalog(self):
        reply = server.handle_request({"jsonrpc": "2.0", "id": 1, "method": "awx/catalog"})
        self.assertNotIn("error", reply, "catalog health must be observable through the existing bridge")
        return reply["result"]

    def test_resource_uris_are_unique(self):
        uris = [row["uri"] for row in server.list_resources()]
        self.assertEqual(len(uris), len(set(uris)), "tool_manifest must not be registered twice")

    def test_all_lists_have_deterministic_name_order(self):
        for rows in (server.list_tools(), server.list_resources(), server.list_prompts()):
            names = [row["name"] for row in rows]
            self.assertEqual(sorted(names), names)

    def test_declared_output_contract_is_exposed_without_version_migration(self):
        expected = {row["name"]: row.get("output_schema") for row in self.manifest["tools"]}
        for tool in server.list_tools():
            self.assertEqual(expected[tool["name"]], tool.get("outputSchema"))
        self.assertEqual("2024-11-05", server.initialize_result()["protocolVersion"])

    def test_output_missing_required_wrong_type_and_closed_contract(self):
        request = {"jsonrpc": "2.0", "id": "1", "method": "tools/call",
                   "params": {"name": "boot_verify", "arguments": {"nodeRole": "desktop"}}}
        invalid_outputs = [{}, {"commands": [], "executeSupported": "false"},
                           {"commands": [3], "executeSupported": False}, ["invalid-object"]]
        for output in invalid_outputs:
            with self.subTest(output=output), patch.dict(server.HANDLERS, boot_verify=lambda _: output), patch.object(server.toolbox, "finalize") as finalize:
                reply = server.handle_request(request)
            self.assertEqual("1", reply["id"])
            self.assertTrue(reply["result"]["isError"])
            self.assertEqual("output_schema_validation_failed", reply["result"]["structuredContent"]["reason"])
            finalize.assert_not_called()
        manifest = copy.deepcopy(self.manifest)
        schema = next(row for row in manifest["tools"] if row["name"] == "boot_verify")["output_schema"]
        schema["additionalProperties"] = False
        self.with_manifest(manifest)
        for output, failed in [({"commands": [], "executeSupported": False}, False),
                               ({"commands": [], "executeSupported": False, "privateExtra": "private-sentinel"}, True)]:
            with patch.dict(server.HANDLERS, boot_verify=lambda _: output):
                reply = server.handle_request(request)
            self.assertEqual(failed, reply["result"]["isError"])
            self.assertNotIn("private-sentinel", json.dumps(reply))
            if not failed:
                self.assertEqual(output, reply["result"]["structuredContent"])

    def test_output_schema_is_checked_after_redaction_and_failure_stays_execution_error(self):
        request = {"jsonrpc": "2.0", "id": 0, "method": "tools/call",
                   "params": {"name": "boot_verify", "arguments": {"nodeRole": "desktop"}}}
        with patch.dict(server.HANDLERS, boot_verify=lambda _: {"commands": [], "executeSupported": False}), \
             patch.object(server.toolbox, "redact", return_value={"commands": "masked", "executeSupported": False}), \
             patch.object(server.toolbox, "finalize"):
            result = server.handle_request(request)["result"]
        self.assertTrue(result["isError"])
        self.assertEqual("output_schema_validation_failed", result["structuredContent"]["reason"])
        with patch.dict(server.HANDLERS, boot_verify=lambda _: {"ok": False, "reason": "business_failure"}):
            reply = server.handle_request(request)
        self.assertEqual(0, reply["id"])
        self.assertTrue(reply["result"]["isError"])
        self.assertEqual("business_failure", reply["result"]["structuredContent"]["reason"])

    def test_source_scan_env_reference_arrays_survive_the_full_mcp_response(self):
        schema = next(row for row in self.manifest["tools"]
                      if row["name"] == "source_scan")["output_schema"]
        for content in ("", "OPENAI_API_KEY NAVER_CLIENT_SECRET"):
            with self.subTest(has_references=bool(content)), tempfile.TemporaryDirectory() as directory:
                resources = Path(directory) / "main" / "resources"
                resources.mkdir(parents=True)
                (resources / "references.properties").write_text(content, encoding="utf-8")
                reply = server.handle_request({"jsonrpc": "2.0", "id": "source-scan",
                    "method": "tools/call", "params": {"name": "source_scan",
                    "arguments": {"nodeRole": "desktop", "root": directory}}})["result"]
                self.assertFalse(reply["isError"])
                result = reply["structuredContent"]
                self.assertTrue(server.schema_matches(result, schema))
                self.assertIsInstance(result["secretEnvRefs"], list)
                self.assertIsInstance(result["apikeyEnvRefs"], list)
                self.assertEqual(sorted(content.split()), result["secretEnvRefs"])

    def test_env_reference_exception_keeps_unapproved_values_redacted(self):
        private_marker = "synthetic-private-value"
        for key in ("secretEnvRefs", "apikeyEnvRefs"):
            with self.subTest(key=key):
                value = {key: ["OPENAI_API_KEY", private_marker,
                               {"nested": private_marker}, 7]}
                result = server.toolbox.redact(value)
                self.assertEqual(["OPENAI_API_KEY", "<redacted>", "<redacted>", "<redacted>"],
                                 result[key])
                self.assertNotIn(private_marker, json.dumps(result))
                self.assertEqual({key: "<redacted>"}, server.toolbox.redact({key: private_marker}))
        self.assertEqual({"apiKey": "<redacted>"},
                         server.toolbox.redact({"apiKey": private_marker}))

    def test_catalog_identifies_style_without_claiming_modern_compliance(self):
        catalog = self.catalog()
        self.assertEqual("MCP_STYLE", catalog["protocolFamily"])
        self.assertEqual("2024-11-05", catalog["protocolVersion"])
        self.assertFalse(catalog["modernSupported"])
        self.assertEqual(len(self.manifest["tools"]), catalog["toolCount"])
        self.assertEqual(0, catalog["duplicateCount"])
        self.assertEqual(0, catalog["schemaInvalidCount"])
        self.assertEqual(0, catalog["manifestMismatchCount"])

    def test_duplicate_and_case_collision_are_not_callable(self):
        manifest = copy.deepcopy(self.manifest)
        duplicate = copy.deepcopy(manifest["tools"][0])
        duplicate["name"] = duplicate["name"].upper()
        manifest["tools"].append(duplicate)
        self.with_manifest(manifest)
        catalog = self.catalog()
        self.assertFalse(catalog["ok"])
        self.assertEqual(1, catalog["duplicateCount"])
        names = [row["name"].lower() for row in server.list_tools()]
        self.assertNotIn(duplicate["name"].lower(), names)

    def test_manifest_only_tool_is_excluded_and_detected(self):
        manifest = copy.deepcopy(self.manifest)
        absent = copy.deepcopy(manifest["tools"][0])
        absent["name"] = "fixture_absent_handler"
        manifest["tools"].append(absent)
        self.with_manifest(manifest)
        catalog = self.catalog()
        self.assertEqual(1, catalog["manifestMismatchCount"])
        self.assertNotIn("fixture_absent_handler", [row["name"] for row in server.list_tools()])

    def test_runtime_only_tool_is_detected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["tools"] = manifest["tools"][1:]
        self.with_manifest(manifest)
        self.assertEqual(1, self.catalog()["manifestMismatchCount"])

    def test_invalid_input_schema_is_excluded(self):
        manifest = copy.deepcopy(self.manifest)
        name = manifest["tools"][0]["name"]
        manifest["tools"][0]["input_schema"] = {"type": "object", "required": ["missing_property"], "properties": {}}
        self.with_manifest(manifest)
        self.assertEqual(1, self.catalog()["schemaInvalidCount"])
        self.assertNotIn(name, [row["name"] for row in server.list_tools()])

    def test_required_arguments_checked_before_handler_runs(self):
        reply = server.handle_request({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                       "params": {"name": "boot_verify", "arguments": {}}})
        self.assertIn("error", reply)
        self.assertEqual(-32602, reply["error"]["code"])

    def test_non_object_params_are_invalid(self):
        reply = server.handle_request({"jsonrpc": "2.0", "id": 3, "method": "tools/list", "params": []})
        self.assertIn("error", reply)
        self.assertEqual(-32602, reply["error"]["code"])

    def test_missing_resource_is_not_empty_success(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["resources"] = [{"name": "missing_fixture", "path": "missing-runtime-fixture.txt", "usage": "Fixture"}]
        self.with_manifest(manifest)
        reply = server.handle_request({"jsonrpc": "2.0", "id": 4, "method": "resources/read",
                                       "params": {"uri": server.manifest_uri("missing-runtime-fixture.txt")}})
        self.assertIn("error", reply)
        self.assertNotIn(str(server.ROOT), json.dumps(reply))

    def test_resource_path_escape_is_not_exposed(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["resources"] = [{"name": "outside", "path": "../outside-fixture.txt", "usage": "Fixture"}]
        self.with_manifest(manifest)
        self.assertNotIn("outside", [row["name"] for row in server.list_resources()])
        self.assertFalse(self.catalog()["ok"])

    def test_manifest_change_invalidates_same_process_catalog(self):
        manifest = copy.deepcopy(self.manifest)
        self.with_manifest(manifest)
        before = self.catalog()
        manifest["tools"].pop()
        server.MANIFEST_PATH.write_text(json.dumps(manifest), encoding="utf-8")
        after = self.catalog()
        self.assertNotEqual(before["manifestHash"], after["manifestHash"])
        self.assertEqual(1, after["manifestMismatchCount"])

    def test_alias_drift_is_visible_and_not_callable(self):
        manifest = copy.deepcopy(self.manifest)
        row = manifest["tools"][0]
        row["aliases"].append("fixture_unregistered_alias")
        self.with_manifest(manifest)
        self.assertFalse(self.catalog()["ok"])
        self.assertGreater(self.catalog()["aliasMismatchCount"], 0)
        self.assertNotIn(row["name"], [r["name"] for r in server.list_tools()])

    def test_resource_definition_conflict_is_diagnosable(self):
        manifest = copy.deepcopy(self.manifest)
        collision = copy.deepcopy(manifest["resources"][0])
        collision["name"] = "different_definition_same_uri"
        manifest["resources"].append(collision)
        self.with_manifest(manifest)
        catalog = self.catalog()
        self.assertFalse(catalog["ok"])
        self.assertTrue(any(row["reason"] == "registration_collision" for row in catalog["issues"]))
        self.assertNotIn(server.manifest_uri(collision["path"]), [r["uri"] for r in server.list_resources()])

    def test_prompt_contract_rejects_ignored_arguments(self):
        reply = server.handle_request({"jsonrpc": "2.0", "id": 0, "method": "prompts/get",
                                       "params": {"name": self.manifest["prompts"][0]["name"], "arguments": {"ignored": "value"}}})
        self.assertEqual(-32602, reply["error"]["code"])


if __name__ == "__main__":
    unittest.main()
