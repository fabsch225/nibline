// End to end: relay (wrangler dev) + built viewer (dist/) in headless Chromium + a fake tablet speaking the live protocol.
import { spawn } from "node:child_process";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import WebSocket from "ws";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH ?? "../../../tools/compat/node_modules/playwright");
const here = new URL("..", import.meta.url).pathname;
const OUT = path.join(here, "test/out"); fs.mkdirSync(OUT, { recursive: true });

const relayDir = path.join(here, "../relay");
const relay = spawn("npx", ["wrangler", "dev", "--local", "--port", "8798", "--ip", "127.0.0.1"], { cwd: relayDir, stdio: ["ignore", "pipe", "pipe"], detached: true });
await new Promise((ok, fail) => {
  const t = setTimeout(() => fail(new Error("relay didn't start")), 60000);
  const on = (d) => { if (/Ready on/.test(String(d))) { clearTimeout(t); ok(); } };
  relay.stdout.on("data", on); relay.stderr.on("data", on);
});
// static server for dist/
const dist = path.join(here, "dist");
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".woff2": "font/woff2", ".svg": "image/svg+xml", ".png": "image/png" };
const server = http.createServer((req, res) => {
  let f = path.join(dist, decodeURIComponent(req.url.split("?")[0]));
  if (f.endsWith("/")) f += "index.html";
  if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { "Content-Type": types[path.extname(f)] ?? "application/octet-stream" }); fs.createReadStream(f).pipe(res);
}).listen(0);
await new Promise((r) => server.once("listening", r));

const CODE = "VIEWX";
const base = { angle: 0, backgroundColor: "transparent", fillStyle: "solid", strokeStyle: "solid", roughness: 0, opacity: 100, groupIds: [], roundness: null, seed: 1, isDeleted: false, boundElements: null, updated: 1, link: null, locked: false, strokeColor: "#1e1e1e", strokeWidth: 2 };
const frame = { ...base, id: "page1", type: "frame", x: 0, y: 0, width: 793.33, height: 1122.67, strokeColor: "#bbb", frameId: null, index: "a0", version: 1, versionNonce: 1, name: "Page 1",
  customData: { inkcalidrawPage: 1, background: { kind: "pdf", file: "Lecture 3.pdf", page: 0 } } };
const stroke = (id, y, color, idx) => ({ ...base, id, type: "freedraw", x: 120, y, width: 520, height: 60, strokeColor: color, frameId: "page1", index: idx, version: 1, versionNonce: 2,
  points: Array.from({ length: 120 }, (_, i) => [i * 4.3, Math.sin(i / 8) * 28]), pressures: Array.from({ length: 120 }, (_, i) => 0.3 + 0.5 * Math.sin((i / 120) * Math.PI)), simulatePressure: false, lastCommittedPoint: null });
// a "rendered PDF page": light grey page with a blue box, as JPEG/PNG data URL made in the browser later
const tablet = new WebSocket("ws://127.0.0.1:8798/live/" + CODE + "?host=tablet-secret-0123456789abcdef");
const fromViewer = [];
tablet.on("message", (d) => fromViewer.push(JSON.parse(String(d))));
await new Promise((r) => tablet.on("open", r));
const send = (m) => tablet.send(JSON.stringify(m));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1000, height: 760 } });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
try {
  // join screen first
  await page.goto(`http://127.0.0.1:${server.address().port}/?relay=http://127.0.0.1:8798`);
  await page.waitForTimeout(400); await page.screenshot({ path: path.join(OUT, "0-join.png") });
  await page.getByLabel("Live code").fill("viewx");
  await page.getByRole("button", { name: "Watch" }).click();
  await page.waitForFunction(() => location.hash === "#VIEWX");
  await page.getByText("Open a note on the tablet.").waitFor({ timeout: 10000 });
  await page.screenshot({ path: path.join(OUT, "1-waiting.png") });
  for (let i = 0; i < 50 && !fromViewer.some((m) => m.t === "viewers" && m.n === 1); i++) await new Promise((r) => setTimeout(r, 100));
  assert.ok(fromViewer.some((m) => m.t === "viewers" && m.n === 1), "tablet sees the viewer");
  console.log("• join screen → code → connected; tablet sees 1 viewer");

  const pdfPage = await page.evaluate(() => { const c = document.createElement("canvas"); c.width = 794; c.height = 1123; const x = c.getContext("2d");
    x.fillStyle = "#fff"; x.fillRect(0, 0, 794, 1123); x.fillStyle = "#1971c2"; x.fillRect(60, 160, 670, 300); x.fillStyle = "#222"; x.font = "36px sans-serif"; x.fillText("Lecture 3 – PDF page", 60, 110); return c.toDataURL("image/jpeg", 0.8); });
  send({ t: "full", note: "Lecture 3", appState: { viewBackgroundColor: "#ffffff" }, elements: [frame, stroke("s1", 620, "#e03131", "a1")] });
  send({ t: "file", id: "pdf-Lecture3pdf-0", mimeType: "image/jpeg", dataURL: pdfPage });
  send({ t: "view", x: 0, y: 0, w: 794, h: 1123 });
  const ids = () => page.evaluate(() => window.__live.api.getSceneElements().map((e) => e.id).sort());
  for (let i = 0; i < 50 && (await ids()).length < 3; i++) await page.waitForTimeout(100);
  assert.deepEqual(await ids(), ["bg-page1", "page1", "s1"], "frame, stroke and PDF page shown");
  await page.waitForTimeout(400);
  const zoom = await page.evaluate(() => window.__live.api.getAppState().zoom.value);
  assert.ok(zoom > 0.5 && zoom < 0.8, "follows the tablet's view (fit page), zoom=" + zoom);
  assert.equal(await page.getByText("Lecture 3", { exact: true }).count(), 1, "note title shown");
  await page.screenshot({ path: path.join(OUT, "2-note.png") });
  console.log("• note, PDF page under the frame, title; viewport follows the tablet (zoom " + zoom.toFixed(2) + ")");

  // drawing live: wet stroke, then committed
  const wet = stroke("s2", 760, "#1971c2", "a2"); wet.points = wet.points.slice(0, 40);
  send({ t: "wet", el: wet });
  await page.waitForFunction(() => window.__live.api.getSceneElements().some((e) => e.id === "s2" && e.points.length === 40));
  await page.screenshot({ path: path.join(OUT, "3-wet.png") });
  send({ t: "delta", upsert: [{ ...stroke("s2", 760, "#1971c2", "a2"), version: 2 }], remove: [] });
  send({ t: "wet", el: null });
  await page.waitForFunction(() => window.__live.api.getSceneElements().some((e) => e.id === "s2" && e.points.length === 120));
  send({ t: "delta", upsert: [], remove: ["s1"] });
  await page.waitForFunction(() => !window.__live.api.getSceneElements().some((e) => e.id === "s1"));
  console.log("• in-progress stroke shows while drawing, then the finished stroke; erased strokes disappear");

  // the tablet scrolls: viewer follows; user pans → follow off
  send({ t: "view", x: 0, y: 500, w: 400, h: 300 });
  await page.waitForFunction(() => window.__live.api.getAppState().zoom.value > 2);
  await page.mouse.move(500, 400); await page.mouse.wheel(0, 200);
  await page.waitForTimeout(200);
  assert.equal(await page.locator("button.on", { hasText: "Follow" }).count(), 0, "follow turns off when you scroll yourself");
  send({ t: "view", x: 0, y: 0, w: 794, h: 1123 });
  await page.waitForTimeout(300);
  assert.ok(await page.evaluate(() => window.__live.api.getAppState().zoom.value) > 1.5, "stays where the viewer scrolled");
  await page.getByRole("button", { name: "Follow" }).click();
  await page.waitForFunction(() => window.__live.api.getAppState().zoom.value < 0.8);
  console.log("• follows the tablet's scrolling; scrolling yourself pauses Follow, tapping it resumes");

  // tablet goes away / comes back and resends
  tablet.close();
  await page.locator(".bar .state", { hasText: "Tablet offline" }).waitFor({ timeout: 5000 });
  const t2 = new WebSocket("ws://127.0.0.1:8798/live/" + CODE + "?host=tablet-secret-0123456789abcdef");
  await new Promise((r) => t2.on("open", r));
  t2.send(JSON.stringify({ t: "full", note: "Week 4", appState: {}, elements: [stroke("n1", 100, "#2f9e44", "a0")] }));
  await page.getByText("Week 4", { exact: true }).waitFor({ timeout: 5000 });
  await page.waitForFunction(() => window.__live.api.getSceneElements().map((e) => e.id).join() === "n1", null, { timeout: 5000 });
  await page.screenshot({ path: path.join(OUT, "4-reconnected.png") });
  console.log("• tablet offline shown; reconnecting with another note replaces the view");
  t2.close();
  assert.deepEqual(errors, [], "no page errors");
  console.log("VIEWER TESTS PASSED");
} finally {
  await browser.close(); server.close();
  try { process.kill(-relay.pid, "SIGTERM"); } catch { /* gone */ }
}
process.exit(0);
