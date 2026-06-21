import { useEffect, useState } from "react";
import JSZip from "jszip";
import { github, ApiError, type GitHubStatus } from "../../api/client";
import { useSession } from "../../session/SessionProvider";
import { useSessionStore } from "../../state/store";
import { Icon } from "../ui/Icon";

function downloadBlob(blob: Blob, filename: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
}

type PushState =
  | { kind: "idle" }
  | { kind: "pushing" }
  | { kind: "success"; htmlUrl: string }
  | { kind: "error"; message: string };

export function ActionsPopover({ onClose }: { onClose: () => void }) {
  const session = useSession();
  const sessionId = useSessionStore((s) => s.sessionId) ?? "";
  const token = useSessionStore((s) => s.token) ?? "";
  const clientId = useSessionStore((s) => s.clientId) ?? "";
  const version = useSessionStore((s) => s.version);
  const ext = useSessionStore((s) => s.languageExt);
  const storedFileName = useSessionStore((s) => s.fileName);

  const [filename, setFilename] = useState(storedFileName || sessionId || "code");
  const [status, setStatus] = useState<GitHubStatus | "loading" | "error">("loading");
  const justConnected = new URLSearchParams(location.search).get("github") === "connected";

  // github form
  const [owner, setOwner] = useState("");
  const [repo, setRepo] = useState("");
  const [path, setPath] = useState("");
  const [branch, setBranch] = useState("");
  const [message, setMessage] = useState("");
  const [push, setPush] = useState<PushState>({ kind: "idle" });

  useEffect(() => {
    github.status().then(setStatus).catch(() => setStatus("error"));
  }, []);

  const base = () => filename.trim() || sessionId || "code";
  const rawName = () => (base().includes(".") ? base() : `${base()}.${ext}`);

  function downloadRaw() {
    downloadBlob(new Blob([session.getDocument()], { type: "text/plain" }), rawName());
    useSessionStore.getState().setFileName(base());
  }

  async function downloadZip() {
    const inner = rawName();
    const meta = { sessionId, filename: inner, version, clientId, timestamp: new Date().toISOString() };
    const zip = new JSZip();
    zip.file(inner, session.getDocument());
    zip.file("metadata.json", JSON.stringify(meta, null, 2));
    const blob = await zip.generateAsync({ type: "blob" });
    downloadBlob(blob, base().endsWith(".zip") ? base() : `${base()}.zip`);
  }

  async function doPush() {
    if (!owner.trim() || !repo.trim() || !path.trim()) {
      setPush({ kind: "error", message: "Owner, repo and path are required." });
      return;
    }
    setPush({ kind: "pushing" });
    try {
      const res = await github.push({
        owner: owner.trim(),
        repo: repo.trim(),
        path: path.trim(),
        branch: branch.trim() || undefined,
        message: message.trim() || undefined,
        sessionId,
        sessionToken: token,
      });
      setPush({ kind: "success", htmlUrl: res.htmlUrl });
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : "Push failed: network error.";
      setPush({ kind: "error", message: msg });
    }
  }

  const inputCls =
    "w-full rounded-lg border border-border-subtle bg-input-bg px-3 py-2 font-mono text-code-md text-text-primary placeholder:text-text-muted/60 outline-none focus:border-primary focus:ring-2 focus:ring-primary/30";
  const labelCls = "mb-1 block font-mono text-label-caps uppercase text-text-muted";

  return (
    <div className="absolute inset-0 z-40 flex items-start justify-center pt-20" onClick={onClose}>
      <div className="absolute inset-0 bg-black/20" aria-hidden />
      <div
        onClick={(e) => e.stopPropagation()}
        className="glass-panel pointer-events-auto relative w-[380px] max-w-[calc(100vw-2rem)] animate-zoom-in shadow-2xl"
      >
        <div className="flex items-center gap-2 border-b border-border-subtle px-5 py-3.5">
          <Icon name="bolt" className="text-[18px] text-primary" />
          <span className="text-body-lg font-semibold text-text-primary">Actions</span>
          <button type="button" onClick={onClose} className="ml-auto text-text-muted hover:text-text-primary">
            <Icon name="close" className="text-[20px]" />
          </button>
        </div>

        <div className="max-h-[70vh] space-y-6 overflow-y-auto p-5 custom-scrollbar">
          {/* export */}
          <section>
            <p className={labelCls}>Export project</p>
            <div className="mb-2 flex items-center gap-2">
              <input
                value={filename}
                onChange={(e) => setFilename(e.target.value)}
                placeholder="filename"
                className={inputCls + " flex-1"}
              />
              <span className="font-mono text-code-md text-text-muted">.{ext}</span>
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={downloadRaw}
                className="flex flex-1 items-center justify-center gap-2 rounded-lg bg-primary py-2.5 text-body-md font-semibold text-on-primary transition hover:brightness-110"
              >
                <Icon name="download" className="text-[18px]" /> Download .{ext}
              </button>
              <button
                type="button"
                onClick={downloadZip}
                title="Download .zip (with metadata)"
                className="grid place-items-center rounded-lg border border-border-subtle bg-surface-container px-4 text-text-primary transition hover:border-primary/40"
              >
                <Icon name="folder_zip" className="text-[18px]" />
              </button>
            </div>
          </section>

          <div className="h-px w-full bg-border-subtle" />

          {/* github */}
          <section>
            <GitHubSection
              status={status}
              justConnected={justConnected}
              owner={owner}
              repo={repo}
              path={path}
              branch={branch}
              message={message}
              push={push}
              setOwner={setOwner}
              setRepo={setRepo}
              setPath={setPath}
              setBranch={setBranch}
              setMessage={setMessage}
              onAuthorize={() => github.authorize(location.pathname + location.search)}
              onDisconnect={async () => {
                await github.disconnect();
                setStatus(await github.status().catch(() => "error" as const));
              }}
              onPush={doPush}
              inputCls={inputCls}
              labelCls={labelCls}
            />
          </section>
        </div>
      </div>
    </div>
  );
}

function GitHubSection(props: {
  status: GitHubStatus | "loading" | "error";
  justConnected: boolean;
  owner: string;
  repo: string;
  path: string;
  branch: string;
  message: string;
  push: PushState;
  setOwner: (v: string) => void;
  setRepo: (v: string) => void;
  setPath: (v: string) => void;
  setBranch: (v: string) => void;
  setMessage: (v: string) => void;
  onAuthorize: () => void;
  onDisconnect: () => void;
  onPush: () => void;
  inputCls: string;
  labelCls: string;
}) {
  const { status, inputCls, labelCls, push } = props;

  const header = (
    <div className="mb-3 flex items-center justify-between">
      <p className={labelCls + " mb-0"}>GitHub Integration</p>
      {status !== "loading" && status !== "error" && status.authorized && (
        <span className="flex items-center gap-1.5 rounded-full bg-success/10 px-2 py-0.5 font-mono text-label-caps uppercase text-success">
          <span className="h-1.5 w-1.5 rounded-full bg-success" /> Connected
        </span>
      )}
    </div>
  );

  if (status === "loading") return <p className="text-body-md text-text-muted">Checking GitHub…</p>;
  if (status === "error") return <p className="text-body-md text-danger">Couldn't reach GitHub status.</p>;
  if (!status.configured) {
    return (
      <>
        {header}
        <p className="text-body-md text-text-muted">GitHub push is not configured on this server.</p>
      </>
    );
  }
  if (!status.authorized) {
    return (
      <>
        {header}
        <button
          type="button"
          onClick={props.onAuthorize}
          className="flex w-full items-center justify-center gap-2 rounded-lg border border-primary/40 py-2.5 text-body-md font-semibold text-primary transition hover:bg-primary/10"
        >
          <Icon name="link" className="text-[18px]" /> Authorize GitHub
        </button>
      </>
    );
  }

  return (
    <>
      {header}
      {props.justConnected && push.kind === "idle" && (
        <p className="mb-3 text-body-md text-success">GitHub connected.</p>
      )}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={labelCls}>Owner</label>
          <input value={props.owner} onChange={(e) => props.setOwner(e.target.value)} placeholder="octocat" className={inputCls} />
        </div>
        <div>
          <label className={labelCls}>Repo</label>
          <input value={props.repo} onChange={(e) => props.setRepo(e.target.value)} placeholder="my-repo" className={inputCls} />
        </div>
      </div>
      <div className="mt-3">
        <label className={labelCls}>Path</label>
        <input value={props.path} onChange={(e) => props.setPath(e.target.value)} placeholder="src/Main.java" className={inputCls} />
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3">
        <div>
          <label className={labelCls}>Branch (optional)</label>
          <input value={props.branch} onChange={(e) => props.setBranch(e.target.value)} placeholder="main" className={inputCls} />
        </div>
        <div>
          <label className={labelCls}>Message (optional)</label>
          <input value={props.message} onChange={(e) => props.setMessage(e.target.value)} placeholder="Update from CodeShare" className={inputCls} />
        </div>
      </div>

      <button
        type="button"
        onClick={props.onPush}
        disabled={push.kind === "pushing"}
        className="mt-4 flex w-full items-center justify-center gap-2 rounded-lg border border-primary/40 py-2.5 text-body-md font-semibold text-primary transition hover:bg-primary/10 disabled:opacity-60"
      >
        <Icon name="cloud_upload" className="text-[18px]" />
        {push.kind === "pushing" ? "Pushing…" : "Push to GitHub"}
      </button>

      {push.kind === "success" && (
        <p className="mt-3 flex items-center gap-1.5 text-body-md text-success">
          <Icon name="check_circle" className="text-[16px]" /> Pushed.
          <a href={push.htmlUrl} target="_blank" rel="noreferrer" className="underline">
            View commit
          </a>
        </p>
      )}
      {push.kind === "error" && (
        <p className="mt-3 flex items-center gap-1.5 text-body-md text-danger">
          <Icon name="error" className="text-[16px]" /> {push.message}
        </p>
      )}

      <button
        type="button"
        onClick={props.onDisconnect}
        className="mt-3 font-mono text-label-caps uppercase text-text-muted transition hover:text-danger"
      >
        Disconnect
      </button>
    </>
  );
}
