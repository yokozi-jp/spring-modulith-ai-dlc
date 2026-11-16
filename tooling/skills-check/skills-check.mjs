#!/usr/bin/env node
// skills-check: skills-lock.json と .agents/skills、.kiro/skills の整合を検査する。
// .kiro/.kiro-plugins-installed に載るファイル（cursor-plugins の kiro/install.sh が入れたもの）は、記録した内容ハッシュと比べる。
// 実行: node tooling/skills-check/skills-check.mjs [ルート。省略するとカレント]（task skills-check が呼ぶ）
// 手順の正文は docs/agents/matt-pocock-skills/workflow.md の「取得元と更新」にある。
import { createHash } from "node:crypto";
import { existsSync, lstatSync, readdirSync, readFileSync, readlinkSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const root = process.argv[2] ?? ".";
const errors = [];
const lock = JSON.parse(readFileSync(join(root, "skills-lock.json"), "utf8")).skills;

// skills CLI の computeSkillFolderHash と同じ。相対パスの昇順に、パスと内容を連結してハッシュする。
function folderHash(dir) {
  const files = [];
  const walk = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      if (entry.name === ".git" || entry.name === "node_modules") continue;
      const path = join(current, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.isFile()) files.push(relative(dir, path).split(sep).join("/"));
    }
  };
  walk(dir);
  const hash = createHash("sha256");
  for (const file of files.sort((a, b) => a.localeCompare(b))) {
    hash.update(file);
    hash.update(readFileSync(join(dir, file)));
  }
  return hash.digest("hex");
}

// kiro/install.sh の content_hash と同じ。相対パスのバイト順に、パスと内容を連結してハッシュする。ファイルは空のパスで自身の内容だけを使う。
function pluginHash(path) {
  const hash = createHash("sha256");
  if (!statSync(path).isDirectory()) return hash.update(readFileSync(path)).digest("hex");
  const files = [];
  const walk = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const child = join(current, entry.name);
      if (entry.isDirectory()) walk(child);
      else if (entry.isFile()) files.push(relative(path, child).split(sep).join("/"));
    }
  };
  walk(path);
  const order = (a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b));
  for (const file of files.sort(order)) {
    hash.update(file);
    hash.update(readFileSync(join(path, file)));
  }
  return hash.digest("hex");
}

const pluginManifest = join(root, ".kiro/.kiro-plugins-installed");
const pluginSkills = new Set();
if (existsSync(pluginManifest)) {
  for (const line of readFileSync(pluginManifest, "utf8").split("\n").filter(Boolean)) {
    const [path, recorded] = line.split("\t");
    if (path.startsWith("skills/")) pluginSkills.add(path.slice("skills/".length));
    if (!recorded) {
      errors.push(`.kiro/${path}: .kiro/.kiro-plugins-installed に内容ハッシュがありません（kiro/install.sh で入れ直してください）`);
    } else if (!existsSync(join(root, ".kiro", path))) {
      errors.push(`.kiro/${path}: .kiro/.kiro-plugins-installed にありますが、ファイルがありません`);
    } else if (pluginHash(join(root, ".kiro", path)) !== recorded) {
      errors.push(`.kiro/${path}: 内容ハッシュが .kiro/.kiro-plugins-installed と一致しません（入れたファイルを編集していないか確認してください）`);
    }
  }
}

function frontmatterValue(text, key) {
  const lines = text.replaceAll("\r\n", "\n").split("\n");
  if (lines[0] !== "---") return undefined;
  const end = lines.indexOf("---", 1);
  const line = lines.slice(1, end < 0 ? undefined : end).find((candidate) => candidate.startsWith(`${key}:`));
  return line?.slice(key.length + 1).trim().replace(/^(['"])(.*)\1$/, "$2");
}

function directories(path) {
  try {
    return readdirSync(path, { withFileTypes: true }).filter((entry) => entry.isDirectory() || entry.isSymbolicLink()).map((entry) => entry.name);
  } catch {
    return [];
  }
}

const refsBySource = new Map();
for (const [name, entry] of Object.entries(lock)) {
  if (pluginSkills.has(name)) errors.push(`${name}: skills-lock.json と .kiro/.kiro-plugins-installed の両方にあります。どちらか一方で管理してください`);
  const dir = join(root, ".agents/skills", name);
  let skillText;
  try {
    skillText = readFileSync(join(dir, "SKILL.md"), "utf8");
  } catch {
    errors.push(`${name}: .agents/skills/${name}/SKILL.md がありません`);
    continue;
  }
  if (frontmatterValue(skillText, "name") !== name) errors.push(`${name}: SKILL.md の name がディレクトリ名と一致しません`);
  if (!frontmatterValue(skillText, "description")) errors.push(`${name}: SKILL.md に description がありません`);

  const link = join(root, ".kiro/skills", name);
  let isLink = false;
  try {
    isLink = lstatSync(link).isSymbolicLink();
  } catch {
    // 無ければ下のエラーにする
  }
  if (!isLink || readlinkSync(link) !== `../../.agents/skills/${name}`) {
    errors.push(`${name}: .kiro/skills/${name} が ../../.agents/skills/${name} へのシンボリックリンクではありません`);
  }

  // note を持つ項目は、上流から意図して変えているため内容ハッシュを比べない。
  if (!entry.note && folderHash(dir) !== entry.computedHash) {
    errors.push(`${name}: 内容ハッシュが skills-lock.json と一致しません（上流のファイルを編集していないか確認してください）`);
  }
  refsBySource.set(entry.source, new Set([...(refsBySource.get(entry.source) ?? []), entry.ref]));
}

for (const [source, refs] of refsBySource) {
  if (refs.size > 1) errors.push(`${source}: ref が複数あります（${[...refs].join(", ")}）。取得元ごとに固定コミットを1つにしてください`);
}
for (const base of [".agents/skills", ".kiro/skills"]) {
  for (const name of directories(join(root, base))) {
    if (base === ".kiro/skills" && pluginSkills.has(name)) continue;
    if (!(name in lock)) errors.push(`${base}/${name} が skills-lock.json にありません`);
  }
}

if (errors.length > 0) {
  console.error(`✗ スキルの整合に ${errors.length} 件の問題があります。\n${errors.map((error) => `    - ${error}`).join("\n")}`);
  process.exit(1);
}
console.log(`skills-check OK: ${Object.keys(lock).length} スキル、kiro/install.sh で入れた ${pluginSkills.size} スキル`);
