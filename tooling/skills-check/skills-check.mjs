#!/usr/bin/env node
// skills-check: skills-lock.json と .agents/skills、.kiro/skills の整合を検査する。
// .kiro/.kiro-plugins-installed に載るファイル（cursor-plugins の kiro/install.sh が入れたもの）は、記録した内容ハッシュと比べる。
// 実行: node tooling/skills-check/skills-check.mjs [ルート。省略するとカレント]（task skills-check が呼ぶ）
// 手順の正文は docs/agents/matt-pocock-skills/workflow.md の「取得元と更新」と docs/agents/rgdd.md にある。
import { createHash } from "node:crypto";
import { existsSync, lstatSync, readdirSync, readFileSync, readlinkSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const root = process.argv[2] ?? ".";
const errors = [];
const lock = JSON.parse(readFileSync(join(root, "skills-lock.json"), "utf8")).skills;

const skipped = new Set([".git", "node_modules"]);

// dir の下のファイルを相対パスで返す。シンボリックリンクは files に入れず symlinks に入れる。
function listFiles(dir) {
  const files = [];
  const symlinks = [];
  const walk = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      if (skipped.has(entry.name)) continue;
      const path = join(current, entry.name);
      const rel = relative(dir, path).split(sep).join("/");
      if (entry.isDirectory()) walk(path);
      else if (entry.isFile()) files.push(rel);
      else if (entry.isSymbolicLink()) symlinks.push(rel);
    }
  };
  walk(dir);
  return { files, symlinks };
}

function hashFiles(dir, files) {
  const hash = createHash("sha256");
  for (const file of files) {
    hash.update(file);
    hash.update(readFileSync(join(dir, file)));
  }
  return hash.digest("hex");
}

// skills CLI の computeSkillFolderHash と同じ。相対パスを localeCompare で並べる。
function folderHash(dir) {
  return hashFiles(dir, listFiles(dir).files.sort((a, b) => a.localeCompare(b)));
}

// kiro/install.sh の content_hash と同じ。相対パスをバイト順に並べる。ファイルは空のパスで自身の内容だけを使う。
function pluginHash(path) {
  if (!statSync(path).isDirectory()) return createHash("sha256").update(readFileSync(path)).digest("hex");
  return hashFiles(path, listFiles(path).files.sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b))));
}

// .kiro/.kiro-plugins-installed は "# cursor-plugins <commit>" の行と、"<パス>\t<内容ハッシュ>" の行でできている。
// kiro/install.sh は更新時にここに載るパスを削除するため、.kiro 直下の skills、agents、steering の1要素と kiro-permissions.yaml だけを認める。
const manifestPath = /^(skills|agents|steering)\/[^/]+$/;
const manifestFile = join(root, ".kiro/.kiro-plugins-installed");
const manifestLines = existsSync(manifestFile) ? readFileSync(manifestFile, "utf8").split("\n").filter(Boolean) : [];
const commits = manifestLines.filter((line) => line.startsWith("#")).map((line) => line.match(/^# cursor-plugins (\S+)$/)?.[1]);
const pluginEntries = manifestLines.filter((line) => !line.startsWith("#")).map((line) => {
  const [path, recorded] = line.split("\t");
  return { path, recorded };
});
const pluginPaths = new Set(pluginEntries.map((entry) => entry.path));
const pluginSkills = new Set([...pluginPaths].filter((path) => path.startsWith("skills/")).map((path) => path.slice("skills/".length)));

if (manifestLines.length > 0) {
  if (commits.length !== 1 || !commits[0]) {
    errors.push(".kiro/.kiro-plugins-installed の先頭に「# cursor-plugins <コミット>」の行が1つ必要です（kiro/install.sh で入れ直してください）");
  } else if (commits[0].endsWith("-dirty")) {
    errors.push(`.kiro/.kiro-plugins-installed の取得元コミット ${commits[0]} は未コミットの変更を含みます。cursor-plugins でコミットしてから入れ直してください`);
  }
}
for (const { path, recorded } of pluginEntries) {
  const file = join(root, ".kiro", path);
  if (!manifestPath.test(path) && path !== "kiro-permissions.yaml") {
    errors.push(`.kiro/.kiro-plugins-installed: ${path} は skills、agents、steering の直下ではありません。kiro/install.sh の更新で消されるため認められません`);
  } else if (!recorded) {
    errors.push(`.kiro/${path}: .kiro/.kiro-plugins-installed に内容ハッシュがありません（kiro/install.sh で入れ直してください）`);
  } else if (!existsSync(file)) {
    errors.push(`.kiro/${path}: .kiro/.kiro-plugins-installed にありますが、ファイルがありません`);
  } else {
    if (statSync(file).isDirectory()) {
      for (const link of listFiles(file).symlinks) errors.push(`.kiro/${path}/${link}: シンボリックリンクは認められません（内容ハッシュに含まれず、改ざんを検出できません）`);
    }
    if (pluginHash(file) !== recorded) {
      errors.push(`.kiro/${path}: 内容ハッシュが .kiro/.kiro-plugins-installed と一致しません（入れたファイルを編集していないか確認してください）`);
    }
  }
}
// install.sh が入れる場所にあるファイルは、すべて記録に載っていなければならない。
for (const [dir, owns] of [[".kiro/agents", () => true], [".kiro/steering", (name) => name.startsWith("kiro-adapter")]]) {
  let names = [];
  try {
    names = readdirSync(join(root, dir));
  } catch {
    // 無ければ検査するものがない
  }
  for (const name of names.filter(owns)) {
    if (!pluginPaths.has(`${dir.slice(".kiro/".length)}/${name}`)) errors.push(`${dir}/${name} が .kiro/.kiro-plugins-installed にありません。編集した記録のないファイルは検査できません`);
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

for (const name of Object.keys(lock)) {
  if (pluginSkills.has(name)) errors.push(`${name}: skills-lock.json と .kiro/.kiro-plugins-installed の両方にあります。どちらか一方で管理してください`);
}
for (const [source, refs] of refsBySource) {
  if (refs.size > 1) errors.push(`${source}: ref が複数あります（${[...refs].join(", ")}）。取得元ごとに固定コミットを1つにしてください`);
}
for (const name of directories(join(root, ".agents/skills"))) {
  if (!(name in lock)) errors.push(`.agents/skills/${name} が skills-lock.json にありません`);
}
for (const name of directories(join(root, ".kiro/skills"))) {
  if (!(name in lock) && !pluginSkills.has(name)) errors.push(`.kiro/skills/${name} が skills-lock.json にも .kiro/.kiro-plugins-installed にもありません`);
}

if (errors.length > 0) {
  console.error(`✗ スキルの整合に ${errors.length} 件の問題があります。\n${errors.map((error) => `    - ${error}`).join("\n")}`);
  process.exit(1);
}
console.log(`skills-check OK: ${Object.keys(lock).length} スキル、kiro/install.sh で入れた ${pluginSkills.size} スキル`);
