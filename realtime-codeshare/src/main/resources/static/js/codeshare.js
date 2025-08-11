// codeshare.js — OT client with pending queue + JS OTEngine
console.log("codeshare (OT client) booting...");

const WS_BASE = "ws://localhost:8080/ws/ot?sessionId=";
const sessionId = prompt("Enter session id (e.g., alpha123)") || "alpha123";
const clientId = "client-" + Math.floor(Math.random() * 100000);
let baseVersion = 0;

// pending operations (FIFO) sent but not yet acknowledged by server
const pendingOps = [];

// ---------------- Editor ----------------
const editor = CodeMirror.fromTextArea(document.getElementById("editor"), {
  mode: "text/x-java",
  lineNumbers: true,
  lineWrapping: true,
  autofocus: true
});
document.getElementById('sessionInfo').innerText = `Session: ${sessionId} — You: ${clientId}`;

// suppress local sending while applying remote ops
let suppress = false;
function runWithoutSending(fn) {
  suppress = true;
  try { fn(); } finally { suppress = false; }
}

// ---------------- Helpers: pos/index conversion ----------------
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
    if (index <= lineText.length) return {line, ch: index};
    index -= (lineText.length + 1);
    line++;
  }
  return {line: doc.lineCount()-1, ch: doc.getLine(Math.max(0, doc.lineCount()-1)).length};
}

// ---------------- JS OTEngine (mirror server rules) ----------------
function clientWinsInsertTie(aClient, bClient) {
  return String(aClient).localeCompare(String(bClient)) < 0;
}

function copyOp(op) {
  return JSON.parse(JSON.stringify(op));
}

function transformOp(a, b) {
  // both are op-like objects with fields: type ('insert'/'delete' case-insensitive), pos, text, length, clientId
  const atype = String(a.type).toLowerCase();
  const btype = String(b.type).toLowerCase();

  if (atype === "insert" && btype === "insert") return transformInsertInsert(copyOp(a), b);
  if (atype === "insert" && btype === "delete") return transformInsertDelete(copyOp(a), b);
  if (atype === "delete" && btype === "insert") return transformDeleteInsert(copyOp(a), b);
  // delete/delete
  return transformDeleteDelete(copyOp(a), b);
}

function transformInsertInsert(a, b) {
  if (a.pos < b.pos) {
    // no change
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
    // no change
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
    // insertion inside delete region -> expand delete
    a.length += (b.text || "").length;
  }
  return a;
}

function transformDeleteDelete(a, b) {
  const aStart = a.pos, aEnd = a.pos + a.length;
  const bStart = b.pos, bEnd = b.pos + b.length;

  if (aEnd <= bStart) {
    // no change
  } else if (aStart >= bEnd) {
    a.pos -= b.length;
  } else {
    const overlapStart = Math.max(aStart, bStart);
    const overlapEnd = Math.min(aEnd, bEnd);
    const overlap = Math.max(0, overlapEnd - overlapStart);

    a.length -= overlap;
    if (aStart >= bStart) {
      // shift a.pos left by chars of b removed before aStart
      a.pos -= Math.max(0, Math.min(bEnd, aStart) - bStart);
    }
  }
  if (a.length < 0) a.length = 0;
  return a;
}

// ---------------- WebSocket ----------------
const ws = new WebSocket(WS_BASE + encodeURIComponent(sessionId));
ws.onopen = () => console.log("Connected to OT server");
ws.onerror = (e) => console.error("WS error", e);

ws.onmessage = (evt) => {
  const msg = JSON.parse(evt.data);

  if (msg.type === "init") {
    // server snapshot
    runWithoutSending(() => {
      editor.setValue(msg.document || "");
      baseVersion = msg.version || 0;
      pendingOps.length = 0; // clear local pending (fresh join)
    });
    console.log("[OTCLIENT] init v=", baseVersion);
    return;
  }

  if (msg.type === "op") {
    const serverOp = msg.op;
    const serverVersion = msg.version;

    // normalize type lower-case for easier comparisons
    serverOp.type = String(serverOp.type).toLowerCase();

    // ACK for our own op?
    if (serverOp.clientId === clientId) {
      // This is server's transformed version of a pending op.
      // Pop first pending (we assume FIFO)
      const acked = pendingOps.shift(); // remove oldest pending
      // transform remaining pending ops by the acknowledged server op
      for (let i = 0; i < pendingOps.length; i++) {
        pendingOps[i] = transformOp(pendingOps[i], serverOp);
      }
      baseVersion = serverVersion;
      // do NOT re-apply serverOp locally (we already applied optimistically)
      console.log("[OTCLIENT] ACK received, new version:", baseVersion);
      return;
    }

    // Remote op: transform it by all pending local ops so it fits our local state
    let incoming = serverOp;
    for (const p of pendingOps) {
      incoming = transformOp(incoming, p);
    }

    // apply incoming to editor (safely)
    runWithoutSending(() => {
      applyOpToEditor(incoming);
    });

    baseVersion = serverVersion;
    console.log("[OTCLIENT] Applied remote op, version:", baseVersion);
  }
};

// apply op to CodeMirror
function applyOpToEditor(op) {
  if (!op) return;
  if ((String(op.type).toLowerCase() === "insert")) {
    const pos = indexToPos(op.pos);
    editor.replaceRange(op.text || "", pos, pos, "remote");
  } else {
    const from = indexToPos(op.pos);
    const to = indexToPos((op.pos || 0) + (op.length || 0));
    editor.replaceRange("", from, to, "remote");
  }
}

// ---------------- Local edit handling ----------------
// We use beforeChange to inspect the operation and send corresponding OT ops.
// For replacements (selection replaced) we send delete then insert.
editor.on("beforeChange", (cm, change) => {
  if (suppress) return;

  const fromIndex = posToIndex(change.from);
  const toIndex = posToIndex(change.to);
  const removedLen = Math.max(0, toIndex - fromIndex);
  const insertedText = change.text.join("\n");

  // Build one or two ops depending on replace/paste/cut/undo/redo
  const opsToSend = [];

  if (removedLen > 0 && insertedText.length > 0) {
    // replace: delete then insert
    opsToSend.push({
      clientId,
      type: "delete",
      baseVersion,
      pos: fromIndex,
      length: removedLen
    });
    opsToSend.push({
      clientId,
      type: "insert",
      baseVersion,
      pos: fromIndex,
      text: insertedText
    });
  } else if (insertedText.length > 0) {
    opsToSend.push({
      clientId,
      type: "insert",
      baseVersion,
      pos: fromIndex,
      text: insertedText
    });
  } else if (removedLen > 0) {
    opsToSend.push({
      clientId,
      type: "delete",
      baseVersion,
      pos: fromIndex,
      length: removedLen
    });
  }

  if (opsToSend.length > 0) {
    // push each to pending queue and send
    for (const op of opsToSend) {
      // normalize type
      op.type = String(op.type).toLowerCase();
      pendingOps.push(copyOp(op));
      ws.send(JSON.stringify(op));
    }
  }
});
