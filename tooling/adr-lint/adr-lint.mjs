// adr-lint.mjs: ADR の番号、索引、状態と日付、リンクのラベルと宛先の整合を検査する（docs/adr/conventions.md）。
// 使い方: node tooling/adr-lint/adr-lint.mjs [リポジトリのルート]（既定は .）。task okf-check が呼ぶ。
// 失敗: 番号の重複、索引の欠落と宛先の不一致、索引と本文の状態と日付の不一致、リンクのラベルと宛先の番号の不一致。
// 警告: 番号の欠け（終了コードは 0）。
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";

const root = process.argv[2] ?? ".";
const adrDir = join(root, "docs", "adr");
const errors = [];
const warnings = [];

const read = (path) => readFileSync(path, "utf8").replaceAll("\r\n", "\n");
const walk = (dir, pattern) =>
  existsSync(dir)
    ? readdirSync(dir, { recursive: true, withFileTypes: true })
        .filter((entry) => entry.isFile() && pattern.test(entry.name))
        .map((entry) => join(entry.parentPath, entry.name))
    : [];

// 見出しの次の空でない行を返す。
const sectionValue = (text, heading) => {
  const lines = text.split("\n");
  const at = lines.indexOf(`## ${heading}`);
  return at < 0 ? undefined : lines.slice(at + 1).find((line) => line.trim() !== "")?.trim();
};
// 状態は先頭の語に正規化する（Superseded by ... は Superseded）。
const firstWord = (value) => value?.split(/\s+/)[0];

// 1. 番号の重複
const files = readdirSync(adrDir)
  .map((name) => ({ name, number: /^ADR-(\d+)-.+\.md$/.exec(name)?.[1] }))
  .filter((file) => file.number);
const byNumber = Map.groupBy(files, (file) => Number(file.number));
for (const [number, group] of byNumber) {
  if (group.length > 1) errors.push(`ADR-${String(number).padStart(3, "0")} の番号が重複しています: ${group.map((f) => f.name).join(", ")}`);
}

// 5. 番号の欠け（警告）
const numbers = [...byNumber.keys()].sort((a, b) => a - b);
for (let n = numbers[0]; n < numbers.at(-1); n++) {
  if (!byNumber.has(n)) warnings.push(`ADR-${String(n).padStart(3, "0")} が欠けています（廃止しても番号は再利用しません）`);
}

// 2, 3. 索引の行と、索引の（状態, 日付）と本文の一致
const indexLines = read(join(adrDir, "index.md")).split("\n").filter((line) => line.startsWith("- [ADR-"));
const indexRow = /^- \[ADR-(\d+): [^\]]+\]\(([^)]+)\)（(.+), (\d{4}-\d{2}-\d{2})）$/;
const rows = new Map();
for (const line of indexLines) {
  const m = indexRow.exec(line);
  if (!m) {
    errors.push(`index.md の行を読めません: ${line}`);
    continue;
  }
  const [, number, target, status, date] = m;
  if (!target.startsWith(`ADR-${number}-`)) errors.push(`index.md の ADR-${number} の行が別の番号のファイルを指しています: ${target}`);
  if (!files.some((file) => file.name === target)) errors.push(`index.md の ADR-${number} の行が存在しないファイルを指しています: ${target}`);
  rows.set(target, [...(rows.get(target) ?? []), { status: firstWord(status), date }]);
}
for (const { name } of files) {
  const found = rows.get(name) ?? [];
  if (found.length !== 1) {
    errors.push(`${name} の索引の行が ${found.length} 個です（ちょうど 1 個が必要です）`);
    continue;
  }
  const text = read(join(adrDir, name));
  const status = firstWord(sectionValue(text, "Status"));
  const date = sectionValue(text, "Date");
  if (found[0].status !== status) errors.push(`${name}: 状態が索引（${found[0].status}）と本文（${status}）で違います`);
  if (found[0].date !== date) errors.push(`${name}: 日付が索引（${found[0].date}）と本文（${date}）で違います`);
}

// 4. リンクのラベルと宛先の番号
// ponytail: 検査の対象は次の場所に固定している。起動条件の paths は docs/**/*.md、.kiro/steering/**、README.md、Taskfile.yml、tooling/adr-lint/** で、
// バックエンドの Javadoc だけを変える PR は okf-validate を起動しない。上限に当たったら paths に backend/src/** を足す（CI の時間が増える）。
const targets = [
  ...walk(join(root, "docs"), /\.md$/),
  ...["README.md", "CONTRIBUTING.md"].map((name) => join(root, name)).filter(existsSync),
  ...walk(join(root, ".kiro", "steering"), /\.md$/),
  ...walk(join(root, "backend", "src"), /\.(java|md)$/),
];
const link = /\[ADR-(\d+)[^\]\n]*\]\([^)\s]*?ADR-(\d+)-[^)\s]*?\.md[^)]*\)/g;
for (const path of targets) {
  const text = read(path);
  for (const m of text.matchAll(link)) {
    if (Number(m[1]) !== Number(m[2])) {
      const lineNo = text.slice(0, m.index).split("\n").length;
      errors.push(`${relative(root, path)}:${lineNo}: リンクのラベルが ADR-${m[1]} で、宛先が ADR-${m[2]} です: ${m[0]}`);
    }
  }
}

for (const warning of warnings) console.error(`⚠ ${warning}`);
if (errors.length > 0) {
  for (const error of errors) console.error(`✗ ${error}`);
  console.error(`adr-lint: ${errors.length} 件の不整合があります。`);
  process.exit(1);
}
console.log(`adr-lint OK: ADR ${files.length} 件（警告 ${warnings.length} 件）`);
