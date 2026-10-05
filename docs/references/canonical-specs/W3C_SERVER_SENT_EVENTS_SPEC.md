---
title: "Server-Sent Events (EventSource) Canonical Specification"
category: "canonical-spec"
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 365
expiresAt: "2027-10-05"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "official_public_documentation"
reviewAfterDays: 365
cadence: "evergreen"
stability: "high"
decayRate: "low"
stabilityReason: "WHATWG HTML living standard + W3C REC — wire format frozen for a decade"
runtimeEnforcement: "unchanged"
canonicalSources:
  - "https://html.spec.whatwg.org/multipage/server-sent-events.html"
  - "https://www.w3.org/TR/eventsource/"
---

# W3C/WHATWG Server-Sent Events (SSE) — canonical spec

## 1. Response envelope

| Header | Required value / behavior |
|---|---|
| `Content-Type` | `text/event-stream;charset=UTF-8` — the only registered SSE MIME type. Missing or different type → client never fires `onmessage`. |
| `Cache-Control` | `no-cache` (and `no-transform` so proxies/CDNs do not buffer or rewrite event payloads). |
| `Transfer-Encoding` | `chunked` — the response never terminates; each event is flushed as produced. |
| `X-Accel-Buffering` | `no` — disables Nginx/Envoy/ingress proxy buffering so events reach the client immediately (non-standard but the de-facto reverse-proxy contract). |
| `Connection` | `keep-alive` (HTTP/1.1). HTTP/2+ needs none — the stream is a long-lived response body. |

## 2. Event stream wire format (invariant)

- The body is a sequence of **events**; each event is a block of field lines
  terminated by a **blank line** (`\n\n` — double newline). `\r\n\r\n` is
  equivalent; lone `\r` is normalized.
- A line starting with `:` is a **comment** — ignored by the parser; the
  standard keep-alive/heartbeat is sending `:` (or `: keep-alive`) on an
  interval to defeat idle timeouts.
- Field lines are `field: value` or `field:value` (one optional leading space
  is stripped). Unknown fields are ignored — forward-compatible extension.

| Field | Semantics |
|---|---|
| `data:` | Payload line. Multiple `data:` lines in one event are concatenated with `\n` between them. |
| `event:` | Named event type → dispatches `addEventListener("<type>")`; absent → `message` → `onmessage`. |
| `id:` | Last-Event-ID; persisted by the client and re-sent as `Last-Event-ID` request header on auto-reconnect — the standard resume cursor. |
| `retry:` | Reconnection delay in milliseconds the client uses after the connection drops. |

- A line with **no colon** is treated as `field:` with an empty value.
- `data:` with an empty value still produces an event with empty data.

## 3. Client contract (EventSource)

- Browser `new EventSource(url)` issues `Accept: text/event-stream` and
  auto-reconnects on drop with `Last-Event-ID` when the server sent `id:`.
- `readyState`: 0 CONNECTING, 1 OPEN, 2 CLOSED. A closed-by-server stream is
  retried unless the response was a non-2xx that ends the source.
- demo-1 anchors: server emitters `main/java/com/abandonware/ai/telemetry/SseEventPublisher.java`,
  `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java`;
  client `main/resources/static/js/chat.js` (`EventSource`),
  `main/resources/static/assets/display/receiver.js`.

## 4. Failure modes this spec pins down

- **No events ever arrive** → check `Content-Type` is exactly `text/event-stream`
  and the endpoint is not returning a normal buffered body.
- **Events arrive in one burst at the end** → proxy/framework buffering; add
  `X-Accel-Buffering: no`, disable response compression for the stream, flush
  after every event.
- **Connection drops at ~30–60 s idle** → send `:` heartbeat comments below the
  shortest idle timeout in the path (browser/proxy/server).
- **Duplicate events after reconnect** → resume semantics live in `id:` /
  `Last-Event-ID`; server must emit ids for dedup to work.
- SSE is **unidirectional** (server→client) and text-only (UTF-8); binary or
  bidirectional needs WebSocket — different spec, not this doc.
