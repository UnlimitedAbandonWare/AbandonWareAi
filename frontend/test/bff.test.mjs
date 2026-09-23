import assert from "node:assert/strict";
import test from "node:test";

import {
  backendUrl,
  buildForwardHeaders,
  relayToBackend,
  responseRelayHeaders
} from "../src/lib/bff.js";
import { readJsonOrText } from "../src/lib/http-result.js";

test("backendUrl uses RAG_BACKEND_URL and preserves path plus search", () => {
  process.env.RAG_BACKEND_URL = "http://spring.local:8080///";

  const url = backendUrl("/api/chat/stream", "?debug=true&attach=false");

  assert.equal(url.toString(), "http://spring.local:8080/api/chat/stream?debug=true&attach=false");
});

test("buildForwardHeaders preserves correlation headers and strips browser Authorization", () => {
  const request = new Request("http://127.0.0.1:3000/api/chat/stream", {
    method: "POST",
    headers: {
      "accept": "text/event-stream",
      "authorization": "Bearer should-not-forward",
      "content-type": "application/json",
      "cookie": "ownerKey=guest",
      "x-csrf-token": "csrf-token",
      "x-request-id": "req-123",
      "x-session-id": "42"
    },
    body: JSON.stringify({ message: "hello", sessionId: 42 })
  });

  const headers = buildForwardHeaders(request);

  assert.equal(headers.get("accept"), "text/event-stream");
  assert.equal(headers.get("content-type"), "application/json");
  assert.equal(headers.get("cookie"), "ownerKey=guest");
  assert.equal(headers.get("x-csrf-token"), "csrf-token");
  assert.equal(headers.get("x-request-id"), "req-123");
  assert.equal(headers.get("x-session-id"), "42");
  assert.equal(headers.has("authorization"), false);
});

test("buildForwardHeaders generates request id but does not fabricate a session id", () => {
  const request = new Request("http://127.0.0.1:3000/api/rag/query", {
    method: "POST",
    headers: {
      "content-type": "application/json"
    },
    body: JSON.stringify({ query: "ping" })
  });

  const headers = buildForwardHeaders(request);

  assert.match(headers.get("x-request-id"), /^[0-9a-f-]{36}$/i);
  assert.equal(headers.has("x-session-id"), false);
});

test("responseRelayHeaders exposes backend session and request identifiers", () => {
  const upstream = new Response("{}", {
    headers: {
      "content-type": "application/json",
      "x-request-id": "backend-req",
      "x-session-id": "77"
    }
  });
  const requestHeaders = new Headers({ "x-request-id": "client-req" });

  const headers = responseRelayHeaders(upstream, requestHeaders);

  assert.equal(headers.get("content-type"), "application/json");
  assert.equal(headers.get("cache-control"), "no-store");
  assert.equal(headers.get("x-request-id"), "backend-req");
  assert.equal(headers.get("x-session-id"), "77");
  assert.match(headers.get("access-control-expose-headers"), /x-session-id/i);
});

test("responseRelayHeaders preserves upstream redirect location", () => {
  const upstream = new Response("", {
    status: 302,
    headers: { location: "/login" }
  });

  const headers = responseRelayHeaders(upstream, new Headers());

  assert.equal(headers.get("location"), "/login");
});

test("relayToBackend returns redacted JSON when the backend is unavailable", async () => {
  const request = new Request("http://127.0.0.1:3000/api/rag/query", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-request-id": "req-down"
    },
    body: JSON.stringify({ query: "ping" })
  });

  const response = await relayToBackend(request, "/api/rag/query", {
    fetchImpl: async () => {
      throw new TypeError("connect ECONNREFUSED 127.0.0.1:8080");
    }
  });
  const body = await response.json();

  assert.equal(response.status, 503);
  assert.equal(response.headers.get("content-type"), "application/json");
  assert.equal(response.headers.get("x-request-id"), "req-down");
  assert.deepEqual(body, {
    error: "backend_unavailable",
    backendPath: "/api/rag/query",
    retryable: true
  });
});

test("relayToBackend converts upstream redirects into inspectable JSON", async () => {
  const request = new Request("http://127.0.0.1:3000/api/rag/probe", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-request-id": "req-redirect"
    },
    body: JSON.stringify({ q: "ping" })
  });

  const response = await relayToBackend(request, "/api/rag/probe", {
    fetchImpl: async () => new Response("", {
      status: 302,
      headers: { location: "http://127.0.0.1:8080/login" }
    })
  });
  const body = await response.json();

  assert.equal(response.status, 502);
  assert.equal(response.headers.get("content-type"), "application/json");
  assert.equal(response.headers.get("x-request-id"), "req-redirect");
  assert.deepEqual(body, {
    error: "upstream_redirect",
    backendPath: "/api/rag/probe",
    redirectLocation: "/login",
    retryable: false
  });
});

test("relayToBackend returns redacted timeout JSON for slow non-stream backends", async () => {
  const request = new Request("http://127.0.0.1:3000/api/rag/query", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-request-id": "req-timeout"
    },
    body: JSON.stringify({ query: "ping" })
  });

  const response = await relayToBackend(request, "/api/rag/query", {
    timeoutMs: 1,
    fetchImpl: async (_url, init) => {
      await new Promise((resolve, reject) => {
        init.signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
        setTimeout(resolve, 50);
      });
      return Response.json({ ok: true });
    }
  });
  const body = await response.json();

  assert.equal(response.status, 504);
  assert.equal(response.headers.get("x-request-id"), "req-timeout");
  assert.deepEqual(body, {
    error: "backend_timeout",
    backendPath: "/api/rag/query",
    retryable: true
  });
});

test("relayToBackend returns an SSE error frame for unavailable stream backend", async () => {
  const request = new Request("http://127.0.0.1:3000/api/chat/stream", {
    method: "POST",
    headers: {
      "accept": "text/event-stream",
      "content-type": "application/json",
      "x-request-id": "req-stream-down"
    },
    body: JSON.stringify({ message: "ping" })
  });

  const response = await relayToBackend(request, "/api/chat/stream", {
    fetchImpl: async () => {
      throw new TypeError("connect ECONNREFUSED 127.0.0.1:8080");
    }
  });
  const body = await response.text();

  assert.equal(response.status, 503);
  assert.equal(response.headers.get("content-type"), "text/event-stream");
  assert.equal(response.headers.get("x-request-id"), "req-stream-down");
  assert.match(body, /^event:error/m);
  assert.match(body, /"backend_unavailable"/);
});

test("readJsonOrText keeps non-JSON upstream responses inspectable", async () => {
  const response = new Response("<html>login</html>", {
    status: 200,
    headers: { "content-type": "text/html" }
  });

  const body = await readJsonOrText(response);

  assert.deepEqual(body, {
    kind: "text",
    contentType: "text/html",
    text: "<html>login</html>"
  });
});
