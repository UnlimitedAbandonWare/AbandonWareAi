function safe(value, fallback = "-") {
  if (value == null || value === "") return fallback;
  return String(value)
    .replace(/https?:\/\/\S+/gi, '[url]')
    .replace(/[A-Za-z]:\\[^\s]+/g, '[path]')
    .replace(/sk-[A-Za-z0-9_-]{12,}/gi, '[secret]')
    .replace(/AIza[0-9A-Za-z_-]{12,}/g, '[secret]')
    .replace(/gsk_[A-Za-z0-9]{12,}/gi, '[secret]')
    .replace(/pcsk_[A-Za-z0-9_-]{12,}/gi, '[secret]')
    .replace(/authorization\s*[:=]\s*(?:Bearer\s+)?\S+/gi, 'authorization:[redacted]')
    .replace(/\b(api[_-]?key|client[_-]?secret|owner[_-]?token|token)\s*[:=]\s*\S+/gi, '$1=[redacted]');
}

function putText(parent, label, value) {
  const row = document.createElement("div");
  const strong = document.createElement("strong");
  const small = document.createElement("small");
  strong.textContent = label;
  small.textContent = safe(value);
  row.replaceChildren(strong, small);
  parent.appendChild(row);
}

function countOf(value) {
  if (Array.isArray(value)) return value.length;
  if (typeof value === "number" && Number.isFinite(value)) return Math.max(0, value);
  if (value && typeof value === "object") return Object.keys(value).length;
  return 0;
}

function renderBrainState(root, data = {}) {
  const learningContext = data.learningContext || data.learning_context || {};
  const sourceSummaries = data.sourceSummaries || learningContext.sourceTags || learningContext.source_tags || [];
  const recentChanges = data.recentChanges || learningContext.signalCount || learningContext.signal_count || [];
  const anchorMap = data.anchorMap || (learningContext.summaryPresent || learningContext.summary_present ? { summary: true } : {});
  const queryTime = data.queryTime || {
    fallbackUsed: Boolean(learningContext.degraded),
    failureClass: learningContext.degradedReason || learningContext.degraded_reason || "-",
    disabledReason: learningContext.degradedReason || learningContext.degraded_reason || "-"
  };
  root.replaceChildren();
  putText(root, "sources", countOf(sourceSummaries));
  putText(root, "recent", countOf(recentChanges));
  putText(root, "anchors", countOf(anchorMap));
  putText(root, "query", queryTime.fallbackUsed ? "fallback" : "direct");
  putText(root, "failure", `failure=${safe(queryTime.failureClass, "-")}`);
  putText(root, "disabled", `disabled=${safe(queryTime.disabledReason, "-")}`);
}

document.addEventListener("brain-state:session", (event) => {
  document.querySelectorAll("[data-brain-state-root]").forEach((root) => renderBrainState(root, event?.detail || {}));
});

document.addEventListener("brain-state:answer", (event) => {
  document.querySelectorAll("[data-brain-state-root]").forEach((root) => renderBrainState(root, event?.detail || {}));
});
