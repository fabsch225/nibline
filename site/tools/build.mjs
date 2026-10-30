// Fills the generated ink strokes into index.src.html -> index.html (run `node strokes.mjs && node build.mjs`).
import fs from "node:fs";
const dir = new URL("..", import.meta.url);
const strokes = JSON.parse(fs.readFileSync(new URL("strokes.json", dir), "utf8"));
const html = fs.readFileSync(new URL("index.src.html", dir), "utf8").replace(/\{\{(\w+)\.(\w+)\}\}/g, (m, k, f) => {
  const v = strokes[k]?.[f];
  if (v === undefined) throw new Error("missing stroke " + m);
  return String(v);
});
fs.writeFileSync(new URL("index.html", dir), html);
console.log("index.html", (html.length / 1024).toFixed(0) + " KB");
