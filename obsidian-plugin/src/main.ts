import { createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { type App, FuzzySuggestModal, loadPdfJs, MarkdownView, Platform, Notice, Plugin, PluginSettingTab, Setting, TextFileView, TFile, type WorkspaceLeaf } from "obsidian";
import { InkScene, parseNote } from "./scene";
import { decodeMd, encodeMd, isExcalidrawMd, type MdNote } from "./mdformat";

const VIEW_TYPE = "inkcalidraw-view";
const EXCALIDRAW_PLUGIN = "obsidian-excalidraw-plugin";

interface Settings { claimExtension: boolean; openMarkdownDrawings: boolean; tapOpensApp: boolean }
const DEFAULTS: Settings = { claimExtension: true, openMarkdownDrawings: true, tapOpensApp: true };

/** The tablet app only edits plain .excalidraw files (and only exists on Android). */
const appCanEdit = (f: TFile | null | undefined): f is TFile => !!f && f.extension === "excalidraw";

/** Absolute folder of the vault on this device, when Obsidian tells us (helps the app find the file). */
function vaultBasePath(app: App): string | null {
  const ad = app.vault.adapter as unknown as { getBasePath?(): string; basePath?: string; getFullPath?(p: string): string };
  let base: string | undefined;
  try { base = ad.getBasePath?.() ?? ad.basePath ?? ad.getFullPath?.(""); } catch { /* not available */ }
  if (!base) return null;
  base = base.replace(/^file:\/\//, "");
  try { base = decodeURIComponent(base); } catch { /* keep */ }
  return base.startsWith("/") ? base.replace(/\/+$/, "") : null;
}

export function appLink(app: App, file: TFile): string {
  const q: [string, string][] = [["vault", app.vault.getName()], ["path", file.path]];
  const abs = vaultBasePath(app);
  if (abs) q.push(["abs", abs]);
  return "nibline://open?" + q.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&");
}

const MIME: Record<string, string> = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp", svg: "image/svg+xml", bmp: "image/bmp" };

function toDataUrl(buf: ArrayBuffer, mime: string) {
  const bytes = new Uint8Array(buf);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return `data:${mime};base64,${btoa(bin)}`;
}

function dataUrlToBytes(url: string): { bytes: ArrayBuffer; ext: string } {
  const m = /^data:([^;,]+)(;base64)?,(.*)$/s.exec(url);
  if (!m) throw new Error("bad data URL");
  const ext = Object.entries(MIME).find(([, v]) => v === m[1])?.[0] ?? "png";
  const bin = m[2] ? atob(m[3]) : decodeURIComponent(m[3]);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return { bytes: out.buffer, ext };
}

let appRef: App | null = null;
/** .excalidraw files, and Excalidraw-plugin notes (".excalidraw.md", or "excalidraw-plugin:" in the frontmatter). */
export const isDrawingFile = (f: TFile | null | undefined): f is TFile =>
  !!f && (f.extension === "excalidraw" || isExcalidrawMd(f.path) ||
    (f.extension === "md" && !!appRef?.metadataCache.getFileCache(f)?.frontmatter?.["excalidraw-plugin"]));

/** A file view for .excalidraw and .excalidraw.md notes (Obsidian handles reading/writing; we render Excalidraw). */
class InkView extends TextFileView {
  private root: Root | null = null;
  private text = "";           // what's on disk (plain JSON, or the whole Markdown note)
  private md: MdNote | null = null;

  constructor(leaf: WorkspaceLeaf, private plugin: InkcalidrawPlugin) { super(leaf); }

  async onOpen() {
    if (Platform.isAndroidApp) {
      this.addAction("pencil", "Edit in Nibline app", () => { if (appCanEdit(this.file)) this.plugin.launchApp(this.file); else new Notice("The Nibline app edits .excalidraw files only"); });
    }
    // Coming back from the app: Obsidian may not have noticed the app's save yet, so re-read the file.
    this.registerDomEvent(document, "visibilitychange", () => {
      if (document.visibilityState !== "visible") return;
      for (const ms of [300, 1500, 4000]) window.setTimeout(() => void this.reloadFromDisk(), ms);
    });
  }

  private async reloadFromDisk() {
    const f = this.file;
    if (!f) return;
    try {
      const disk = await this.app.vault.adapter.read(f.path);
      if (disk && disk !== this.text) this.setViewData(disk, false);
    } catch { /* file gone */ }
  }

  getViewType() { return VIEW_TYPE; }
  getDisplayText() { return this.file?.basename.replace(/\.excalidraw$/, "") ?? "Nibline"; }
  getIcon() { return "pen-tool"; }
  canAcceptExtension(ext: string) { return ext === "excalidraw"; }

  getViewData() { return this.text; }

  setViewData(data: string, clear: boolean) {
    // Remount when the file changed outside this view (e.g. git pull), otherwise the next edit would
    // overwrite it. Our own saves come back identical to this.text and are ignored.
    const changed = data !== this.text;
    this.text = data;
    if (clear || !this.root || changed) this.mount();
  }

  clear() { this.root?.unmount(); this.root = null; }

  onPaneMenu(menu: import("obsidian").Menu, source: string) {
    super.onPaneMenu(menu, source);
    if (this.file?.extension === "md") {
      menu.addItem((i) => i.setTitle("Open as Markdown").setIcon("file-text").onClick(() => {
        const f = this.file!;
        this.plugin.skipOnce.add(f.path);
        void this.leaf.setViewState({ type: "markdown", state: { file: f.path } });
      }));
    }
  }

  private sibling(path: string) {
    const dir = this.file?.parent?.path ?? "";
    return this.app.vault.getAbstractFileByPath(dir && dir !== "/" ? `${dir}/${path}` : path);
  }

  private mount() {
    this.root?.unmount(); this.root = null;
    this.contentEl.empty();
    this.contentEl.addClass("inkcalidraw-view");
    const file = this.file;
    let json = this.text;
    this.md = null;
    try {
      if (file?.extension === "md") { const d = decodeMd(this.text); json = d.json; this.md = d.note; }
      parseNote(json);
    } catch (e) {
      this.contentEl.createEl("p", { text: `Can't open this file: ${(e as Error).message}` });
      return;
    }
    const host = {
      dark: document.body.classList.contains("theme-dark"),
      pdfjs: () => loadPdfJs(),
      readSibling: async (name: string) => {
        const f = this.sibling(name);
        return f instanceof TFile ? this.app.vault.readBinary(f) : null;
      },
      loadFiles: () => this.loadEmbeds(),
      save: (next: string) => { void this.saveScene(next); },
    };
    const holder = this.contentEl.createDiv({ cls: "inkcalidraw-host" });
    this.root = createRoot(holder);
    this.root.render(createElement(InkScene, { key: `${file?.path}:${Date.now()}`, text: json, host }));
  }

  /** Images of a .excalidraw.md note: "fileId: [[image.png]]" (PDF pages "[[x.pdf#page=3]]" too). */
  private async loadEmbeds() {
    const md = this.md, file = this.file;
    if (!md || !file) return [];
    const out: { id: string; mimeType: string; dataURL: string }[] = [];
    for (const [id, link] of md.embeds) {
      try {
        const [path, sub] = link.split("|")[0].split("#");
        const f = this.app.metadataCache.getFirstLinkpathDest(path, file.path);
        if (!f) continue;
        if (f.extension === "pdf") {
          const page = Number(/page=(\d+)/.exec(sub ?? "")?.[1] ?? 1);
          const pdfjs = await loadPdfJs();
          const doc = await pdfjs.getDocument({ data: new Uint8Array(await this.app.vault.readBinary(f)) }).promise;
          const p = await doc.getPage(page);
          const vp = p.getViewport({ scale: 2 });
          const c = document.createElement("canvas"); c.width = vp.width; c.height = vp.height;
          const ctx = c.getContext("2d")!; ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, c.width, c.height);
          await p.render({ canvasContext: ctx, viewport: vp }).promise;
          out.push({ id, mimeType: "image/png", dataURL: c.toDataURL("image/png") });
        } else if (MIME[f.extension]) {
          out.push({ id, mimeType: MIME[f.extension], dataURL: toDataUrl(await this.app.vault.readBinary(f), MIME[f.extension]) });
        }
      } catch (e) { console.warn("Nibline: embedded file", link, e); }
    }
    return out;
  }

  private async saveScene(json: string) {
    const file = this.file;
    if (!this.md || !file) { this.text = json; this.requestSave(); return; }
    // .excalidraw.md: new images become vault attachments listed under "## Embedded Files"
    const scene = JSON.parse(json) as { files?: Record<string, { id: string; dataURL: string }> };
    for (const f of Object.values(scene.files ?? {})) {
      if (this.md.embeds.has(f.id) || f.id.startsWith("pdf-")) continue;
      try {
        const { bytes, ext } = dataUrlToBytes(f.dataURL);
        const path = await this.app.fileManager.getAvailablePathForAttachment(`Pasted Image ${f.id.slice(0, 8)}.${ext}`, file.path);
        const created = await this.app.vault.createBinary(path, bytes);
        this.md.embeds.set(f.id, created.path);
      } catch (e) { console.warn("Nibline: couldn't store image", e); }
    }
    this.text = encodeMd(this.md, json);
    this.md.text = this.text;
    this.requestSave();
  }

  async onClose() { this.clear(); }
}

export default class InkcalidrawPlugin extends Plugin {
  settings: Settings = DEFAULTS;
  skipOnce = new Set<string>();
  skipApp = new Set<string>();
  private ready = false;

  /** Opens the drawing in the Nibline Android app (it edits the vault file in place). */
  launchApp(file: TFile) {
    const url = appLink(this.app, file);
    // Obsidian hands non-web links to Android, which starts the app registered for nibline://
    window.open(url);
  }
  private claimed = false;

  private excalidrawPluginOn() {
    return !!(this.app as unknown as { plugins?: { enabledPlugins?: Set<string> } }).plugins?.enabledPlugins?.has(EXCALIDRAW_PLUGIN);
  }

  /** Takes .excalidraw if nobody else has it (retried, so disabling the Excalidraw plugin hands it over). */
  private claim() {
    if (this.claimed || !this.settings.claimExtension) return;
    const reg = (this.app as unknown as { viewRegistry?: { getTypeByExtension(e: string): string | undefined } }).viewRegistry;
    if (reg && reg.getTypeByExtension("excalidraw")) return;
    try { this.registerExtensions(["excalidraw"], VIEW_TYPE); this.claimed = true; } catch { /* still taken */ }
  }

  async onload() {
    appRef = this.app;
    this.settings = Object.assign({}, DEFAULTS, await this.loadData());
    this.registerView(VIEW_TYPE, (leaf) => new InkView(leaf, this));
    this.claim();
    this.app.workspace.onLayoutReady(() => {
      // files restored at startup shouldn't launch the app
      window.setTimeout(() => { this.ready = true; }, 1500);
      this.claim();
      if (!this.claimed && this.settings.claimExtension && this.excalidrawPluginOn()) {
        new Notice("Nibline: the Excalidraw plugin handles .excalidraw files. Use “Open in Nibline” from the file menu, or disable the Excalidraw plugin.");
      }
    });
    this.registerEvent(this.app.workspace.on("layout-change", () => this.claim()));

    // Drawings opened in another view switch over (when the Excalidraw plugin isn't handling them)
    this.registerEvent(this.app.workspace.on("file-open", (file) => {
      if (!isDrawingFile(file) || this.excalidrawPluginOn()) return;
      if (this.skipOnce.delete(file.path)) return;
      if (file.extension === "md" && !this.settings.openMarkdownDrawings) return;
      if (file.extension === "excalidraw" && !this.settings.claimExtension) return;
      const leaf = this.app.workspace.getMostRecentLeaf();
      const view = leaf?.view as { file?: TFile } | undefined;
      if (!leaf || view?.file?.path !== file.path) return;
      // On the tablet, tapping a drawing hands it to the app (the viewer stays open behind it, and refreshes when you come back)
      if (Platform.isAndroidApp && this.settings.tapOpensApp && appCanEdit(file) && this.ready && !this.skipApp.delete(file.path)) this.launchApp(file);
      if (leaf.view.getViewType() === VIEW_TYPE) return;
      void leaf.setViewState({ type: VIEW_TYPE, state: { file: file.path }, active: true });
    }));

    this.registerEvent(this.app.workspace.on("file-menu", (menu, file) => {
      if (file instanceof TFile && isDrawingFile(file)) {
        if (Platform.isAndroidApp && appCanEdit(file)) menu.addItem((i) => i.setTitle("Edit in Nibline app").setIcon("pencil").setSection("open").onClick(() => this.launchApp(file)));
        menu.addItem((i) => i.setTitle("Open in Nibline viewer").setIcon("pen-tool").setSection("open").onClick(() => { this.skipApp.add(file.path); void this.openIn(file); }));
      }
    }));
    this.addCommand({
      id: "open-current-in-inkcalidraw",
      name: "Open current drawing in Nibline",
      checkCallback: (checking) => {
        const f = this.app.workspace.getActiveFile();
        if (!isDrawingFile(f)) return false;
        if (!checking) void this.openIn(f);
        return true;
      },
    });
    this.addCommand({
      id: "edit-in-app",
      name: "Edit current drawing in the Nibline app",
      checkCallback: (checking) => {
        const f = this.app.workspace.getActiveFile();
        if (!Platform.isAndroidApp || !appCanEdit(f)) return false;
        if (!checking) this.launchApp(f);
        return true;
      },
    });
    this.addCommand({
      id: "open-drawing",
      name: "Open a drawing…",
      callback: () => new DrawingPicker(this).open(),
    });
    this.addRibbonIcon("pen-tool", "Nibline: open a drawing", () => new DrawingPicker(this).open());
    this.addSettingTab(new InkSettings(this));
  }

  async openIn(file: TFile) {
    const active = this.app.workspace.getActiveViewOfType(MarkdownView);
    const leaf = active?.file?.path === file.path ? active.leaf : this.app.workspace.getLeaf(false);
    await leaf.setViewState({ type: VIEW_TYPE, state: { file: file.path }, active: true });
  }

  async saveSettings() { await this.saveData(this.settings); }
}

class DrawingPicker extends FuzzySuggestModal<TFile> {
  constructor(private plugin: InkcalidrawPlugin) { super(plugin.app); this.setPlaceholder("Open a drawing in Nibline"); }
  getItems() { return this.app.vault.getFiles().filter((f) => isDrawingFile(f)).sort((a, b) => b.stat.mtime - a.stat.mtime); }
  getItemText(f: TFile) { return f.path; }
  onChooseItem(f: TFile) {
    if (Platform.isAndroidApp && this.plugin.settings.tapOpensApp && appCanEdit(f)) this.plugin.launchApp(f);
    this.plugin.skipApp.add(f.path);
    void this.plugin.openIn(f);
  }
}

class InkSettings extends PluginSettingTab {
  constructor(private plugin: InkcalidrawPlugin) { super(plugin.app, plugin); }
  display() {
    this.containerEl.empty();
    new Setting(this.containerEl)
      .setName("Tapping a drawing opens the Nibline app")
      .setDesc("Android: edit .excalidraw files in the tablet app; they save straight back into the vault. When off, they open in the viewer here (the app is still in the file menu).")
      .addToggle((t) => t.setValue(this.plugin.settings.tapOpensApp).onChange(async (v) => { this.plugin.settings.tapOpensApp = v; await this.plugin.saveSettings(); }));
    new Setting(this.containerEl)
      .setName("Open .excalidraw files with Nibline")
      .setDesc("Used whenever the Excalidraw plugin isn't handling them. You can always use “Open in Nibline”.")
      .addToggle((t) => t.setValue(this.plugin.settings.claimExtension).onChange(async (v) => { this.plugin.settings.claimExtension = v; await this.plugin.saveSettings(); }));
    new Setting(this.containerEl)
      .setName("Open Excalidraw-plugin drawings (.excalidraw.md) with Nibline")
      .setDesc("When the Excalidraw plugin is disabled. Use “Open as Markdown” in the view's menu to see the note's text.")
      .addToggle((t) => t.setValue(this.plugin.settings.openMarkdownDrawings).onChange(async (v) => { this.plugin.settings.openMarkdownDrawings = v; await this.plugin.saveSettings(); }));
  }
}
