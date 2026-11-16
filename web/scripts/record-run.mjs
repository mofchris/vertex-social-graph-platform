// Record a real run of the demo for the offline fallback. Drives the live demo through all five
// steps against a running backend, then writes what the page captured to src/data/recording.json.
//   npm run build && npm run preview        # in another terminal, with the backend up
//   node scripts/record-run.mjs [http://localhost:4400]
// Then rebuild and deploy. The page only collects a recording when opened with ?record.
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright";

const base = process.argv[2] ?? "http://localhost:4400";
const out = join(import.meta.dirname, "..", "src", "data", "recording.json");
const steps = ["follow", "followBack", "post", "replay", "poison"];

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on("pageerror", (e) => console.error("page error:", e.message));
await page.goto(`${base}/demo?record`, { waitUntil: "networkidle" });

const status = await page.evaluate(() => fetch("/api/status").then((r) => r.json()));
if (status.status !== "UP") throw new Error(`backend is ${status.status}; start it first`);

await page.fill("#display-name", "Visitor");
await page.click("button[type=submit]");
await page.waitForSelector(".console__bar", { timeout: 30000 });

for (const id of steps) {
  await page.locator(".step.is-current button").first().click();
  await page.waitForFunction((k) => window.__vertexRecording?.steps?.[k], id, { timeout: 60000 });
  console.log(`recorded ${id}`);
}
// Let the panels refresh after the last step, then take the snapshot.
await page.waitForTimeout(2500);
await page.getByRole("button", { name: "Refresh" }).click();
await page.waitForTimeout(2500);
const rec = await page.evaluate(() => window.__vertexRecording);
await browser.close();

if (steps.some((k) => !rec.steps[k])) throw new Error("a step is missing from the recording");
writeFileSync(out, JSON.stringify(rec, null, 2) + "\n");
console.log(`wrote ${out}`);
