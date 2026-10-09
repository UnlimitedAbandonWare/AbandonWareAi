"""Existing NAVER probe: mirror runtime auto/openapi/apihub selection, one HTTP attempt."""
import csv
import json
import os
from urllib.parse import urlsplit

try:
    from .. import common
except ImportError:
    import common

# NAVER Search v1 / API HUB Search v1 official contracts checked 2026-10-08.
SPEC = {
    "name": "naver",
    "key_envs": ["NAVER_APIHUB_CLIENT_ID", "NAVER_APIHUB_CLIENT_SECRET",
                 "NAVER_CLIENT_ID", "NAVER_CLIENT_SECRET", "NAVER_KEYS"],
    "hosts": ["naverapihub.apigw.ntruss.com", "openapi.naver.com"],
    "fix_url": "https://console.ncloud.com/",
    "doc_url": "https://api.ncloud-docs.com/docs/naver-api-hub-search-webkr",
    "doc_checked": "2026-10-08",
    "code_paths": [("errorCode",), ("errorMessage",)],
    "code_map": {"SE01": common.KEY_INVALID, "SE02": common.BAD_SHAPE,
                 "SE03": common.BAD_SHAPE, "SE04": common.RATE_LIMIT,
                 "SE05": common.BAD_SHAPE, "SE06": common.BAD_SHAPE, "SE99": common.SERVER_5XX},
}
APIHUB_BASE_URL = "https://naverapihub.apigw.ntruss.com"
OPENAPI_BASE_URL = "https://openapi.naver.com"
_PLACEHOLDERS = {"__missing__", "dummy", "dummy-key", "null", "test", "changeme", "change-me",
                 "none", "n/a", "na", "todo", "tbd", "placeholder", "ollama", "sk-local"}


def _present(value):
    text = str(value or "").strip()
    lower = text.lower()
    return bool(text) and lower not in _PLACEHOLDERS and not (
        ("${" in text and "}" in text) or lower.startswith(("change_me", "sk-local"))
        or (text.startswith("<") and text.endswith(">")))


def _pair(resolve, prefix):
    parts = [resolve([prefix + suffix]) for suffix in ("CLIENT_ID", "CLIENT_SECRET")]
    pair = tuple(str(part["value"]).strip() if _present(part["value"]) else None for part in parts)
    ki = dict(parts[0], env=prefix + "CLIENT_ID+SECRET", value=pair,
              len=sum(len(v or "") for v in pair),
              sha8="/".join(part["sha8"] or "-" for part in parts),
              mismatch=any(part["mismatch"] for part in parts),
              srcs=sorted({s for part in parts for s in part["srcs"]}),
              src=parts[0]["src"] or parts[1]["src"])
    return ki


def _csv_pairs(raw):
    try: tokens = next(csv.reader([raw or ""]), [])
    except csv.Error: return []
    pairs, bare = [], []
    for token in tokens:
        token = token.strip().strip("\"'").strip().replace(";", ":")
        if not token:
            continue
        separator = ":" if ":" in token else "," if "," in token else None
        if separator:
            left, right = token.split(separator, 1)
            if _present(left) and _present(right): pairs.append((left.strip(), right.strip()))
        else:
            # Consume original positions in pairs, including missing placeholders.
            bare.append(token)
    for index in range(0, len(bare) - 1, 2):
        left, right = bare[index:index + 2]
        if _present(left) and _present(right): pairs.append((left, right))
    return pairs


def _selection(resolve):
    """Private value-bearing selection; only explicit metadata leaves either probe."""
    mode = resolve(["NAVER_SEARCH_PROVIDER"])["value"]
    if mode is None:
        mode = os.environ.get("NAVER_SEARCH_PROVIDER", "auto")
    mode = mode.strip().lower()
    hub = _pair(resolve, "NAVER_APIHUB_")
    active = "apihub" if mode == "apihub" or (mode == "auto" and all(hub["value"])) else "openapi"
    ki = hub if active == "apihub" else _pair(resolve, "NAVER_")
    reason = None
    if active == "openapi":
        csv_key = resolve(["NAVER_KEYS"])
        pairs = _csv_pairs(csv_key["value"]) if _present(csv_key["value"]) else []
        if _present(csv_key["value"]):
            # Alias admission is independent of successful CSV parsing, as in the runtime resolver.
            conflicting_pair = all(ki["value"]) and str(csv_key["value"]).strip() != ":".join(ki["value"])
            ki = dict(csv_key, value=pairs[0] if pairs else ki["value"],
                      mismatch=csv_key["mismatch"] or (ki["mismatch"] and (not pairs or all(ki["value"]))) or conflicting_pair)
    if mode not in ("auto", "openapi", "apihub"):
        reason = "invalid_naver_search_provider"
    elif ki["mismatch"]:
        reason = "conflicting-credential-aliases"
    elif not all(ki["value"]):
        reason = "missing_naver_apihub_credentials" if active == "apihub" else "missing_naver_client_credentials"
    base_name = "NAVER_APIHUB_BASE_URL" if active == "apihub" else "NAVER_SEARCH_API_BASE_URL"
    base = (resolve([base_name])["value"] or os.environ.get(base_name)
            or (APIHUB_BASE_URL if active == "apihub" else OPENAPI_BASE_URL)).strip().rstrip("/")
    try:
        uri = urlsplit(base)
        valid = uri.scheme in ("http", "https") and uri.hostname and not uri.username and not uri.query and not uri.fragment
        _ = uri.port
    except ValueError:
        valid = False
    if not valid and reason is None: reason = "invalid_naver_search_endpoint"
    metadata = {"provider_mode": mode if mode in ("auto", "openapi", "apihub") else "invalid",
                "selected_provider": active}
    app = resolve(["NAVER_APIHUB_APP_NAME"])["value"]
    if app: metadata["app_name"] = str(app).strip()[:160]
    if reason: metadata["disabledReason"] = reason
    cid, secret = ki["value"]
    headers = {"X-NCP-APIGW-API-KEY-ID": cid, "X-NCP-APIGW-API-KEY": secret} if active == "apihub" else {
        "X-Naver-Client-Id": cid, "X-Naver-Client-Secret": secret}
    path = "/search/v1/webkr" if active == "apihub" else "/v1/search/webkr.json"
    url = base + path + "?query=test&display=1&start=1" + ("&format=json" if active == "apihub" else "")
    return ki, metadata, url, headers


def check(ctx):
    def resolve(names):
        # Missing placeholders are absent, matching ConfigValueGuards before alias conflict checks.
        scopes = {s: {n: v for n, v in values.items() if n in names and _present(v)}
                  for s, values in ctx["scopes"].items()}
        secrets = {n: v for n, v in ctx["secrets"].items() if n in names and _present(v)}
        ki = common.resolve_key(names, secrets, scopes)
        if names == ["NAVER_SEARCH_PROVIDER"]:
            # Settings preserve explicit blank/placeholder values and source precedence.
            sources = [ctx["scopes"].get(scope, {}) for scope in ("Process", "User", "Machine")] + [ctx["secrets"]]
            mode = next((source[names[0]] for source in sources if source.get(names[0]) is not None), None)
            if mode is not None:
                ki["value"] = str(mode)
        return ki
    ki, metadata, url, headers = _selection(resolve)
    reason = metadata.get("disabledReason")
    if reason:
        cls = common.KEY_MISMATCH if reason == "conflicting-credential-aliases" else common.BAD_SHAPE if reason.startswith("invalid_") else common.KEY_MISSING
        return [common.scrub_result(dict(common.key_row(SPEC, ki, step="search", cls=cls, detail=reason), **metadata), ki["value"])]
    result = common.http_request("GET", url, headers, timeout=ctx["timeout"])
    cls, code, detail = common.classify(SPEC, result)
    if cls == common.OK:
        try: total = json.loads(result["text"]).get("total")
        except (ValueError, AttributeError): total = None
        detail = "total=%s" % total
    row = dict(common.key_row(SPEC, ki, step="search", cls=cls, detail=detail, ms=result.get("ms")), **metadata)
    row["http"], row["code"] = result.get("status"), code
    return [common.scrub_result(row, ki["value"])]
