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
// Playwright はフロントエンドの devDependencies にあるので、そこから解決する。
const require = createRequire(join(root, "frontend/apps/web/package.json"));
const { chromium, expect } = require("@playwright/test");

const [journeyArg, ...rest] = process.argv.slice(2);
if (journeyArg === undefined) {
  console.error("使い方: drive.mjs <journey.mjs> [key=value ...]");
  process.exit(2);
}
const params = Object.fromEntries(rest.map((pair) => pair.split(/=(.*)/su).slice(0, 2)));

// .env.test.local が .env.test より優先する（e2e/environment.ts と同じ）。
function readEnv() {
  const env = {};
  for (const file of [".env.test", ".env.test.local"]) {
    const path = join(root, file);
    if (!existsSync(path)) continue;
    for (const line of readFileSync(path, "utf8").split("\n")) {
      const match = /^([A-Z0-9_]+)=(.*)$/u.exec(line.trim());
      if (match) env[match[1]] = match[2].replace(/^"(.*)"$/u, "$1");
    }
  }
  return env;
}
const env = readEnv();
const baseURL = "http://localhost:5173";
const gatewayURL = env.PAYMENT_GATEWAY_BASE_URL ?? "http://127.0.0.1:8082";

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
// 操作の後の状態を、screenshot と ARIA snapshot の対で残す。
async function step(label, target = page) {
  stepNo += 1;
  const prefix = `${String(stepNo).padStart(2, "0")}-${label}`;
  await target.screenshot({ path: join(runDir, `${prefix}.png`), fullPage: true });
  writeFileSync(join(runDir, `${prefix}.aria.yml`), await target.locator("body").ariaSnapshot());
  log("steps.log", `${prefix} ${target.url()}`);
}

// 決済代行の WireMock の管理 API（e2e/payment-gateway.ts と同じ）。
const gateway = {
  async chargeCount(orderId) {
    const response = await context.request.post(`${gatewayURL}/__admin/requests/count`, {
      data: { method: "POST", url: "/v1/charges", headers: { "Idempotency-Key": { equalTo: orderId } } },
    });
    return (await response.json()).count;
  },
  async failChargesFor(orderId, response) {
    const id = crypto.randomUUID();
    const created = await context.request.post(`${gatewayURL}/__admin/mappings`, {
      data: { id, priority: 1, request: { method: "POST", url: "/v1/charges", headers: { "Idempotency-Key": { equalTo: orderId } } }, response },
    });
    expect(created.status(), "WireMock のスタブの登録").toBe(201);
    return () => context.request.delete(`${gatewayURL}/__admin/mappings/${id}`);
  },
};

// アプリのロール（DML 限定）で、読み取り専用のトランザクションとして SQL を実行する。
function sql(query) {
  const out = execFileSync(
    "docker",
    ["compose", "-f", "docker/compose-test.yml", "exec", "-T", "postgres", "psql", "-U", "demo", "-d", "demo",
      "-v", "ON_ERROR_STOP=1", "-qtA", "-c", "SET default_transaction_read_only = on", "-c", query],
    { cwd: root, encoding: "utf8" },
  );
  log("sql.log", `> ${query}\n${out.trim()}`);
  return out.trim();
}

// 利用者と同じく Keycloak の画面からログインする（e2e/auth.setup.ts と同じ経路）。
async function login(target = page) {
  await target.goto("/oauth2/authorization/web");
  await target.getByLabel("Username or email").fill(env.E2E_USERNAME);
  await target.getByLabel("Password", { exact: true }).fill(env.E2E_PASSWORD);
  await target.getByRole("button", { name: "Sign In" }).click();
  await expect(target).toHaveURL(`${baseURL}/`);
}

const result = { journey: journeyPath, params, startedAt: new Date().toISOString(), status: "passed" };
let exitCode = 0;
try {
  const journey = await import(pathToFileURL(journeyPath).href);
  // ログインそのものを確かめる journey は export const login = false で自分でログインする。
  if (journey.login !== false) await login();
  await journey.default({ page, context, expect, step, gateway, sql, login, params, record: (k, v) => (result[k] = v) });
} catch (error) {
  result.status = "failed";
  result.error = String(error?.stack ?? error);
  exitCode = 1;
  await page.screenshot({ path: join(runDir, "failure.png"), fullPage: true }).catch(() => {});
} finally {
  result.finishedAt = new Date().toISOString();
  // trace は Cookie を含む。build/ は Git から除外され、片付けで破棄する環境の Cookie である。
  await context.tracing.stop({ path: join(runDir, "trace.zip") });
  await browser.close();
  writeFileSync(join(runDir, "result.json"), `${JSON.stringify(result, null, 2)}\n`);
}
console.log(`${result.status}: ${runDir}`);
if (exitCode !== 0) console.error(result.error);
process.exit(exitCode);
