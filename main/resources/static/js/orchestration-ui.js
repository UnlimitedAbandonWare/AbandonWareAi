function text(value, fallback = "unknown") {
  if (value == null) return fallback;
  const s = String(value).replace(/\s+/g, " ").trim();
  return s || fallback;
}

function setField(root, name, value, fallback = "unknown") {
  if (value === undefined) return;
  const el = root?.querySelector?.(`[data-orch-field="${name}"] strong`);
  if (!el) return;
  el.textContent = text(value, fallback);
}

function setBadge(root, name, active, label) {
  const el = root?.querySelector?.(`[data-orch-badge="${name}"]`);
  if (!el) return;
  if (active !== undefined) el.dataset.active = active ? "true" : "false";
  if (label !== undefined && label != null) el.textContent = text(label);
}

function ratio(value, fallback = "-") {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  const pct = Math.max(0, Math.min(100, Math.round(n * 100)));
  return `${pct}%`;
}

function count(value, fallback = "-") {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return String(Math.max(0, Math.trunc(n)));
}

function streamValue(value, context) {
  const base = text(value);
  const contextText = text(context, "");
  return contextText ? `${base} | ${contextText}` : base;
}

function scoreValue(value, context) {
  const base = ratio(value);
  const contextText = text(context, "");
  return contextText ? `${base} | ${contextText}` : base;
}

function pipelineRouteValue(pipeline, fallbackRoute) {
  const plan = text(pipeline?.planId ?? pipeline?.plan, "");
  const route = text(pipeline?.route ?? fallbackRoute, "");
  return [plan, route].filter(Boolean).join(" / ") || "-";
}

function pipelineContextCountsValue(pipeline) {
  const web = pipeline?.webCount;
  const vector = pipeline?.vectorCount;
  const context = pipeline?.finalContextCount;
  if (web == null && vector == null && context == null) return "-";
  return `web:${count(web, "0")} vector:${count(vector, "0")} ctx:${count(context, "0")}`;
}

function pipelineQualityValue(pipeline) {
  const citation = pipeline?.citationCoverage;
  const finalSigmoid = pipeline?.finalSigmoid;
  if (citation == null && finalSigmoid == null) return "-";
  return `citation:${ratio(citation)} final:${ratio(finalSigmoid)}`;
}

function pipelineHealthValue(pipeline) {
  const failure = text(pipeline?.failureClass, "");
  const disabled = text(pipeline?.disabledReason, "");
  if (failure) return `failure:${failure}`;
  if (disabled) return `disabled:${disabled}`;
  return "ok";
}

function appendLine(root, label, value) {
  const row = document.createElement("span");
  const labelEl = document.createElement("b");
  labelEl.textContent = `${label}: `;
  const valueEl = document.createElement("em");
  valueEl.textContent = text(value, "-");
  row.append(labelEl, valueEl);
  root.appendChild(row);
}

export function updateOrchestrationSignalBar(partial = {}) {
  const root = document.getElementById("orchestrationSignalBar");
  if (!root || !partial || typeof partial !== "object") return;

  const has = (key) => Object.prototype.hasOwnProperty.call(partial, key);
  const previousFieldValue = (name) => {
    const el = root.querySelector?.(`[data-orch-field="${name}"] strong`);
    return el ? text(el.textContent, "") : "";
  };

  if (has("streamStatus") || has("streamContext")) {
    const streamStatus = has("streamStatus") && text(partial.streamStatus, "") ? partial.streamStatus : previousFieldValue("streamStatus");
    setField(root, "streamStatus", streamValue(streamStatus, partial.streamContext));
  }
  if (has("model")) setField(root, "model", partial.model);
  if (has("answerMode")) setField(root, "answerMode", partial.answerMode);
  if (has("evidenceCount")) setField(root, "evidenceCount", partial.evidenceCount, "0");
  if (has("pipelineSnapshot") || has("route")) {
    const pipeline = partial.pipelineSnapshot || {};
    setField(root, "route", pipelineRouteValue(pipeline, partial.route), "-");
    setField(root, "contextCounts", pipelineContextCountsValue(pipeline), "-");
    setField(root, "quality", pipelineQualityValue(pipeline), "-");
    setField(root, "health", pipelineHealthValue(pipeline), "ok");
  }
  if (has("traceTurnId") || has("traceTurn")) {
    setField(root, "traceTurn", partial.traceTurnId ?? partial.traceTurn, "-");
  }
  if (has("scoreDelta") || has("scoreDeltaContext")) {
    setField(root, "scoreDelta", scoreValue(partial.scoreDelta, partial.scoreDeltaContext));
  }
  if (has("dropRatio")) setField(root, "dropRatio", ratio(partial.dropRatio));
  if (has("maxDrawdown")) setField(root, "maxDrawdown", ratio(partial.maxDrawdown));
  if (has("expectedDelta")) setField(root, "expectedDelta", ratio(partial.expectedDelta));

  if (has("plan")) setBadge(root, "plan", Boolean(partial.plan), partial.plan || "Plan");
  if (has("moeActive")) setBadge(root, "moe", Boolean(partial.moeActive), "MoE");
  if (has("modelActive") || has("modelBadge")) {
    setBadge(root, "model", has("modelActive") ? Boolean(partial.modelActive) : undefined, partial.modelBadge);
  }
  if (has("anchorActive")) setBadge(root, "anchor", Boolean(partial.anchorActive), "Anchor");
  if (has("dppActive")) setBadge(root, "dpp", Boolean(partial.dppActive), "DPP");
  if (has("cfvmActive")) setBadge(root, "cfvm", Boolean(partial.cfvmActive), "CFVM");
  if (has("supabaseActive") || has("supabaseBadge")) {
    setBadge(root, "supabase", has("supabaseActive") ? Boolean(partial.supabaseActive) : undefined, partial.supabaseBadge || "Supabase");
  }
  if (has("resilienceActive")) setBadge(root, "resilience", Boolean(partial.resilienceActive), "Resilience");
}

export function renderPlanModeCard(meta = {}, wrap) {
  if (!wrap || !meta || typeof meta !== "object") return;

  let holder = wrap.querySelector('[data-role="plan-mode-card"]');
  if (!holder) {
    holder = document.createElement("aside");
    holder.dataset.role = "plan-mode-card";
    holder.dataset.ttsIgnore = "1";
    holder.className = "plan-mode-card";
    holder.setAttribute("aria-label", "Orchestration plan and learning context");
    wrap.appendChild(holder);
  }

  const learning = meta.learningContext || {};
  const pipeline = meta.pipelineSnapshot || {};
  const sourceTags = Array.isArray(learning.sourceTags) ? learning.sourceTags.join(", ") : "";
  const summaryState = learning.summaryPresent === true ? "present" : "absent";
  const degraded = learning.degraded === true ? text(learning.degradedReason, "degraded") : "false";
  const planId = meta.planId || meta.plan || pipeline.planId || "unknown";
  const route = meta.route || pipeline.route || "";

  holder.replaceChildren();
  const title = document.createElement("strong");
  title.textContent = "Route Mode";
  holder.appendChild(title);

  appendLine(holder, "plan", planId);
  appendLine(holder, "mode", meta.answerMode || meta.pipelineSnapshot?.answerMode || "unknown");
  appendLine(holder, "route", route || "unknown");
  appendLine(holder, "role", learning.actorRole || "ANONYMOUS");
  appendLine(holder, "tags", sourceTags || "none");
  appendLine(holder, "signals", learning.signalCount ?? 0);
  appendLine(holder, "summary", summaryState);
  appendLine(holder, "web", count(pipeline.webCount));
  appendLine(holder, "vector", count(pipeline.vectorCount));
  appendLine(holder, "context", count(pipeline.finalContextCount));
  appendLine(holder, "citation", ratio(pipeline.citationCoverage));
  appendLine(holder, "finalSigmoid", ratio(pipeline.finalSigmoid));
  appendLine(holder, "failure", pipeline.failureClass || "none");
  appendLine(holder, "disabled", pipeline.disabledReason || "none");
  appendLine(holder, "degraded", degraded);
}
