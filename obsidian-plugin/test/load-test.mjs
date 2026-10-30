// Loads dist/main.js the way Obsidian does (CommonJS + a stand-in "obsidian" module) and exercises the real plugin:
// onload, the file menu, the command, auto-opening, and editing an Excalidraw-plugin note (.excalidraw.md).
import fs from "node:fs";
import { createRequire } from "node:module";
import assert from "node:assert/strict";
import LZString from "lz-string";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH ?? "../../tools/compat/node_modules/playwright");
const dist = (f) => fs.readFileSync(new URL("../dist/" + f, import.meta.url), "utf8");
const OUT = new URL("./out/", import.meta.url).pathname; fs.mkdirSync(OUT, { recursive: true });

// A drawing as the Obsidian Excalidraw plugin saves it: text + image + compressed scene
const base = { angle: 0, backgroundColor: "transparent", fillStyle: "solid", strokeStyle: "solid", roughness: 1, opacity: 100, groupIds: [], roundness: null, seed: 3, isDeleted: false, boundElements: null, updated: 1, link: null, locked: false, frameId: null, strokeColor: "#1e1e1e", strokeWidth: 2 };
const scene = { type: "excalidraw", version: 2, source: "https://github.com/zsviczian/obsidian-excalidraw-plugin", elements: [
  { ...base, id: "txt1", type: "text", x: 100, y: 80, width: 300, height: 45, text: "Hello from Obsidian", originalText: "Hello from Obsidian", rawText: "Hello from Obsidian", fontSize: 36, fontFamily: 5, textAlign: "left", verticalAlign: "top", containerId: null, autoResize: true, lineHeight: 1.25, index: "a0", version: 3, versionNonce: 11 },
  { ...base, id: "img1", type: "image", x: 100, y: 160, width: 200, height: 120, fileId: "abc123def456", status: "saved", scale: [1, 1], crop: null, index: "a1", version: 2, versionNonce: 12 },
  { ...base, id: "ink1", type: "freedraw", x: 100, y: 320, width: 400, height: 50, strokeColor: "#1971c2", points: Array.from({ length: 80 }, (_, i) => [i * 5, Math.sin(i / 6) * 25]), pressures: [], simulatePressure: true, lastCommittedPoint: null, index: "a2", version: 2, versionNonce: 13 },
], appState: { gridSize: null, viewBackgroundColor: "#ffffff" }, files: {} };
const c = LZString.compressToBase64(JSON.stringify(scene)); const chunks = []; for (let i = 0; i < c.length; i += 256) chunks.push(c.slice(i, i + 256));
const note = `---

excalidraw-plugin: parsed
tags: [excalidraw]

---
==⚠  Switch to EXCALIDRAW VIEW in the MORE OPTIONS menu of this document. ⚠== You can decompress Drawing data with the command palette: 'Decompress current Excalidraw file'. For more info check in plugin settings under 'Saving'


# Excalidraw Data

## Text Elements
Hello from Obsidian ^txt1

## Embedded Files
abc123def456: [[Pasted Image 1.png]]

%%
## Drawing
\`\`\`compressed-json
${chunks.join("\n\n")}
\`\`\`
%%`;
// 2x1 px red PNG as the embedded image
const png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAEUlEQVR4nGP4z8DwHwyZGAAhzwP+6GvqawAAAABJRU5ErkJggg==";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 800 } });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
await page.setContent(`<html><head><style>${dist("styles.css")}</style><style>html,body{margin:0;height:100%}.view{position:relative;width:1100px;height:800px}</style></head><body></body></html>`);

const r = await page.evaluate(async ({ code, note, png }) => {
  const log = { extensions: [], views: {}, commands: {}, handlers: {}, notices: [], ribbon: 0, viewStates: [], saves: 0 };
  const div = () => { const d = document.createElement("div"); d.empty = function () { this.innerHTML = ""; }; d.addClass = function (c) { this.classList.add(c); };
    d.createDiv = function (o) { const x = div(); if (o?.cls) x.className = o.cls; this.appendChild(x); return x; };
    d.createEl = function (t, o) { const x = document.createElement(t); x.textContent = o?.text ?? ""; this.appendChild(x); return x; }; return d; };
  class Component { registerEvent() {} register() {} }
  class TFile { constructor(path) { this.path = path; this.name = path.split("/").pop(); this.extension = this.name.split(".").pop(); this.basename = this.name.replace(/\.[^.]+$/, ""); this.parent = { path: "/" }; this.stat = { mtime: 1 }; } }
  const files = { "Lectures/Week 3.excalidraw": new TFile("Lectures/Week 3.excalidraw"), "Drawing 1.excalidraw.md": new TFile("Drawing 1.excalidraw.md"), "Pasted Image 1.png": new TFile("Pasted Image 1.png"), "notes.md": new TFile("notes.md") };
  const disk = { "Drawing 1.excalidraw.md": note };
  const bin = { "Pasted Image 1.png": Uint8Array.from(atob(png), (ch) => ch.charCodeAt(0)).buffer };
  class Plugin extends Component {
    constructor(app, manifest) { super(); this.app = app; this.manifest = manifest; }
    async loadData() { return null; } async saveData() {}
    registerView(t, f) { log.views[t] = f; }
    registerExtensions(e) { log.extensions.push(...e); }
    addCommand(c) { log.commands[c.id] = c; }
    addSettingTab() {} addRibbonIcon() { log.ribbon++; }
  }
  class ItemView extends Component { constructor(leaf) { super(); this.leaf = leaf; this.app = app; this.contentEl = div(); } onPaneMenu() {} }
  class TextFileView extends ItemView { requestSave() { log.saves++; disk[this.file.path] = this.getViewData(); } }
  class Modal { constructor(app) { this.app = app; } open() {} setPlaceholder() {} }
  const obsidian = { Plugin, ItemView, TextFileView, FileView: ItemView, TFile, TFolder: class {}, MarkdownView: class {}, FuzzySuggestModal: Modal,
    Notice: class { constructor(m) { log.notices.push(m); } }, PluginSettingTab: class { constructor(app) { this.app = app; } }, Setting: class {}, Platform: { isMobile: true, isAndroidApp: true },
    loadPdfJs: async () => ({}) };
  const leaf = { view: { getViewType: () => "markdown", file: files["Drawing 1.excalidraw.md"] }, setViewState: async (s) => { log.viewStates.push(s); } };
  window.__opened = []; window.open = (u) => { window.__opened.push(u); return null; };
  const app = {
    plugins: { enabledPlugins: new Set(["obsidian-git"]) },
    viewRegistry: { getTypeByExtension: () => undefined },
    workspace: { on: (n, fn) => { (log.handlers[n] ??= []).push(fn); return {}; }, onLayoutReady: (fn) => fn(), getActiveFile: () => files["Drawing 1.excalidraw.md"],
      getLeaf: () => leaf, getMostRecentLeaf: () => leaf, getActiveViewOfType: () => null },
    vault: { getName: () => "Uni", adapter: { basePath: "file:///storage/emulated/0/Documents/Uni/", read: async (p) => disk[p] }, getFiles: () => Object.values(files), getAbstractFileByPath: (p) => files[p.replace(/^\//, "")] ?? null, readBinary: async (f) => bin[f.path],
      createBinary: async (p, b) => { files[p] = new TFile(p); bin[p] = b; return files[p]; } },
    metadataCache: { getFileCache: () => null, getFirstLinkpathDest: (p) => files[p] ?? null },
    fileManager: { getAvailablePathForAttachment: async (n) => n },
  };
  const module = { exports: {} };
  new Function("module", "exports", "require", code)(module, module.exports, (n) => { if (n === "obsidian") return obsidian; throw new Error("Cannot find module " + n); });
  const P = module.exports.default ?? module.exports;
  const plugin = new P(app, { id: "inkcalidraw" });
  await plugin.onload();

  // file menu: offered for .excalidraw.md and .excalidraw, not for ordinary notes
  const menuFor = (f) => { const items = []; const menu = { addItem: (fn) => { const i = { setTitle: (t) => (items.push(t), i), setIcon: () => i, onClick: () => i, setSection: () => i }; fn(i); return menu; } };
    for (const h of log.handlers["file-menu"]) h(menu, f, "file-explorer-context-menu"); return items; };
  const menu = { md: menuFor(files["Drawing 1.excalidraw.md"]), plain: menuFor(files["Lectures/Week 3.excalidraw"]), note: menuFor(files["notes.md"]) };
  // "Edit in Nibline app" sends the vault file to the tablet app
  let edit; { const menu2 = { addItem: (fn) => { const i = { setTitle: (t) => (i.t = t, i), setIcon: () => i, setSection: () => i, onClick: (f) => { if (i.t === "Edit in Nibline app") edit = f; return i; } }; fn(i); return menu2; } };
    for (const h of log.handlers["file-menu"]) h(menu2, files["Lectures/Week 3.excalidraw"]); }
  edit();
  const menuLink = window.__opened.pop();
  // tapping a .excalidraw file (after startup) opens the app too; files restored at startup don't
  const weekLeaf = { view: { getViewType: () => "inkcalidraw-view", file: files["Lectures/Week 3.excalidraw"] }, setViewState: async () => {} };
  app.workspace.getMostRecentLeaf = () => weekLeaf;
  for (const h of log.handlers["file-open"]) h(files["Lectures/Week 3.excalidraw"]);
  const atStartup = window.__opened.length;
  await new Promise((r) => setTimeout(r, 1700));
  for (const h of log.handlers["file-open"]) h(files["Lectures/Week 3.excalidraw"]);
  const tapLink = window.__opened.pop();
  app.workspace.getMostRecentLeaf = () => leaf;
  const cmd = log.commands["open-current-in-inkcalidraw"].checkCallback(true);
  // opening the drawing (Excalidraw plugin disabled) switches the leaf to our view
  for (const h of log.handlers["file-open"]) h(files["Drawing 1.excalidraw.md"]);
  await new Promise((r) => setTimeout(r, 0));

  // open it in the real view
  const view = log.views["inkcalidraw-view"](leaf);
  view.file = files["Drawing 1.excalidraw.md"];
  const host = document.createElement("div"); host.className = "view"; host.appendChild(view.contentEl); document.body.appendChild(host);
  view.contentEl.style.cssText = "position:absolute;inset:0";
  view.setViewData(note, true);
  window.__view = view; window.__disk = disk; window.__log = log;
  return { extensions: log.extensions, commands: Object.keys(log.commands), ribbon: log.ribbon, notices: log.notices, menu, cmd, viewStates: log.viewStates, menuLink, atStartup, tapLink };
}, { code: dist("main.js"), note, png });
console.log(JSON.stringify(r));
assert.ok(r.extensions.includes("excalidraw"), ".excalidraw claimed");
assert.deepEqual(r.menu.md, ["Open in Nibline viewer"], "viewer only for .excalidraw.md (the app edits .excalidraw)");
assert.deepEqual(r.menu.plain, ["Edit in Nibline app", "Open in Nibline viewer"], "file menu entries for .excalidraw");
const expected = "nibline://open?vault=Uni&path=Lectures%2FWeek%203.excalidraw&abs=%2Fstorage%2Femulated%2F0%2FDocuments%2FUni";
assert.equal(r.menuLink, expected, "app link from the file menu");
assert.equal(r.atStartup, 0, "files restored at startup don't launch the app");
assert.equal(r.tapLink, expected, "tapping a drawing launches the app");
console.log("• Android: “Edit in Nibline app” and tapping a drawing open " + expected);
assert.deepEqual(r.menu.note, [], "no entry for ordinary notes");
assert.equal(r.cmd, true, "command available for the open drawing");
assert.equal(r.viewStates[0]?.type, "inkcalidraw-view", "opening a drawing switches to Inkcalidraw");
console.log("• plugin loads; file menu, command, ribbon and auto-open work for .excalidraw and .excalidraw.md");

const ids = () => page.evaluate(() => (window.__inkScene?.getSceneElements() ?? []).map((e) => e.id));
for (let i = 0; i < 100 && (await ids()).length < 3; i++) await page.waitForTimeout(100);
assert.deepEqual((await ids()).sort(), ["img1", "ink1", "txt1"], "drawing decoded from the compressed note");
for (let i = 0; i < 50 && !(await page.evaluate(() => !!window.__inkScene.getFiles()["abc123def456"])); i++) await page.waitForTimeout(100);
assert.ok(await page.evaluate(() => !!window.__inkScene.getFiles()["abc123def456"]), "embedded image loaded from the vault");
await page.waitForTimeout(500);
await page.screenshot({ path: OUT + "obsidian-md-view.png" });

await page.evaluate(() => window.__inkScene.setActiveTool({ type: "freedraw" }));
const box = await page.locator("canvas.interactive").boundingBox();
await page.mouse.move(box.x + 600, box.y + 500); await page.mouse.down();
for (let i = 0; i < 20; i++) await page.mouse.move(box.x + 600 + i * 8, box.y + 500 + Math.cos(i / 3) * 20);
await page.mouse.up();
await page.waitForTimeout(600);
const saved = await page.evaluate(() => window.__disk["Drawing 1.excalidraw.md"]);
assert.ok(saved.startsWith("---\n\nexcalidraw-plugin: parsed"), "frontmatter kept");
assert.ok(saved.includes("## Text Elements\nHello from Obsidian ^txt1"), "text elements section kept");
assert.ok(saved.includes("abc123def456: [[Pasted Image 1.png]]"), "embedded file kept");
const m = /```compressed-json\n([\s\S]*?)\n```/.exec(saved);
assert.ok(m, "still compressed-json");
const back = JSON.parse(LZString.decompressFromBase64(m[1].replace(/\s+/g, "")));
assert.equal(back.elements.filter((e) => e.type === "freedraw" && !e.isDeleted).length, 2, "new stroke saved");
assert.deepEqual(back.files, {}, "images not inlined");
console.log("• .excalidraw.md: opens (compressed scene, embedded image), edits save back in the same format");
assert.deepEqual(errors.filter((e) => !/favicon|ResizeObserver/.test(e)), [], "no page errors");
await browser.close();
console.log("PLUGIN LOAD TEST PASSED");
