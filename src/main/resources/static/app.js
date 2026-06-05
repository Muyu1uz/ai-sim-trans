const statusEl = document.getElementById("status");
const listEl = document.getElementById("subtitleList");
const lines = new Map();

function connect() {
  const socket = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/subtitles`);
  socket.addEventListener("open", () => statusEl.textContent = "connected");
  socket.addEventListener("close", () => {
    statusEl.textContent = "reconnecting";
    setTimeout(connect, 1200);
  });
  socket.addEventListener("message", event => handleEvent(JSON.parse(event.data)));
}

function handleEvent(event) {
  if (event.type === "STATUS" || event.type === "ERROR") {
    statusEl.textContent = event.message || event.type.toLowerCase();
    return;
  }

  const id = event.segmentId || "current";
  const sourceText = event.sourceText || "";
  const translationText = event.translationText || event.delta || "";
  if (!sourceText && !translationText) {
    return;
  }

  let row = lines.get(id);
  if (!row) {
    row = document.createElement("article");
    row.className = "line";
    row.innerHTML = `<p class="source"></p><p class="translation"></p>`;
    lines.set(id, row);
    listEl.appendChild(row);
  }

  row.querySelector(".source").textContent = sourceText;
  row.querySelector(".translation").textContent = translationText;

  while (listEl.children.length > 3) {
    const first = listEl.firstElementChild;
    lines.delete([...lines.entries()].find(([, value]) => value === first)?.[0]);
    first.remove();
  }
}

document.getElementById("startBtn").addEventListener("click", async () => {
  const response = await fetch("/api/pipeline/start", { method: "POST" });
  statusEl.textContent = response.ok ? "starting" : "start failed";
});

document.getElementById("stopBtn").addEventListener("click", async () => {
  const response = await fetch("/api/pipeline/stop", { method: "POST" });
  statusEl.textContent = response.ok ? "stopped" : "stop failed";
});

connect();
