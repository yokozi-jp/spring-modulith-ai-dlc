// ruleset-check.mjs: .github/rulesets/main.json の必須チェックと、docs/repository/branch-protection.md の常時必須の一覧が一致することを検査する。
// 使い方: node tooling/ruleset-check/ruleset-check.mjs [リポジトリのルート]（既定は .）。task okf-check が呼ぶ。
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2] ?? ".";
const read = (path) => readFileSync(join(root, path), "utf8").replaceAll("\r\n", "\n");

const ruleset = JSON.parse(read(".github/rulesets/main.json"));
const inRuleset = new Set(
  ruleset.rules
    .filter((rule) => rule.type === "required_status_checks")
    .flatMap((rule) => rule.parameters.required_status_checks.map((check) => check.context)),
);

const doc = read("docs/repository/branch-protection.md");
const section = /^## 常時必須にするステータスチェック\n([\s\S]*?)^## /m.exec(doc)?.[1] ?? "";
const inDocs = new Set([...section.matchAll(/^- `([^`]+)`$/gm)].map((m) => m[1]));

const errors = [
  ...[...inRuleset].filter((name) => !inDocs.has(name)).map((name) => `ruleset にあり、docs の一覧にない: ${name}`),
  ...[...inDocs].filter((name) => !inRuleset.has(name)).map((name) => `docs の一覧にあり、ruleset にない: ${name}`),
];
if (inDocs.size === 0) errors.push("docs の「常時必須にするステータスチェック」の一覧を読めません");

if (errors.length > 0) {
  for (const error of errors) console.error(`✗ ${error}`);
  console.error("ruleset-check: .github/rulesets/main.json と docs/repository/branch-protection.md を揃えてください。");
  process.exit(1);
}
console.log(`ruleset-check OK: 必須チェック ${inRuleset.size} 件`);
