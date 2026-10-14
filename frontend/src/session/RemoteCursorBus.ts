// Carries remote cursor positions straight from the WebSocket to the editor's
// CodeMirror decorations, bypassing React state so a peer moving their caret
// never triggers a re-render of the app tree.
export interface CursorEvent {
  clientId: string;
  pos: number | null; // null = remove
}

export class RemoteCursorBus {
  private listeners = new Set<(e: CursorEvent) => void>();

  push(clientId: string, pos: number): void {
    this.emit({ clientId, pos });
  }

  remove(clientId: string): void {
    this.emit({ clientId, pos: null });
  }

  subscribe(fn: (e: CursorEvent) => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private emit(e: CursorEvent): void {
    for (const fn of this.listeners) fn(e);
  }
}
