function text(value, fallback = "-", max = 80) {
  if (value == null) return fallback;
  const s = String(value).replace(/\s+/g, " ").trim();
  if (!s) return fallback;
  return s.length > max ? `${s.slice(0, max - 1)}...` : s;
}

function normalizedStatus(value) {
  const status = text(value, "queued", 24).toLowerCase();
  if (["queued", "running", "done", "warn", "error", "skipped"].includes(status)) return status;
  if (status.includes("complete") || status.includes("final")) return "done";
  if (status.includes("error") || status.includes("cancel") || status.includes("timeout")) return "warn";
  return "running";
}

function fallbackBlocks() {
  return [
    { id: "intake", label: "Intake", phase: "stream", status: "running", reason: "request accepted", order: 0 },
    { id: "route", label: "MoE Route", phase: "orchestration", status: "queued", reason: "route pending", order: 1 },
    { id: "retrieve", label: "Retrieve", phase: "rag", status: "queued", reason: "evidence pending", order: 2 },
    { id: "compose", label: "Context", phase: "prompt", status: "queued", reason: "context pending", order: 3 },
    { id: "model", label: "Model", phase: "llm", status: "queued", reason: "generation pending", order: 4 },
    { id: "recover", label: "Resilience", phase: "recovery", status: "done", reason: "ok", order: 5 }
  ];
}

function normalizeBlocks(blocks) {
  const source = Array.isArray(blocks) && blocks.length ? blocks : fallbackBlocks();
  return source
    .map((block, index) => ({
      id: text(block?.id, `block-${index}`, 48),
      label: text(block?.label, `Block ${index + 1}`, 48),
      phase: text(block?.phase, "-", 48),
      status: normalizedStatus(block?.status),
      reason: text(block?.reason, "-", 96),
      order: Number.isFinite(Number(block?.order)) ? Number(block.order) : index,
      tookMs: Number.isFinite(Number(block?.tookMs)) ? Math.max(0, Number(block.tookMs)) : null
    }))
    .sort((a, b) => a.order - b.order)
    .slice(0, 12);
}

function summarizeBlocks(blocks) {
  const counts = blocks.reduce((acc, block) => {
    acc[block.status] = (acc[block.status] || 0) + 1;
    return acc;
  }, {});
  const total = blocks.length || 1;
  const attention = (counts.warn || 0) + (counts.error || 0);
  const health = counts.error ? "error"
    : counts.warn ? "warn"
      : counts.running ? "running"
        : counts.queued ? "queued"
          : "done";
  const active = blocks.find((block) => block.status === "running")
    || blocks.find((block) => block.status === "warn" || block.status === "error")
    || blocks[blocks.length - 1];
  return {
    total,
    done: counts.done || 0,
    running: counts.running || 0,
    queued: counts.queued || 0,
    skipped: counts.skipped || 0,
    attention,
    health,
    activeLabel: active ? active.label : "Core",
    verification: `${counts.done || 0}/${total} passed`,
    debug: `${counts.running || 0} active, ${counts.queued || 0} waiting`,
    exception: attention ? `${attention} needs review` : "clear"
  };
}

function appendLabelValue(parent, label, value) {
  const item = document.createElement("span");
  const key = document.createElement("b");
  key.textContent = label;
  const val = document.createElement("em");
  val.textContent = value;
  item.append(key, val);
  parent.appendChild(item);
}

function renderSummary(summary, meta) {
  const summaryEl = document.createElement("div");
  summaryEl.setAttribute("data-role", "transformer-core-summary");
  summaryEl.className = "transformer-core-summary";
  appendLabelValue(summaryEl, "Health", summary.health);
  appendLabelValue(summaryEl, "Verification", summary.verification);
  appendLabelValue(summaryEl, "Debug", summary.debug);
  appendLabelValue(summaryEl, "Exception", summary.exception);
  appendLabelValue(summaryEl, "Event", text(meta?.status || meta?.streamStatus || summary.activeLabel, "stream", 36));
  return summaryEl;
}

function renderFlow(blocks) {
  const flow = document.createElement("div");
  flow.setAttribute("data-role", "transformer-core-flow");
  flow.className = "transformer-core-flow";
  blocks.forEach((block) => {
    const dot = document.createElement("span");
    dot.dataset.status = block.status;
    dot.title = `${block.label}: ${block.status}`;
    dot.setAttribute("aria-label", `${block.label} ${block.status}`);
    flow.appendChild(dot);
  });
  return flow;
}

function renderBlock(block) {
  const item = document.createElement("div");
  item.className = "transformer-block";
  item.dataset.blockId = block.id;
  item.dataset.status = block.status;

  const top = document.createElement("span");
  top.className = "transformer-block-phase";
  top.textContent = block.phase;

  const name = document.createElement("strong");
  name.textContent = block.label;

  const bottom = document.createElement("small");
  const took = block.tookMs == null ? "" : ` - ${Math.round(block.tookMs)}ms`;
  bottom.textContent = `${block.status}${took} - ${block.reason}`;

  item.append(top, name, bottom);
  return item;
}

function renderDebugPanel(summary, blocks) {
  const panel = document.createElement("div");
  panel.setAttribute("data-role", "transformer-core-debug");
  panel.className = "transformer-core-debug";
  appendLabelValue(panel, "Verification", `${summary.done} completed, ${summary.skipped} skipped`);
  appendLabelValue(panel, "Debug", `${summary.activeLabel} is the current focus`);
  const exceptionBlock = blocks.find((block) => block.status === "error" || block.status === "warn");
  appendLabelValue(panel, "Exception", exceptionBlock ? `${exceptionBlock.label}: ${exceptionBlock.reason}` : "no exception signal");
  return panel;
}

export function renderTransformerCoreRail(wrap, blocks, meta = {}) {
  if (!wrap) return null;
  let holder = wrap.querySelector('[data-role="transformer-core-rail"]');
  if (!holder) {
    holder = document.createElement("aside");
    holder.dataset.role = "transformer-core-rail";
    holder.dataset.ttsIgnore = "1";
    holder.className = "transformer-core-rail";
    holder.setAttribute("aria-label", "Transformer block runtime status");
    wrap.appendChild(holder);
  }

  const normalized = normalizeBlocks(blocks);
  const summary = summarizeBlocks(normalized);
  holder.dataset.health = summary.health;

  const header = document.createElement("div");
  header.className = "transformer-core-head";
  const title = document.createElement("strong");
  title.textContent = "Transformer Core";
  const subtitle = document.createElement("span");
  subtitle.textContent = `${summary.health} / ${summary.activeLabel}`;
  header.append(title, subtitle);

  const grid = document.createElement("div");
  grid.className = "transformer-core-grid";
  normalized.forEach((block) => grid.appendChild(renderBlock(block)));

  holder.replaceChildren(
    header,
    renderSummary(summary, meta),
    renderFlow(normalized),
    grid,
    renderDebugPanel(summary, normalized)
  );
  return holder;
}
