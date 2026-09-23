"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { debugSignalMessagePatch } from "@/lib/chat-debug-signal";
import { readJsonOrText } from "@/lib/http-result";

const MODES = ["balanced", "fact", "creative"];

function requestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `req-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function parseSseFrames(buffer) {
  const frames = buffer.split("\n\n");
  return { complete: frames.slice(0, -1), rest: frames.at(-1) || "" };
}

function eventFromFrame(frame) {
  let eventType = "message";
  const data = [];
  for (const line of frame.split("\n")) {
    if (line.startsWith("event:")) eventType = line.slice(6).trim() || eventType;
    if (line.startsWith("data:")) data.push(line.slice(5).trim());
  }
  const raw = data.join("\n");
  if (!raw) return { type: eventType, data: "" };
  try {
    const parsed = JSON.parse(raw);
    return { ...parsed, type: parsed.type || eventType };
  } catch {
    return { type: eventType, data: raw };
  }
}

export default function ChatPage() {
  const [messages, setMessages] = useState([]);
  const [draft, setDraft] = useState("");
  const [mode, setMode] = useState("balanced");
  const [useRag, setUseRag] = useState(true);
  const [useWebSearch, setUseWebSearch] = useState(false);
  const [status, setStatus] = useState("idle");
  const [sessionId, setSessionId] = useState("");
  const [request, setRequest] = useState("");
  const [modelUsed, setModelUsed] = useState("");
  const [ragUsed, setRagUsed] = useState(false);
  const [evidence, setEvidence] = useState([]);
  const [sessions, setSessions] = useState([]);
  const [ragQuery, setRagQuery] = useState("");
  const [ragResult, setRagResult] = useState(null);
  const [ragMode, setRagMode] = useState("query");
  const controllerRef = useRef(null);

  const canSend = draft.trim().length > 0 && status !== "streaming";
  const eventCount = useMemo(() => messages.filter((message) => message.role === "assistant").length, [messages]);

  useEffect(() => {
    void refreshSessions();
  }, []);

  async function refreshSessions() {
    try {
      const response = await fetch("/api/chat/sessions", { cache: "no-store" });
      if (!response.ok) throw new Error(`sessions_${response.status}`);
      const body = await response.json();
      setSessions(Array.isArray(body) ? body.slice(0, 12) : []);
    } catch {
      setSessions([]);
    }
  }

  function applyStreamEvent(event, assistantId) {
    if (event.type === "token" || event.type === "message") {
      setMessages((items) => items.map((item) => (
        item.id === assistantId ? { ...item, content: `${item.content}${event.data || ""}` } : item
      )));
    } else if (event.type === "status") {
      setStatus(event.statusSignal?.code || event.data || "streaming");
    } else if (event.type === "session" && event.sessionId != null) {
      setSessionId(String(event.sessionId));
    } else if (event.type === "evidence") {
      setEvidence(Array.isArray(event.evidence) ? event.evidence : []);
    } else if (event.type === "debug_fx" || event.type === "trace" || event.type === "scoreDelta") {
      setMessages((items) => items.map((item) => (
        item.id === assistantId
          ? { ...item, ...debugSignalMessagePatch(item, event) }
          : item
      )));
    } else if (event.type === "final") {
      setStatus("done");
      if (event.sessionId != null) setSessionId(String(event.sessionId));
      setModelUsed(event.modelUsed || "");
      setRagUsed(Boolean(event.ragUsed));
      setEvidence(Array.isArray(event.evidence) ? event.evidence : []);
    } else if (event.type === "error") {
      setStatus(event.data || "error");
    }
  }

  async function sendMessage(formEvent) {
    formEvent?.preventDefault();
    if (!canSend) return;

    const text = draft.trim();
    const nextRequestId = requestId();
    const assistantId = `assistant-${nextRequestId}`;
    const headers = {
      "content-type": "application/json",
      "accept": "text/event-stream",
      "x-request-id": nextRequestId
    };
    if (sessionId) headers["x-session-id"] = sessionId;

    setDraft("");
    setRequest(nextRequestId);
    setStatus("streaming");
    setEvidence([]);
    setMessages((items) => [
      ...items,
      { id: `user-${nextRequestId}`, role: "user", content: text },
      { id: assistantId, role: "assistant", content: "" }
    ]);

    controllerRef.current = new AbortController();
    let buffer = "";
    try {
      const response = await fetch("/api/chat/stream", {
        method: "POST",
        headers,
        body: JSON.stringify({
          message: text,
          mode,
          sessionId: sessionId || undefined,
          useRag,
          useWebSearch
        }),
        signal: controllerRef.current.signal
      });
      if (!response.ok || !response.body) throw new Error(`stream_${response.status}`);
      const responseSessionId = response.headers.get("x-session-id");
      if (responseSessionId) setSessionId(responseSessionId);

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const next = await reader.read();
        if (next.done) break;
        buffer += decoder.decode(next.value, { stream: true });
        const parsed = parseSseFrames(buffer);
        buffer = parsed.rest;
        for (const frame of parsed.complete) {
          applyStreamEvent(eventFromFrame(frame), assistantId);
        }
      }
      setStatus((current) => current === "streaming" ? "done" : current);
      void refreshSessions();
    } catch (error) {
      if (error?.name === "AbortError") {
        setStatus("stopped");
      } else {
        setStatus(error?.message || "stream_error");
      }
    } finally {
      controllerRef.current = null;
    }
  }

  async function stopStream() {
    controllerRef.current?.abort();
    if (!sessionId) {
      setStatus("stopped");
      return;
    }
    try {
      await fetch("/api/chat/cancel", {
        method: "POST",
        headers: { "content-type": "application/json", "x-session-id": sessionId },
        body: JSON.stringify({ sessionId })
      });
      setStatus("cancelled");
    } catch {
      setStatus("local_stop");
    }
  }

  async function runRagProbe(event) {
    event.preventDefault();
    if (!ragQuery.trim()) return;
    setRagResult({ status: "loading" });
    const target = ragMode === "probe" ? "/api/rag/probe" : "/api/rag/query";
    const body = ragMode === "probe" ? { q: ragQuery.trim() } : { query: ragQuery.trim(), enableSelfAsk: true };
    try {
      const response = await fetch(target, {
        method: "POST",
        headers: { "content-type": "application/json", "x-request-id": requestId() },
        body: JSON.stringify(body)
      });
      const payload = await readJsonOrText(response);
      const responseStatus = response.ok && payload.kind === "json" ? "ok" : response.ok ? "non_json" : `http_${response.status}`;
      setRagResult({ status: responseStatus, payload });
    } catch (error) {
      setRagResult({ status: error?.message || "rag_error" });
    }
  }

  return (
    <main className="shell">
      <aside className="rail">
        <div className="brand">
          <span className="brandMark">D1</span>
          <strong>RAG Console</strong>
        </div>
        <div className="statGrid">
          <span>Status</span><strong>{status}</strong>
          <span>Session</span><strong>{sessionId || "-"}</strong>
          <span>Request</span><strong>{request ? request.slice(0, 8) : "-"}</strong>
          <span>Model</span><strong>{modelUsed || "-"}</strong>
          <span>RAG</span><strong>{ragUsed ? "on" : "pending"}</strong>
          <span>Evidence</span><strong>{evidence.length}</strong>
        </div>
        <div className="sessionList" aria-label="Sessions">
          {sessions.length === 0 ? <span className="muted">No sessions</span> : sessions.map((session) => (
            <button key={session.id} type="button" onClick={() => setSessionId(String(session.id))}>
              <span>{session.title || `Session ${session.id}`}</span>
              <small>{session.answerMode || session.lastAnswerMode || "-"}</small>
            </button>
          ))}
        </div>
      </aside>

      <section className="workspace">
        <header className="toolbar">
          <div className="segmented" aria-label="Answer mode">
            {MODES.map((item) => (
              <button
                key={item}
                type="button"
                className={mode === item ? "active" : ""}
                onClick={() => setMode(item)}
              >
                {item}
              </button>
            ))}
          </div>
          <label className="toggle">
            <input type="checkbox" checked={useRag} onChange={(event) => setUseRag(event.target.checked)} />
            <span>RAG</span>
          </label>
          <label className="toggle">
            <input type="checkbox" checked={useWebSearch} onChange={(event) => setUseWebSearch(event.target.checked)} />
            <span>Web</span>
          </label>
        </header>

        <div className="chatSurface" aria-live="polite">
          {messages.length === 0 ? (
            <div className="emptyState">
              <strong>Ready</strong>
              <span>Spring backend: {status}</span>
            </div>
          ) : messages.map((message) => (
            <article key={message.id} className={`message ${message.role}`}>
              <span>{message.role}</span>
              <p>{message.content || (message.role === "assistant" ? "..." : "")}</p>
              {message.signal ? <small>{message.signal}</small> : null}
              {message.debugSummary ? <small className="debugSummary">{message.debugSummary}</small> : null}
            </article>
          ))}
        </div>

        <form className="composer" onSubmit={sendMessage}>
          <textarea
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) void sendMessage(event);
            }}
            rows={3}
            placeholder="Message"
          />
          <div className="composerActions">
            <button type="button" className="secondary" onClick={stopStream} disabled={status !== "streaming"}>Stop</button>
            <button type="submit" disabled={!canSend}>Send</button>
          </div>
        </form>
      </section>

      <aside className="ragPane">
        <form onSubmit={runRagProbe}>
          <div className="segmented compact" aria-label="RAG endpoint">
            <button type="button" className={ragMode === "query" ? "active" : ""} onClick={() => setRagMode("query")}>query</button>
            <button type="button" className={ragMode === "probe" ? "active" : ""} onClick={() => setRagMode("probe")}>probe</button>
          </div>
          <textarea value={ragQuery} onChange={(event) => setRagQuery(event.target.value)} rows={4} placeholder="RAG query" />
          <button type="submit">Run</button>
        </form>
        <pre>{ragResult ? JSON.stringify(ragResult, null, 2) : "[]"}</pre>
        <div className="evidenceList">
          {evidence.slice(0, 6).map((item, index) => (
            <div key={`${item.title || item.url || index}`} className="evidenceItem">
              <strong>{item.title || item.source || `Evidence ${index + 1}`}</strong>
              <span>{item.url || item.provider || item.score || "-"}</span>
            </div>
          ))}
        </div>
      </aside>
    </main>
  );
}
