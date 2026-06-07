const statusEl = document.getElementById("status");
const modelStatusEl = document.getElementById("modelStatus");
const listEl = document.getElementById("subtitleList");
const jumpBottomBtn = document.getElementById("jumpBottomBtn");
const clearSubtitlesBtn = document.getElementById("clearSubtitlesBtn");
const settingsPanel = document.getElementById("settingsPanel");
const settingsBackdrop = document.getElementById("settingsBackdrop");
const lines = new Map();
const MAX_SUBTITLE_LINES = 500;

const controls = {
  mode: document.getElementById("modeSelect"),
  audioDevice: document.getElementById("audioDeviceSelect"),
  asrEngine: document.getElementById("asrEngineSelect"),
  asrModel: document.getElementById("asrModelInput"),
  vad: document.getElementById("vadSelect"),
  device: document.getElementById("deviceSelect"),
  translationModel: document.getElementById("translationModelInput"),
  translationBaseUrl: document.getElementById("translationBaseUrlInput"),
  translationPrompt: document.getElementById("translationPromptInput"),
};

let defaultModels = {};
let followLatest = true;

const modeLabels = {
  "local-asr": "\u672c\u5730 ASR",
  "dashscope-livetranslate": "DashScope \u4e91\u7aef\u540c\u4f20",
};

const vadLabels = {
  silero: "Silero VAD",
  energy: "Energy VAD",
  disabled: "\u7981\u7528 VAD",
};

function connect() {
  const socket = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/subtitles`);
  socket.addEventListener("open", () => statusEl.textContent = "\u5df2\u8fde\u63a5");
  socket.addEventListener("close", () => {
    statusEl.textContent = "\u6b63\u5728\u91cd\u8fde";
    setTimeout(connect, 1200);
  });
  socket.addEventListener("message", event => handleEvent(JSON.parse(event.data)));
}

function handleEvent(event) {
  if (event.type === "STATUS" || event.type === "ERROR") {
    statusEl.textContent = event.message || event.type.toLowerCase();
    return;
  }

  const cloudMode = controls.mode.value === "dashscope-livetranslate";
  let sourceText = normalizeSubtitleText(event.sourceText || "");
  let translationText = normalizeSubtitleText(event.translationText || "");
  if (cloudMode) {
    if (!event.type || !event.type.startsWith("TRANSLATION_")) {
      return;
    }
    sourceText = "";
  }
  if (!sourceText && !translationText) {
    return;
  }
  const id = event.segmentId || "current";

  let row = lines.get(id);
  if (!row) {
    row = document.createElement("article");
    row.className = "line";
    row.innerHTML = `<p class="source"></p><p class="translation"></p>`;
    lines.set(id, row);
    listEl.appendChild(row);
  }

  const sourceEl = row.querySelector(".source");
  const translationEl = row.querySelector(".translation");
  if (shouldApplySource(event, sourceText, cloudMode)) {
    sourceEl.textContent = sourceText;
  }
  if (shouldApplyTranslation(event, translationText)) {
    translationEl.textContent = translationText;
  }

  const hasTranslation = Boolean(translationEl.textContent.trim());
  row.classList.toggle("source-only", !hasTranslation);
  row.classList.toggle("has-translation", hasTranslation);
  row.classList.toggle("is-final", event.type === "TRANSLATION_COMPLETED");
  if (event.type === "CORRECTED") {
    row.classList.add("is-corrected");
    window.setTimeout(() => row.classList.remove("is-corrected"), 1400);
  }
  row.dataset.updatedAt = String(Date.now());

  markCurrentLine(id);

  while (listEl.children.length > MAX_SUBTITLE_LINES) {
    const first = listEl.firstElementChild;
    lines.delete([...lines.entries()].find(([, value]) => value === first)?.[0]);
    first.remove();
  }

  if (followLatest) {
    scrollSubtitlesToBottom();
  } else {
    updateJumpBottomVisibility();
  }
}

function normalizeSubtitleText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function shouldApplySource(event, sourceText, cloudMode) {
  if (event.type === "SOURCE_DELTA" || event.type === "CORRECTED") {
    return true;
  }
  return !cloudMode && Boolean(sourceText);
}

function shouldApplyTranslation(event, translationText) {
  if (event.type === "SOURCE_DELTA" || event.type === "CORRECTED") {
    return true;
  }
  return Boolean(translationText);
}

function markCurrentLine(id) {
  for (const [lineId, row] of lines.entries()) {
    row.classList.toggle("is-current", lineId === id);
  }
}

function scrollSubtitlesToBottom() {
  listEl.scrollTop = listEl.scrollHeight;
  followLatest = true;
  updateJumpBottomVisibility();
}

function updateFollowLatestState() {
  const distanceFromBottom = listEl.scrollHeight - listEl.scrollTop - listEl.clientHeight;
  followLatest = distanceFromBottom < 32;
  updateJumpBottomVisibility();
}

function updateJumpBottomVisibility() {
  jumpBottomBtn.classList.toggle("is-visible", !followLatest);
}

function clearSubtitles() {
  lines.clear();
  listEl.replaceChildren();
  followLatest = true;
  updateJumpBottomVisibility();
}

function openSettings() {
  settingsPanel.classList.add("is-open");
  settingsPanel.setAttribute("aria-hidden", "false");
  settingsBackdrop.hidden = false;
}

function closeSettings() {
  settingsPanel.classList.remove("is-open");
  settingsPanel.setAttribute("aria-hidden", "true");
  settingsBackdrop.hidden = true;
}

function isMostlyCjk(value) {
  const text = String(value || "").replace(/\s+/g, "");
  if (!text) {
    return false;
  }
  const cjk = text.match(/[\u3400-\u9fff\u3000-\u303f\uff00-\uffef]/g) || [];
  return cjk.length / text.length >= 0.45;
}

async function loadRuntimeOptions() {
  const options = await fetchJson("/api/runtime/options");
  const devices = await fetchJson("/api/audio/devices");
  defaultModels = options.defaultModels || {};
  fillSelect(controls.mode, options.modes || [], modeLabels);
  fillAudioDevices(devices.value || devices || [], options.config?.audioDeviceName || "");
  fillSelect(controls.asrEngine, options.asrEngines || []);
  fillSelect(controls.vad, options.vadProviders || [], vadLabels);
  applyRuntimeConfig(options.config);
  setModelStatus(options.modelStatus, options.modelMessage);
}

function fillSelect(select, values, labels = {}) {
  select.innerHTML = "";
  values.forEach(value => {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = labels[value] || value;
    select.appendChild(option);
  });
}

function fillAudioDevices(devices, selected) {
  controls.audioDevice.innerHTML = "";
  const defaultOption = document.createElement("option");
  defaultOption.value = "";
  defaultOption.textContent = "\u7cfb\u7edf\u9ed8\u8ba4\u8f93\u51fa";
  controls.audioDevice.appendChild(defaultOption);
  devices.forEach(device => {
    const option = document.createElement("option");
    option.value = device;
    option.textContent = device;
    controls.audioDevice.appendChild(option);
  });
  controls.audioDevice.value = selected || "";
}

function applyRuntimeConfig(config) {
  controls.mode.value = config.mode;
  controls.audioDevice.value = config.audioDeviceName || "";
  controls.asrEngine.value = config.asrEngine;
  controls.asrModel.value = config.asrModelId;
  controls.vad.value = config.vadProvider;
  if (!controls.vad.value && controls.vad.options.length > 0) {
    controls.vad.value = controls.vad.options[0].value;
  }
  controls.device.value = config.asrDevice;
  controls.translationModel.value = config.translationModel;
  controls.translationBaseUrl.value = config.translationBaseUrl;
  controls.translationPrompt.value = config.translationPrompt || "";
  applyModeState();
}

async function saveRuntimeConfig() {
  const config = await fetchJson("/api/runtime/config", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(runtimePayload()),
  });
  applyRuntimeConfig(config);
  statusEl.textContent = "\u8bbe\u7f6e\u5df2\u4fdd\u5b58";
}

async function loadModel() {
  await saveRuntimeConfig();
  setModelStatus("loading", "\u6b63\u5728\u52a0\u8f7d\u6a21\u578b");
  const status = await fetchJson("/api/runtime/model/load", { method: "POST" });
  setModelStatus(status.status, status.message);
}

function runtimePayload() {
  return {
    mode: controls.mode.value,
    audioDeviceName: controls.audioDevice.value,
    asrEngine: controls.asrEngine.value,
    asrModelId: controls.asrModel.value,
    vadProvider: controls.vad.value || "energy",
    asrDevice: controls.device.value,
    translationModel: controls.translationModel.value,
    translationBaseUrl: controls.translationBaseUrl.value,
    translationPrompt: controls.translationPrompt.value,
  };
}

function setModelStatus(status, message) {
  modelStatusEl.dataset.state = status || "missing";
  modelStatusEl.textContent = message || status || "\u6a21\u578b\u5c1a\u672a\u68c0\u67e5";
}

function applyModeState() {
  const cloudMode = controls.mode.value === "dashscope-livetranslate";
  document.querySelectorAll("[data-local-only]").forEach(group => {
    group.classList.toggle("is-disabled", cloudMode);
    group.querySelectorAll("input, select, textarea, button").forEach(element => {
      element.disabled = cloudMode;
    });
  });
  document.querySelectorAll("[data-local-control]").forEach(element => {
    element.disabled = cloudMode;
    element.classList.toggle("is-disabled", cloudMode);
  });
  if (cloudMode) {
    setModelStatus("ready", "DashScope \u4e91\u7aef\u540c\u4f20\u6a21\u5f0f\u4e0d\u4f7f\u7528\u672c\u5730 ASR/VAD/\u7ffb\u8bd1\u6a21\u578b");
  }
}

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    throw new Error(await response.text());
  }
  return response.json();
}

controls.asrEngine.addEventListener("change", () => {
  controls.asrModel.value = defaultModels[controls.asrEngine.value] || controls.asrModel.value;
});

controls.mode.addEventListener("change", applyModeState);

listEl.addEventListener("scroll", updateFollowLatestState);
jumpBottomBtn.addEventListener("click", scrollSubtitlesToBottom);
clearSubtitlesBtn.addEventListener("click", clearSubtitles);
document.getElementById("settingsOpenBtn").addEventListener("click", openSettings);
document.getElementById("settingsCloseBtn").addEventListener("click", closeSettings);
settingsBackdrop.addEventListener("click", closeSettings);
document.addEventListener("keydown", event => {
  if (event.key === "Escape") {
    closeSettings();
  }
});

document.getElementById("saveRuntimeBtn").addEventListener("click", () => {
  saveRuntimeConfig().catch(error => statusEl.textContent = error.message);
});

document.getElementById("loadModelBtn").addEventListener("click", () => {
  loadModel().catch(error => setModelStatus("error", error.message));
});

document.getElementById("startBtn").addEventListener("click", async () => {
  try {
    await saveRuntimeConfig();
    const response = await fetch("/api/pipeline/start", { method: "POST" });
    if (!response.ok) {
      throw new Error(await response.text());
    }
    statusEl.textContent = "\u6b63\u5728\u542f\u52a8";
    await loadRuntimeOptions();
  } catch (error) {
    statusEl.textContent = error.message;
  }
});

document.getElementById("stopBtn").addEventListener("click", async () => {
  const response = await fetch("/api/pipeline/stop", { method: "POST" });
  statusEl.textContent = response.ok ? "\u5df2\u505c\u6b62" : "\u505c\u6b62\u5931\u8d25";
});

loadRuntimeOptions().catch(error => statusEl.textContent = error.message);
connect();
