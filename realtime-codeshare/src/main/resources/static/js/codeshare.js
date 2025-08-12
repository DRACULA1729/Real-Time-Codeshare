// codeshare.js — OT client with pending queue + JS OTEngine + Remote Cursors
console.log("codeshare (OT client) booting...");

const WS_BASE = "ws://localhost:8080/ws/ot?sessionId=";
const sessionId = prompt("Enter session id (e.g., alpha123)") || "alpha123";
const userName =prompt("Enter your pseudo name (e.g. Ghost)") || "Anonymous";
const clientId = "client-" + Math.floor(Math.random() * 100000);
// Show session info on topbar
document.addEventListener("DOMContentLoaded", () => {
  document.getElementById('sessionId').innerText = sessionId;
  document.getElementById('userName').innerText = clientId;
  document.getElementById('sessionInfo').innerText = `Session: ${sessionId}  Name: ${userName}  ${clientId}`;
});

let baseVersion = 0;
const pendingOps = []; // FIFO pending ops

// ---------------- Editor ----------------
const editor = CodeMirror.fromTextArea(document.getElementById("editor"), {
  mode: "text/x-java",
  lineNumbers: true,
  lineWrapping: true,
  autofocus: true
});

let suppress = false;
function runWithoutSending(fn) {
  suppress = true;
  try { fn(); } finally { suppress = false; }
}

// ---------------- Helpers ----------------
function posToIndex(pos) {
  const doc = editor.getDoc();
  let index = 0;
  for (let i = 0; i < pos.line; i++) index += doc.getLine(i).length + 1;
  return index + pos.ch;
}
function indexToPos(index) {
  const doc = editor.getDoc();
  let line = 0;
  while (line < doc.lineCount()) {
    const lineText = doc.getLine(line);
    if (index <= lineText.length) return { line, ch: index };
    index -= (lineText.length + 1);
    line++;
  }
  return { line: doc.lineCount()-1, ch: doc.getLine(Math.max(0, doc.lineCount()-1)).length };
}

// ---------------- OT Engine ----------------
function clientWinsInsertTie(aClient, bClient) {
  return String(aClient).localeCompare(String(bClient)) < 0;
}
function copyOp(op) { return JSON.parse(JSON.stringify(op)); }
function transformOp(a, b) {
  const atype = String(a.type).toLowerCase();
  const btype = String(b.type).toLowerCase();
  if (atype === "insert" && btype === "insert") return transformInsertInsert(copyOp(a), b);
  if (atype === "insert" && btype === "delete") return transformInsertDelete(copyOp(a), b);
  if (atype === "delete" && btype === "insert") return transformDeleteInsert(copyOp(a), b);
  return transformDeleteDelete(copyOp(a), b);
}
function transformInsertInsert(a, b) {
  if (a.pos < b.pos) {
  } else if (a.pos > b.pos) {
    a.pos += (b.text || "").length;
  } else {
    if (!clientWinsInsertTie(a.clientId, b.clientId)) {
      a.pos += (b.text || "").length;
    }
  }
  return a;
}
function transformInsertDelete(a, b) {
  if (a.pos <= b.pos) {
  } else if (a.pos > b.pos + (b.length || 0)) {
    a.pos -= (b.length || 0);
  } else {
    a.pos = b.pos;
  }
  return a;
}
function transformDeleteInsert(a, b) {
  if (b.pos <= a.pos) {
    a.pos += (b.text || "").length;
  } else if (b.pos < a.pos + a.length) {
    a.length += (b.text || "").length;
  }
  return a;
}
function transformDeleteDelete(a, b) {
  const aStart = a.pos, aEnd = a.pos + a.length;
  const bStart = b.pos, bEnd = b.pos + b.length;
  if (aEnd <= bStart) {
  } else if (aStart >= bEnd) {
    a.pos -= b.length;
  } else {
    const overlapStart = Math.max(aStart, bStart);
    const overlapEnd = Math.min(aEnd, bEnd);
    const overlap = Math.max(0, overlapEnd - overlapStart);
    a.length -= overlap;
    if (aStart >= bStart) {
      a.pos -= Math.max(0, Math.min(bEnd, aStart) - bStart);
    }
  }
  if (a.length < 0) a.length = 0;
  return a;
}

// ---------------- Remote Cursor System ----------------
function colorForClient(id) {
  const colors = ["red", "blue", "green", "purple", "orange", "magenta", "cyan", "pink"];
  let hash = 0;
  for (let i = 0; i < id.length; i++) hash = id.charCodeAt(i) + ((hash << 5) - hash);
  return colors[Math.abs(hash) % colors.length];
}
const remoteCursors = {}; // clientId -> marker
function sendCursor() {
  const doc = editor.getDoc();
  const pos = doc.getCursor();
  const index = posToIndex(pos);
  ws.send(JSON.stringify({ type: "cursor", clientId, pos: index, sessionId }));
}
function updateRemoteCursor(fromClient, index) {
  if (remoteCursors[fromClient]) remoteCursors[fromClient].clear();
  const doc = editor.getDoc();
  const cursorPos = indexToPos(index);
  const cursorEl = document.createElement("span");
  cursorEl.style.borderLeft = `2px solid ${colorForClient(fromClient)}`;
  cursorEl.style.height = `${(editor.defaultTextHeight() || 14)}px`;
  cursorEl.style.marginLeft = cursorEl.style.marginRight = "0px";
  cursorEl.title = fromClient;
  const label = document.createElement("div");
  label.style.position = "absolute";
  label.style.background = colorForClient(fromClient);
  label.style.color = "white";
  label.style.fontSize = "10px";
  label.style.padding = "0 2px";
  label.style.borderRadius = "3px";
  label.innerText = fromClient;
  cursorEl.appendChild(label);
  remoteCursors[fromClient] = doc.setBookmark(cursorPos, { widget: cursorEl });
}

// ---------------- WebSocket ----------------
const ws = new WebSocket(WS_BASE + encodeURIComponent(sessionId));
ws.onopen = () => console.log("Connected to OT server");
ws.onerror = (e) => console.error("WS error", e);

ws.onmessage = (evt) => {
  const msg = JSON.parse(evt.data);

  if (msg.type === "init") {
    runWithoutSending(() => {
      editor.setValue(msg.document || "");
      baseVersion = msg.version || 0;
      pendingOps.length = 0;
    });
    console.log("[OTCLIENT] init v=", baseVersion);
    return;
  }

  if (msg.type === "op") {
    const serverOp = msg.op;
    const serverVersion = msg.version;
    serverOp.type = String(serverOp.type).toLowerCase();

    if (serverOp.clientId === clientId) {
      pendingOps.shift();
      for (let i = 0; i < pendingOps.length; i++) {
        pendingOps[i] = transformOp(pendingOps[i], serverOp);
      }
      baseVersion = serverVersion;
      console.log("[OTCLIENT] ACK received, new version:", baseVersion);
      return;
    }

    let incoming = serverOp;
    for (const p of pendingOps) incoming = transformOp(incoming, p);

    runWithoutSending(() => {
      applyOpToEditor(incoming);
    });
    baseVersion = serverVersion;
    console.log("[OTCLIENT] Applied remote op, version:", baseVersion);
    return;
  }

  if (msg.type === "cursor") {
    if (msg.clientId !== clientId) {
      updateRemoteCursor(msg.clientId, msg.pos);
    }
  }
};

// ---------------- Apply op ----------------
function applyOpToEditor(op) {
  if (!op) return;
  if (String(op.type).toLowerCase() === "insert") {
    const pos = indexToPos(op.pos);
    editor.replaceRange(op.text || "", pos, pos, "remote");
  } else {
    const from = indexToPos(op.pos);
    const to = indexToPos(op.pos + op.length);
    editor.replaceRange("", from, to, "remote");
  }
}

// ---------------- Local edit handling ----------------
editor.on("cursorActivity", () => sendCursor());
editor.on("beforeChange", (cm, change) => {
  if (suppress) return;
  const fromIndex = posToIndex(change.from);
  const toIndex = posToIndex(change.to);
  const removedLen = Math.max(0, toIndex - fromIndex);
  const insertedText = change.text.join("\n");
  const opsToSend = [];
  if (removedLen > 0 && insertedText.length > 0) {
    opsToSend.push({ clientId, type: "delete", baseVersion, pos: fromIndex, length: removedLen });
    opsToSend.push({ clientId, type: "insert", baseVersion, pos: fromIndex, text: insertedText });
  } else if (insertedText.length > 0) {
    opsToSend.push({ clientId, type: "insert", baseVersion, pos: fromIndex, text: insertedText });
  } else if (removedLen > 0) {
    opsToSend.push({ clientId, type: "delete", baseVersion, pos: fromIndex, length: removedLen });
  }
  if (opsToSend.length > 0) {
    for (const op of opsToSend) {
      op.type = String(op.type).toLowerCase();
      pendingOps.push(copyOp(op));
      ws.send(JSON.stringify(op));
    }
  }
});
// ---------- Helpers for language ext detection (reuse existing mode logic) ----------
function detectExtension() {
  const mode = (editor.getOption("mode") || "").toLowerCase();
  switch (mode) {
    case "text/x-java": return "java";
    case "javascript":
    case "text/javascript": return "js";
    case "python":
    case "text/x-python": return "py";
    case "text/x-csrc": return "c";
    case "text/x-c++src": return "cpp";
    case "text/html": return "html";
    case "text/css": return "css";
    case "text/x-sql": return "sql";
    default: return "txt";
  }
}

// ---------- UI wiring ----------
const filenameInput = document.getElementById("filenameInput");
const downloadMainBtn = document.getElementById("downloadMainBtn");
const downloadMenuBtn = document.getElementById("downloadMenuBtn");
const downloadMenu = document.getElementById("downloadMenu");
const downloadExtDisplay = document.getElementById("downloadExtDisplay");

// initialize ext display
downloadExtDisplay.innerText = detectExtension();

// toggle menu
downloadMenuBtn.addEventListener("click", () => {
  downloadMenu.style.display = downloadMenu.style.display === "none" ? "block" : "none";
});

// update ext display on mode change (if you change editor mode dynamically)
editor.on("optionChange", (cm, option) => {
  if (option === "mode") downloadExtDisplay.innerText = detectExtension();
});

// menu option clicks
downloadMenu.querySelectorAll(".download-option").forEach(opt => {
  opt.addEventListener("click", () => {
    const mode = opt.getAttribute("data-mode"); // "raw" or "zip"
    handleDownloadChoice(mode);
    downloadMenu.style.display = "none";
  });
});

// main button = raw download by default
downloadMainBtn.addEventListener("click", () => handleDownloadChoice("raw"));

// ---------- download handlers ----------
function filenameOrDefault() {
  const ext = detectExtension();
  const raw = (filenameInput.value || "").trim();
  if (!raw) return `${sessionId || "code"}.${ext}`;
  // add extension if user hasn't
  if (!raw.includes(".")) return `${raw}.${ext}`;
  return raw;
}

// Raw download (client-only)
function downloadRaw(filename) {
  const code = editor.getValue();
  const blob = new Blob([code], { type: "text/plain" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
}

// Zip download (client-side, includes metadata.json)
async function downloadZip(filenameBase) {
  const code = editor.getValue();
  const ext = detectExtension();
  const filename = filenameBase.endsWith(`.${ext}`) ? filenameBase : `${filenameBase}.${ext}`;

  const meta = {
    sessionId,
    filename,
    version: baseVersion,
    clientId,
    timestamp: new Date().toISOString()
    // optionally: participants list if you maintain it in client
  };

  const zip = new JSZip();
  zip.file(filename, code);
  zip.file("metadata.json", JSON.stringify(meta, null, 2));

  const content = await zip.generateAsync({ type: "blob" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(content);
  a.download = (filenameBase.endsWith(".zip") ? filenameBase : `${filenameBase}.zip`);
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
}

// route handler
function handleDownloadChoice(mode) {
  const input = filenameInput.value.trim();
  const defaultName = (input && input.length > 0) ? input : sessionId || "code";
  if (mode === "raw") {
    downloadRaw(defaultName.includes(".") ? defaultName : `${defaultName}.${detectExtension()}`);
  } else {
    downloadZip(defaultName);
  }
}

async function pushToGitHub() {
  const owner = prompt("GitHub owner/org:");
  const repo = prompt("Repo name:");
  const path = prompt("File path (e.g. src/Main.java):", `${sessionId}.${detectExtension()}`);
  const message = prompt("Commit message:", "Commit from Ghost CodeShare");
  const token = prompt("Personal Access Token (has repo:contents permissions):");

  if (!owner || !repo || !path || !token) return alert("Missing fields");

  const payload = { token, owner, repo, path, message, sessionId };
  const resp = await fetch("/api/github/push", {
    method: "POST",
    headers: { "Content-Type":"application/json" },
    body: JSON.stringify(payload)
  });

  const txt = await resp.text();
  if (resp.ok) alert("Pushed to GitHub — response: " + txt);
  else alert("GitHub push failed: " + txt);
}

document.getElementById("githubPushBtn").addEventListener("click", pushToGitHub);
