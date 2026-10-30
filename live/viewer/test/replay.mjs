// Replays what a viewer received from the real tablet app (saved by the emulator test) into the built viewer page,
// and checks that Excalidraw renders the tablet's elements.   node test/replay.mjs <live-messages.jsonl> <out-dir>
import { spawn } from "node:child_process";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import WebSocket from "ws";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH ?? "playwright");
const [input, outDir] = process.argv.slice(2);
const lines = fs.readFileSync(input, "utf8").split("\n").filter(Boolean);
const hostMsgs = lines.filter((l) => /^\{"t":"(full|delta|file|view|wet)"/.test(l));
const here = new URL("..", import.meta.url).pathname;

const relay = spawn("npx", ["wrangler", "dev", "--local", "--port", "8797", "--ip", "127.0.0.1"], { cwd: path.join(here, "../relay"), stdio: ["ignore", "pipe", "pipe"], detached: true });
await new Promise((ok, fail) => { const t = setTimeout(() => fail(new Error("relay didn't start")), 90000); const on = (d) => { if (/Ready on/.test(String(d))) { clearTimeout(t); ok(); } }; relay.stdout.on("data", on); relay.stderr.on("data", on); });
const dist = path.join(here, "dist");
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".woff2": "font/woff2" };
const server = http.createServer((req, res) => {
  let f = path.join(dist, decodeURIComponent(req.url.split("?")[0])); if (f.endsWith("/")) f += "index.html";
  if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { "Content-Type": types[path.extname(f)] ?? "application/octet-stream" }); fs.createReadStream(f).pipe(res);
}).listen(0);
await new Promise((r) => server.once("listening", r));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const errors = []; page.on("pageerror", (e) => errors.push(e.message));
try {
  await page.goto(`http://127.0.0.1:${server.address().port}/?relay=http://127.0.0.1:8797#RPLAY`);
  await page.waitForFunction(() => !!window.__live);
  const host = new WebSocket("ws://127.0.0.1:8797/live/RPLAY?host=replay-key-0123456789abcdef");
  await new Promise((r) => host.on("open", r));
  let i = 0;
  for (const m of hostMsgs) {
    host.send(m);
    // screenshot mid-stroke once, to show the in-progress stroke
    if (m.startsWith('{"t":"wet","el":{') && ++i === 4) { await page.waitForTimeout(300); await page.screenshot({ path: path.join(outDir, "viewer-from-tablet-drawing.png") }); }
    await new Promise((r) => setTimeout(r, 15));
  }
  await page.waitForTimeout(800);
  const scene = await page.evaluate(() => window.__live.api.getSceneElements().map((e) => ({ id: e.id, type: e.type })));
  await page.screenshot({ path: path.join(outDir, "viewer-from-tablet.png") });
  const kinds = scene.reduce((a, e) => ((a[e.type] = (a[e.type] ?? 0) + 1), a), {});
  console.log("viewer shows:", JSON.stringify(kinds), `(${hostMsgs.length} messages from the tablet)`);
  assert.ok(kinds.frame >= 1, "page frame from the tablet rendered");
  assert.ok(kinds.freedraw >= 1, "strokes from the tablet rendered");
  assert.deepEqual(errors, [], "no page errors");
  console.log("REPLAY OK");
  host.close();
} finally {
  await browser.close(); server.close();
  try { process.kill(-relay.pid, "SIGTERM"); } catch { /* gone */ }
}
process.exit(0);
