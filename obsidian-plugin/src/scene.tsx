/**
 * The editor itself, independent of Obsidian (so it can be tested in a plain browser).
 *
 * Nibline notes are ordinary .excalidraw files. Two app-specific things are handled here:
 *  - Page frames whose customData.background = {kind: "pdf", file, page} show that page of a PDF that sits
 *    next to the note. The page is rendered with pdf.js into a locked image *under* the frame. These images
 *    are marked customData.ephemeral and are never written back to the file.
 *  - Saving keeps everything Excalidraw itself would drop: the "inkcalidraw" block (pens, page layout),
 *    our appState keys and unknown top-level fields.
 */
import { useEffect, useRef, useState } from "react";
import { CaptureUpdateAction, Excalidraw } from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";
import type { BinaryFileData, ExcalidrawImperativeAPI } from "@excalidraw/excalidraw/types";

type Json = Record<string, unknown>;
type El = Json & { id: string; type: string; version: number; versionNonce: number; isDeleted?: boolean; customData?: Json; frameId?: string | null };

export interface SceneHost {
  /** Raw bytes of a file next to the note (for PDFs), or null if it isn't there. */
  readSibling(name: string): Promise<ArrayBuffer | null>;
  /** pdf.js (Obsidian ships it: loadPdfJs()). */
  pdfjs(): Promise<{ getDocument(src: { data: Uint8Array }): { promise: Promise<PdfDoc> } }>;
  save(json: string): void;
  /** Images stored outside the JSON (".excalidraw.md" notes keep them as vault files). */
  loadFiles?(): Promise<{ id: string; mimeType: string; dataURL: string }[]>;
  dark: boolean;
}
type PdfDoc = { numPages: number; getPage(n: number): Promise<{ getViewport(o: { scale: number }): { width: number; height: number }; render(o: unknown): { promise: Promise<void> } }> };

export function parseNote(text: string): Json {
  const j = JSON.parse(text || "{}") as Json;
  if (j.type !== "excalidraw") throw new Error("Not an .excalidraw file");
  return j;
}

const isEphemeral = (e: El) => !!(e.customData as Json | undefined)?.ephemeral;

/** What a page frame shows underneath (also reads the older pdfFile/pdfPage keys). */
export function pdfPointer(f: El): { file: string; page: number } | null {
  if (f.type !== "frame" || f.isDeleted) return null;
  const cd = (f.customData ?? {}) as Json;
  const bg = cd.background as Json | undefined;
  if (bg?.kind === "pdf" && typeof bg.file === "string") return { file: bg.file, page: Number(bg.page ?? 0) };
  if (typeof cd.pdfFile === "string") return { file: cd.pdfFile, page: Number(cd.pdfPage ?? 0) };
  return null;
}

/** Serialises the scene back into the original file structure. */
export function toFileJson(original: Json, elements: readonly El[], files: Record<string, { id: string; mimeType: string; dataURL: string; created?: number }>): string {
  const keptFiles: Json = { ...((original.files as Json) ?? {}) };
  for (const f of Object.values(files)) {
    if (f.id.startsWith("pdf-")) continue; // rendered PDF pages: never stored
    keptFiles[f.id] = { mimeType: f.mimeType, id: f.id, dataURL: f.dataURL, created: f.created ?? Date.now() };
  }
  const out: Json = { ...original };
  out.type = "excalidraw"; out.version = original.version ?? 2;
  out.elements = elements.filter((e) => !isEphemeral(e));
  out.files = keptFiles;
  return JSON.stringify(out);
}

function signature(els: readonly El[]) {
  let s = 0;
  for (const e of els) if (!isEphemeral(e)) s = (s * 31 + e.version * 7 + (e.versionNonce % 1000003)) % 2147483647;
  return `${els.length}:${s}`;
}

export function InkScene({ text, host }: { text: string; host: SceneHost }) {
  const [api, setApi] = useState<ExcalidrawImperativeAPI | null>(null);
  const original = useRef<Json>(parseNote(text));
  const lastSig = useRef<string>("");
  const [initial] = useState(() => {
    const j = original.current;
    const files = Object.values((j.files as Record<string, Json>) ?? {}).map((f) => ({ ...f })) as unknown as BinaryFileData[];
    const app = (j.appState as Json) ?? {};
    return {
      elements: (j.elements as El[]) ?? [],
      files: Object.fromEntries(files.map((f) => [f.id, f])),
      appState: { viewBackgroundColor: (app.viewBackgroundColor as string) ?? "#ffffff", theme: host.dark ? "dark" : "light" },
      scrollToContent: !((j.elements as El[]) ?? []).some((e) => e.type === "frame"),
    };
  });

  // Images kept as separate vault files
  useEffect(() => {
    if (!api || !host.loadFiles) return;
    host.loadFiles().then((fs) => { if (fs.length) api.addFiles(fs.map((f) => ({ ...f, created: Date.now() })) as never); })
      .catch((e) => console.warn("Nibline: images", e));
  }, [api]);

  // Paged notes open with the first page fitted to the view (like the tablet app's "fit page")
  useEffect(() => {
    if (!api) return;
    const first = ((original.current.elements as El[]) ?? []).find((e) => e.type === "frame" && !e.isDeleted);
    if (!first) return;
    let tries = 0;
    const t = setInterval(() => {
      const f = api.getSceneElements().find((e) => e.id === first.id);
      if (f) api.scrollToContent(f, { fitToViewport: true, viewportZoomFactor: 0.95, animate: false });
      if (f || ++tries > 40) clearInterval(t);
    }, 50);
    return () => clearInterval(t);
  }, [api]);

  // PDF pages under PDF-backed page frames
  useEffect(() => {
    if (!api) return;
    let cancelled = false;
    (async () => {
      // from the file itself: right after mount the API may not have the initial scene yet
      const frames = (((original.current.elements as El[]) ?? [])).filter((e) => pdfPointer(e));
      if (!frames.length) return;
      const pdfjs = await host.pdfjs();
      const docs = new Map<string, Promise<PdfDoc | null>>();
      const add: El[] = [];
      for (const f of frames) {
        const ptr = pdfPointer(f)!;
        if (!docs.has(ptr.file)) docs.set(ptr.file, host.readSibling(ptr.file).then((b) => (b ? pdfjs.getDocument({ data: new Uint8Array(b) }).promise : null)));
        const doc = await docs.get(ptr.file);
        if (!doc || cancelled || ptr.page >= doc.numPages) continue;
        const page = await doc.getPage(ptr.page + 1);
        const w1 = page.getViewport({ scale: 1 }).width;
        const vp = page.getViewport({ scale: (Number(f.width) * 2) / w1 }); // ~2x for sharp zooming
        const canvas = document.createElement("canvas");
        canvas.width = Math.ceil(vp.width); canvas.height = Math.ceil(vp.height);
        const ctx = canvas.getContext("2d")!;
        ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, canvas.width, canvas.height);
        await page.render({ canvasContext: ctx, viewport: vp }).promise;
        const fileId = `pdf-${ptr.file.replace(/[^a-zA-Z0-9]/g, "").slice(0, 24)}-${ptr.page}`;
        api.addFiles([{ id: fileId as never, mimeType: "image/png" as never, dataURL: canvas.toDataURL("image/png") as never, created: Date.now() }]);
        add.push({
          id: `bg-${f.id}`, type: "image", x: f.x, y: f.y, width: f.width, height: f.height, angle: 0,
          strokeColor: "transparent", backgroundColor: "transparent", fillStyle: "solid", strokeWidth: 1, strokeStyle: "solid",
          roughness: 0, opacity: 100, groupIds: [], frameId: f.id, roundness: null, seed: 1, version: 1, versionNonce: 1,
          isDeleted: false, boundElements: null, updated: 1, link: null, locked: true, status: "saved", fileId, scale: [1, 1], crop: null,
          customData: { ephemeral: true },
        } as El);
      }
      if (cancelled || !add.length) return;
      // never replace a scene that hasn't been loaded yet
      const want = ((original.current.elements as El[]) ?? []).length;
      for (let i = 0; i < 100 && api.getSceneElementsIncludingDeleted().length < Math.min(want, 1) && !cancelled; i++) await new Promise((r) => setTimeout(r, 50));
      if (cancelled) return;
      const cur = api.getSceneElementsIncludingDeleted() as unknown as El[];
      const ids = new Set(cur.map((e) => e.id));
      // backgrounds go to the bottom of the z-order
      api.updateScene({ elements: [...add.filter((e) => !ids.has(e.id)), ...cur] as never, captureUpdate: CaptureUpdateAction.NEVER });
    })().catch((e) => console.warn("Nibline: PDF pages", e));
    return () => { cancelled = true; };
  }, [api]);

  return (
    <Excalidraw
      excalidrawAPI={(a) => { setApi(a); (window as unknown as { __inkScene?: unknown }).__inkScene = a; }}
      initialData={initial as never}
      theme={host.dark ? "dark" : "light"}
      onChange={(els, _state, files) => {
        const sig = signature(els as unknown as El[]);
        if (!lastSig.current) { lastSig.current = sig; return; } // initial load
        if (sig === lastSig.current) return;
        lastSig.current = sig;
        host.save(toFileJson(original.current, els as unknown as El[], files as never));
      }}
    />
  );
}
