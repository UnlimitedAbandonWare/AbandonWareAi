"""Naver Search — free check GET /v1/search/webkr.json (display=1). Needs
X-Naver-Client-Id + X-Naver-Client-Secret (two env names, both resolved).
NAVER_KEYS (CSV of id:secret pairs, per NaverSearchService) is used as a
fallback when the client pair is absent — first parsed pair is probed."""
import json

try:
    from .. import common
except ImportError:
    import common

# https://developers.naver.com/docs/serviceapi/search/blog/blog.md — verified 2026-09-29
SPEC = {
    "name": "naver",
    "key_envs": ["NAVER_CLIENT_ID", "NAVER_CLIENT_SECRET", "NAVER_KEYS"],
    "hosts": ["openapi.naver.com"],
    "fix_url": "https://developers.naver.com/apps/#/myapps",
    "doc_url": "https://developers.naver.com/docs/serviceapi/search/blog/blog.md",
    "doc_checked": "2026-09-29",
    "code_paths": [("errorCode",), ("errorMessage",)],
    "code_map": {
        "SE01": common.KEY_INVALID,
        "SE02": common.BAD_SHAPE,
        "SE03": common.BAD_SHAPE,
        "SE04": common.RATE_LIMIT,
        "SE05": common.BAD_SHAPE,
        "SE06": common.BAD_SHAPE,
        "SE99": common.SERVER_5XX,
    },
}


def _merged_ki(ctx):
    """One display row combining ID+Secret presence."""
    id_ki = common.resolve_key(["NAVER_CLIENT_ID"], ctx["secrets"], ctx["scopes"])
    pw_ki = common.resolve_key(["NAVER_CLIENT_SECRET"], ctx["secrets"], ctx["scopes"])
    ki = dict(id_ki)
    ki["env"] = "NAVER_CLIENT_ID+SECRET"
    ki["value"] = (id_ki["value"], pw_ki["value"])
    ki["len"] = id_ki["len"] + pw_ki["len"]
    ki["sha8"] = "%s/%s" % (id_ki["sha8"] or "-", pw_ki["sha8"] or "-")
    ki["mismatch"] = id_ki["mismatch"] or pw_ki["mismatch"]
    ki["srcs"] = sorted(set(id_ki["srcs"]) | set(pw_ki["srcs"]))
    ki["src"] = id_ki["src"] or pw_ki["src"]
    return ki


def _parse_naver_keys(raw):
    """NAVER_KEYS CSV -> (client_id, client_secret, pair_count). Tokens are
    'id:secret' or 'id;secret'; remaining bare tokens pair positionally
    (id,secret) like NaverSearchService's splitCsv fallback."""
    if not raw:
        return None, None, 0
    pairs, bare = [], []
    for tok in (t.strip() for t in raw.split(",")):
        if not tok:
            continue
        hit = False
        for sep in (":", ";"):
            if sep in tok:
                a, _, b = tok.partition(sep)
                if a.strip() and b.strip():
                    pairs.append((a.strip(), b.strip()))
                hit = True
                break
        if not hit:
            bare.append(tok)
    for i in range(0, len(bare) - 1, 2):
        pairs.append((bare[i], bare[i + 1]))
    if not pairs:
        return None, None, 0
    return pairs[0][0], pairs[0][1], len(pairs)


def check(ctx):
    ki = _merged_ki(ctx)
    rows = []
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    cid, csecret = ki["value"]
    if not (cid and csecret):
        csv_ki = common.resolve_key(["NAVER_KEYS"], ctx["secrets"], ctx["scopes"])
        cid, csecret, npairs = _parse_naver_keys(csv_ki["value"])
        if cid and csecret:
            ki["value"] = (cid, csecret)
            ki["env"] = "NAVER_KEYS"
            ki["src"], ki["srcs"] = csv_ki["src"], csv_ki["srcs"]
            ki["len"], ki["sha8"] = csv_ki["len"], csv_ki["sha8"]
            ki["mismatch"] = csv_ki["mismatch"]
    if not (cid and csecret):
        rows.append(common.key_row(SPEC, ki, step="search", cls=common.KEY_MISSING,
                                   detail="need NAVER_CLIENT_ID + NAVER_CLIENT_SECRET or NAVER_KEYS csv"))
        return rows
    result = common.http_request(
        "GET", "https://openapi.naver.com/v1/search/webkr.json?query=test&display=1",
        {"X-Naver-Client-Id": cid, "X-Naver-Client-Secret": csecret},
        timeout=ctx["timeout"])
    cls, code, detail = common.classify(SPEC, result)
    if cls == common.OK:
        try:
            total = (json.loads(result["text"])).get("total")
        except (ValueError, AttributeError):
            total = None
        detail = "total=%s" % total
    row = common.key_row(SPEC, ki, step="search", cls=cls, detail=detail,
                         ms=result.get("ms"))
    row["http"] = result.get("status")
    row["code"] = code
    rows.append(row)
    return rows
