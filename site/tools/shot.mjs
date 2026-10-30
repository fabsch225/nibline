import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH ?? "../../tools/compat/node_modules/playwright");
const [url, out, w = 1440, h = 900, full = "1"] = process.argv.slice(2);
const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: +w, height: +h } });
await p.goto(url, { waitUntil: "networkidle" }); await p.waitForTimeout(2600);
await p.screenshot({ path: out, fullPage: full === "1" }); await b.close();
