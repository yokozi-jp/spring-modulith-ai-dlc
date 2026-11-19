#!/usr/bin/env node
// 検証スタックに Chromium でログインし、journey を 1 つ実行して証拠を build/verify/runs/<run>/ に残す。
// 使い方: drive.mjs <journey.mjs> [key=value ...]
import { execFileSync } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = execFileSync("git", ["rev-parse", "--show-toplevel"], {
  cwd: dirname(fileURLToPath(import.meta.url)),
  encoding: "utf8",
}).trim();
const web = join(root, "frontend/apps/web");
// up が取った slot のポートと Compose project を環境変数へ入れる。e2e/environment.ts と docker compose がそれを読む。
const slotFile = join(root, "build/verify/stack/slot.env");
if (!existsSync(slotFile)) {
  console.error("build/verify/stack/slot.env がありません。stack.sh up を実行してください。");
  process.exit(2);
}
for (const [, key, value] of readFileSync(slotFile, "utf8").matchAll(/^export ([A-Z_0-9]+)=(.*)$/gmu)) process.env[key] = value;
const require = createRequire(join(web, "package.json"));
const { chromium, expect, request } = require("@playwright/test");
const e2e = (file) => import(pathToFileURL(join(web, "e2e", file)).href);
// 資格情報と決済代行の URL は .env.test から e2e/environment.ts が読み、なければ止まる。
const { chooseSeededTenant, idpOrigin, signInOnKeycloak, webOrigin } = await e2e("environment.ts");
const { chargeRequestCount, failChargesFor } = await e2e("payment-gateway.ts");
const orders = await e2e("orders.ts");

const [journeyArg, ...rest] = process.argv.slice(2);
if (journeyArg === undefined) {
  console.error("使い方: drive.mjs <journey.mjs> [key=value ...]");
  process.exit(2);
}
const params = Object.fromEntries(rest.map((pair) => pair.split(/=(.*)/su).slice(0, 2)));
const baseURL = webOrigin;

const journeyPath = resolve(journeyArg);
const name = basename(journeyPath, ".mjs");
const stamp = new Date().toISOString().replace(/[-:]/gu, "").replace(/\..*/u, "Z");
const runDir = join(root, "build/verify/runs", `${stamp}-${name}`);
mkdirSync(runDir, { recursive: true });
const log = (file, line) => appendFileSync(join(runDir, file), `${line}\n`);

const browser = await chromium.launch();
const context = await browser.newContext({ baseURL, locale: "ja-JP", viewport: { width: 1280, height: 900 } });
// compose-test には Collector がないので、E2E と同じく /collect を 202 で返す（e2e/fixtures.ts）。
await context.route("**/collect", (route) => route.fulfill({ status: 202 }));
await context.tracing.start({ screenshots: true, snapshots: true });
context.on("page", (page) => watch(page));
function watch(page) {
  page.on("console", (message) => log("console.log", `[${message.type()}] ${message.text()}`));
  page.on("pageerror", (error) => log("console.log", `[pageerror] ${error.message}`));
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (url.origin === baseURL && url.pathname.startsWith("/api/")) {
      log("network.log", `${response.request().method()} ${url.pathname}${url.search} ${response.status()}`);
    }
  });
}
const page = await context.newPage();

let stepNo = 0;
async function step(label, target = page) {
  stepNo += 1;
  const prefix = `${String(stepNo).padStart(2, "0")}-${label}`;
  await target.screenshot({ path: join(runDir, `${prefix}.png`), fullPage: true });
  writeFileSync(join(runDir, `${prefix}.aria.yml`), `${await target.locator("body").ariaSnapshot()}\n`);
  log("steps.log", `${prefix} ${target.url()}`);
}

const gateway = {
  chargeCount: (orderId) => chargeRequestCount(context.request, orderId),
  failChargesFor: (orderId, failure) => failChargesFor(context.request, orderId, failure),
};

// アプリのロール demo は業務のスキーマを読めない（ADR-094）。payment_module は payment と ordering を読める。
// 値は psql の変数（vars）で渡し、クエリの中で :'name' と書く。読み取り専用は誤操作の防止で、クエリの中で解除できる。
function sql(query, vars = {}) {
  const args = ["compose", "-f", "docker/compose-test.yml", "--profile", "e2e", "exec", "-T", "postgres",
    "psql", "-U", "demo", "-d", "demo", "-v", "ON_ERROR_STOP=1", "-qtA"];
  for (const [key, value] of Object.entries(vars)) args.push("-v", `${key}=${value}`);
  const input = `SET ROLE payment_module;\nSET default_transaction_read_only = on;\n${query};\n`;
  const out = execFileSync("docker", args, { cwd: root, encoding: "utf8", input }).trim();
  log("sql.log", `> ${query} ${JSON.stringify(vars)}\n${out}`);
  return out;
}

async function login(target = page) {
  await target.goto("/oauth2/authorization/web");
  await signInOnKeycloak(target);
  // test-user は 2 つのテナントに属するので、ログインの後にテナントを選ぶ。選んだ後にトップページへ戻る。
  await chooseSeededTenant(target);
}

const result = { journey: journeyPath, params, startedAt: new Date().toISOString(), status: "passed", records: {} };
try {
  const journey = await import(pathToFileURL(journeyPath).href);
  // ログインそのものを確かめる journey は export const login = false で自分でログインする。
  if (journey.login !== false) await login();
  await journey.default({
    page, context, expect, step, gateway, sql, login, orders, params, webOrigin, idpOrigin,
    request,
    record: (key, value) => (result.records[key] = value),
  });
} catch (error) {
  result.status = "failed";
  result.error = String(error?.stack ?? error);
  await page.screenshot({ path: join(runDir, "failure.png"), fullPage: true }).catch(() => {});
}
// trace は Cookie を含む。build/ は Git から除外され、片付けで破棄する環境の Cookie である。
await context.tracing.stop({ path: join(runDir, "trace.zip") }).catch((error) => (result.traceError = String(error)));
await browser.close().catch(() => {});
result.finishedAt = new Date().toISOString();
writeFileSync(join(runDir, "result.json"), `${JSON.stringify(result, null, 2)}\n`);
console.log(`${result.status}: ${runDir}`);
if (result.status !== "passed") {
  console.error(result.error);
  process.exit(1);
}
