const DEFAULT_BACKEND_URL = "http://127.0.0.1:8080";
const DEFAULT_NON_STREAM_TIMEOUT_MS = 15000;
const EXPOSED_HEADERS = [
  "x-session-id",
  "x-request-id",
  "x-model-used",
  "x-rag-used",
  "x-trace-snapshot-id"
];

const FORWARD_HEADERS = [
  "accept",
  "accept-language",
  "content-type",
  "cookie",
  "x-conversation-id",
  "x-csrf-token",
  "x-request-id",
  "x-session-id",
  "x-xsrf-token"
];

export function backendUrl(path, search = "") {
  const rawBase = (process.env.RAG_BACKEND_URL || DEFAULT_BACKEND_URL).trim();
  let base = rawBase.replace(/\/+$/, "");
  try {
    const parsed = new URL(base);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      base = DEFAULT_BACKEND_URL;
    }
  } catch {
    base = DEFAULT_BACKEND_URL;
  }

  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  const normalizedSearch = search && !search.startsWith("?") ? `?${search}` : search;
  return new URL(`${normalizedPath}${normalizedSearch || ""}`, `${base}/`);
}

export function buildForwardHeaders(request) {
  const headers = new Headers();
  for (const name of FORWARD_HEADERS) {
    const value = request.headers.get(name);
    if (value != null && value !== "") headers.set(name, value);
  }
  if (!headers.has("x-request-id")) {
    headers.set("x-request-id", makeRequestId());
  }
  return headers;
}

export function responseRelayHeaders(upstream, requestHeaders) {
  const headers = new Headers();
  headers.set("content-type", upstream.headers.get("content-type") || "application/json");
  headers.set("cache-control", "no-store");
  headers.set("access-control-expose-headers", EXPOSED_HEADERS.join(", "));
  const location = upstream.headers.get("location");
  if (location) headers.set("location", location);

  for (const name of EXPOSED_HEADERS) {
    const upstreamValue = upstream.headers.get(name);
    const requestValue = requestHeaders.get(name);
    const value = upstreamValue || requestValue;
    if (value) headers.set(name, value);
  }
  return headers;
}

export async function relayToBackend(request, backendPath, options = {}) {
  const method = options.method || request.method;
  const search = options.search ?? new URL(request.url).search;
  const forwardHeaders = buildForwardHeaders(request);
  const fetchImpl = options.fetchImpl || fetch;
  const stream = wantsEventStream(request, backendPath);
  const timeoutMs = resolveTimeoutMs(options.timeoutMs, stream);
  const controller = timeoutMs > 0 ? new AbortController() : null;
  const init = {
    method,
    headers: forwardHeaders,
    cache: "no-store",
    redirect: "manual"
  };
  if (controller) init.signal = controller.signal;

  if (method !== "GET" && method !== "HEAD") {
    init.body = await request.text();
  }

  const timeoutId = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
  try {
    const upstream = await fetchImpl(backendUrl(backendPath, search), init);
    if (isRedirect(upstream.status)) {
      return upstreamRedirectResponse(backendPath, upstream, forwardHeaders, stream);
    }
    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseRelayHeaders(upstream, forwardHeaders)
    });
  } catch (error) {
    if (isAbortError(error)) {
      return backendTimeoutResponse(backendPath, forwardHeaders, stream);
    }
    return backendUnavailableResponse(backendPath, forwardHeaders, stream);
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
  }
}

function resolveTimeoutMs(value, stream) {
  if (value != null) return Math.max(0, Number(value) || 0);
  if (stream) return 0;
  const envValue = Number(process.env.RAG_BACKEND_TIMEOUT_MS);
  return Number.isFinite(envValue) && envValue > 0 ? envValue : DEFAULT_NON_STREAM_TIMEOUT_MS;
}

function isAbortError(error) {
  return error?.name === "AbortError";
}

function isRedirect(status) {
  return status >= 300 && status < 400;
}

function upstreamRedirectResponse(backendPath, upstream, requestHeaders, stream) {
  const body = {
    error: "upstream_redirect",
    backendPath,
    redirectLocation: safeRedirectLocation(upstream.headers.get("location")),
    retryable: false
  };
  const headers = new Headers({
    "cache-control": "no-store",
    "x-request-id": requestHeaders.get("x-request-id") || makeRequestId()
  });

  if (stream) {
    headers.set("content-type", "text/event-stream");
    return new Response(`event:error\ndata:${JSON.stringify({ type: "error", data: body.error })}\n\n`, {
      status: 502,
      headers
    });
  }

  headers.set("content-type", "application/json");
  return Response.json(body, {
    status: 502,
    headers
  });
}

function makeRequestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (part) => {
    const value = Math.floor(Math.random() * 16);
    const nibble = part === "x" ? value : (value & 0x3) | 0x8;
    return nibble.toString(16);
  });
}

function backendUnavailableResponse(backendPath, requestHeaders, stream) {
  const requestId = requestHeaders.get("x-request-id");
  const body = {
    error: "backend_unavailable",
    backendPath,
    retryable: true
  };
  const headers = new Headers({
    "cache-control": "no-store",
    "x-request-id": requestId || makeRequestId()
  });

  if (stream) {
    headers.set("content-type", "text/event-stream");
    return new Response(`event:error\ndata:${JSON.stringify({ type: "error", data: body.error })}\n\n`, {
      status: 503,
      headers
    });
  }

  headers.set("content-type", "application/json");
  return Response.json(body, {
    status: 503,
    headers
  });
}

function backendTimeoutResponse(backendPath, requestHeaders, stream) {
  const body = {
    error: "backend_timeout",
    backendPath,
    retryable: true
  };
  const headers = new Headers({
    "cache-control": "no-store",
    "x-request-id": requestHeaders.get("x-request-id") || makeRequestId()
  });

  if (stream) {
    headers.set("content-type", "text/event-stream");
    return new Response(`event:error\ndata:${JSON.stringify({ type: "error", data: body.error })}\n\n`, {
      status: 504,
      headers
    });
  }

  headers.set("content-type", "application/json");
  return Response.json(body, {
    status: 504,
    headers
  });
}

function wantsEventStream(request, backendPath) {
  const accept = request.headers.get("accept") || "";
  return accept.includes("text/event-stream") || backendPath.endsWith("/stream");
}

function safeRedirectLocation(location) {
  if (!location) return "";
  try {
    const parsed = new URL(location, DEFAULT_BACKEND_URL);
    return `${parsed.pathname}${parsed.search}`;
  } catch {
    return "";
  }
}
