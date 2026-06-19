// codeshare.js — OT client: create/join gate, waiting room, presence, GitHub OAuth push.
"use strict";

const $ = (id) => document.getElementById(id);
const clientId = "client-" + Math.floor(Math.random() * 1e9);

let sessionId = new URLSearchParams(location.search).get("sessionId");
let myToken = null;
let myRole = "JOINER";
let myName = "Anonymous";
let myColor = "#7aa2f7";
let admitted = false;

let baseVersion = 0;
const pendingOps = [];      // FIFO of un-acked local ops
let ws = null;
let editor = null;

// ---------------- per-session profile persistence ----------------
const k = (p) => `cs-${p}:${sessionId}`;
function storeProfile(token, name, color) {
  if (!sessionId) return;
  if (token) sessionStorage.setItem(k("token"), token);
  if (name) sessionStorage.setItem(k("name"), name);
  if (color) sessionStorage.setItem(k("color"), color);
}
const storedToken = () => sessionId && sessionStorage.getItem(k("token"));
const storedName = () => sessionId && sessionStorage.getItem(k("name"));
const storedColor = () => sessionId && sessionStorage.getItem(k("color"));

// ---------------- gate panels ----------------
function showPanel(id) {
  ["createPanel", "joinPanel", "waitingPanel", "deniedPanel"].forEach(p => { $(p).hidden = (p !== id); });
  $("gate").hidden = false;
}
function showDenied(reason) {
  admitted = false;
  $("app").hidden = true;
  $("deniedMsg").textContent = reason || "Your request was declined.";
  showPanel("deniedPanel");
}

document.addEventListener("DOMContentLoaded", () => {
  $("createBtn").addEventListener("click", onCreate);
  $("joinBtn").addEventListener("click", onJoin);

  if (sessionId && storedToken() && storedName()) {
    // Returning member (e.g. back from GitHub OAuth) — reconnect straight away.
    connect(storedName(), storedColor() || myColor, storedToken());
  } else if (sessionId) {
    $("joinSessionId").textContent = sessionId;
    if (storedName()) $("joinName").value = storedName();
    showPanel("joinPanel");
  } else {
    showPanel("createPanel");
  }
});

async function onCreate() {
  const name = $("createName").value.trim() || "Anonymous";
  const color = $("createColor").value || myColor;
  let data;
  try {
    const resp = await fetch("/api/sessions", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, color })
    });
    if (!resp.ok) throw new Error("create failed");
    data = await resp.json();
  } catch (e) {
    alert("Could not create a session. Is the server reachable?");
    return;
  }
  sessionId = data.sessionId;
  storeProfile(data.adminToken, name, color);
  history.replaceState({}, "", "?sessionId=" + encodeURIComponent(sessionId));
  connect(name, color, data.adminToken);
}

function onJoin() {
  const name = $("joinName").value.trim() || "Anonymous";
  const color = $("joinColor").value || myColor;
  storeProfile(null, name, color);
  showPanel("waitingPanel");
  connect(name, color, storedToken());
}

// ---------------- WebSocket ----------------
function connect(name, color, token) {
  myName = name;
  myColor = color;
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  const params = new URLSearchParams({ sessionId, clientId, name, color });
  if (token) params.set("token", token);
  ws = new WebSocket(`${proto}//${location.host}/ws/ot?${params.toString()}`);
  ws.onmessage = onMessage;
  ws.onerror = (e) => console.error("WS error", e);
  ws.onclose = (e) => {
    if (!admitted && $("deniedPanel").hidden && $("waitingPanel").hidden) {
      showDenied("Session not found or no longer available.");
    }
  };
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function onMessage(evt) {
  let msg;
  try { msg = JSON.parse(evt.data); } catch { return; }
  switch (msg.type) {
    case "init": handleInit(msg); break;
    case "op": handleOp(msg); break;
    case "cursor": if (msg.clientId !== clientId) showRemoteCursor(msg.clientId, indexToPos(msg.pos), colorForClient(msg.clientId)); break;
    case "cursorRemove": removeRemoteCursor(msg.clientId); break;
    case "presence": renderPresence(msg.users || []); break;
    case "waitingForApproval": myToken = msg.token; storeProfile(msg.token, myName, myColor); showPanel("waitingPanel"); break;
    case "joinRequest": addJoinRequest(msg); break;
    case "joinResolved": removeJoinRequest(msg.joinKey); break;
    case "joinDenied": showDenied(msg.reason); break;
    case "adminChanged": onAdminChanged(msg); break;
    case "kicked": showDenied("You were removed from the session by the host."); break;
    case "sessionEnded": showDenied("The host ended the session."); break;
    default: break;
  }
}

function handleInit(msg) {
  admitted = true;
  myToken = msg.token || myToken;
  myRole = msg.role || "JOINER";
  storeProfile(myToken, myName, myColor);

  $("gate").hidden = true;
  $("app").hidden = false;
  ensureEditor();

  runWithoutSending(() => {
    editor.setValue(msg.document || "");
    baseVersion = msg.version || 0;
    pendingOps.length = 0;
  });
  detectAndSetMode();

  $("sessionInfo").textContent = `Session: ${sessionId}`;
  $("joinRequestsPanel").hidden = (myRole !== "CREATOR");
  initGitHub();
}

// ---------------- editor ----------------
let suppress = false;
function runWithoutSending(fn) { suppress = true; try { fn(); } finally { suppress = false; } }

function ensureEditor() {
  if (editor) return;
  editor = CodeMirror.fromTextArea($("editor"), {
    mode: "text/plain", lineNumbers: true, lineWrapping: true, autofocus: true, theme: "dracula"
  });
  editor.on("beforeChange", onLocalChange);
  editor.on("cursorActivity", sendCursor);
  let detectTimer;
  editor.on("change", () => { clearTimeout(detectTimer); detectTimer = setTimeout(detectAndSetMode, 600); });
  wireActions();
}

function onLocalChange(cm, change) {
  if (suppress) return;
  const fromIndex = posToIndex(change.from);
  const toIndex = posToIndex(change.to);
  const removedLen = Math.max(0, toIndex - fromIndex);
  const insertedText = change.text.join("\n");
  const ops = [];
  if (removedLen > 0) ops.push({ clientId, type: "delete", baseVersion, pos: fromIndex, length: removedLen });
  if (insertedText.length > 0) ops.push({ clientId, type: "insert", baseVersion, pos: fromIndex, text: insertedText });
  for (const op of ops) {
    pendingOps.push(copyOp(op));
    send(op);
  }
}

function handleOp(msg) {
  const serverOp = msg.op;
  serverOp.type = String(serverOp.type).toLowerCase();
  if (serverOp.clientId === clientId) {
    pendingOps.shift();
    for (let i = 0; i < pendingOps.length; i++) pendingOps[i] = transformOp(pendingOps[i], serverOp);
    baseVersion = msg.version;
    return;
  }
  let incoming = serverOp;
  for (const p of pendingOps) incoming = transformOp(incoming, p);
  runWithoutSending(() => applyOpToEditor(incoming));
  baseVersion = msg.version;
}

function applyOpToEditor(op) {
  if (!op) return;
  if (String(op.type).toLowerCase() === "insert") {
    const pos = indexToPos(op.pos);
    editor.replaceRange(op.text || "", pos, pos, "remote");
  } else {
    editor.replaceRange("", indexToPos(op.pos), indexToPos(op.pos + op.length), "remote");
  }
}

// ---------------- position helpers ----------------
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
  const last = Math.max(0, doc.lineCount() - 1);
  return { line: last, ch: doc.getLine(last).length };
}

// ---------------- OT engine (client mirror of the server) ----------------
function clientWinsInsertTie(a, b) { return String(a).localeCompare(String(b)) < 0; }
function copyOp(op) { return JSON.parse(JSON.stringify(op)); }
function transformOp(a, b) {
  const at = String(a.type).toLowerCase(), bt = String(b.type).toLowerCase();
  if (at === "insert" && bt === "insert") return transformII(copyOp(a), b);
  if (at === "insert" && bt === "delete") return transformID(copyOp(a), b);
  if (at === "delete" && bt === "insert") return transformDI(copyOp(a), b);
  return transformDD(copyOp(a), b);
}
function transformII(a, b) {
  if (a.pos < b.pos) {} else if (a.pos > b.pos) a.pos += (b.text || "").length;
  else if (!clientWinsInsertTie(a.clientId, b.clientId)) a.pos += (b.text || "").length;
  return a;
}
function transformID(a, b) {
  if (a.pos <= b.pos) {} else if (a.pos > b.pos + (b.length || 0)) a.pos -= (b.length || 0);
  else a.pos = b.pos;
  return a;
}
function transformDI(a, b) {
  if (b.pos <= a.pos) a.pos += (b.text || "").length;
  else if (b.pos < a.pos + a.length) a.length += (b.text || "").length;
  return a;
}
function transformDD(a, b) {
  const aStart = a.pos, aEnd = a.pos + a.length, bStart = b.pos, bEnd = b.pos + b.length;
  if (aEnd <= bStart) {} else if (aStart >= bEnd) a.pos -= b.length;
  else {
    const overlap = Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
    a.length -= overlap;
    if (aStart >= bStart) a.pos -= Math.max(0, Math.min(bEnd, aStart) - bStart);
  }
  if (a.length < 0) a.length = 0;
  return a;
}

// ---------------- remote cursors ----------------
const remoteCursors = {};
function colorForClient(id) {
  const colors = ["#f7768e", "#7aa2f7", "#9ece6a", "#bb9af7", "#e0af68", "#7dcfff", "#ff9e64"];
  let hash = 0;
  for (let i = 0; i < id.length; i++) hash = id.charCodeAt(i) + ((hash << 5) - hash);
  return colors[Math.abs(hash) % colors.length];
}
function sendCursor() {
  if (!admitted) return;
  send({ type: "cursor", clientId, pos: posToIndex(editor.getDoc().getCursor()), sessionId });
}
function showRemoteCursor(id, pos, color) {
  removeRemoteCursor(id);
  const el = document.createElement("span");
  el.style.borderLeft = `2px solid ${color}`;
  el.style.height = `${editor.defaultTextHeight() || 14}px`;
  el.style.display = "inline-block";
  el.title = id;
  remoteCursors[id] = editor.getDoc().setBookmark(pos, { widget: el });
}
function removeRemoteCursor(id) {
  if (remoteCursors[id]) { remoteCursors[id].clear(); delete remoteCursors[id]; }
}

// ---------------- presence & admit panel ----------------
function renderPresence(users) {
  const list = $("usersOnlineList");
  list.innerHTML = "";
  for (const u of users) {
    if (u.clientId === clientId) { myRole = u.role; }
    const badge = document.createElement("span");
    badge.className = "user-badge";
    badge.style.backgroundColor = u.color || "#555";
    const isHost = u.role === "CREATOR";
    badge.textContent = (u.name || "Anonymous") + (u.clientId === clientId ? " (you)" : "") + (isHost ? " ★" : "");
    if (myRole === "CREATOR" && u.clientId !== clientId) {
      const kick = document.createElement("button");
      kick.className = "kick-btn";
      kick.textContent = "×";
      kick.title = "Remove";
      kick.addEventListener("click", () => send({ type: "kick", clientId: u.clientId }));
      badge.appendChild(kick);
    }
    list.appendChild(badge);
  }
  $("joinRequestsPanel").hidden = (myRole !== "CREATOR");
}

function addJoinRequest(msg) {
  if ($(`jr-${msg.joinKey}`)) return;
  const row = document.createElement("div");
  row.className = "join-request";
  row.id = `jr-${msg.joinKey}`;
  const label = document.createElement("span");
  label.textContent = msg.name || "Anonymous";
  if (msg.color) label.style.color = msg.color;
  const ok = document.createElement("button");
  ok.textContent = "Admit";
  ok.addEventListener("click", () => send({ type: "approve", joinKey: msg.joinKey }));
  const no = document.createElement("button");
  no.className = "ghost";
  no.textContent = "Deny";
  no.addEventListener("click", () => send({ type: "reject", joinKey: msg.joinKey }));
  row.append(label, ok, no);
  $("joinRequestsList").appendChild(row);
}
function removeJoinRequest(joinKey) {
  const row = $(`jr-${joinKey}`);
  if (row) row.remove();
}

function onAdminChanged(msg) {
  if (msg.clientId === clientId) {
    myRole = "CREATOR";
    $("joinRequestsPanel").hidden = false;
  }
}

// ---------------- actions: download, copy link, language ----------------
function wireActions() {
  $("toggleActionsBtn").addEventListener("click", () =>
    document.querySelector(".action-buttons-collapsible").classList.toggle("expanded"));

  $("copyLinkBtn").addEventListener("click", async () => {
    const link = `${location.origin}/?sessionId=${encodeURIComponent(sessionId)}`;
    try { await navigator.clipboard.writeText(link); $("copyLinkStatus").textContent = "copied!"; }
    catch { $("copyLinkStatus").textContent = link; }
    setTimeout(() => { $("copyLinkStatus").textContent = ""; }, 2500);
  });

  const menuBtn = $("downloadMenuBtn"), menu = $("downloadMenu");
  menuBtn.addEventListener("click", () => { menu.style.display = (menu.style.display === "block") ? "none" : "block"; });
  $("downloadMainBtn").addEventListener("click", () => handleDownloadChoice("raw"));
  menu.querySelectorAll(".download-option").forEach(opt =>
    opt.addEventListener("click", () => { handleDownloadChoice(opt.getAttribute("data-mode")); menu.style.display = "none"; }));

  $("githubAuthBtn").addEventListener("click", () => {
    location.href = "/api/github/authorize?returnTo=" + encodeURIComponent(location.pathname + location.search);
  });
  $("githubDisconnectBtn").addEventListener("click", async () => {
    await fetch("/api/github/disconnect", { method: "POST" });
    initGitHub();
  });
  $("githubPushBtn").addEventListener("click", pushToGitHub);

  detectAndSetMode();
}

function detectExtension() {
  const mime = String(editor ? (editor.getOption("mode") || "") : "").toLowerCase();
  const map = {
    "text/x-java": "java", "javascript": "js", "text/javascript": "js", "application/json": "json",
    "python": "py", "text/x-python": "py", "text/x-csrc": "c", "text/x-c++src": "cpp",
    "text/html": "html", "text/css": "css", "text/x-sql": "sql", "application/xml": "xml"
  };
  const ext = map[mime] || "txt";
  const disp = $("download-ext-display");
  if (disp) disp.textContent = `.${ext}`;
  return ext;
}

function detectAndSetMode() {
  if (!editor || typeof hljs === "undefined") return;
  const code = editor.getValue();
  if (!code.trim()) return;
  const detection = hljs.highlightAuto(code);
  const lang = detection.language || "plaintext";
  $("lang-name").textContent = lang;
  const modeInfo = CodeMirror.findModeByName(lang);
  // Modes are bundled locally; if one wasn't vendored we fall back to plaintext (no CDN fetch).
  editor.setOption("mode", modeInfo ? (modeInfo.mime || modeInfo.mode) : "text/plain");
  detectExtension();
}

function filenameBase() {
  const raw = ($("filenameInput").value || "").trim();
  return raw || sessionId || "code";
}
function handleDownloadChoice(mode) {
  const base = filenameBase();
  if (mode === "raw") downloadRaw(base.includes(".") ? base : `${base}.${detectExtension()}`);
  else downloadZip(base);
}
function downloadBlob(blob, filename) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
}
function downloadRaw(filename) {
  downloadBlob(new Blob([editor.getValue()], { type: "text/plain" }), filename);
}
async function downloadZip(base) {
  const ext = detectExtension();
  const filename = base.endsWith(`.${ext}`) ? base : `${base}.${ext}`;
  const meta = { sessionId, filename, version: baseVersion, clientId, timestamp: new Date().toISOString() };
  const zip = new JSZip();
  zip.file(filename, editor.getValue());
  zip.file("metadata.json", JSON.stringify(meta, null, 2));
  downloadBlob(await zip.generateAsync({ type: "blob" }), base.endsWith(".zip") ? base : `${base}.zip`);
}

// ---------------- GitHub ----------------
async function initGitHub() {
  let status;
  try { status = await (await fetch("/api/github/status")).json(); }
  catch { return; }
  const section = $("githubSection");
  if (!status.configured) {
    section.hidden = false;
    $("githubStatus").textContent = "GitHub push is not configured on this server.";
    $("githubAuthBtn").hidden = $("githubForm").hidden = $("githubDisconnectBtn").hidden = true;
    return;
  }
  section.hidden = false;
  const authed = !!status.authorized;
  $("githubAuthBtn").hidden = authed;
  $("githubForm").hidden = !authed;
  $("githubDisconnectBtn").hidden = !authed;
  if (new URLSearchParams(location.search).get("github") === "connected") {
    $("githubStatus").textContent = "GitHub connected.";
  } else {
    $("githubStatus").textContent = authed ? "GitHub connected." : "";
  }
}

async function pushToGitHub() {
  const payload = {
    owner: $("ghOwner").value.trim(),
    repo: $("ghRepo").value.trim(),
    path: $("ghPath").value.trim(),
    branch: $("ghBranch").value.trim(),
    message: $("ghMessage").value.trim(),
    sessionId,
    sessionToken: myToken
  };
  if (!payload.owner || !payload.repo || !payload.path) {
    $("githubStatus").textContent = "owner, repo and path are required.";
    return;
  }
  $("githubStatus").textContent = "Pushing…";
  try {
    const resp = await fetch("/api/github/push", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
    });
    const data = await resp.json().catch(() => ({}));
    if (resp.ok) {
      $("githubStatus").textContent = data.htmlUrl ? `Pushed → ${data.htmlUrl}` : "Pushed to GitHub.";
    } else {
      $("githubStatus").textContent = `Push failed: ${data.error || resp.status}`;
    }
  } catch (e) {
    $("githubStatus").textContent = "Push failed: network error.";
  }
}
