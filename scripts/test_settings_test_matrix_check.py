"""Offline tests for settings_test_matrix_check.py.

Fixtures live in a tmp dir (fake src/test tree, fake matrices, fake JUnit
XML); no network, no Gradle. Run:
pytest scripts/test_settings_test_matrix_check.py
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "settings_test_matrix_check",
    ROOT / "scripts" / "settings_test_matrix_check.py")
mtx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mtx)


def make_tree(tmp_path: Path):
    jdir = tmp_path / "src/test/java/com/example/lms/api"
    jdir.mkdir(parents=True)
    (jdir / "SettingsCapabilityProjectionTest.java").write_text(
        "class SettingsCapabilityProjectionTest {\n"
        "  @Test void conditionalBeanIsNotLiveSupport() {}  // V3-01\n"
        "  @Test void configuredValueIsNotObservedExecution() {}  // V3-02\n"
        "  @Test void missingValueStaysUnknown() {}             // V3-03\n"
        "  @Test void other() {}\n"
        "}\n", encoding="utf-8")
    jsdir = tmp_path / "src/test/js"
    jsdir.mkdir(parents=True)
    (jsdir / "settings-core.test.cjs").write_text(
        "test('a', ()=>{});\ntest('b', ()=>{});\n", encoding="utf-8")
    return tmp_path


def make_matrices(tmp_path: Path):
    v3 = tmp_path / "v3.json"
    v3.write_text(json.dumps([
        {"id": "V3-01", "test_name": "conditionalBeanIsNotLiveSupport",
         "test_class": "SettingsCapabilityProjectionTest"},
        {"id": "V3-02", "test_name": "configuredValueIsNotObservedExecution",
         "test_class": "SettingsCapabilityProjectionTest"},
        {"id": "V3-99", "test_name": "totallyAbsentSpec"},
    ]), encoding="utf-8")
    v2 = tmp_path / "v2.json"
    v2.write_text(json.dumps([
        {"id": "T01", "test_name": "unknownRoleRejected"},
        {"id": "T99", "test_name": "neverImplementedSpec"},
    ]), encoding="utf-8")
    return v3, v2


def test_report_maps_classes_specs(tmp_path):
    root = make_tree(tmp_path)
    v3, v2 = make_matrices(tmp_path)
    rep = mtx.build_report(root, v3, v2)
    row = rep["classes"]["SettingsCapabilityProjectionTest"]
    assert row["exists"] and row["testCount"] == 4
    assert set(row["specIds"]) == {"V3-01", "V3-02", "V3-03"}
    assert rep["classes"]["RoutingSettingsControllerTest"]["exists"] is False
    assert rep["nodeTests"]["settings-core.test.cjs"]["testCount"] == 2
    assert rep["nodeTests"]["settings-routing.test.cjs"]["exists"] is False
    assert rep["summary"]["specTotal"] == 5
    # V3-03 is referenced in the file but is not a matrix row -> not listed.
    assert set(rep["coveredIds"]) == {"V3-01", "V3-02"}
    assert "V3-99" in rep["notCoveredIds"] and "T99" in rep["notCoveredIds"]


def test_excluded_spec_marked(tmp_path):
    root = make_tree(tmp_path)
    v3 = tmp_path / "v3.json"
    v3.write_text(json.dumps([
        {"id": "V3-50", "test_name": "excludedSpec"},
    ]), encoding="utf-8")
    d = root / "src/test/java/com/example/lms/api"
    (d / "SettingsRoutingPageTest.java").write_text(
        "// V3-50 excluded: out-of-scope for this round\n"
        "class SettingsRoutingPageTest { @Test void x() {} }\n",
        encoding="utf-8")
    rep = mtx.build_report(root, v3, None)
    assert rep["excludedIds"] == ["V3-50"]


def test_xml_results_read(tmp_path):
    root = make_tree(tmp_path)
    xdir = root / "build/test-results/test"
    xdir.mkdir(parents=True)
    (xdir / "TEST-com.example.lms.api.SettingsCapabilityProjectionTest.xml"
     ).write_text(
        '<testsuite name="com.example.lms.api.SettingsCapabilityProjectionTest"'
        ' tests="4" failures="1" errors="0" skipped="0"/>',
        encoding="utf-8")
    v3, _ = make_matrices(tmp_path)
    rep = mtx.build_report(root, v3, None)
    run = rep["classes"]["SettingsCapabilityProjectionTest"]["lastRun"]
    assert run["tests"] == 4 and run["failures"] == 1


def test_empty_tree(tmp_path):
    rep = mtx.build_report(tmp_path, None, None)
    assert rep["summary"]["classesFound"] == 0
    assert rep["summary"]["specTotal"] == 0


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
