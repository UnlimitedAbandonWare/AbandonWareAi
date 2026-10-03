"""apikit.providers — registry. Loads each provider module; also works when the
package is executed as a plain directory (`python scripts/apikit ...`)."""
import importlib
import importlib.util
import os
import sys

_PKG_DIR = os.path.dirname(os.path.abspath(__file__))
_NAMES = [
    "jev", "openai", "gemini", "ollama",
    "groq", "anthropic", "mistral", "openrouter", "zai", "opencode",
    "cerebras",
    "brave", "tavily", "serpapi", "naver", "kakao",
    "deepgram", "soniox", "pinecone", "upstash",
]

PROVIDERS = {}
for _name in _NAMES:
    _mod = None
    try:
        _mod = importlib.import_module("." + _name, __package__)
    except (ImportError, TypeError):
        _common_dir = os.path.dirname(_PKG_DIR)
        if _common_dir not in sys.path:
            sys.path.insert(0, _common_dir)
        _spec = importlib.util.spec_from_file_location(
            "apikit_provider_" + _name, os.path.join(_PKG_DIR, _name + ".py"))
        _mod = importlib.util.module_from_spec(_spec)
        _spec.loader.exec_module(_mod)
    PROVIDERS[_name] = _mod
