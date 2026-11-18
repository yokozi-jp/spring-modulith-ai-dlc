// ruleset-sync.mjs: .github/rulesets/main.json と GitHub 上の ruleset を比べ、反映する。
// 使い方: node tooling/ruleset-sync/ruleset-sync.mjs diff|apply
//   diff : 差分を出す。差分があれば終了コード 1。
//   apply: 差分があれば、反映前の ruleset を build/ruleset-backup/ に保存し、CONFIRM_RULESET=yes のとき PUT して取り直す。
// 認証済みの gh が要る。管理者の手元で使い、CI では動かさない（手順は docs/repository/branch-protection.md）。
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const RULESET_ID = 23297515;
const FILE = ".github/rulesets/main.json";
const FIELDS = ["name", "target", "enforcement", "conditions", "bypass_actors", "rules"];

const gh = (...args) => execFileSync("gh", args, { encoding: "utf8" });
const endpoint = `repos/{owner}/{repo}/rulesets/${RULESET_ID}`;

// 比べる単位に分ける。rules は種類ごとに 1 項目にして、どの規則が違うかを示す。キーの順は無視する。
const sorted = (value) =>
  Array.isArray(value)
    ? value.map(sorted)
    : value && typeof value === "object"
      ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, sorted(value[key])]))
      : value;
const parts = (ruleset) =>
  new Map([
    ...FIELDS.filter((field) => field !== "rules").map((field) => [field, ruleset[field]]),
    ...ruleset.rules.map((rule) => [`rules.${rule.type}`, rule]),
  ].map(([key, value]) => [key, JSON.stringify(sorted(value))]));
const differences = (live, file) => {
  const [a, b] = [parts(live), parts(file)];
  return [...new Set([...a.keys(), ...b.keys()])].filter((key) => a.get(key) !== b.get(key));
};

const mode = process.argv[2];
if (mode !== "diff" && mode !== "apply") {
  console.error("使い方: ruleset-sync.mjs diff|apply");
  process.exit(2);
}

// rules がない ruleset を PUT すると、保護が外れる。読めない形なら、比べる前に止める。
const checked = (ruleset, source) => {
  if (!Array.isArray(ruleset.rules) || ruleset.rules.length === 0) {
    console.error(`✗ ${source} の rules を読めません。`);
    process.exit(2);
  }
  return ruleset;
};
const file = checked(JSON.parse(readFileSync(FILE, "utf8")), FILE);
const live = checked(JSON.parse(gh("api", endpoint)), "GitHub 上の ruleset");
const diffs = differences(live, file);

if (diffs.length === 0) {
  console.log("ruleset-sync OK: GitHub 上の ruleset は main.json と同じです。");
  process.exit(0);
}
console.error(`GitHub 上の ruleset が main.json と違います: ${diffs.join(", ")}`);
if (mode === "diff") process.exit(1);

// 戻すときに PUT できるよう、PUT が受け取るフィールドだけを保存する。
mkdirSync("build/ruleset-backup", { recursive: true });
const backup = `build/ruleset-backup/${new Date().toISOString().replaceAll(":", "-")}.json`;
writeFileSync(backup, `${JSON.stringify(Object.fromEntries(FIELDS.map((field) => [field, live[field]])), null, 2)}\n`);
console.error(`反映前の ruleset を ${backup} に保存しました。戻すときは gh api -X PUT ${endpoint} --input ${backup} です。`);

if (process.env.CONFIRM_RULESET !== "yes") {
  console.error("main の保護設定を書き換えます。反映するには CONFIRM_RULESET=yes を指定してください（例: task ruleset-apply CONFIRM_RULESET=yes）。");
  process.exit(1);
}
gh("api", "-X", "PUT", endpoint, "--input", FILE);

const after = differences(checked(JSON.parse(gh("api", endpoint)), "反映後の ruleset"), file);
if (after.length > 0) {
  console.error(`✗ 反映後も GitHub 上の ruleset が main.json と違います: ${after.join(", ")}`);
  process.exit(1);
}
console.log("ruleset-sync OK: 反映し、main.json と同じになったことを確かめました。");
