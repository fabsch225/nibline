import { useEffect, useRef, useState } from "react";
import { CaptureUpdateAction, Excalidraw, restoreElements } from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";
import type { ExcalidrawImperativeAPI } from "@excalidraw/excalidraw/types";

type Json = Record<string, unknown>;
type El = Json & { id: string; type: string; index?: string; isDeleted?: boolean; customData?: Json; x: number; y: number; width: number; height: number };
type Status = "connecting" | "live" | "offline";

/** Same id the tablet uses for a rendered PDF page. */
export const pdfFileId = (file: string, page: number) => `pdf-${file.replace(/[^A-Za-z0-9]/g, "").slice(0, 40)}-${page}`;

function pdfPointer(f: El): { file: string; page: number } | null {
  if (f.type !== "frame" || f.isDeleted) return null;
  const cd = (f.customData ?? {}) as Json;
  const bg = cd.background as Json | undefined;
  if (bg?.kind === "pdf" && typeof bg.file === "string") return { file: bg.file, page: Number(bg.page ?? 0) };
  if (typeof cd.pdfFile === "string") return { file: cd.pdfFile, page: Number(cd.pdfPage ?? 0) };
  return null;
}

const restore = (els: Json[]) => restoreElements(els as never, null, { refreshDimensions: false, repairBindings: false }) as unknown as El[];

export function Live({ code, relay }: { code: string; relay: string }) {
  const [api, setApi] = useState<ExcalidrawImperativeAPI | null>(null);
  const [status, setStatus] = useState<Status>("connecting");
  const [note, setNote] = useState("");
  const [follow, setFollow] = useState(true);
  const [empty, setEmpty] = useState(true);
  const followRef = useRef(follow);
  followRef.current = follow;
  const dark = typeof matchMedia !== "undefined" && matchMedia("(prefers-color-scheme: dark)").matches;

  useEffect(() => {
    if (!api) return;
    const els = new Map<string, El>();
    const files = new Set<string>();
    let wet: El | null = null;
    let view: { x: number; y: number; w: number; h: number } | null = null;
    let frame = 0;
    let ws: WebSocket | null = null;
    let closed = false;
    let retry = 0;

    const render = () => {
      frame = 0;
      const out: El[] = [];
      // PDF pages go under their page frames
      for (const f of els.values()) {
        const p = pdfPointer(f);
        if (!p) continue;
        const fileId = pdfFileId(p.file, p.page);
        if (!files.has(fileId)) continue;
        out.push({
          id: `bg-${f.id}`, type: "image", x: f.x, y: f.y, width: f.width, height: f.height, angle: 0, strokeColor: "transparent",
          backgroundColor: "transparent", fillStyle: "solid", strokeWidth: 1, strokeStyle: "solid", roughness: 0, opacity: 100, groupIds: [],
          frameId: f.id, roundness: null, seed: 1, version: 1, versionNonce: 1, isDeleted: false, boundElements: null, updated: 1, link: null,
          locked: true, status: "saved", fileId, scale: [1, 1], crop: null, index: "a0",
        } as El);
      }
      const sorted = [...els.values()].sort((a, b) => (a.index ?? "") < (b.index ?? "") ? -1 : (a.index ?? "") > (b.index ?? "") ? 1 : 0);
      const bg = restore(out);
      api.updateScene({ elements: [...bg, ...sorted, ...(wet ? [wet] : [])] as never, captureUpdate: CaptureUpdateAction.NEVER });
      setEmpty(els.size === 0);
    };
    const schedule = () => { if (!frame) frame = requestAnimationFrame(render); };

    const applyView = () => {
      if (!view || !followRef.current) return;
      const st = api.getAppState();
      const W = st.width, H = st.height;
      if (!W || !H || !view.w || !view.h) return;
      const zoom = Math.min(Math.max(Math.min(W / view.w, H / view.h), 0.1), 30);
      api.updateScene({
        appState: { zoom: { value: zoom as never }, scrollX: (W / zoom - view.w) / 2 - view.x, scrollY: (H / zoom - view.h) / 2 - view.y },
        captureUpdate: CaptureUpdateAction.NEVER,
      });
    };

    const onMessage = (m: Json) => {
      switch (m.t) {
        case "full":
          els.clear(); wet = null; view = null;
          for (const e of restore((m.elements as Json[]) ?? [])) els.set(e.id, e);
          setNote(String(m.note ?? ""));
          if ((m.appState as Json)?.viewBackgroundColor && !dark) api.updateScene({ appState: { viewBackgroundColor: (m.appState as Json).viewBackgroundColor as string }, captureUpdate: CaptureUpdateAction.NEVER });
          schedule(); break;
        case "delta":
          for (const e of restore((m.upsert as Json[]) ?? [])) { els.set(e.id, e); if (wet && wet.id === e.id) wet = null; }
          for (const id of (m.remove as string[]) ?? []) els.delete(id);
          schedule(); break;
        case "wet":
          wet = m.el ? (restore([m.el as Json])[0] ?? null) : null;
          schedule(); break;
        case "file":
          if (files.has(String(m.id))) break;
          files.add(String(m.id));
          api.addFiles([{ id: m.id as never, mimeType: m.mimeType as never, dataURL: m.dataURL as never, created: Date.now() }]);
          schedule(); break;
        case "view":
          view = { x: Number(m.x), y: Number(m.y), w: Number(m.w), h: Number(m.h) };
          applyView(); break;
        case "host":
          setStatus(m.online ? "live" : "offline"); break;
      }
    };

    const connect = () => {
      if (closed) return;
      setStatus("connecting");
      ws = new WebSocket(`${relay.replace(/^http/, "ws").replace(/\/$/, "")}/live/${code}`);
      ws.onopen = () => { retry = 0; };
      ws.onmessage = (e) => { try { onMessage(JSON.parse(String(e.data))); } catch (err) { console.warn("live: bad message", err); } };
      ws.onclose = () => {
        if (closed) return;
        setStatus("connecting");
        setTimeout(connect, Math.min(1000 * 2 ** retry++, 10000));
      };
    };
    connect();
    (window as unknown as { __live: unknown }).__live = { api, els, files, get view() { return view; }, applyView };
    const onResize = () => applyView();
    window.addEventListener("resize", onResize);
    return () => { closed = true; ws?.close(); if (frame) cancelAnimationFrame(frame); window.removeEventListener("resize", onResize); };
  }, [api, code, relay]);

  // Re-follow immediately when switched back on
  useEffect(() => { if (follow) (window as unknown as { __live?: { applyView(): void } }).__live?.applyView(); }, [follow]);

  const label = status === "live" ? "Live" : status === "offline" ? "Tablet offline" : "Connecting…";
  const dot = status === "live" ? "#2f9e44" : status === "offline" ? "#868e96" : "#f08c00";
  return (
    <div className="live" style={{ height: "100%", position: "relative" }}>
      <style>{`
        .live .bar { position: absolute; z-index: 10; top: max(10px, env(safe-area-inset-top)); left: 50%; transform: translateX(-50%);
          display: flex; align-items: center; gap: 10px; padding: 6px 8px 6px 14px; border-radius: 999px; font-size: 14px; white-space: nowrap;
          background: rgba(255,255,255,.92); box-shadow: 0 1px 6px rgba(0,0,0,.12); max-width: calc(100% - 32px); box-sizing: border-box; }
        .live .bar .title { overflow: hidden; text-overflow: ellipsis; font-weight: 600; min-width: 0; }
        .live .bar .code { font-family: ui-monospace, Menlo, monospace; opacity: .6; letter-spacing: .08em; }
        .live .bar button { font: 500 13px system-ui; border: 0; border-radius: 999px; padding: 6px 10px; cursor: pointer; background: #ececf4; color: inherit; }
        .live .bar button.on { background: #6965db; color: white; }
        .live .hint { position: absolute; inset: 0; display: grid; place-items: center; pointer-events: none; z-index: 5; opacity: .6; font-size: 15px; padding: 16px; text-align: center; }
        @media (prefers-color-scheme: dark) { .live .bar { background: rgba(35,35,41,.92); color: #e8e8ec; } .live .bar button { background: #34343e; } }
        .live .excalidraw .App-menu_top, .live .excalidraw .layer-ui__wrapper__footer-left { visibility: hidden; }
      `}</style>
      <div className="bar">
        <span style={{ width: 8, height: 8, borderRadius: 4, background: dot, flex: "none" }} />
        <span className="title">{note || label}</span>
        {note && status !== "live" ? <span style={{ opacity: .6 }}>· {label}</span> : null}
        <span className="code">{code}</span>
        <button className={follow ? "on" : ""} onClick={() => setFollow(!follow)} title="Keep showing what's on the tablet's screen">Follow</button>
        <button onClick={() => { location.hash = ""; }} title="Watch another code">✕</button>
      </div>
      {empty && <div className="hint">{status === "live" ? "Open a note on the tablet." : "Waiting for the tablet… Start Live there with this code."}</div>}
      <div style={{ position: "absolute", inset: 0 }}
        onWheel={() => setFollow(false)} onPointerDown={(e) => { if (e.pointerType !== "mouse" || e.buttons === 1) setFollow(false); }}>
        <Excalidraw excalidrawAPI={setApi} viewModeEnabled theme={dark ? "dark" : "light"}
          UIOptions={{ canvasActions: { changeViewBackgroundColor: false, clearCanvas: false, export: false, loadScene: false, saveToActiveFile: false, toggleTheme: false, saveAsImage: false } }} />
      </div>
    </div>
  );
}
