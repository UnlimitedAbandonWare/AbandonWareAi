#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gemini_route_config_probe 오프라인 fixture 테스트 (stdlib unittest 전용)."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gemini_route_config_probe as g


def _write(root, name, text):
    path = os.path.join(root, "main", "resources", name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return path


class ProbeFixtureTest(unittest.TestCase):
    def _root(self):
        return tempfile.TemporaryDirectory()

    def test_profile_yaml_overrides_base_properties(self):
        # properties 기본값 api3 -> meta-display 기본값 api3,gemini-pro로 덮어쓰기
        with self._root() as d:
            _write(d, "application.properties",
                   "app.ai.allow-remote-model-selection=false\n"
                   "app.ai.remote-model-selection-routes=${CHAT_REMOTE_MODEL_SELECTION_ROUTES:api3}\n")
            _write(d, "application-meta-display.yml",
                   "app:\n  ai:\n    remote-model-selection-routes: ${CHAT_REMOTE_MODEL_SELECTION_ROUTES:api3,gemini-pro}\n")
            rep = g.probe(d, ["local", "meta-display"],
                          ["app.ai.allow-remote-model-selection", "app.ai.remote-model-selection-routes"],
                          env_reader=lambda n: (None, None))
        k = rep["keys"]["app.ai.remote-model-selection-routes"]
        self.assertEqual(k["effective"], "api3,gemini-pro")
        self.assertIn("application-meta-display.yml:", k["source"])
        self.assertEqual(rep["keys"]["app.ai.allow-remote-model-selection"]["effective"], "false")

    def test_env_override_and_unset_default(self):
        # env 설정 시 env 값이 우선하고 source에 env 이름이 붙는다
        with self._root() as d:
            _write(d, "application.yml",
                   "gemini:\n  gateway:\n    enabled: ${GEMINI_GATEWAY_ENABLED:true}\n")
            rep1 = g.probe(d, ["local"], ["gemini.gateway.enabled"],
                           env_reader=lambda n: (None, None))
            rep2 = g.probe(d, ["local"], ["gemini.gateway.enabled"],
                           env_reader=lambda n: ("false", "env:GEMINI_GATEWAY_ENABLED(User)") if n == "GEMINI_GATEWAY_ENABLED" else (None, None))
        self.assertEqual(rep1["keys"]["gemini.gateway.enabled"]["effective"], "true")
        e2 = rep2["keys"]["gemini.gateway.enabled"]
        self.assertEqual(e2["effective"], "false")
        self.assertIn("env GEMINI_GATEWAY_ENABLED", e2["source"])

    def test_secret_named_env_is_masked(self):
        # KEY/TOKEN/SECRET/PASSWORD 이름은 present/absent만 보고하고 effective도 가린다
        with self._root() as d:
            _write(d, "application.yml",
                   "x:\n  y:\n    z: ${DEMO_SECRET_TOKEN:default-v}\n")
            rep = g.probe(d, ["local"], ["x.y.z"],
                          env_reader=lambda n: ("SENSITIVE-VALUE", "env:DEMO_SECRET_TOKEN(User)") if n == "DEMO_SECRET_TOKEN" else (None, None))
        e = rep["keys"]["x.y.z"]
        self.assertEqual(e["env"]["DEMO_SECRET_TOKEN"]["value"], "<masked:present>")
        self.assertNotIn("SENSITIVE-VALUE", e["effective"])
        self.assertIn("masked", e["effective"])

    def test_code_default_when_key_undefined(self):
        # 로딩된 파일에 정의가 없는 키는 코드 기본값 출처로 표시된다
        with self._root() as d:
            _write(d, "application.properties", "app.ai.x=1\n")
            rep = g.probe(d, ["local", "meta-display"], ["llmrouter.api-first.enabled"],
                          env_reader=lambda n: (None, None))
        e = rep["keys"]["llmrouter.api-first.enabled"]
        self.assertEqual(e["effective"], "false")
        self.assertTrue(e["source"].startswith("java-default:"))

    def test_nested_placeholder_chain(self):
        # ${A:${B:x}} 중첩 체인 해석 — B unset이면 최종 x
        with self._root() as d:
            _write(d, "application.yml",
                   "m:\n  n: ${OUTER:${INNER_NAME:deep-value}}\n")
            rep = g.probe(d, ["local"], ["m.n"], env_reader=lambda n: (None, None))
        self.assertEqual(rep["keys"]["m.n"]["effective"], "deep-value")

    def test_yaml_subset_nested_map_and_comments(self):
        # 들여쓰기 중첩 + 인라인 주석 + 따옴표 스칼라
        with self._root() as d:
            _write(d, "application-meta-display.yml",
                   "llmrouter:\n  models:\n    gemini-pro:   # inline\n      enabled: ${E1:true}\n      name: 'q-name'\n")
            rep = g.probe(d, ["meta-display"],
                          ["llmrouter.models.gemini-pro.enabled", "llmrouter.models.gemini-pro.name"],
                          env_reader=lambda n: (None, None))
        self.assertEqual(rep["keys"]["llmrouter.models.gemini-pro.enabled"]["effective"], "true")
        self.assertEqual(rep["keys"]["llmrouter.models.gemini-pro.name"]["effective"], "q-name")

    def test_env_snapshot_allowlist_names_only(self):
        # env 스냅샷은 체인에 나온 이름만 사용한다
        with self._root() as d:
            _write(d, "application.yml", "a:\n  b: ${FROM_SNAP:unset-v}\n")
            rep = g.probe(d, ["local"], ["a.b"],
                          env_snapshot={"FROM_SNAP": "snap-v", "UNRELATED": "x"},
                          env_reader=lambda n: (None, None))
        self.assertEqual(rep["keys"]["a.b"]["effective"], "snap-v")


if __name__ == "__main__":
    unittest.main()
