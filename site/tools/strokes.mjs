// Generates the ink strokes on the homepage with perfect-freehand and the same options the app and Excalidraw use,
// so the illustration is exactly what real strokes look like. Writes ../strokes.json (outline + centerline per stroke).
import { getStroke } from "perfect-freehand";
import fs from "node:fs";

const opts = (w, last = true) => ({ size: w * 4.25, thinning: 0.6, smoothing: 0.5, streamline: 0.5, easing: (t) => Math.sin((t * Math.PI) / 2), last, simulatePressure: false });
const outline = (pts, w) => {
  const o = getStroke(pts, opts(w));
  if (!o.length) return "";
  const d = o.reduce((acc, [x0, y0], i, a) => { const [x1, y1] = a[(i + 1) % a.length]; acc.push(x0.toFixed(1), y0.toFixed(1), ((x0 + x1) / 2).toFixed(1), ((y0 + y1) / 2).toFixed(1)); return acc; }, ["M", ...o[0].map((v) => v.toFixed(1)), "Q"]);
  return d.join(" ") + " Z";
};
const center = (pts) => "M" + pts.map(([x, y]) => `${x.toFixed(1)} ${y.toFixed(1)}`).join(" L");
const press = (n, i, base = 0.35, amp = 0.45) => base + amp * Math.sin((i / (n - 1)) * Math.PI);

const out = {};
// hero flourish: cursive-like loops (a trochoid) that end in a long swash
{
  const pts = []; const n = 420;
  for (let i = 0; i < n; i++) {
    const t = i / (n - 1);
    const k = t * Math.PI * 2 * 7.5;
    const r = 26 * (1 - t * 0.55);
    const x = 20 + t * 1500 - r * Math.sin(k) * (t < 0.82 ? 1 : (1 - (t - 0.82) / 0.18));
    const y = 70 + r * Math.cos(k) * (t < 0.82 ? 1 : (1 - (t - 0.82) / 0.18)) - (t > 0.82 ? (t - 0.82) * 260 : 0);
    pts.push([x, y, press(n, i, 0.3, 0.55)]);
  }
  out.flourish = { d: outline(pts, 2.4), c: center(pts.map(([x, y]) => [x, y])), w: 1540, h: 140 };
}
// annotations on the illustrated PDF page (page is 600×848)
{
  // highlighter over the title (wide, constant pressure)
  const hl = Array.from({ length: 60 }, (_, i) => [60 + i * 6.2, 98 + Math.sin(i / 9) * 1.5, 0.5]);
  out.highlight = { d: outline(hl, 7) };
  // circle around the peak of the plot
  const circ = Array.from({ length: 90 }, (_, i) => { const a = -0.6 + (i / 89) * Math.PI * 2.15; return [372 + 62 * Math.cos(a) * (1 + i / 900), 300 + 38 * Math.sin(a), press(90, i)]; });
  out.circle = { d: outline(circ, 1.8) };
  // arrow from the circle to the margin note
  const arr = Array.from({ length: 40 }, (_, i) => { const t = i / 39; return [420 + t * 110, 335 + t * 120 - Math.sin(t * Math.PI) * 30, press(40, i)]; });
  const head1 = Array.from({ length: 10 }, (_, i) => [530 - i * 2.2, 455 - i * 2.4, 0.6]);
  const head2 = Array.from({ length: 10 }, (_, i) => [530 - i * 3.0, 455 + i * 0.4, 0.6]);
  out.arrow = { d: [outline(arr, 1.6), outline(head1, 1.6), outline(head2, 1.6)].join(" ") };
  // underline under the formula, two quick strokes
  const ul = Array.from({ length: 50 }, (_, i) => [70 + i * 7.4, 568 + Math.sin(i / 7) * 2.5, press(50, i, 0.4, 0.4)]);
  const ul2 = Array.from({ length: 40 }, (_, i) => [90 + i * 8.2, 580 + Math.sin(i / 5) * 2, press(40, i, 0.3, 0.4)]);
  out.underline = { d: outline(ul, 1.6) + " " + outline(ul2, 1.4) };
  // margin note: lines of small cursive loops broken into "words", so it reads as handwriting at a glance
  const word = (x0, y0, len, seed) => { const n = Math.max(12, Math.round(len * 1.6)); return Array.from({ length: n }, (_, i) => { const t = i / (n - 1); const k = t * Math.PI * 2 * (len / 14) + seed; const h = 4 + 3 * Math.sin(t * 7 + seed * 3); return [x0 + t * len - 2.2 * Math.sin(k), y0 - h * (0.5 + 0.5 * Math.cos(k)) + Math.sin(t * 3 + seed) * 1.2, press(n, i, 0.35, 0.35)]; }); };
  const words = [[440, 490, 34], [480, 490, 22], [508, 490, 40], [440, 512, 26], [472, 512, 44], [522, 512, 18], [440, 534, 38], [484, 534, 28]];
  out.note = { d: words.map(([x, y, l], i) => outline(word(x, y, l, i * 1.7), 0.8)).join(" ") };
  // check mark next to the bullet
  const ck = [[24, 656, .3], [29, 662, .5], [34, 668, .7], [41, 657, .7], [48, 645, .6], [55, 633, .4]];
  const ckd = []; for (let i = 0; i < ck.length - 1; i++) for (let j = 0; j < 6; j++) { const t = j / 6; ckd.push([ck[i][0] + (ck[i + 1][0] - ck[i][0]) * t, ck[i][1] + (ck[i + 1][1] - ck[i][1]) * t, ck[i][2]]); }
  out.check = { d: outline(ckd, 2) };
}
fs.writeFileSync(new URL("../strokes.json", import.meta.url), JSON.stringify(out));
console.log(Object.fromEntries(Object.entries(out).map(([k, v]) => [k, v.d.length])));
