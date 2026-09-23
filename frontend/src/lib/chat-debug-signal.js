const LABEL_GROUPS = [
  {
    title: "MLA",
    keys: ["stageBoundaryStage", "stageBoundaryFailureClass", "stageBoundaryReason"]
  },
  {
    title: "Supabase",
    keys: ["supabaseStatus", "supabaseEvidenceNeeded"]
  },
  {
    title: "Agent DB",
    keys: ["agentDbContextStatus", "agentDbContextReason"]
  },
  {
    title: "Browser",
    keys: ["browserStatus"]
  },
  {
    title: "Computer",
    keys: ["computerUseStatus"]
  },
  {
    title: "Next",
    keys: ["debugAiNextAction", "localLlmNextAction", "agentDbContextNextAction", "supabaseNextAction"]
  }
];

const ALLOWED_LABEL_KEYS = new Set(LABEL_GROUPS.flatMap((group) => group.keys));
const SECRET_KEY_PATTERN = /(authorization|token|secret|password|cookie|apikey|apiKey|service[_-]?role)/i;
const SECRET_VALUE_PATTERN = /(Bearer\s+|sk-[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{16,}|gsk_[A-Za-z0-9]{16,}|pcsk_[A-Za-z0-9_-]{16,}|eyJ[A-Za-z0-9_-]{12,})/i;

export function debugFxSignal(payload = {}) {
  if (!payload || typeof payload !== "object") return {};
  return payload.debugFxSignal
    || payload.debug_fx_signal
    || payload.debugFx
    || payload.debug_fx
    || payload.signal
    || payload;
}

function safeLabelValue(key, value) {
  if (!ALLOWED_LABEL_KEYS.has(key) || SECRET_KEY_PATTERN.test(key)) return "";
  if (value == null) return "";
  const text = String(value).replace(/\s+/g, " ").trim();
  if (!text || text === "-") return "";
  if (SECRET_VALUE_PATTERN.test(text)) return "[redacted]";
  return text.length > 160 ? `${text.slice(0, 157)}...` : text;
}

export function debugFxLabels(payload = {}) {
  const signal = debugFxSignal(payload);
  const source = signal?.labels || payload?.labels || {};
  if (!source || typeof source !== "object") return {};

  return Object.fromEntries(
    Object.entries(source)
      .map(([key, value]) => [key, safeLabelValue(key, value)])
      .filter(([, value]) => value)
  );
}

export function debugFxSummary(payload = {}) {
  const labels = debugFxLabels(payload);
  const parts = [];

  for (const group of LABEL_GROUPS) {
    const value = group.keys.map((key) => labels[key]).filter(Boolean).join(" ");
    if (value) parts.push(`${group.title} ${value}`);
  }

  return parts.join(" | ");
}

export function debugSignalMessagePatch(previous = {}, event = {}) {
  const debugLabels = debugFxLabels(event);
  const debugSummary = debugFxSummary(event);
  const authoritative = event?.type === "debug_fx";

  return {
    signal: event?.type || previous.signal || "",
    debugLabels: Object.keys(debugLabels).length > 0
      ? debugLabels
      : authoritative ? {} : previous.debugLabels || {},
    debugSummary: debugSummary || (authoritative ? "" : previous.debugSummary || "")
  };
}
