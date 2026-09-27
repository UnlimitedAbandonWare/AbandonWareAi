// /js/model-strategy.js
(function () {
  "use strict";

  const ACTIVE_KEY = "models.active";
  const STRATEGY_KEY = "models.strategy";
  const CACHE_KEY = "models.cache";
  const RECENT_KEY = "models.recent";
  const FAVORITES_KEY = "models.favorites";
  const MAX_RECENT = 8;

  const surfaceState = new Map();

  try {
    const legacyActive = localStorage.getItem("model.active");
    const legacyStrategy = localStorage.getItem("model.strategy");
    if (legacyActive && !localStorage.getItem(ACTIVE_KEY)) {
      localStorage.setItem(ACTIVE_KEY, legacyActive);
    }
    if (legacyStrategy && !localStorage.getItem(STRATEGY_KEY)) {
      localStorage.setItem(STRATEGY_KEY, legacyStrategy);
    }
  } catch {}

  const isPrerender = () =>
    typeof document.prerendering === "boolean" && document.prerendering;

  const safeText = (value) => String(value ?? "").trim();
  const lower = (value) => safeText(value).toLowerCase();
  const storageGet = (key) => {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  };
  const storageSet = (key, value) => {
    try {
      localStorage.setItem(key, value);
    } catch {}
  };
  const storageRemove = (key) => {
    try {
      localStorage.removeItem(key);
    } catch {}
  };

  const isLocalProvider = () =>
    lower(window.initialData?.llmProvider) === "local";

  const isRemoteSelectionAllowed = () => {
    const value = window.initialData?.allowRemoteModelSelection;
    if (value === false) return false;
    if (typeof value === "string") return value.trim().toLowerCase() !== "false";
    return value === true;
  };

  const isRemoteLookingId = (id) => {
    const s = lower(id);
    return s.startsWith("gpt-") || s.startsWith("openai") || /^o\d/.test(s) || s.startsWith("o-");
  };

  const isChatSelectableId = (id) => {
    let s = lower(id);
    if (!s) return false;
    if (s.includes(":")) s = s.split(":")[0];
    if (s.includes("embedding") || s.startsWith("text-embedding")) return false;
    if (s === "babbage-002" || s === "davinci-002") return false;
    if (isLocalProvider() && !isRemoteSelectionAllowed() && isRemoteLookingId(s)) return false;
    return true;
  };

  const LOCAL_PREFERRED_DEFAULTS = [
    "gemma4:26b",
    "qwen3:8b",
  ];

  const REMOTE_PREFERRED_DEFAULTS = [
    "gpt-5.5",
    "gpt-5.4",
    "gpt-5.4-mini",
    "gpt-4.1-mini",
    "o4-mini",
  ];

  const preferredDefaults = () => {
    const configured = [
      window.initialData?.currentModel,
      window.initialData?.defaultModel,
    ].filter(Boolean);
    return configured.concat(isLocalProvider() ? LOCAL_PREFERRED_DEFAULTS : REMOTE_PREFERRED_DEFAULTS);
  };

  const readIdList = (key) => {
    try {
      const value = JSON.parse(storageGet(key) || "[]");
      return Array.isArray(value) ? value.map(safeText).filter(Boolean) : [];
    } catch {
      return [];
    }
  };

  const writeIdList = (key, ids) => {
    try {
      storageSet(key, JSON.stringify((ids || []).map(safeText).filter(Boolean)));
    } catch {}
  };

  const normalizeProvider = (raw, id) => {
    const explicit = safeText(raw?.provider || raw?.owner || raw?.ownedBy || raw?.owned_by);
    if (explicit) return explicit.toLowerCase();

    const s = lower(id);
    if (s.startsWith("gpt-") || /^o\d/.test(s) || s.startsWith("o-")) return "openai";
    if (s.includes("gemini")) return "gemini";
    if (s.includes("groq") || s.includes("llama-3") || s.includes("mixtral")) return "groq";
    if (s.includes("claude")) return "anthropic";
    if (s.includes(":") || s.includes("qwen") || s.includes("gemma") || s.includes("llama")) return "local";
    return "unknown";
  };

  const inferUsage = (model) => {
    const text = `${model.id} ${model.label} ${model.features}`.toLowerCase();
    const usage = new Set();
    if (/vision|image|ocr|multimodal/.test(text)) usage.add("vision");
    if (/code|coder|coding/.test(text)) usage.add("coding");
    if (/reason|thinking|qwq|r1|o1|o3|o4/.test(text)) usage.add("reasoning");
    if (/fast|mini|flash|small|8b|7b/.test(text)) usage.add("fast");
    if (/moe|mixture/.test(text)) usage.add("moe");
    if (/long|context|128k|200k|1m/.test(text) || Number(model.ctx || 0) >= 100000) usage.add("long-context");
    if (!usage.size) usage.add("chat");
    return [...usage];
  };

  const normalizeModel = (raw) => {
    const id = safeText(raw?.modelId || raw?.id || raw?.value);
    if (!id || !isChatSelectableId(id)) return null;
    const label = safeText(raw?.label || raw?.name || id);
    const model = {
      id,
      label,
      provider: normalizeProvider(raw, id),
      features: lower(raw?.features),
      ctx: Number(raw?.ctxWindow ?? raw?.ctx ?? 0) || 0,
      release: safeText(raw?.releaseDate || raw?.release || ""),
    };
    model.usage = inferUsage(model);
    return model;
  };

  const uniqById = (list) => {
    const seen = new Set();
    const out = [];
    for (const item of list || []) {
      const model = normalizeModel(item);
      if (!model || seen.has(model.id)) continue;
      seen.add(model.id);
      out.push(model);
    }
    return out;
  };

  const getModelsFromSelect = (select) => {
    if (!select?.options?.length) return [];
    return [...select.options]
      .filter((option) => option.value)
      .map((option) => normalizeModel({
        id: option.value,
        label: option.textContent,
        provider: option.dataset.provider,
        features: option.dataset.features,
        ctxWindow: option.dataset.ctxWindow,
        releaseDate: option.dataset.releaseDate,
      }))
      .filter(Boolean);
  };

  const getInitialModels = () => {
    const fromInitial = uniqById(window.initialData?.models || []);
    if (fromInitial.length) return fromInitial;

    const fromSelects = uniqById([
      ...getModelsFromSelect(document.getElementById("defaultModelSelect")),
      ...getModelsFromSelect(document.getElementById("defaultModel")),
      ...getModelsFromSelect(document.getElementById("modelPicker")),
    ]);
    if (fromSelects.length) return fromSelects;

    try {
      return uniqById(JSON.parse(storageGet(CACHE_KEY) || "[]"));
    } catch {
      return [];
    }
  };

  const modelsById = (models) => new Map((models || []).map((model) => [model.id, model]));

  const cleanupStoredIds = (models) => {
    const valid = new Set((models || []).map((model) => model.id));
    for (const key of [RECENT_KEY, FAVORITES_KEY]) {
      const cleaned = readIdList(key).filter((id) => valid.has(id) && isChatSelectableId(id));
      writeIdList(key, cleaned);
    }
    const active = safeText(storageGet(ACTIVE_KEY));
    if (active && (!valid.has(active) || !isChatSelectableId(active))) {
      storageRemove(ACTIVE_KEY);
    }
  };

  const scoreModel = (model) => {
    const id = lower(model?.id);
    if (!isChatSelectableId(id)) return -999999;

    const preferredIndex = preferredDefaults()
      .map((value) => lower(value))
      .indexOf(id);
    let pref = preferredIndex >= 0 ? 10000 - (preferredIndex * 100) : 0;

    if (!isLocalProvider()) {
      if (id === "gpt-5.5") pref = Math.max(pref, 10000);
      else if (id.startsWith("gpt-5.5")) pref = Math.max(pref, 9000);
      else if (id.startsWith("gpt-5")) pref = Math.max(pref, 8000);
    } else if (id.includes("gemma")) {
      pref += 500;
    } else if (id.includes("qwen")) {
      pref += 300;
    }

    const moeBoost = model?.usage?.includes("moe") ? 100 : 0;
    const ctxScore = Math.min(Number(model?.ctx || 0), 256000) / 1000;
    return pref + moeBoost + ctxScore;
  };

  const pickPreferred = (models) => {
    const set = new Set((models || []).map((model) => model.id));
    for (const preferred of preferredDefaults()) {
      if (set.has(preferred)) return preferred;
    }
    return "";
  };

  const pickTopMoe = (models) =>
    (models || []).slice().sort((a, b) => scoreModel(b) - scoreModel(a))[0] || null;

  const rememberRecent = (modelId) => {
    const id = safeText(modelId);
    if (!id || !isChatSelectableId(id)) return;
    const next = [id, ...readIdList(RECENT_KEY).filter((item) => item !== id)].slice(0, MAX_RECENT);
    writeIdList(RECENT_KEY, next);
  };

  const toggleFavorite = (modelId) => {
    const id = safeText(modelId);
    if (!id || !isChatSelectableId(id)) return;
    const current = readIdList(FAVORITES_KEY);
    const next = current.includes(id)
      ? current.filter((item) => item !== id)
      : [id, ...current];
    writeIdList(FAVORITES_KEY, next);
    refreshAllSurfaces();
  };

  async function persistDefaultModel(modelId, options = {}) {
    const id = safeText(modelId);
    if (!isChatSelectableId(id)) {
      console.warn("[AWX2AF2][model-picker:save-blocked]", {
        model: id,
        provider: window.initialData?.llmProvider,
        reason: "not_chat_selectable",
      });
      return false;
    }

    try {
      const res = await fetch("/api/settings/model", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ model: id }),
      });
      if (!res.ok) {
        console.warn("[AWX2AF2][model-picker:save-failed]", {
          model: id,
          status: res.status,
        });
        return false;
      }
      if (options.recordRecent) rememberRecent(id);
      return true;
    } catch (error) {
      console.warn("[AWX2AF2][model-picker:save-failed]", {
        model: id,
        error: error?.message || String(error),
      });
      return false;
    }
  }

  function optionLabel(model) {
    const pieces = [model.id];
    if (model.provider && model.provider !== "unknown") pieces.push(model.provider);
    if (model.usage?.length) pieces.push(model.usage.slice(0, 2).join("/"));
    return pieces.join(" - ");
  }

  function appendOption(parent, model) {
    const option = document.createElement("option");
    option.value = model.id;
    option.textContent = optionLabel(model);
    option.dataset.modelId = model.id;
    option.dataset.provider = model.provider || "";
    option.dataset.features = model.features || "";
    option.dataset.ctxWindow = String(model.ctx || 0);
    option.dataset.releaseDate = model.release || "";
    parent.appendChild(option);
  }

  function appendGroup(select, label, models) {
    if (!models.length) return;
    const group = document.createElement("optgroup");
    group.label = label;
    models.forEach((model) => appendOption(group, model));
    select.appendChild(group);
  }

  function appendUniqueGroup(select, label, models, seen) {
    const unique = [];
    for (const model of models || []) {
      if (!model?.id || seen.has(model.id)) continue;
      seen.add(model.id);
      unique.push(model);
    }
    appendGroup(select, label, unique);
    return unique.length;
  }

  function appendDisabledOption(select, label) {
    const option = document.createElement("option");
    option.disabled = true;
    option.value = "";
    option.textContent = label;
    select.appendChild(option);
  }

  function filteredModels(models, surface) {
    const state = surfaceState.get(surface.select) || {};
    const query = lower(state.search);
    const provider = lower(state.provider);
    const usage = lower(state.usage);

    return (models || []).filter((model) => {
      const haystack = `${model.id} ${model.label} ${model.provider} ${model.features} ${model.usage.join(" ")}`.toLowerCase();
      if (query && !haystack.includes(query)) return false;
      if (provider && model.provider !== provider) return false;
      if (usage && !model.usage.includes(usage)) return false;
      return true;
    });
  }

  function sortedModels(models) {
    const favoriteSet = new Set(readIdList(FAVORITES_KEY));
    const recentRank = new Map(readIdList(RECENT_KEY).map((id, index) => [id, index]));
    return (models || []).slice().sort((a, b) => {
      const favoriteDelta = Number(favoriteSet.has(b.id)) - Number(favoriteSet.has(a.id));
      if (favoriteDelta) return favoriteDelta;
      const aRecent = recentRank.has(a.id) ? recentRank.get(a.id) : 999;
      const bRecent = recentRank.has(b.id) ? recentRank.get(b.id) : 999;
      if (aRecent !== bRecent) return aRecent - bRecent;
      return scoreModel(b) - scoreModel(a) || a.id.localeCompare(b.id);
    });
  }

  function renderSelect(surface, models, active) {
    const select = surface.select;
    const currentValue = safeText(select.value || active);
    const allById = modelsById(models);
    const matches = sortedModels(filteredModels(models, surface));
    const favoriteIds = readIdList(FAVORITES_KEY);
    const recentIds = readIdList(RECENT_KEY);
    const hasFilters = Boolean((surfaceState.get(select)?.search || surfaceState.get(select)?.provider || surfaceState.get(select)?.usage));
    const rendered = new Set();

    select.innerHTML = "";

    if (!models.length) {
      appendDisabledOption(select, "No models available");
      select.disabled = true;
      updateResultCount(surface, 0, 0, false);
      return;
    }

    select.disabled = false;

    if (hasFilters) {
      appendUniqueGroup(select, "Matches", matches, rendered);
      if (!matches.length) appendDisabledOption(select, "No matching models");
    } else {
      appendUniqueGroup(select, "Favorites", favoriteIds.map((id) => allById.get(id)).filter(Boolean), rendered);
      appendUniqueGroup(select, "Recent", recentIds.map((id) => allById.get(id)).filter(Boolean), rendered);
      appendUniqueGroup(select, "All models", sortedModels(models), rendered);
    }

    const target = allById.has(currentValue) ? currentValue : active;
    if (target && allById.has(target) && !matches.some((model) => model.id === target) && hasFilters) {
      appendUniqueGroup(select, "Current selection", [allById.get(target)], rendered);
    }

    select.value = (target && allById.has(target)) ? target : (matches[0]?.id || sortedModels(models)[0]?.id || "");
    updateResultCount(surface, matches.length, models.length, hasFilters);
  }

  function populateFilterOptions(surface, models) {
    const state = surfaceState.get(surface.select) || {};
    const providers = [...new Set((models || []).map((model) => model.provider).filter(Boolean))].sort();
    const usages = [...new Set((models || []).flatMap((model) => model.usage || []))].sort();

    const fill = (select, values, allLabel, current) => {
      if (!select) return;
      select.innerHTML = "";
      const all = document.createElement("option");
      all.value = "";
      all.textContent = allLabel;
      select.appendChild(all);
      values.forEach((value) => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = value;
        select.appendChild(option);
      });
      select.value = values.includes(current) ? current : "";
    };

    fill(surface.providerFilter, providers, "All providers", state.provider || "");
    fill(surface.usageFilter, usages, "All uses", state.usage || "");
  }

  function renderQuickPicks(surface, models) {
    if (!surface.quick) return;
    const byId = modelsById(models);
    const ids = [
      ...readIdList(FAVORITES_KEY),
      ...readIdList(RECENT_KEY),
    ].filter((id, index, list) => list.indexOf(id) === index);

    surface.quick.innerHTML = "";
    ids.slice(0, 6).forEach((id) => {
      const model = byId.get(id);
      if (!model) return;
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "model-picker-chip";
      chip.textContent = model.id;
      chip.title = optionLabel(model);
      chip.setAttribute("aria-label", `Select model ${model.id}`);
      chip.addEventListener("click", () => chooseModel(surface, id, { persist: surface.persistOnChange }));
      surface.quick.appendChild(chip);
    });
  }

  function updateResultCount(surface, matchCount, totalCount, hasFilters) {
    if (!surface.resultCount) return;
    const total = Number(totalCount || 0);
    const matches = Number(matchCount || 0);
    surface.resultCount.textContent = hasFilters
      ? `${matches} of ${total} models`
      : `${total} models`;
  }

  function refreshFavoriteButton(surface) {
    if (!surface.favoriteButton) return;
    const selected = safeText(surface.select.value);
    const isFavorite = readIdList(FAVORITES_KEY).includes(selected);
    surface.favoriteButton.classList.toggle("is-active", isFavorite);
    surface.favoriteButton.setAttribute("aria-pressed", String(isFavorite));
    surface.favoriteButton.title = isFavorite ? "Remove favorite" : "Add favorite";
    surface.favoriteButton.setAttribute(
      "aria-label",
      selected
        ? `${isFavorite ? "Remove" : "Add"} favorite model ${selected}`
        : "Toggle favorite model"
    );
    const icon = surface.favoriteButton.querySelector(".bi");
    if (icon) {
      icon.className = isFavorite ? "bi bi-star-fill" : "bi bi-star";
    }
    surface.favoriteButton.disabled = !selected;
  }

  function updateActiveText(active) {
    const text = safeText(active) || "(backend default)";
    document.querySelectorAll("#activeModelText, [data-active-model-text]").forEach((node) => {
      node.textContent = text;
    });
  }

  function embeddingDiagnosticText(value, fallback = "-") {
    const text = safeText(value);
    return text || fallback;
  }

  function setEmbeddingDiagnosticText(id, value, fallback = "-") {
    const node = document.getElementById(id);
    if (!node) return;
    node.textContent = embeddingDiagnosticText(value, fallback);
  }

  function renderEmbeddingDiagnostics(snapshot) {
    const root = document.querySelector("[data-embedding-runtime-diagnostics='true']");
    if (!root) return;

    if (snapshot?.error) {
      setEmbeddingDiagnosticText("embeddingRuntimeStatus", "unavailable");
      setEmbeddingDiagnosticText("embeddingFailurePressure", "error=" + embeddingDiagnosticText(snapshot.error));
      return;
    }

    const ollama = snapshot?.ollama || {};
    const available = ollama.available === false ? "unavailable" : "available";
    setEmbeddingDiagnosticText("embeddingRuntimeStatus", available);
    setEmbeddingDiagnosticText("embeddingTargetDim", ollama.targetDim, "0");
    setEmbeddingDiagnosticText("embeddingRawProviderDim", ollama.rawProviderDim, "unknown");
    setEmbeddingDiagnosticText("embeddingNormalizationMode", ollama.normalizationMode);
    setEmbeddingDiagnosticText("embeddingFastFail", ollama.fastFailEnabled);
    setEmbeddingDiagnosticText("embeddingHealthMode", ollama.healthMode);
    setEmbeddingDiagnosticText("embeddingBackupAvailable", ollama.backupAvailable);
    setEmbeddingDiagnosticText(
      "embeddingFailurePressure",
      `failureStreak=${embeddingDiagnosticText(ollama.failureStreak, "0")} `
      + `tripCount=${embeddingDiagnosticText(ollama.tripCount, "0")} `
      + `skipRemainingMs=${embeddingDiagnosticText(ollama.skipRemainingMs, "0")}`
    );
  }

  async function refreshEmbeddingDiagnostics() {
    if (!document.querySelector("[data-embedding-runtime-diagnostics='true']")) return;
    try {
      const res = await fetch("/api/diagnostics/embedding", {
        credentials: "same-origin",
        headers: { Accept: "application/json" },
      });
      if (!res.ok) throw new Error("status=" + res.status);
      renderEmbeddingDiagnostics(await res.json());
    } catch (error) {
      renderEmbeddingDiagnostics({ error: error?.message || "unavailable" });
    }
  }

  async function chooseModel(surface, modelId, options = {}) {
    const id = safeText(modelId);
    if (!id || !isChatSelectableId(id)) return;
    surface.select.value = id;
    if (surface.mirrorSelect && surface.mirrorSelect !== surface.select) {
      surface.mirrorSelect.value = id;
    }
    if (options.persist) {
      const ok = await persistDefaultModel(id, { recordRecent: true });
      if (!ok) {
        refreshAllSurfaces();
        return;
      }
      storageSet(STRATEGY_KEY, "manual");
      storageSet(ACTIVE_KEY, id);
      if (window.initialData) window.initialData.currentModel = id;
      updateActiveText(id);
      const strategy = document.getElementById("modelStrategySelect");
      if (strategy) strategy.value = "manual";
      document.dispatchEvent(new CustomEvent("model-picker:changed", { detail: { modelId: id } }));
    }
    refreshAllSurfaces();
  }

  function ensureChrome(surface) {
    const key = surface.select.id || surface.name;
    const existing = document.querySelector(`[data-model-picker-controls="${key}"]`);
    if (existing) {
      surface.searchInput = existing.querySelector(".model-picker-search");
      surface.providerFilter = existing.querySelector(".model-picker-provider");
      surface.usageFilter = existing.querySelector(".model-picker-usage");
      surface.clearButton = existing.querySelector(".model-picker-clear");
      surface.favoriteButton = existing.querySelector(".model-picker-favorite-toggle");
      surface.resultCount = existing.querySelector(".model-picker-result-count");
      surface.quick = existing.querySelector(".model-picker-quick");
      if (!surfaceState.has(surface.select)) {
        surfaceState.set(surface.select, {
          search: surface.searchInput?.value || "",
          provider: surface.providerFilter?.value || "",
          usage: surface.usageFilter?.value || "",
        });
      }
      return;
    }

    const controls = document.createElement("div");
    controls.className = "model-picker-controls";
    controls.dataset.modelPickerControls = key;

    const search = document.createElement("input");
    search.type = "search";
    search.className = "form-control form-control-sm model-picker-search";
    search.placeholder = "Search models";
    search.autocomplete = "off";
    search.setAttribute("aria-label", "Search models");

    const provider = document.createElement("select");
    provider.className = "form-select form-select-sm model-picker-provider";
    provider.setAttribute("aria-label", "Filter models by provider");

    const usage = document.createElement("select");
    usage.className = "form-select form-select-sm model-picker-usage";
    usage.setAttribute("aria-label", "Filter models by use");

    const clear = document.createElement("button");
    clear.type = "button";
    clear.className = "btn btn-sm btn-outline-secondary model-picker-clear";
    clear.textContent = "Clear";
    clear.setAttribute("aria-label", "Clear model filters");

    const favorite = document.createElement("button");
    favorite.type = "button";
    favorite.className = "btn btn-sm btn-outline-warning model-picker-favorite-toggle";
    favorite.setAttribute("aria-label", "Toggle favorite model");
    favorite.setAttribute("aria-pressed", "false");
    favorite.innerHTML = '<i class="bi bi-star" aria-hidden="true"></i><span class="model-picker-favorite-fallback" aria-hidden="true">*</span>';

    const resultCount = document.createElement("span");
    resultCount.className = "model-picker-result-count";
    resultCount.setAttribute("aria-live", "polite");

    const quick = document.createElement("div");
    quick.className = "model-picker-quick";
    quick.setAttribute("aria-label", "Recent and favorite models");

    controls.append(search, provider, usage, clear, favorite, resultCount, quick);

    if (surface.name === "top") {
      const strategy = document.getElementById("modelStrategySelect");
      if (strategy?.parentElement) {
        strategy.insertAdjacentElement("afterend", controls);
      } else {
        surface.root.insertBefore(controls, surface.select);
      }
    } else if (surface.inputGroup) {
      surface.inputGroup.insertAdjacentElement("beforebegin", controls);
    } else {
      surface.select.insertAdjacentElement("beforebegin", controls);
    }

    surface.searchInput = search;
    surface.providerFilter = provider;
    surface.usageFilter = usage;
    surface.clearButton = clear;
    surface.favoriteButton = favorite;
    surface.resultCount = resultCount;
    surface.quick = quick;
    surface.enhanced = true;

    surfaceState.set(surface.select, { search: "", provider: "", usage: "" });

    const onFilter = () => {
      surfaceState.set(surface.select, {
        search: search.value,
        provider: provider.value,
        usage: usage.value,
      });
      refreshAllSurfaces();
    };

    search.addEventListener("input", onFilter);
    provider.addEventListener("change", onFilter);
    usage.addEventListener("change", onFilter);
    clear.addEventListener("click", () => {
      search.value = "";
      provider.value = "";
      usage.value = "";
      onFilter();
      search.focus();
    });
    favorite.addEventListener("click", () => toggleFavorite(surface.select.value));
  }

  function buildSurfaces() {
    const surfaces = [];
    const seen = new Set();

    const add = (name, select, options = {}) => {
      if (!select || seen.has(select)) return;
      seen.add(select);
      const inputGroup = select.closest(".input-group");
      surfaces.push({
        name,
        select,
        root: options.root || inputGroup?.parentElement || select.parentElement || document.body,
        inputGroup,
        persistOnChange: Boolean(options.persistOnChange),
        useStrategy: Boolean(options.useStrategy),
      });
    };

    add("top", document.getElementById("modelPicker"), {
      root: document.getElementById("model-strategy"),
      persistOnChange: true,
      useStrategy: true,
    });
    add("settings", document.getElementById("defaultModelSelect"));
    add("form", document.getElementById("defaultModel"));

    return surfaces;
  }

  function selectedActive(models, primarySelect) {
    const inList = (id) => (models || []).some((model) => model.id === id);
    let active =
      safeText(storageGet(ACTIVE_KEY)) ||
      safeText(window.initialData?.currentModel) ||
      safeText(window.initialData?.defaultModel);

    if (active && (!isChatSelectableId(active) || ((models || []).length && !inList(active)))) {
      storageRemove(ACTIVE_KEY);
      active = "";
    }

    if (!active) {
      active = pickPreferred(models) || primarySelect?.value || models?.[0]?.id || "";
    }
    return active;
  }

  async function initStrategy(models, active) {
    const strategySelect = document.getElementById("modelStrategySelect");
    if (!strategySelect) return active;

    let strategy = storageGet(STRATEGY_KEY);
    if (!strategy) {
      strategy = storageGet(ACTIVE_KEY) ? "manual" : "auto-moe";
      storageSet(STRATEGY_KEY, strategy);
    }
    strategySelect.value = strategy;

    if (strategy === "auto-moe") {
      const preferred = pickPreferred(models);
      const top = preferred ? { id: preferred } : pickTopMoe(models);
      if (top?.id && isChatSelectableId(top.id)) {
        active = top.id;
        storageSet(ACTIVE_KEY, active);
      }
    } else if (strategy === "backend-default") {
      active = "";
      storageSet(ACTIVE_KEY, "");
    } else if (active && isChatSelectableId(active)) {
      storageSet(ACTIVE_KEY, active);
    }

    if (!strategySelect.dataset.modelStrategyBound) {
      strategySelect.dataset.modelStrategyBound = "true";
      strategySelect.addEventListener("change", async () => {
        storageSet(STRATEGY_KEY, strategySelect.value);
        await initUI();
      });
    }
    return active;
  }

  function bindSurface(surface) {
    if (surface.select.dataset.modelPickerBound) return;
    surface.select.dataset.modelPickerBound = "true";
    surface.select.classList.add("model-picker-hidden-select");
    surface.select.addEventListener("change", () => {
      console.log("[AWX2AF2][model-picker:change]", {
        selected: surface.select.value,
        surface: surface.name,
        selectable: isChatSelectableId(surface.select.value),
      });
      if (surface.persistOnChange) {
        chooseModel(surface, surface.select.value, { persist: true });
      } else {
        refreshFavoriteButton(surface);
      }
    });

  }

  function refreshAllSurfaces() {
    const models = getInitialModels();
    cleanupStoredIds(models);
    const surfaces = buildSurfaces();
    const active = selectedActive(models, surfaces[0]?.select);

    if (models.length) {
      storageSet(CACHE_KEY, JSON.stringify(models));
    }

    for (const surface of surfaces) {
      ensureChrome(surface);
      populateFilterOptions(surface, models);
      renderSelect(surface, models, active);
      bindSurface(surface);
      renderQuickPicks(surface, models);
      refreshFavoriteButton(surface);
    }
    updateActiveText(active);
    void refreshEmbeddingDiagnostics();
  }

  async function initUI() {
    const models = getInitialModels();
    cleanupStoredIds(models);
    const surfaces = buildSurfaces();
    const active = await initStrategy(models, selectedActive(models, surfaces[0]?.select));
    if (active && document.querySelector("[data-model-save-success='true']")) {
      rememberRecent(active);
    }

    console.log("[AWX2AF2][model-picker:init]", {
      provider: window.initialData?.llmProvider,
      allowRemoteModelSelection: isRemoteSelectionAllowed(),
      currentModel: window.initialData?.currentModel,
      defaultModel: window.initialData?.defaultModel,
      modelCount: models.length,
      recentCount: readIdList(RECENT_KEY).length,
      favoriteCount: readIdList(FAVORITES_KEY).length,
    });

    if (models.length) {
      storageSet(CACHE_KEY, JSON.stringify(models));
    }

    for (const surface of surfaces) {
      ensureChrome(surface);
      populateFilterOptions(surface, models);
      renderSelect(surface, models, active);
      bindSurface(surface);
      renderQuickPicks(surface, models);
      refreshFavoriteButton(surface);
    }
    updateActiveText(active);
    void refreshEmbeddingDiagnostics();
  }

  window.__modelStrategy = {
    getActiveModel: () => safeText(storageGet(ACTIVE_KEY)),
    getRecentModels: () => readIdList(RECENT_KEY),
    getFavoriteModels: () => readIdList(FAVORITES_KEY),
    refresh: refreshAllSurfaces,
    setActiveModel: (modelId, options = {}) => {
      const id = safeText(modelId);
      if (!id || !isChatSelectableId(id)) return;
      const applyActive = () => {
        rememberRecent(id);
        storageSet(ACTIVE_KEY, id);
        storageSet(STRATEGY_KEY, "manual");
        if (window.initialData) window.initialData.currentModel = id;
        updateActiveText(id);
        refreshAllSurfaces();
      };
      if (options.persist) {
        persistDefaultModel(id, { recordRecent: false }).then((ok) => {
          if (ok) applyActive();
          else refreshAllSurfaces();
        });
      } else {
        applyActive();
      }
    },
    toggleFavorite,
  };

  if (isPrerender()) {
    document.addEventListener(
      "visibilitychange",
      () => { if (!isPrerender()) initUI(); },
      { once: true }
    );
  } else if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initUI, { once: true });
  } else {
    initUI();
  }
})();
