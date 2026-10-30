import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require("../../tools/compat/node_modules/playwright");
const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 390, height: 844 } });
await p.goto(process.argv[2]); await p.waitForTimeout(800);
console.log(await p.evaluate(() => { const W = document.documentElement.clientWidth; return [...document.querySelectorAll("body *")].filter((e) => e.getBoundingClientRect().right > W + 1).slice(0, 8).map((e) => e.tagName + "." + e.className + " " + Math.round(e.getBoundingClientRect().right)); }));
await b.close();
