import assert from "node:assert/strict";
import test from "node:test";

import * as chatDebugSignal from "../src/lib/chat-debug-signal.js";

const { debugFxLabels, debugFxSignal, debugFxSummary } = chatDebugSignal;

test("debug_fx labels preserve Supabase status", () => {
  const signal = debugFxSignal({
    type: "debug_fx",
    debugFxSignal: {
      labels: {
        supabaseStatus: "WARN"
      }
    }
  });

  const labels = debugFxLabels(signal);

  assert.equal(labels.supabaseStatus, "WARN");
});

test("debug_fx summary includes Supabase evidence_needed", () => {
  const summary = debugFxSummary({
    debugFxSignal: {
      labels: {
        supabaseStatus: "WARN",
        supabaseEvidenceNeeded: "supabase_project_scope_or_auth_unverified"
      }
    }
  });

  assert.match(summary, /Supabase WARN supabase_project_scope_or_auth_unverified/);
});

test("debug_fx summary includes optional external proof labels", () => {
  const summary = debugFxSummary({
    debugFxSignal: {
      labels: {
        browserStatus: "SUPPORTING_EVIDENCE_MISSING",
        computerUseStatus: "WARN",
        agentDbContextStatus: "EVIDENCE_NEEDED"
      }
    }
  });

  assert.match(summary, /Browser SUPPORTING_EVIDENCE_MISSING/);
  assert.match(summary, /Computer WARN/);
  assert.match(summary, /Agent DB EVIDENCE_NEEDED/);
});

test("debug_fx summary renders the canonical MLA stage boundary", () => {
  const summary = debugFxSummary({
    type: "debug_fx",
    debugFxSignal: {
      labels: {
        stageBoundaryStage: "llm",
        stageBoundaryFailureClass: "timeout",
        stageBoundaryReason: "timeout"
      }
    }
  });

  assert.equal(summary, "MLA llm timeout timeout");
});

test("debug_fx stage boundary reason remains redacted", () => {
  const labels = debugFxLabels({
    type: "debug_fx",
    debugFxSignal: {
      labels: {
        stageBoundaryStage: "llm",
        stageBoundaryFailureClass: "timeout",
        stageBoundaryReason: "Bearer no-render"
      }
    }
  });

  assert.equal(labels.stageBoundaryReason, "[redacted]");
});

test("debug_fx summary does not expose secret-like values", () => {
  const summary = debugFxSummary({
    debugFxSignal: {
      labels: {
        supabaseStatus: "WARN",
        supabaseNextAction: "run_readonly_supabase_context_probe",
        supabaseEvidenceNeeded: "Bearer no-render",
        Authorization: "Bearer no-render",
        rawToken: "jwt_header.payload",
        ownerToken: "pcsk_placeholder"
      }
    }
  });

  assert.match(summary, /run_readonly_supabase_context_probe/);
  assert.doesNotMatch(summary, /Authorization/i);
  assert.doesNotMatch(summary, /Bearer/i);
  assert.doesNotMatch(summary, /rawToken/i);
  assert.doesNotMatch(summary, /ownerToken/i);
  assert.doesNotMatch(summary, /pcsk_/);
  assert.doesNotMatch(summary, /jwt_header/);
});

test("debug_fx summary returns an empty fallback when labels are missing", () => {
  assert.equal(debugFxSummary({ type: "debug_fx" }), "");
  assert.deepEqual(debugFxLabels({ type: "debug_fx" }), {});
});

test("authoritative empty debug_fx clears stale debug state", () => {
  assert.equal(typeof chatDebugSignal.debugSignalMessagePatch, "function");

  const patch = chatDebugSignal.debugSignalMessagePatch({
    signal: "trace",
    debugLabels: { stageBoundaryStage: "orchestration" },
    debugSummary: "MLA orchestration fallback query_transformer_bypassed"
  }, {
    type: "debug_fx",
    debugFxSignal: { labels: {} }
  });

  assert.deepEqual(patch, {
    signal: "debug_fx",
    debugLabels: {},
    debugSummary: ""
  });
});

test("supplemental trace without debug labels preserves current debug state", () => {
  assert.equal(typeof chatDebugSignal.debugSignalMessagePatch, "function");

  const previous = {
    signal: "debug_fx",
    debugLabels: {
      stageBoundaryStage: "llm",
      stageBoundaryFailureClass: "timeout",
      stageBoundaryReason: "timeout"
    },
    debugSummary: "MLA llm timeout timeout"
  };
  const patch = chatDebugSignal.debugSignalMessagePatch(previous, { type: "trace" });

  assert.deepEqual(patch, {
    signal: "trace",
    debugLabels: previous.debugLabels,
    debugSummary: previous.debugSummary
  });
});
