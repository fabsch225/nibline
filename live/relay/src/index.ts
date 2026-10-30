/**
 * Inkcalidraw live relay: mirrors the note open on the tablet to read-only web viewers.
 * Nothing is stored: the current note lives in memory only while someone is connected.
 *
 *   wss://<relay>/live/ABCDE?host=<secret>   the tablet (one at a time; its key claims the code while the room is open)
 *   wss://<relay>/live/ABCDE                 viewers (read-only; anything they send is ignored)
 *
 * The tablet sends JSON messages:
 *   {t:"full",  note, appState, elements}     replace the whole scene (a note was opened, or the tablet reconnected)
 *   {t:"delta", upsert:[element], remove:[id]} changed/new elements, deleted ids
 *   {t:"file",  id, mimeType, dataURL}         an image (or a rendered PDF page) referenced by fileId
 *   {t:"wet",   el}                            the stroke being drawn right now (el = null when it ends)
 *   {t:"view",  x, y, w, h}                    the part of the note visible on the tablet
 * They're kept in memory (except "wet") so viewers joining later get the current state, and forwarded to viewers.
 * Viewers also get {t:"host", online} and the tablet gets {t:"viewers", n}.
 *
 * One Durable Object per code is what lets the tablet's and the viewers' connections meet in one place.
 * It doesn't hibernate, so the in-memory note survives while connections are open.
 */
import { DurableObject } from "cloudflare:workers";

export interface Env { ROOMS: DurableObjectNamespace<LiveRoom> }

const CODE = /^\/live\/([A-Z]{5})\/?$/;
const CHUNK = 400_000; // keep single messages well below WebSocket limits

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/") return new Response("Inkcalidraw live relay\n");
    const m = CODE.exec(url.pathname);
    if (!m) return new Response("not found", { status: 404 });
    if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("expected a WebSocket", { status: 426 });
    return env.ROOMS.get(env.ROOMS.idFromName(m[1])).fetch(req);
  },
};

type Msg = { t: string; [k: string]: unknown };

export class LiveRoom extends DurableObject<Env> {
  private host: WebSocket | null = null;
  private hostKey: string | null = null;
  private viewers = new Set<WebSocket>();
  // the mirrored note (memory only)
  private note = "";
  private appState: unknown = {};
  private view: unknown = null;
  private elements = new Map<string, string>(); // id -> element JSON
  private files = new Map<string, string>();    // id -> {"t":"file",...} message

  async fetch(req: Request): Promise<Response> {
    const key = new URL(req.url).searchParams.get("host");
    if (key !== null) {
      if (key.length < 16) return new Response("host key too short", { status: 400 });
      if (this.hostKey && this.hostKey !== key) return new Response("this code is in use by another tablet", { status: 403 });
    }
    const [client, server] = Object.values(new WebSocketPair());
    server.accept();
    if (key !== null) {
      this.hostKey = key;
      if (this.host) try { this.host.close(4000, "replaced by a newer connection"); } catch { /* gone */ }
      this.host = server;
      server.addEventListener("message", (e) => { if (this.host === server) this.onHostMessage(e.data); });
      server.addEventListener("close", () => this.onHostGone(server));
      server.addEventListener("error", () => this.onHostGone(server));
      this.broadcast({ t: "host", online: true });
      this.sendViewerCount();
    } else {
      this.viewers.add(server);
      const gone = () => { if (this.viewers.delete(server)) { this.sendViewerCount(); this.maybeForget(); } };
      server.addEventListener("close", gone);
      server.addEventListener("error", gone);
      this.sendState(server);
      this.sendViewerCount();
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Everything a new viewer needs, in chunks. */
  private sendState(ws: WebSocket) {
    ws.send(JSON.stringify({ t: "full", note: this.note, appState: this.appState, elements: [] }));
    let batch: string[] = [], size = 0;
    const flush = () => { if (batch.length) ws.send(`{"t":"delta","upsert":[${batch.join(",")}],"remove":[]}`); batch = []; size = 0; };
    for (const e of this.elements.values()) { if (size + e.length > CHUNK) flush(); batch.push(e); size += e.length; }
    flush();
    for (const f of this.files.values()) ws.send(f);
    if (this.view) ws.send(JSON.stringify({ t: "view", ...(this.view as object) }));
    ws.send(JSON.stringify({ t: "host", online: this.host !== null }));
  }

  private broadcast(m: Msg | string) {
    const s = typeof m === "string" ? m : JSON.stringify(m);
    for (const ws of this.viewers) { try { ws.send(s); } catch { /* closing */ } }
  }

  private sendViewerCount() {
    try { this.host?.send(JSON.stringify({ t: "viewers", n: this.viewers.size })); } catch { /* closing */ }
  }

  private onHostMessage(raw: unknown) {
    if (typeof raw !== "string") return;
    let m: Msg;
    try { m = JSON.parse(raw); } catch { return; }
    switch (m.t) {
      case "full":
        this.elements.clear();
        this.view = null;
        for (const e of (m.elements as { id: string }[]) ?? []) this.elements.set(e.id, JSON.stringify(e));
        this.note = String(m.note ?? "");
        this.appState = m.appState ?? {};
        break;
      case "delta":
        for (const e of (m.upsert as { id: string }[]) ?? []) this.elements.set(e.id, JSON.stringify(e));
        for (const id of (m.remove as string[]) ?? []) this.elements.delete(id);
        break;
      case "file":
        if (typeof m.id !== "string" || typeof m.dataURL !== "string") return;
        this.files.set(m.id, raw);
        break;
      case "view": this.view = { x: m.x, y: m.y, w: m.w, h: m.h }; break;
      case "wet": break; // live only
      case "ping": try { this.host?.send('{"t":"pong"}'); } catch { /* closing */ } return;
      default: return;
    }
    this.broadcast(raw);
  }

  private onHostGone(ws: WebSocket) {
    if (this.host !== ws) return;
    this.host = null;
    this.broadcast({ t: "host", online: false });
    this.maybeForget();
  }

  /** Nobody connected: drop the note (and release the code for any tablet). */
  private maybeForget() {
    if (this.host || this.viewers.size) return;
    this.hostKey = null; this.note = ""; this.appState = {}; this.view = null;
    this.elements.clear(); this.files.clear();
  }
}
