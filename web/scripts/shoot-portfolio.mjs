// Portfolio screenshots from a running site and backend: 1440x900 at device scale 2, PNG + WebP.
// Drives the real demo end to end, so every number on screen came from the services.
//   node scripts/shoot-portfolio.mjs <out dir> [http://localhost:4400] [light|dark]
// Files are named NN-name-<theme>.png/.webp.
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright";
import sharp from "sharp";

const [outDir, base = "http://localhost:4400", theme = "light"] = process.argv.slice(2);
if (!outDir) throw new Error("usage: node scripts/shoot-portfolio.mjs <out dir> [base url]");
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, colorScheme: theme });
page.on("pageerror", (e) => console.error("page error:", e.message));

async function shot(name) {
  await page.waitForTimeout(400);
  name = `${name}-${theme}`;
  const png = join(outDir, `${name}.png`);
  await page.screenshot({ path: png });
  await sharp(png).webp({ quality: 86 }).toFile(join(outDir, `${name}.webp`));
  console.log(`shot ${name}`);
}
const scrollTo = async (y) => {
  await page.evaluate((top) => window.scrollTo({ top, behavior: "instant" }), y);
  await page.waitForTimeout(1800);
};
const toConsole = async () => {
  const y = await page.evaluate(() => document.querySelector(".console__bar").getBoundingClientRect().top + scrollY - 88);
  await scrollTo(y);
};

// 1. Home, first screen.
await page.goto(base + "/", { waitUntil: "networkidle" });
await page.waitForTimeout(3000);
await shot("01-home");

// 2. The pinned trace schematic, scrubbed most of the way through.
const traceTop = await page.evaluate(() => document.querySelector("#trace").getBoundingClientRect().top + scrollY);
await scrollTo(traceTop + 900 * 2.2 * 0.8);
await page.waitForTimeout(1500);
await shot("02-trace-schematic");

// 3. Demo start screen with every service reporting in.
await page.goto(base + "/demo?record", { waitUntil: "networkidle" });
await page.waitForFunction(() => document.body.innerText.includes("All 7 services are up"), null, { timeout: 60000 });
await page.waitForTimeout(1500);
await shot("03-demo-start");

// 4-6. Run the real flow; shoot the follow, the replay and the dead letter.
await page.fill("#display-name", "Visitor");
await page.click("button[type=submit]");
await page.waitForSelector(".console__bar", { timeout: 30000 });
const run = async (id) => {
  await page.locator(".step.is-current button").first().click();
  await page.waitForFunction((k) => window.__vertexRecording?.steps?.[k], id, { timeout: 60000 });
  await page.waitForTimeout(1500);
};
await run("follow");
await toConsole();
await shot("04-follow-trace");
await run("followBack");
await run("post");
await run("replay");
await toConsole();
await shot("05-replay-idempotent");
await run("poison");
await toConsole();
await shot("06-dead-letter");

await browser.close();
