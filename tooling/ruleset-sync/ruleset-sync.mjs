// ruleset-sync.mjs: .github/rulesets/main.json と GitHub 上の ruleset を比べ、反映する。
// 使い方: node tooling/ruleset-sync/ruleset-sync.mjs diff|apply
//   diff : 差分を出す。差分があれば終了コード 1。
//   apply: 反映前の ruleset を build/ruleset-backup/ に保存し、PUT してから取り直す。反映後も差分があれば終了コード 1。
// 認証済みの gh が要る。管理者の手元で使い、CI では動かさない（反映の手順は docs/repository/branch-protection.md）。
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const RULESET_ID = 23297515;
const FILE = ".github/rulesets/main.json";
const FIELDS = ["name", "target", "enforcement", "conditions", "bypass_actors", "rules"];

const gh = (...args) => execFileSync("gh", args, { encoding: "utf8" });
const endpoint = `repos/{owner}/{repo}/rulesets/${RULESET_ID}`;
const fetchLive = () => JSON.parse(gh("api", endpoint));

// JSON.stringify は、キーの順が違うだけで差が出る。キーを並べ替えて比べる。
const canonical = (value) =>
  Array.isArray(value)
    ? value.map(canonical)
    : value && typeof value === "object"
      ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]))
      : value;
const pick = (ruleset) => Object.fromEntries(FIELDS.map((field) => [field, canonical(ruleset[field])]));

// 差のあるフィールドと、rules は規則の種類ごとに名前を返す。
function differences(live, file) {
  const diffs = [];
  for (const field of FIELDS.filter((f) => f !== "rules")) {
    if (JSON.stringify(live[field]) !== JSON.stringify(file[field])) diffs.push(field);
  }
  const byType = (rules) => new Map(rules.map((rule) => [rule.type, JSON.stringify(rule)]));
  const liveRules = byType(live.rules);
  const fileRules = byType(file.rules);
  for (const type of new Set([...liveRules.keys(), ...fileRules.keys()])) {
    if (liveRules.get(type) !== fileRules.get(type)) diffs.push(`rules.${type}`);
  }
  return diffs;
}

const mode = process.argv[2];
if (mode !== "diff" && mode !== "apply") {
  console.error("使い方: ruleset-sync.mjs diff|apply");
  process.exit(2);
}

const file = pick(JSON.parse(readFileSync(FILE, "utf8")));
const before = fetchLive();
const beforeDiffs = differences(pick(before), file);

if (mode === "diff") {
  if (beforeDiffs.length === 0) console.log("ruleset-sync OK: GitHub 上の ruleset は main.json と同じです。");
  else console.error(`✗ GitHub 上の ruleset が main.json と違います: ${beforeDiffs.join(", ")}`);
  process.exit(beforeDiffs.length === 0 ? 0 : 1);
}

mkdirSync("build/ruleset-backup", { recursive: true });
const backup = `build/ruleset-backup/${new Date().toISOString().replaceAll(":", "-")}.json`;
writeFileSync(backup, `${JSON.stringify(before, null, 2)}\n`);
console.log(`反映前の ruleset を ${backup} に保存しました。戻すときは gh api -X PUT ${endpoint} --input ${backup} です。`);
console.log(`反映する差分: ${beforeDiffs.length === 0 ? "なし" : beforeDiffs.join(", ")}`);

gh("api", "-X", "PUT", endpoint, "--input", FILE);

const afterDiffs = differences(pick(fetchLive()), file);
if (afterDiffs.length > 0) {
  console.error(`✗ 反映後も GitHub 上の ruleset が main.json と違います: ${afterDiffs.join(", ")}`);
  process.exit(1);
}
console.log("ruleset-sync OK: 反映し、main.json と同じになったことを確かめました。");
