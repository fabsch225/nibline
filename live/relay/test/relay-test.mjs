// Starts the relay with `wrangler dev` (or uses RELAY_URL) and checks the live protocol end to end.
import { spawn } from "node:child_process";
import assert from "node:assert/strict";
import WebSocket from "ws";

let proc = null;
let base = process.env.RELAY_URL;
if (!base) {
  proc = spawn("npx", ["wrangler", "dev", "--local", "--port", "8799", "--ip", "127.0.0.1"], { cwd: new URL("..", import.meta.url).pathname, stdio: ["ignore", "pipe", "pipe"], detached: true });
  await new Promise((ok, fail) => {
    const t = setTimeout(() => fail(new Error("wrangler dev didn't start")), 60000);
    const on = (d) => { if (/Ready on/.test(String(d))) { clearTimeout(t); ok(); } };
    proc.stdout.on("data", on); proc.stderr.on("data", on);
  });
  base = "http://127.0.0.1:8799";
}
const wsBase = base.replace(/^http/, "ws");
const code = "T" + Array.from({ length: 4 }, () => "ABCDEFGHJKLMNPQRSTUVWXYZ"[Math.floor(Math.random() * 24)]).join("");
const KEY = "test-host-key-0123456789abcdef";

function connect(path) {
  const ws = new WebSocket(wsBase + path);
  const msgs = [];
  ws.on("message", (d) => msgs.push(JSON.parse(String(d))));
  const opened = new Promise((ok, fail) => { ws.on("open", ok); ws.on("unexpected-response", (_r, res) => fail(new Error("HTTP " + res.statusCode))); ws.on("error", fail); });
  const until = async (pred, ms = 5000) => { const t0 = Date.now(); while (Date.now() - t0 < ms) { const m = msgs.find(pred); if (m) return m; await new Promise((r) => setTimeout(r, 25)); } throw new Error("timeout waiting for message; got " + JSON.stringify(msgs.map((m) => m.t))); };
  return { ws, msgs, opened, until, send: (m) => ws.send(JSON.stringify(m)) };
}
const el = (id, v, extra = {}) => ({ id, type: "freedraw", version: v, versionNonce: v, x: 0, y: 0, points: [[0, 0], [10, 10]], pressures: [0.5, 0.5], isDeleted: false, ...extra });

try {
  const host = connect(`/live/${code}?host=${KEY}`); await host.opened;
  host.send({ t: "full", note: "Lecture 3", appState: { viewBackgroundColor: "#fff" }, elements: [el("a", 1), el("b", 1)] });
  host.send({ t: "file", id: "img1", mimeType: "image/png", dataURL: "data:image/png;base64,iVBORw0KGgo=" });
  host.send({ t: "view", x: 0, y: 0, w: 800, h: 600 });
  await new Promise((r) => setTimeout(r, 300));

  // a viewer joining later gets the current state
  const v1 = connect(`/live/${code}`); await v1.opened;
  await v1.until((m) => m.t === "host" && m.online === true);
  const got = new Set(v1.msgs.filter((m) => m.t === "delta").flatMap((m) => m.upsert.map((e) => e.id)));
  assert.equal(v1.msgs[0].t, "full"); assert.equal(v1.msgs[0].note, "Lecture 3");
  assert.deepEqual([...got].sort(), ["a", "b"], "viewer gets the elements");
  assert.ok(v1.msgs.some((m) => m.t === "file" && m.id === "img1"), "viewer gets files");
  assert.ok(v1.msgs.some((m) => m.t === "view" && m.w === 800), "viewer gets the viewport");
  await host.until((m) => m.t === "viewers" && m.n === 1);
  console.log("• viewer joining later gets note, elements, images, viewport; tablet sees 1 viewer");

  // live changes are forwarded
  host.send({ t: "wet", el: el("w", 1) });
  await v1.until((m) => m.t === "wet" && m.el?.id === "w");
  host.send({ t: "delta", upsert: [el("c", 1), el("a", 2)], remove: ["b"] });
  const d = await v1.until((m) => m.t === "delta" && m.remove?.includes("b"));
  assert.deepEqual(d.upsert.map((e) => e.id), ["c", "a"]);
  console.log("• in-progress strokes and changes reach viewers live");

  // viewers can't write
  v1.send({ t: "delta", upsert: [el("evil", 1)], remove: ["a"] });
  await new Promise((r) => setTimeout(r, 300));
  const v2 = connect(`/live/${code}`); await v2.opened;
  await v2.until((m) => m.t === "host");
  const ids2 = v2.msgs.filter((m) => m.t === "delta").flatMap((m) => m.upsert.map((e) => e.id)).sort();
  assert.deepEqual(ids2, ["a", "c"], "state = a(v2), c; viewer writes ignored");
  assert.equal(v2.msgs.filter((m) => m.t === "delta").flatMap((m) => m.upsert).find((e) => e.id === "a").version, 2);
  assert.ok(!v2.msgs.some((m) => m.t === "wet"), "wet strokes aren't stored");
  console.log("• viewers are read-only; stored state is current");

  // another tablet can't take over the code
  await assert.rejects(connect(`/live/${code}?host=another-key-0123456789abcdef`).opened, /403/);
  console.log("• a different host key is refused (403)");

  // tablet leaves → viewers told; reconnects with its key and resets the scene
  host.ws.close();
  await v1.until((m) => m.t === "host" && m.online === false);
  const host2 = connect(`/live/${code}?host=${KEY}`); await host2.opened;
  await v1.until((m) => m.t === "host" && m.online === true && v1.msgs.filter((x) => x.t === "host").length >= 3);
  host2.send({ t: "full", note: "Other note", appState: {}, elements: [el("z", 1)] });
  await v1.until((m) => m.t === "full" && m.note === "Other note");
  console.log("• tablet offline/online reaches viewers; switching notes replaces the scene");

  // big scenes arrive in chunks
  const big = Array.from({ length: 3000 }, (_, i) => el("e" + i, 1, { points: Array.from({ length: 40 }, (_, k) => [k, k * 2]) }));
  for (let i = 0; i < big.length; i += 500) host2.send({ t: "delta", upsert: big.slice(i, i + 500), remove: [] });
  await new Promise((r) => setTimeout(r, 800));
  const v3 = connect(`/live/${code}`); await v3.opened; await v3.until((m) => m.t === "host");
  const deltas = v3.msgs.filter((m) => m.t === "delta");
  assert.equal(deltas.flatMap((m) => m.upsert).length, 3001);
  assert.ok(deltas.length > 1, "chunked");
  console.log(`• 3001 elements reach a new viewer in ${deltas.length} chunks`);

  for (const c of [v1, v2, v3, host2]) c.ws.close();
  await new Promise((r) => setTimeout(r, 500));
  const v4 = connect(`/live/${code}`); await v4.opened; await v4.until((m) => m.t === "host");
  assert.equal(v4.msgs.filter((m) => m.t === "delta").length, 0, "nothing kept once everyone left");
  assert.equal(v4.msgs.find((m) => m.t === "host").online, false);
  v4.ws.close();
  console.log("• once everyone has left, the note is gone (nothing stored)");
  console.log("RELAY TESTS PASSED");
} finally {
  if (proc) try { process.kill(-proc.pid, "SIGTERM"); } catch { /* already gone */ }
}
process.exit(0);
