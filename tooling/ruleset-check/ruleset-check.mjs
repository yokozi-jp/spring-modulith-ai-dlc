// ruleset-check.mjs: .github/rulesets/main.json の必須チェックを、docs/repository/branch-protection.md の常時必須の一覧と、workflow の名前に突き合わせる。
// 使い方: node tooling/ruleset-check/ruleset-check.mjs [リポジトリのルート]（既定は .）。task okf-check が呼ぶ。
// 失敗: 一覧を読めない、ruleset と一覧の不一致、一覧の行の書式違反、workflow に同名の name がない必須チェック。
// ponytail: workflow の name は job と step を区別せず、行頭の `name:` を全部集める。job でない name と衝突すると見逃す。
// ponytail: GitHub 上の ruleset との一致は見ない。比べるのは認証が要る手元の task ruleset-diff で、ファイルだけ直した変更は CI で検出できない。
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2] ?? ".";
const read = (path) => readFileSync(join(root, path), "utf8").replaceAll("\r\n", "\n");

const ruleset = JSON.parse(read(".github/rulesets/main.json"));
const inRuleset = new Set(
  ruleset.rules
    .filter((rule) => rule.type === "required_status_checks")
    .flatMap((rule) => rule.parameters.required_status_checks.map((check) => check.context)),
);

const lines = read("docs/repository/branch-protection.md").split("\n");
const start = lines.indexOf("## 常時必須にするステータスチェック");
if (start < 0) {
  console.error("✗ docs の「常時必須にするステータスチェック」の節を読めません。");
  process.exit(1);
}
const end = lines.findIndex((line, i) => i > start && line.startsWith("## "));
const section = lines.slice(start + 1, end < 0 ? undefined : end);
const bullets = section.filter((line) => /^[-*] /.test(line));
const inDocs = new Set();
const errors = [];
for (const line of bullets) {
  const name = /^- `([^`]+)`$/.exec(line)?.[1];
  if (name) inDocs.add(name);
  else errors.push(`一覧の行は「- \`名前\`」だけにしてください: ${line}`);
}

const workflowDir = join(root, ".github", "workflows");
const workflowNames = new Set(
  (existsSync(workflowDir) ? readdirSync(workflowDir).filter((file) => /\.ya?ml$/.test(file)) : [])
    .flatMap((file) => read(join(".github", "workflows", file)).split("\n"))
    .map((line) => /^\s+(?:- )?name:\s*(.+?)\s*$/.exec(line)?.[1]?.replace(/^(["'])(.*)\1$/, "$2"))
    .filter(Boolean),
);

errors.push(
  ...[...inRuleset].filter((name) => !inDocs.has(name)).map((name) => `ruleset にあり、docs の一覧にありません: ${name}`),
  ...[...inDocs].filter((name) => !inRuleset.has(name)).map((name) => `docs の一覧にあり、ruleset にありません: ${name}`),
  ...[...inRuleset].filter((name) => !workflowNames.has(name)).map((name) => `workflow に同名の name がなく、報告されません: ${name}`),
);

if (errors.length > 0) {
  for (const error of errors) console.error(`✗ ${error}`);
  console.error("ruleset-check: 不整合があります。.github/rulesets/main.json、branch-protection.md、workflow の名前を揃えてください。");
  process.exit(1);
}
console.log(`ruleset-check OK: 必須チェック ${inRuleset.size} 件`);
