function clean(raw) {
  const value = String(raw ?? "");
  if (value.startsWith("data:")) return "[data-uri]";
  return value
    .replace(/https?:\/\/\S+/gi, "[url]")
    .replace(/\b(api[_-]?key|authorization|token|secret)\b\s*[:=]\s*\S+/gi, "$1:[redacted]")
    .slice(0, 160);
}

function imageJobArtifactUrl(raw) {
  if (typeof raw !== "string" || !raw.startsWith("/") || raw.startsWith("//")
      || /[\\\u0000-\u0020\u007f]/.test(raw) || /%(?![0-9a-f]{2})/i.test(raw)) return null;
  try {
    const url = new URL(raw, window.location.origin);
    return url.origin === window.location.origin ? url.href : null;
  } catch (_) {
    return null;
  }
}

function textSeparator(value = " ") {
  return document.createTextNode(String(value ?? " "));
}

function appendLine(root, label, value) {
  const row = document.createElement("div");
  const key = document.createElement("strong");
  const val = document.createElement("small");
  key.textContent = `${label}: `;
  val.textContent = clean(value || "-");
  row.append(key, val);
  root.appendChild(row);
  root.appendChild(textSeparator(" "));
}

function imageJobPromptLabel(job = {}) {
  const status = String(job.status || "pending").trim().toLowerCase();
  const reason = String(job.reason || job.error || job.errorCode || job.error_code || "").trim().toLowerCase();
  const content = String(job.content || "").trim().toLowerCase();
  if (status === "failed") {
    if (reason.includes("disabled") || reason.includes("unavailable") || content.includes("unavailable")) {
      return "Image request unavailable";
    }
    return "Image request failed";
  }
  if (status === "succeeded" || status === "complete" || status === "completed" || status === "done") {
    return "Image generated";
  }
  return "Image request submitted";
}

function imageJobCardLabel(job = {}) {
  const parts = [
    imageJobPromptLabel(job),
    `status ${clean(job.status || "pending")}`
  ];
  const id = clean(job.id || job.jobId || job.job_id || "");
  const reason = clean(job.reason || job.error || job.errorCode || job.error_code || "");
  if (id) parts.push(`id ${id}`);
  if (reason) parts.push(`reason ${reason}`);
  return parts.join("; ");
}

export function renderImageJobCard(target, job = {}) {
  const card = document.createElement("section");
  card.dataset.imageJobDebug = "true";
  card.className = "image-job-card";
  card.setAttribute("role", "status");
  card.setAttribute("aria-live", "polite");

  const title = document.createElement("strong");
  title.className = "image-job-title";
  title.textContent = "Image job";
  const promptEl = document.createElement("small");
  promptEl.className = "image-job-prompt";
  promptEl.dataset.role = "image-job-prompt";
  promptEl.textContent = imageJobPromptLabel(job);

  card.append(title, textSeparator(" "), promptEl);
  updateImageJobCard(card, job);
  target?.appendChild(card);
  return card;
}

export function updateImageJobCard(card, job = {}) {
  if (!card) return;
  card.dataset.status = clean(job.status || "pending");
  const cardLabel = imageJobCardLabel(job);
  card.setAttribute("aria-label", cardLabel);
  card.title = cardLabel;
  const promptEl = card.querySelector("[data-role='image-job-prompt']");
  if (promptEl) promptEl.textContent = imageJobPromptLabel(job);
  let meta = card.querySelector("[data-role='image-job-meta']");
  if (!meta) {
    meta = document.createElement("div");
    meta.dataset.role = "image-job-meta";
    card.appendChild(textSeparator(" "));
    card.appendChild(meta);
  }
  meta.replaceChildren();
  appendLine(meta, "status", job.status || "pending");
  appendLine(meta, "id", job.id || job.jobId || job.job_id);
  if (job.reason || job.error || job.errorCode || job.error_code) {
    appendLine(meta, "reason", job.reason || job.error || job.errorCode || job.error_code);
  }
  if (job.content) appendLine(meta, "content", job.content);
  const imageUrl = String(job.status || "").trim().toUpperCase() === "SUCCEEDED"
    ? imageJobArtifactUrl(job.publicUrl) : null;
  if (imageUrl) {
    const image = document.createElement("img");
    image.src = imageUrl;
    image.alt = "Generated image";
    image.loading = "lazy";
    image.referrerPolicy = "no-referrer";
    image.style.maxWidth = "100%";
    image.style.height = "auto";
    const link = document.createElement("a");
    link.dataset.role = "image-job-artifact";
    link.href = imageUrl;
    link.textContent = "Open generated image";
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    meta.append(image, textSeparator(" "), link);
  }
  const manifestUrl = imageJobArtifactUrl(job.manifestUrl);
  const id = String(job.id || job.jobId || job.job_id || "");
  if (manifestUrl && id && manifestUrl === `${window.location.origin}/api/image-plugin/jobs/${encodeURIComponent(id)}/manifest`) {
    const link = document.createElement("a");
    link.dataset.role = "image-job-manifest";
    link.href = manifestUrl;
    link.textContent = "Open image manifest";
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    meta.append(textSeparator(" "), link);
  }
}

export async function attachImageJobDebug(card, id, headers = {}) {
  if (!card || !id || card.isConnected === false) return null;
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), 10000);
  let projection = null;
  let diagnosticStatus = "debug_unavailable";
  try {
    const response = await fetch(`/api/diagnostics/image/jobs/${encodeURIComponent(id)}/debug`, {
      method: "GET", headers, signal: controller.signal
    });
    if (response.ok) {
      projection = await response.json();
      diagnosticStatus = projection?.job?.status || "debug_unknown";
    } else {
      diagnosticStatus = `debug_${Number(response.status) || 0}`;
    }
  } catch (_) {
    diagnosticStatus = "debug_unavailable";
  } finally {
    window.clearTimeout(timeoutId);
  }
  if (card.isConnected === false) return projection;
  let debug = card.querySelector("[data-role='image-job-diagnostics']");
  if (!debug) {
    debug = document.createElement("div");
    debug.dataset.role = "image-job-diagnostics";
    card.appendChild(textSeparator(" "));
    card.appendChild(debug);
  }
  debug.replaceChildren();
  appendLine(debug, "diagnostic status", diagnosticStatus);
  return projection;
}

export async function attachImageJobConfigDebug(card, headers = {}) {
  if (!card) return null;
  const response = await fetch("/api/diagnostics/image/config", {
    method: "GET",
    headers
  });
  const config = response.ok ? await response.json() : {};
  let warning = card.querySelector("[data-role='image-job-config']");
  if (!warning) {
    warning = document.createElement("small");
    warning.dataset.role = "image-job-config";
    card.appendChild(textSeparator(" "));
    card.appendChild(warning);
  }
  if (config["openai.image.enabled"] === false || config["imageServiceAvailable"] === false) {
    warning.textContent = clean(config.disabledReason || config.nextAction || "image provider unavailable");
  } else {
    warning.textContent = `image config:${clean(config.status || "checked")}`;
  }
  return config;
}
