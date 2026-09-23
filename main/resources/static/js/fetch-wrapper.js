function hashHeaderValue(value) {
  const text = String(value || "");
  let hash = 0;
  for (let i = 0; i < text.length; i += 1) {
    hash = ((hash << 5) - hash + text.charCodeAt(i)) | 0;
  }
  return text ? `h${Math.abs(hash)}` : "";
}

function safeDebugUrl(url) {
  try {
    const parsed = new URL(url, window.location.origin);
    return { path: parsed.pathname, hash: hashHeaderValue(parsed.href) };
  } catch {
    return { path: "[path]", hash: "" };
  }
}

function responseDebugMeta(res, url) {
  const pick = (name) => res.headers.get(name) || "";
  const debugUrl = safeDebugUrl(url);
  if (typeof console !== "undefined" && typeof console.debug === "function") {
    console.debug(`[debug][http] ${res.status} ${debugUrl.path}`);
  }
  return {
    requestIdHash: hashHeaderValue(pick('X-Request-Id')),
    urlPath: debugUrl.path,
    urlHash: debugUrl.hash
  };
}
