// === Editor Setup ===
const editor = CodeMirror.fromTextArea(document.getElementById("editor"), {
    mode: "text/x-java",
    lineNumbers: true,
    lineWrapping: true
});

const sessionId = "alpha123";
const userName = prompt("Enter your name") || "Anonymous";
const socket = new SockJS("/ws");
const stompClient = Stomp.over(socket);

const userColors = {};
const remoteCursors = {};

function assignColor(user) {
    if (!userColors[user]) {
        const colors = ["#e6194b", "#3cb44b", "#ffe119", "#4363d8", "#f58231", "#911eb4"];
        userColors[user] = colors[Object.keys(userColors).length % colors.length];
        injectCursorStyle(user, userColors[user]);
    }
    return userColors[user];
}

function injectCursorStyle(user, color) {
    const style = document.createElement("style");
    style.innerHTML = `
        .remote-cursor-${user} {
            border-left: 2px solid ${color};
            margin-left: -1px;
        }
    `;
    document.head.appendChild(style);
}

stompClient.connect({}, function () {
    // Code changes
    stompClient.subscribe(`/topic/session/${sessionId}/code`, function (message) {
        const payload = JSON.parse(message.body);
        if (payload.userName !== userName) {
            editor.setValue(payload.content);
        }
    });

    // Cursor updates
    stompClient.subscribe(`/topic/session/${sessionId}/cursor`, function (message) {
        const payload = JSON.parse(message.body);
        if (payload.userName !== userName) {
            const color = assignColor(payload.userName);

            if (remoteCursors[payload.userName]) {
                remoteCursors[payload.userName].clear();
            }

            const cursorPos = payload.position;
            const marker = editor.markText(cursorPos, cursorPos, {
                className: `remote-cursor-${payload.userName}`,
                title: payload.userName
            });

            remoteCursors[payload.userName] = marker;
        }
    });

    // First push
    stompClient.send("/app/editor/update", {}, JSON.stringify({
        sessionId: sessionId,
        userName: userName,
        content: editor.getValue()
    }));
});

let codeUpdateTimeout;
editor.on("change", function () {
    clearTimeout(codeUpdateTimeout);
    codeUpdateTimeout = setTimeout(() => {
        const updatedCode = editor.getValue();
        stompClient.send("/app/editor/update", {}, JSON.stringify({
            sessionId: sessionId,
            userName: userName,
            content: updatedCode
        }));
    }, 500);
});

editor.on("cursorActivity", function () {
    const cursorPos = editor.getCursor();
    stompClient.send("/app/editor/cursor", {}, JSON.stringify({
        sessionId: sessionId,
        userName: userName,
        position: cursorPos
    }));
});
