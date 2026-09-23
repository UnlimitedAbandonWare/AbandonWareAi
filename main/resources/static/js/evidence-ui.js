function cleanText(value, fallback = "") {
  const text = value == null ? "" : String(value);
  const compact = text.replace(/\s+/g, " ").trim();
  return compact || fallback;
}

function confidenceLabel(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return "confidence unknown";
  const pct = Math.max(0, Math.min(100, Math.round(n * 100)));
  return `${pct}%`;
}

function lineLabel(ev) {
  const start = Number(ev?.lineStart);
  const end = Number(ev?.lineEnd);
  if (Number.isInteger(start) && Number.isInteger(end) && end >= start) {
    return `lines ${start}-${end}`;
  }
  if (Number.isInteger(start)) {
    return `line ${start}`;
  }
  return "";
}

export function renderEvidenceRail(evidence = [], wrap) {
  if (!wrap || !Array.isArray(evidence) || evidence.length === 0) return 0;

  let holder = wrap.querySelector('[data-role="evidence-rail"]');
  if (!holder) {
    holder = document.createElement("section");
    holder.dataset.role = "evidence-rail";
    holder.dataset.ttsIgnore = "1";
    holder.className = "evidence-rail";
    holder.setAttribute("aria-label", "Answer evidence");
    wrap.appendChild(holder);
  }

  holder.replaceChildren();

  const head = document.createElement("div");
  head.className = "evidence-rail-head";
  const title = document.createElement("strong");
  title.textContent = "Evidence Flight Deck";
  const count = document.createElement("span");
  count.textContent = `${evidence.length} sources`;
  head.append(title, count);
  holder.appendChild(head);

  const list = document.createElement("div");
  list.className = "evidence-chip-list";
  holder.appendChild(list);

  evidence.slice(0, 8).forEach((ev, index) => {
    const card = document.createElement("article");
    card.className = "evidence-chip";

    const top = document.createElement("div");
    top.className = "evidence-chip-top";

    const marker = document.createElement("span");
    marker.className = "evidence-marker";
    marker.textContent = cleanText(ev?.marker, `E${index + 1}`);

    const confidence = document.createElement("span");
    confidence.className = "evidence-confidence";
    confidence.textContent = confidenceLabel(ev?.confidence);

    top.append(marker, confidence);

    const evidenceTitle = document.createElement("strong");
    evidenceTitle.className = "evidence-title";
    evidenceTitle.textContent = cleanText(ev?.title || ev?.source || ev?.filePath, "Untitled evidence");

    const source = document.createElement("span");
    source.className = "evidence-source";
    source.textContent = cleanText(ev?.source || ev?.filePath || ev?.kind, "source unknown");

    const meta = document.createElement("span");
    meta.className = "evidence-line";
    const bits = [
      cleanText(ev?.kind),
      Number.isInteger(Number(ev?.rank)) ? `rank ${Number(ev.rank)}` : "",
      lineLabel(ev),
      cleanText(ev?.confidenceSource)
    ].filter(Boolean);
    meta.textContent = bits.join(" · ");

    card.append(top, evidenceTitle, source, meta);
    list.appendChild(card);
  });

  return evidence.length;
}
